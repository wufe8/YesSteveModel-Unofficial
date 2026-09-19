package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code query.distance_from_camera} 必须是**相机**到实体的距离。
 *
 * <p>回归点（实测，用户报的遗留 bug）：原实现是
 * {@code mc.renderViewEntity.getDistanceToEntity(player)}，本机玩家上恒为 0 ——
 * {@code renderViewEntity} 就是玩家自己，第三人称的镜头后退只加在
 * {@code EntityRenderer.orientCamera} 的 GL 变换里。于是模型里
 * {@code !q.is_first_person && q.distance_from_camera < 1.75}（"镜头贴近才脸红"）在第三人称下
 * 永远为真，角色一直显示害羞的脸红部件。</p>
 */
class CameraDistanceQueryTest {

    /** 眼睛在实体原点上方 1.62，站着不动。 */
    private static final double EYE_Y = 1.62D;
    /** 原版第三人称默认视距。 */
    private static final double BACK = 4.0D;

    private static double thirdPerson(float yaw, float pitch, int view) {
        return CameraDistanceQuery.distance(0.0D, EYE_Y, 0.0D, 0.0D, 0.0D, 0.0D, BACK, yaw, pitch, view);
    }

    @Test
    void thirdPersonDefaultIsCameraDistanceNotZero() {
        double d = thirdPerson(0.0F, 0.0F, 1);
        // 相机在 (0, 1.62, -4) → sqrt(4² + 1.62²)
        assertEquals(Math.sqrt(BACK * BACK + EYE_Y * EYE_Y), d, 0.001D);
        assertTrue(d > 1.75D,
            "默认第三人称视距下必须 > 1.75，否则相机贴近类条件永远为真（就是那个遗留 bug）: " + d);
    }

    /** 第一人称：相机在眼睛里，距离就是眼睛到脚下。 */
    @Test
    void firstPersonIsEyeHeight() {
        assertEquals(EYE_Y, CameraDistanceQuery.distance(
            0.0D, EYE_Y, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F, 0), 0.001D);
    }

    /** 前视（F5 两次）：镜头到身前，距离应和背后一致。 */
    @Test
    void frontViewIsSymmetric() {
        assertEquals(thirdPerson(0.0F, 0.0F, 1), thirdPerson(0.0F, 0.0F, 2), 0.001D);
    }

    /** 朝向决定镜头落在哪一侧：yaw=90（朝 -X）时镜头在 +X 侧 4 格。 */
    @Test
    void cameraSitsBehindTheFacingDirection() {
        // 实体挪到 (4, 0, 0)：正好落在镜头上，距离只剩眼睛高度。
        assertEquals(EYE_Y, CameraDistanceQuery.distance(
            0.0D, EYE_Y, 0.0D, 4.0D, 0.0D, 0.0D, BACK, 90.0F, 0.0F, 1), 0.001D);
    }

    /** 俯仰会抬高镜头：pitch=45° 时相机在 (0, 1.62+2.83, -2.83)。 */
    @Test
    void pitchLiftsTheCamera() {
        double d = thirdPerson(0.0F, 45.0F, 1);
        double expected = Math.sqrt(Math.pow(EYE_Y + BACK * Math.sin(Math.PI / 4), 2)
            + Math.pow(BACK * Math.cos(Math.PI / 4), 2));
        assertEquals(expected, d, 0.01D);
    }
}
