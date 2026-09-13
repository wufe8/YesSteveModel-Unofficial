package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@code ysm.ground_speed2}：每 tick 位置差值 ×20 的真实水平速度。
 *
 * <p>wiki 里的参考量级（1.7.10 客户端实测的 pos 差值）：行走 ≈0.216/tick → 4.32 blocks/s，
 * 疾跑 ≈0.28/tick → 5.61，静止 0。注意它比 {@code query.ground_speed}（walk≈1.7 / run≈3.2）
 * 大不少 —— wiki 明确说 ground_speed2 "数值稍大"，所以模型用它时阈值也是按真实速度写的。</p>
 */
class RemotePlayerAnimationQueriesTest {

    @Test
    void stationaryPlayerHasZeroSpeed() {
        assertEquals(0.0F, RemotePlayerAnimationQueries.realGroundSpeed(0.0d, 0.0d), 0.0001F);
    }

    @Test
    void walkingSpeedIsRealDistanceTimesTwenty() {
        assertEquals(4.32F, RemotePlayerAnimationQueries.realGroundSpeed(0.216d, 0.0d), 0.0001F);
        assertEquals(4.32F, RemotePlayerAnimationQueries.realGroundSpeed(0.0d, -0.216d), 0.0001F);
    }

    /** 斜向移动取欧氏长度，不能把两轴相加。 */
    @Test
    void diagonalMovementUsesEuclideanLength() {
        assertEquals(3.0547F, RemotePlayerAnimationQueries.realGroundSpeed(0.108d, 0.108d), 0.001F);
    }

    /** 与 query.ground_speed 的差别就在这里：真实速度不裁切上限（20 blocks/s 的飞行也算得出来）。 */
    @Test
    void fastMovementIsNotClamped() {
        // query.ground_speed 会被 clamp 到 MAX_GROUND_SPEED=12；ground_speed2 不该被裁。
        assertEquals(20.0F, RemotePlayerAnimationQueries.realGroundSpeed(1.0d, 0.0d), 0.0001F);
    }
}
