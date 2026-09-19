package com.fox.ysmu.client.renderer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;

/**
 * 第一人称下 HUD 纸娃娃是自身模型的**唯一**动画 pass，所以它的刷新间隔有一个和渲染性能无关的
 * 硬约束：不能超过控制器的再入窗口（{@code OpenYsmPlayerControllerRuntime.isReEntry}）。
 *
 * <p>这条约束已经踩过两次，方向相反：</p>
 * <ul>
 *   <li>太密：模型选择页一帧十几个 pass 把帧计数推快 → 每帧误判再入（见
 *       {@code ControllerReEntryFrameTest}）。</li>
 *   <li>太疏：给纸娃娃加了刷新率上限（最低 12 Hz ≈ 83 ms），在 300-500 fps 下就是
 *       25-33 个渲染帧 → 判成"模型被换走又换回来"，动画全被钉回 tick 0（走路/挥剑停在第一帧）。</li>
 * </ul>
 *
 * <p>所以 {@code HudPreviewCache} 用一个**帧数**下界兜底，并且直接从再入窗口推导出来。
 * 这里守住的是"推导"这件事本身：谁把窗口调小到 2 帧以下，或者把下界改成硬编码的常数，
 * 这个测试会红。</p>
 */
class HudPreviewSoleDriverBoundTest {

    @Test
    void soleDriverSkipBoundStaysInsideTheReEntryWindow() {
        int window = OpenYsmPlayerControllerRuntime.reEntryFrameWindow();
        int bound = HudPreviewCache.HUD_SOLE_DRIVER_MAX_SKIP_FRAMES;

        assertTrue(bound <= window,
            "纸娃娃的 pass 间隔一旦达到再入窗口，控制器就会把限频当成'模型被换走又换回来'，"
                + "动画被钉回 tick 0: bound=" + bound + ", window=" + window);
        assertTrue(bound >= 2,
            "下界太小等于每帧重烘焙（静止时白烧帧率），刷新率上限永远没机会省东西: bound=" + bound);
    }
}
