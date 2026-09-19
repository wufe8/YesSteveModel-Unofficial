package com.fox.ysmu.client.renderer;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;

/**
 * 模型 / 贴图预览平铺页（模型选择页、贴图页）的**共享**刷新预算。
 *
 * <p>和 {@link HudPreviewCache} 的思路一样（预算 ÷ 单次成本 → 目标 Hz，用墙钟累加器而不是
 * "每 N 帧"），但这里的乘数完全不同：一页最多同时显示十几个预览，每个都是一次完整的
 * GeckoLib 渲染 —— 一次烘焙 1 ms 的话，13 个预览"每 2 帧"刷新就是每帧 6.5 ms，直接吃掉
 * 整个帧预算。所以预算是**整页共享**的，再按当前可见数量摊到每个预览上：</p>
 *
 * <pre>
 *   visible        = 上一帧调用过 due() 的预览数量（翻页/切页自动跟随）
 *   costPerBake    = 实测单次烘焙耗时（EMA）
 *   perPreviewHz   = clamp(BUDGET_MS_PER_SEC / (visible × costPerBake), MIN_HZ, MAX_HZ)
 *                    再被当前帧率封顶（同一帧烘两次没有意义）
 * </pre>
 *
 * <p>两个细节不是可选的：</p>
 * <ul>
 *   <li><b>错峰</b>：所有预览如果用同一个相位累加，就会在同一帧集体重烘焙 —— 13 个 1 ms 的
 *       烘焙撞在一起是一帧 13 ms 的尖峰（会看见整页规律性卡顿）。{@link Tracker} 用按钮 id
 *       给每个预览一个不同的初始相位，之后各走各的墙钟累加器，长期看仍然等间隔。</li>
 *   <li><b>焦点放宽</b>：正在悬停或选中的那个预览在播 <code>hover</code>/<code>focus</code>
 *       动画，用户正盯着它看，按整页平均速率会明显发顿。所以焦点预览单独按
 *       {@link #FOCUS_BOOST} 倍放宽（略超总预算，换"看着的东西不卡"）。</li>
 *   <li><b>目标曲线</b>：{@code 帧率 × }{@link #GRID_FRAME_RATIO}（上限 {@link #MAX_HZ}），
 *       和 HUD 纸娃娃一致 —— 60 fps 给 45 Hz、120 fps 及以上给 90 Hz。整页的"每帧开销"
 *       由 {@link #OVERHEAD_MS_PER_FRAME} 兜底（预览极多或烘焙极贵时才会绑上）。</li>
 *   <li><b>间隔硬下界（帧数）</b>：预算压得再低，相邻两次烘焙的间隔也不能超过
 *       {@link OpenYsmPlayerControllerRuntime#safePassWindowFrames()} 帧。这一页的每个预览
 *       都是那个模型唯一一次动画推进，间隔一旦超过控制器的再入窗口，眼睛/尾巴这些并行控制器
 *       每次都会被判成"模型被换走又换回来"、重新装一次动画并被钉回 tick 0 —— 实测表现是
 *       眨眼卡在过程中。所以下界同时写进 {@link #targetHzFor}（让显示的目标说实话）和
 *       {@link Tracker} 的帧计数（真正的保证，不受帧时间抖动影响）。</li>
 * </ul>
 *
 * <p>预算 200 ms/秒（= 20 % 墙钟）是给"整页预览"的，比 HUD 纸娃娃那 15 % 宽松：这一页打开时
 * 用户的注意力就在预览上，而世界渲染在这个界面下本来就是背景。</p>
 */
public final class PreviewRefreshPolicy {

