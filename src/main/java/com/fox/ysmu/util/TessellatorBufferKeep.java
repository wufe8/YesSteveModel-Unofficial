package com.fox.ysmu.util;

import java.lang.reflect.Field;

import net.minecraft.client.renderer.Tessellator;

import com.fox.ysmu.ysmu;

/**
 * 让共享 Tessellator 的 raw int 缓冲不再"draw 之后被缩回 256 KiB、下一批再翻倍长回来"。
 *
 * <p><b>现象</b>（480p 预览页 + preview FBO 的实机 JFR，6271 个分配样本）：{@code int[]} 占
 * 81.3%；其中</p>
 * <pre>
 *   Arrays.copyOf &lt;- Tessellator.func_78377_a:342 &lt;- func_78374_a:327
 *                &lt;- IGeoRenderer.renderCube:260 &lt;- renderBoneCubes &lt;- renderRecursively
 * </pre>
 * <p>占 49.6%（3079 个样本）；另一大块是 Angelica 自己那 31.9%
 * （{@code TessellatorStreamingDrawer.draw:102}）。</p>
 *
 * <p><b>根因</b>：补丁版 {@code Tessellator.draw()} 与 <b>Angelica 的
 * {@code TessellatorStreamingDrawer.draw()}</b>（Angelica 的 Mixin 接管了 draw；实机跑的是它
 * 这一份 —— {@code Tessellator.func_78381_a} 从不作为分配点出现，而
 * {@code TessellatorStreamingDrawer.draw:102} 就是那 31.9%）末尾都有同一段：</p>
 * <pre>
 *   if (rawBufferSize &gt; 0x20000 &amp;&amp; rawBufferIndex &lt; (rawBufferSize &lt;&lt; 3)) {
 *       rawBufferSize = 0x10000;
 *       rawBuffer = new int[rawBufferSize];      // 每次触发都新分配 256 KiB
 *   }
 * </pre>
 * <p>因为 {@code rawBufferIndex &lt;= rawBufferSize &lt; (rawBufferSize &lt;&lt; 3)} 恒成立，这段
 * 实际含义就是"<b>容量超过 512 KiB 就缩回去</b>"；而 {@code func_78377_a} 里
 * {@code rawBufferIndex >= rawBufferSize - 32} 又把缓冲翻倍（{@code Arrays.copyOf}）长回来
 * —— 一缩一长，每帧数 MB 拷贝加垃圾。</p>
 *
 * <p><b>为什么"只把我们自己的 draw 包起来、事后把大缓冲装回去"不管用</b>（第一版做法，实测
 * 无效）：缩回是"容量大"就触发，与用量无关；预览页里每次 {@code FboCache.draw}（模型按钮的
 * FBO 烘焙，{@code ModelButton.func_146112_a}）以及 GUI/字体的 draw 都走同一个共享实例、又
 * 不在我们的包裹范围内，于是我们装回去的大缓冲立刻又被它们缩掉，下一批照样 {@code copyOf}
 * 长回来。</p>
 *
 * <p><b>做法</b>：反过来 —— <b>让容量永远不超过 0x20000</b>，缩回分支就永远不进：</p>
 * <ol>
 *   <li>我们自己的批次在塞满之前先 flush：每渲染一个面前 {@link #nearlyFull(Tessellator)}
 *       查一次，快满就 draw + {@code startDrawing}（见
 *       {@code IGeoRenderer.flushBatchIfNearlyFull}），批次用量控制在
 *       {@code 0x20000 - 0x2000} 以内；容量只会在第一次从 0x10000 长到 0x20000 一次；</li>
 *   <li>draw 前把容量压到不超过 0x20000（只写这个 int，不换数组、不分配），避免别的模组把
 *       容量撑大之后，我们这一次 draw 替它们触发缩回。</li>
 * </ol>
 * <p>两者都只读写 {@code rawBufferSize}/{@code rawBufferIndex} 两个私有字段：不用 AT、不注入
 * vanilla 方法、不改 GL 状态；拿不到字段就退化成原版行为并只报一次。</p>
 */
public final class TessellatorBufferKeep {

    /** 缩回门槛：容量超过 0x20000（131072 个 int ≈ 512 KiB）就会触发。 */
    public static final int SHRINK_THRESHOLD = 0x20000;
    /**
     * 一批顶点预留的余量（0x2000 个 int ≈ 1024 顶点 ≈ 42 个普通 cube；普通 cube 24 顶点
     * = 192 int）。留余量是为了"检查之后又塞进一个 cube"不会把用量顶过门槛。
     */
    public static final int BATCH_RESERVE = 0x2000;

    private static Field rawBufferSizeField;
    private static Field rawBufferIndexField;
    /** 两个字段都拿到才启用。 */
    private static volatile boolean available;
    private static boolean warned;

    static {
        try {
            rawBufferSizeField = declaredField("rawBufferSize");
            // 运行世界（RFB/SRG）是 field_147569_p，开发/IDE 环境是 MCP 名 rawBufferIndex。
            Field index = declaredField("field_147569_p");
            if (index == null) {
                index = declaredField("rawBufferIndex");
            }
            rawBufferIndexField = index;
        } catch (Throwable t) {
            rawBufferSizeField = null;
            rawBufferIndexField = null;
        }
        available = rawBufferSizeField != null && rawBufferIndexField != null;
        if (!available) {
            warnOnce();
        }
    }

    private TessellatorBufferKeep() {}

    private static Field declaredField(String name) {
        try {
            Field field = Tessellator.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void warnOnce() {
        if (warned) {
            return;
        }
        warned = true;
        try {
            ysmu.LOG.warn(
                "[YSMU-TESS] 拿不到 Tessellator 的 rawBufferSize/rawBufferIndex 字段，"
                    + "跳过顶点批次切分与缓冲容量控制（不影响渲染，只是不省这部分分配）");
        } catch (Throwable ignored) {
            // 日志本身出问题也不能影响渲染。
        }
    }

    /** 本批顶点是否快把 0x20000 个 int 用满（快满就该先 flush）。 */
    public static boolean nearlyFull(Tessellator tessellator) {
        if (!available || tessellator == null) {
            return false;
        }
        try {
            return nearlyFull(rawBufferIndexField.getInt(tessellator));
        } catch (Throwable t) {
            available = false;
            warnOnce();
            return false;
        }
    }

    /** 纯逻辑版（可单测）：用量超过"门槛减余量"就该 flush。 */
    public static boolean nearlyFull(int rawBufferIndex) {
        return rawBufferIndex > SHRINK_THRESHOLD - BATCH_RESERVE;
    }

    /**
     * 画一次；画之前把容量压到不超过 {@link #SHRINK_THRESHOLD}，让原版/Angelica 的缩回分支
     * 不执行（只写容量这个 int，不换数组、不分配）。
     *
     * @return {@code draw()} 的返回值
     */
    public static int draw(Tessellator tessellator) {
        if (!available || tessellator == null) {
            return tessellator.draw();
        }
        try {
            int size = rawBufferSizeField.getInt(tessellator);
            if (size > SHRINK_THRESHOLD) {
                rawBufferSizeField.setInt(tessellator, SHRINK_THRESHOLD);
            }
        } catch (Throwable t) {
            available = false;
            warnOnce();
        }
        return tessellator.draw();
    }
}
