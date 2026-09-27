package com.fox.ysmu.util;

import java.lang.reflect.Field;

import net.minecraft.client.renderer.Tessellator;

import com.fox.ysmu.ysmu;

/**
 * 让共享 Tessellator 的 raw int 缓冲不再"每次 draw 被缩回 256 KiB、下一次 draw 再翻倍长回来"。
 *
 * <p><b>现象</b>（480p 预览页 + preview FBO 的实机 JFR，69s / 1.9 万分配样本）：{@code int[]}
 * 占 51% 的样本；同一次取样的瞬时垃圾里 {@code int[]} 占 48%（平均 62 KB/个）；
 * 它同时还是 CPU 侧最大的单项。调用链是
 * {@code IGeoRenderer.renderCube → Tessellator.func_78374_a → func_78377_a → Arrays.copyOf(int[], int)}。</p>
 *
 * <p><b>根因</b>：1.7.10（GTNH 补丁版）{@code Tessellator.draw()} 末尾：
 * <pre>
 *   if (rawBufferSize &gt; 0x20000 &amp;&amp; rawBufferIndex &lt; (rawBufferSize &lt;&lt; 3)) {
 *       rawBufferSize = 0x10000;
 *       rawBuffer = new int[rawBufferSize];
 *   }
 * </pre>
 * 原意是"共享 Tessellator 偶尔被超大绘制撑大后把内存还回去"。但我们的模型绘制**每帧**
 * 都超过 512 KiB（一个模型几千个 quad），于是每次 draw 之后缓冲被缩回 256 KiB，
 * 下一次 draw 再 256K→512K→1M→… 一路 {@code Arrays.copyOf} 长回来
 * —— 每次 draw 数 MB 的内存拷贝加一堆 int[] 垃圾。</p>
 *
 * <p><b>做法</b>：不改原版逻辑、不改 GL 状态、不注入 vanilla 方法，只在我们自己的模型 draw
 * 前后各记一次：draw 之后若缓冲被缩回了，就把原来那块大缓冲装回去（draw 之后
 * {@code rawBufferIndex} 与 byteBuffer 的位置本就无效，不受影响）。缓冲一次长到高水位之后
 * 就不再反复重建；残留的只有原版收缩路径自己分配的那 256 KiB。</p>
 *
 * <p><b>为什么用反射而不是 AT/Mixin</b>：
 * <ul>
 *   <li>{@code rawBuffer} 在运行世界叫 {@code field_78405_h}、{@code rawBufferSize} 是补丁
 *       新增字段。Mixin 的 {@code @Shadow} 在这种"一个字段有 SRG 名、另一个没有"的情况下会被
 *       混淆映射卡住（实测 AP 报 {@code Unable to locate obfuscation mapping}）；</li>
 *   <li>AT 则要求本 mod 自己声明规则 —— 而 Angelica 的 {@code angelica_at.cfg} 里**已经有**
 *       这两条一模一样的规则（{@code field_78405_h} 与 {@code rawBufferSize}），重复规则把
 *       类加载链卷进来并不划算（2026-09-27 那次 init 崩溃就在排查这条线）；</li>
 *   <li>反射是"能力探测 + 失败即退化"：拿不到字段就退化成原版行为并只报一次，
 *       既不依赖别的模组的 AT，也不参与任何类加载/字节码改写。</li>
 * </ul>
 */
public final class TessellatorBufferKeep {

    /** 探测结果：两个字段都拿到了才启用。 */
    private static final boolean AVAILABLE;

    private static Field rawBufferField;
    private static Field rawBufferSizeField;

    static {
        Field buffer = null;
        Field size = null;
        try {
            // 运行世界（RFB/SRG）是 field_78405_h；开发/IDE 环境是 MCP 名 rawBuffer。两个都试。
            buffer = declaredField("field_78405_h");
            if (buffer == null) {
                buffer = declaredField("rawBuffer");
            }
            size = declaredField("rawBufferSize");
        } catch (Throwable t) {
            buffer = null;
            size = null;
        }
        rawBufferField = buffer;
        rawBufferSizeField = size;
        AVAILABLE = buffer != null && size != null;
        if (!AVAILABLE) {
            ysmu.LOG.warn(
                "[YSMU-TESS] Tessellator 的 raw 缓冲字段拿不到（field_78405_h/rawBufferSize），"
                    + "跳过 draw 后的缓冲保持优化（不影响渲染，只是不省这部分分配）");
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

    /**
     * 画一次，并把被原版缩回去的 raw 缓冲恢复回来。
     *
     * @param tessellator 要 draw 的 Tessellator（通常是 {@link Tessellator#instance}）
     * @return {@code draw()} 的返回值
     */
    public static int draw(Tessellator tessellator) {
        if (!AVAILABLE) {
            return tessellator.draw();
        }
        try {
            int[] bufferBefore = (int[]) rawBufferField.get(tessellator);
            int sizeBefore = rawBufferSizeField.getInt(tessellator);
            int result = tessellator.draw();
            // 只有"被缩回去了"才恢复；draw 期间扩容了就保持现状（那是它自己需要的大小）。
            if (bufferBefore != null && sizeBefore > 0 && rawBufferSizeField.getInt(tessellator) < sizeBefore) {
                rawBufferField.set(tessellator, bufferBefore);
                rawBufferSizeField.setInt(tessellator, sizeBefore);
            }
            return result;
        } catch (Throwable t) {
            // 反射出意外（字段被别的模组改掉等）：直接退化成原版行为，别再抛。
            return tessellator.draw();
        }
    }
}