    /** 目标频率取帧率的这个比例 —— 和 HUD 纸娃娃同一条曲线：60 fps → 45 Hz、120 fps → 90 Hz。
     *  这一页打开时用户的注意力就在预览模型上，周边背景顺不顺看不出来，所以宁可把帧预算花在
     *  预览上（实测用户反馈：35-45 Hz 在 300 fps 下偏顿，要求按这条曲线放宽）。 */
    private static final float GRID_FRAME_RATIO = 0.75F;
    /** 单个预览的上限（Hz）。比 HUD 的 120 低：缩略图 90 Hz 已远超肉眼需求。 */
    private static final float MAX_HZ = 90.0F;
    /** 单个预览的下限：再低就看着像静止图了。 */
    private static final float MIN_HZ = 5.0F;
    /** 整页预览允许给**每一个渲染帧**加的最大毫秒数（兜底）。
     *
     *  <p>为什么兜底写成"每帧开销"而不是"每秒预算"：比例式的目标意味着每帧的烘焙次数是
     *  {@code 可见数量 × 比例}（与帧率无关），所以预览一多、烘焙一贵，帧时间就会被顶上去，
     *  帧率随之下降 —— 而"每秒预算"是按帧率换算的，会在帧率高时严重超支、帧率低时又太紧。
     *  直接按每帧开销封顶才是这一页真正要守的约束。</p> */
    private static final float OVERHEAD_MS_PER_FRAME = 8.0F;
    /** 只用来保证除法有限。 */
    private static final float MIN_COST_MS = 0.05F;
    /** 忽略超过这个的帧间隔（切页、卡顿、alt-tab）。 */
    private static final float MAX_FRAME_DELTA_MS = 200.0F;
    /** 焦点预览（悬停 / 选中）的速率倍数。 */
    private static final float FOCUS_BOOST = 3.0F;
    /** 相邻两次烘焙允许的最大渲染帧间隔。**不是可调项**：超过控制器的再入窗口就会把
     *  并行控制器钉回 tick 0（眨眼卡住），所以由窗口推导。 */
    static final int MAX_SKIP_FRAMES = OpenYsmPlayerControllerRuntime.safePassWindowFrames();

    private static long lastFrameNanos;
    private static float frameDeltaMs = 16.0F;
    private static int visibleThisFrame;
    private static int visibleLastFrame;
    private static float smoothCostMs = 0.0F;
    /** 观测用的间隔统计：窗口内实际出现的最大烘焙间隔（帧）。用来验证
     *  {@link #MAX_SKIP_FRAMES} 真的生效 —— 修"眨眼卡住"时唯一能证伪这一点的数。 */
    private static int maxGapThisFrame;
    private static int maxGapSincePublish;
    private static int maxGapLastSecond = -1;
    private static long gapWindowStartNanos;

    private PreviewRefreshPolicy() {}

    /** 每个渲染帧调一次（{@code ClientEventHandler.onRenderTick}）。
     *  可见数量按"上一帧调用了几次 {@link #noteVisible()}"统计，所以翻页/切页自动跟随。 */
    public static void beginFrame() {
        long now = System.nanoTime();
        float dtMs = lastFrameNanos == 0L ? 16.0F : (now - lastFrameNanos) / 1.0e6F;
        lastFrameNanos = now;
        if (dtMs > 0.0F && dtMs < MAX_FRAME_DELTA_MS) {
            frameDeltaMs = frameDeltaMs * 0.9F + dtMs * 0.1F;
        }
        visibleLastFrame = visibleThisFrame;
        visibleThisFrame = 0;

        maxGapSincePublish = Math.max(maxGapSincePublish, maxGapThisFrame);
        maxGapThisFrame = 0;
        if (gapWindowStartNanos == 0L) {
            gapWindowStartNanos = now;
        } else if (now - gapWindowStartNanos >= 1_000_000_000L) {
            maxGapLastSecond = maxGapSincePublish;
            maxGapSincePublish = 0;
            gapWindowStartNanos = now;
        }
    }

    /** 某次烘焙距上一次隔了几个渲染帧（{@link Tracker} 每次真的烘焙时上报）。 */
    static void noteBakeGap(int gapFrames) {
        maxGapThisFrame = Math.max(maxGapThisFrame, gapFrames);
    }

    /** 最近一秒内观测到的最大烘焙间隔（帧）；-1 = 还没满一秒。要一直 &le;
     *  {@link #MAX_SKIP_FRAMES}，超过就说明有预览被跳过了（例如模型同步期间
     *  `ModelButton` 故意不重烘焙）。 */
    public static int maxGapFrames() {
        return maxGapLastSecond;
    }

    /** 当前帧里又有一个预览参与刷新（按钮每次绘制时调）。 */
    public static void noteVisible() {
        visibleThisFrame++;
    }

    /** 实测一次烘焙的耗时，供预算换算。 */
    public static void noteBake(float costMs) {
        if (!(costMs > 0.0F) || Float.isInfinite(costMs)) {
            return;
        }
        if (smoothCostMs <= 0.0F) {
            smoothCostMs = costMs;
            return;
        }
        // 尖峰只允许把估计往上推这么多：每页/每个模型第一次烘焙会把几何与动画一起加载进来
        // （几十毫秒量级），那是加载成本而不是渲染成本。原样吃进 EMA 会让整页的频率被压到底
        // 好几个周期 —— 用户报的"目标频率太低"就有这一份。夹住而不是丢弃：真实的成本上升
        // （更重的模型）仍然能把估计推上去，只是每采样最多 ×1.9。
        float sample = Math.min(costMs, smoothCostMs * 4.0F);
        smoothCostMs = smoothCostMs * 0.7F + sample * 0.3F;
    }

