package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@code query.head_z_rotation} 的数据源：相机 roll 的插值。
 *
 * <p>1.7.10 没有实体 roll，唯一的 Z 旋转是 {@code EntityRenderer.camRoll}，渲染时按
 * {@code prevCamRoll + (camRoll - prevCamRoll) * partialTicks} 应用 —— 这里锁住同一套算术，
 * 保证模型拿到的角度和屏幕上镜头的倾斜一致。</p>
 */
class CameraRollQueryTest {

    /** partialTicks = 0 时取上一 tick 的采样值（渲染插值的起点）。 */
    @Test
    void zeroPartialTicksUsesPreviousSample() {
        assertEquals(0.0F, CameraRollQuery.interpolate(0.0F, 10.0F, 0.0F), 0.0001F);
    }

    /** partialTicks = 1 时取本 tick 的采样值。 */
    @Test
    void fullPartialTicksUsesCurrentSample() {
        assertEquals(10.0F, CameraRollQuery.interpolate(0.0F, 10.0F, 1.0F), 0.0001F);
    }

    @Test
    void halfPartialTicksIsTheMidpoint() {
        assertEquals(5.0F, CameraRollQuery.interpolate(0.0F, 10.0F, 0.5F), 0.0001F);
        assertEquals(-5.0F, CameraRollQuery.interpolate(-10.0F, 0.0F, 0.5F), 0.0001F);
    }

    /** 原版无相机 mod 时 camRoll 恒 0：插值也必须恒 0（这就是"默认与官方一致"的依据）。 */
    @Test
    void zeroSamplesAlwaysYieldZero() {
        assertEquals(0.0F, CameraRollQuery.interpolate(0.0F, 0.0F, 0.0F), 0.0001F);
        assertEquals(0.0F, CameraRollQuery.interpolate(0.0F, 0.0F, 0.37F), 0.0001F);
        assertEquals(0.0F, CameraRollQuery.interpolate(0.0F, 0.0F, 1.0F), 0.0001F);
    }

    /** partialTicks 越界（某些渲染路径会传 >1 或 <0）必须夹紧，不能外推出更大的 roll。 */
    @Test
    void partialTicksIsClamped() {
        assertEquals(10.0F, CameraRollQuery.interpolate(0.0F, 10.0F, 5.0F), 0.0001F);
        assertEquals(0.0F, CameraRollQuery.interpolate(0.0F, 10.0F, -3.0F), 0.0001F);
    }
}
