package com.fox.ysmu.client.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;

/**
 * 模型 / 贴图预览平铺页的整页预算换算。
 *
 * <p>这一页和 HUD 纸娃娃完全不同：一页同时显示十几个预览，每个都是一次完整的 GeckoLib
 * 渲染。所以刷新率不是"每个预览各自一个上限"，而是**整页共享一份每秒预算再摊到可见数量上**：
 * 忘记除可见数量（照抄 HUD 那个 15 % 预算）会让 13 个预览一起把帧预算吃光；除错方向
 * （乘以可见数量）则会变成几乎每帧重烘焙。这里钉住算式与两边夹取。</p>
 */
class PreviewRefreshPolicyTest {

    /** 用户给的曲线：60 fps → 45 Hz、120 fps 及以上 → 90 Hz（同 HUD 纸娃娃的 3/4 + 上限）。 */
    @Test
    void targetFollowsTheFrameRateCurve() {
        assertEquals(45.0F, PreviewRefreshPolicy.targetHzFor(1, 0.5F, 60.0F), 0.01F, "60 fps -> 45 Hz");
        assertEquals(90.0F, PreviewRefreshPolicy.targetHzFor(1, 0.5F, 120.0F), 0.01F, "120 fps -> 90 Hz");
        assertEquals(90.0F, PreviewRefreshPolicy.targetHzFor(1, 0.5F, 300.0F), 0.01F,
            ">120 fps 不该继续涨：缩略图 90 Hz 已经过头了");
    }

    /** 整页兜底按"每帧开销"摊到可见数量上：8 ms/帧 ÷ 13 个 ÷ 1 ms = 55.4 Hz。 */
    @Test
    void perFrameOverheadIsSplitAcrossVisiblePreviews() {
        assertEquals(8.0F * 90.0F / 13.0F, PreviewRefreshPolicy.targetHzFor(13, 1.0F, 90.0F), 0.01F,
            "预览极多/极贵时要按每帧开销摊分，否则十几个预览会把帧时间顶爆");
        // 可见数量少时兜底更宽松，于是比例项（67.5 Hz）接管；要用更贵的烘焙才看得到兜底。
        assertEquals(8.0F * 90.0F / (5.0F * 3.0F), PreviewRefreshPolicy.targetHzFor(5, 3.0F, 90.0F), 0.01F,
            "可见数量越少，单个预览分到的每帧开销越多");
    }

    /** 便宜的预览不因此无限提频：90 Hz 是单个预览的上限。 */
    @Test
    void cheapPreviewIsCapped() {
        assertEquals(90.0F, PreviewRefreshPolicy.targetHzFor(1, 0.01F, 300.0F), 0.001F);
        assertEquals(90.0F, PreviewRefreshPolicy.targetHzFor(13, 0.01F, 300.0F), 0.001F);
    }

    /** 再贵也不能低于 5 Hz，否则缩略图看起来就是一张静止图（帧率低到 8 帧下界 < 5 时才轮到它）。 */
    @Test
    void expensivePreviewFloorsAtMinimum() {
        assertEquals(5.0F, PreviewRefreshPolicy.targetHzFor(13, 20.0F, 20.0F), 0.001F);
    }

    /** 同一帧烘两次没有意义：目标不超过帧率（比例 3/4 让它在低帧率下也留了一档余量）。 */
    @Test
    void targetNeverExceedsFrameRate() {
        float hz = PreviewRefreshPolicy.targetHzFor(1, 0.1F, 20.0F);
        assertTrue(hz <= 20.0F, "目标不能超过帧率: " + hz);
        assertEquals(15.0F, hz, 0.001F, "20 fps 下应是 3/4 的 15 Hz");
    }

    /**
     * 预算再低也不能让间隔超过控制器的再入窗口：这一页每个预览都是那个模型唯一一次动画推进，
     * 超窗口就会被判成"模型被换走又换回来"，眼睛/尾巴被钉回 tick 0（实测：眨眼卡在过程中）。
     */
    @Test
    void expensivePreviewNeverExceedsThePassWindow() {
        assertEquals(90.0F / PreviewRefreshPolicy.MAX_SKIP_FRAMES,
            PreviewRefreshPolicy.targetHzFor(13, 500.0F, 90.0F), 0.01F,
            "预算低到离谱时也该由'每 N 帧至少一次'兜底，而不是让控制器把限频当成再入");

        int window = OpenYsmPlayerControllerRuntime.reEntryFrameWindow();
        assertTrue(PreviewRefreshPolicy.MAX_SKIP_FRAMES <= window,
            "间隔下界必须留在再入窗口内: bound=" + PreviewRefreshPolicy.MAX_SKIP_FRAMES
                + ", window=" + window);
    }

    /** 可见数量为 0（预览页没开）时按 1 处理，不能除以 0。 */
    @Test
    void zeroVisibleIsTreatedAsOne() {
        float hz = PreviewRefreshPolicy.targetHzFor(0, 1.0F, 1000.0F);
        assertTrue(Float.isFinite(hz) && hz > 0.0F, "可见数量 0 不能产生 NaN/Inf: " + hz);
        assertEquals(PreviewRefreshPolicy.targetHzFor(1, 1.0F, 1000.0F), hz, 0.001F);
    }
}