    /** 每个预览当前的目标刷新率（Hz），供 F3 读数与 {@link Tracker} 使用。 */
    public static float targetHz() {
        return targetHzFor(visibleLastFrame, smoothCostMs, 1000.0F / Math.max(frameDeltaMs, 0.5F));
    }

    /** {@link #targetHz()} 的纯计算部分（拆出来便于单测）。算式就是全部策略：
     *  帧率 × 比例（上限 {@link #MAX_HZ}），再被"每帧最多加 {@link #OVERHEAD_MS_PER_FRAME} ms"
     *  的整页兜底压住，最后抬到"每 {@link #MAX_SKIP_FRAMES} 帧至少一次"。 */
    static float targetHzFor(int visible, float costMs, float frameHz) {
        float perBakeCost = Math.max(costMs, MIN_COST_MS);
        float hz = Math.min(frameHz * GRID_FRAME_RATIO, MAX_HZ);
        hz = Math.min(hz, OVERHEAD_MS_PER_FRAME * frameHz / (Math.max(1, visible) * perBakeCost));
        hz = Math.max(hz, frameHz / MAX_SKIP_FRAMES);
        return Math.max(hz, MIN_HZ);
    }

    /** 上一帧的可见预览数量（0 = 当前没在预览页上）。 */
    public static int visibleCount() {
        return visibleLastFrame;
    }

    /** 实测的单次烘焙耗时（ms）。 */
    public static float bakeCostMs() {
        return smoothCostMs;
    }

    /** 版本号后面那截状态文本（模型选择页左下角）：自动模式直接给出每个预览的当前 Hz
     *  与可见数量，不用开 F3 就能看到限频在做什么。 */
    public static String describeMode() {
        int mode = Config.GUI_MODEL_PREVIEW_REFRESH;
        if (mode < 0) {
            return "preview FBO " + String.format(java.util.Locale.ROOT, "%.1f", targetHz())
                + " Hz x" + visibleCount();
        }
        if (mode == 0) {
            return "preview FBO static";
        }
        return "preview FBO 1/" + mode + " frames";
    }

    /** 单个预览的墙钟累加器。每个预览按钮持有一个。 */
    public static final class Tracker {

        private double accumMs;
        private boolean started;
        /** 距上一次烘焙过了几个渲染帧（真正的硬保证，与帧时间无关）。 */
        private int framesSinceBake;

        /** true = 这一帧该重烘焙了。
         *
         *  @param slot    预览的稳定编号（按钮 id），用来错峰
         *  @param focused 正在悬停 / 选中：按 {@link #FOCUS_BOOST} 倍放宽 */
        public boolean due(int slot, boolean focused) {
            float hz = targetHz();
            if (focused) {
                hz = Math.min(MAX_HZ, hz * FOCUS_BOOST);
            }
            double intervalMs = 1000.0D / hz;
            if (!started) {
                started = true;
                // 7 与 16 互质：连续 10 个按钮 id 会落在 10 个不同相位上，
                // 一批预览不会挤在同一个渲染帧里集体烘焙。
                accumMs = intervalMs * ((slot * 7 % 16) / 16.0D);
            }
            boolean frameDeadline = ++framesSinceBake >= MAX_SKIP_FRAMES;
            accumMs += frameDeltaMs;
            if (!frameDeadline && accumMs < intervalMs) {
                return false;
            }
            noteBakeGap(framesSinceBake);
            framesSinceBake = 0;
            if (frameDeadline) {
                // 落后于墙钟了（帧时间抖动，或预算算出来的间隔本来就接近窗口）。
                // 丢掉多余额度，不做补帧。
                accumMs = 0.0D;
            } else {
                accumMs -= intervalMs;
                if (accumMs >= intervalMs) {
                    // 单帧跨过多个间隔时丢掉多余额度，不做补帧（同 HudPreviewCache）。
                    accumMs %= intervalMs;
                }
            }
            return true;
        }

        /** 预览被销毁 / 换页时调用，下次绘制重新错峰。 */
        public void reset() {
            started = false;
            accumMs = 0.0D;
            framesSinceBake = 0;
        }
    }
}
