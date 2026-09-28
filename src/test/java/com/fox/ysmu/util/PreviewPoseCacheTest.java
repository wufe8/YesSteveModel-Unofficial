package com.fox.ysmu.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

/**
 * 预览缩略图的"姿态没变就跳过几何提交"判定（{@link PreviewPoseCache#shouldSkip}）。
 *
 * <p>回归点：预览动画播完后骨骼姿态逐帧不变（只有定期眨眼会变），而旧实现每次都把整份几何
 * 重新发一遍（实测预览页约 1000 万顶点/秒）。跳过本身很简单，容易写错的是**什么情况下必须画**：
 * 第一次烘焙 / FBO 刚被重建 / 翻页换模型 / 悬停与 gui 动画切换 —— 这些都必须强制重画，
 * 否则会留下空白或过期的缩略图。这里把那条约定锁住。
 */
class PreviewPoseCacheTest {

    @Test
    void exactZeroScaleCannotAliasAThinVisibleBone() {
        com.fox.ysmu.client.animation.VirtualBone bone = new com.fox.ysmu.client.animation.VirtualBone("flat");
        bone.setScaleZ(0f);
        long hidden = PreviewPoseCache.appendBone(7L, bone);
        bone.setScaleZ(.001f);
        long visible = PreviewPoseCache.appendBone(7L, bone);
        org.junit.jupiter.api.Assertions.assertNotEquals(hidden, visible);
        PreviewPoseCache.clear();
        assertFalse(PreviewPoseCache.shouldSkip(A, hidden));
        assertFalse(PreviewPoseCache.shouldSkip(A, visible), "Zero-to-nonzero scale must redraw even inside one quantum");
        PreviewPoseCache.clear();
    }

    @Test
    void continuousSubQuantumMotionStillReusesSignature() {
        com.fox.ysmu.client.animation.VirtualBone bone = new com.fox.ysmu.client.animation.VirtualBone("still");
        long before = PreviewPoseCache.appendBone(7L, bone);
        bone.setPositionX(.001f);
        org.junit.jupiter.api.Assertions.assertEquals(before, PreviewPoseCache.appendBone(7L, bone));
    }

    private static final ResourceLocation A = new ResourceLocation("ysmu", "_test_pose_a");
    private static final ResourceLocation B = new ResourceLocation("ysmu", "_test_pose_b");

    @Test
    void firstSignatureAlwaysDraws() {
        PreviewPoseCache.clear();
        assertFalse(PreviewPoseCache.shouldSkip(A, 12345L), "缓存里没有条目时第一次必须画");
        assertTrue(PreviewPoseCache.shouldSkip(A, 12345L), "同一个签名第二次可以跳过");
        PreviewPoseCache.clear();
    }

    @Test
    void anyBoneChangeDrawsAgain() {
        PreviewPoseCache.clear();
        PreviewPoseCache.shouldSkip(A, 1000L);
        assertFalse(PreviewPoseCache.shouldSkip(A, 1001L), "签名变了（例如眨眼）必须重画");
        assertTrue(PreviewPoseCache.shouldSkip(A, 1001L));
        PreviewPoseCache.clear();
    }

    /** 作废（FBO 重建 / 悬停 / 翻页 / 换贴图）之后即使签名一模一样也必须画。 */
    @Test
    void invalidatedEntryForcesADraw() {
        PreviewPoseCache.clear();
        PreviewPoseCache.shouldSkip(A, 7L);
        assertTrue(PreviewPoseCache.shouldSkip(A, 7L));

        PreviewPoseCache.invalidate(A);
        assertFalse(PreviewPoseCache.shouldSkip(A, 7L), "作废后必须重画一次");
        PreviewPoseCache.clear();
    }

    /** 每个模型各记各的：A 的签名不能替 B 做决定。 */
    @Test
    void modelsDoNotShareASignature() {
        PreviewPoseCache.clear();
        PreviewPoseCache.shouldSkip(A, 42L);

        assertFalse(PreviewPoseCache.shouldSkip(B, 42L), "B 还没画过，必须画");
        assertTrue(PreviewPoseCache.shouldSkip(A, 42L));
        assertTrue(PreviewPoseCache.shouldSkip(B, 42L));
        PreviewPoseCache.clear();
    }
}
