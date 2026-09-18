package com.fox.ysmu.client.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.ClientModelManager;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.keyframe.BoneAnimation;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;
import software.bernie.geckolib3.geo.raw.tree.RawGeometryTree;
import software.bernie.geckolib3.geo.render.GeoBuilder;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * 弹射物动画的**通道存在性**：只写了一条通道的动画不能把别的通道清零。
 *
 * <p>解析器会给动画没写的通道留下一个存在但三轴为空的 {@code VectorKeyFrameList}
 * （{@code scaleKeyFrames != null} 但 {@code xKeyFrames/yKeyFrames/zKeyFrames} 全空）。
 * 旧实现只判断 {@code frames != null} 就把三个空轴算成 0 写进骨骼：于是"只旋转"的动画
 * 把前一条动画写好的缩放清零，"只缩放"的动画把旋转清零，反之亦然。</p>
 *
 * <p>真实案例：某弹射物的外圈骨骼由 {@code parallel2} 写 0.9 缩放、由 {@code parallel3}
 * 只写 {@code 0 → -360} 度旋转。缩放松在 0 上，落地后旋转的六边形光效完全不可见 ——
 * 看起来像"外层动画没生效"。修法参照 GeckoLib 玩家路径 {@code AnimationProcessor.tickAnimation}：
 * 三轴关键帧点齐全时才写这条通道。</p>
 */
class ProjectileAnimationChannelTest {

    private static final String GEO_JSON = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},"
        + "\"bones\":[{\"name\":\"b\",\"pivot\":[0,0,0],"
        + "\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";

    /** glow 只写缩放，spin 只写旋转，move 只写位移 —— 三条动画互相不该踩对方的通道。 */
    private static final String ANIM_JSON = "{\"format_version\":\"1.19.0\",\"animations\":{"
        + "\"glow\":{\"loop\":true,\"bones\":{\"b\":{\"scale\":0.9}}},"
        + "\"spin\":{\"loop\":true,\"bones\":{\"b\":{\"rotation\":{\"0.0\":0.0,\"1.0\":[0.0,0.0,-360.0]}}}},"
        + "\"move\":{\"loop\":true,\"bones\":{\"b\":{\"position\":{\"0.0\":[0.0,0.0,0.0],\"1.0\":[4.0,0.0,0.0]}}}}"
        + "}}";

    private static GeoModel loadGeo() throws Exception {
        RawGeoModel raw = Converter.fromJsonString(GEO_JSON);
        RawGeometryTree tree = RawGeometryTree.parseHierarchy(raw);
        return GeoBuilder.getGeoBuilder("ysmu").constructGeoModel(tree);
    }

    private static GeoBone bone(GeoModel model) {
        for (GeoBone b : model.topLevelBones) {
            if ("b".equals(b.name)) return b;
        }
        throw new IllegalStateException("bone 'b' missing");
    }

    private static void saveSnapshots(List<GeoBone> bones) {
        for (GeoBone bone : bones) {
            bone.saveInitialSnapshot();
            saveSnapshots(bone.childBones);
        }
    }

    private static GeoBone apply(String... anims) throws Exception {
        AnimationFile file = ClientModelManager.parseAnimationFileFromJson(ANIM_JSON);
        GeoModel model = loadGeo();
        GeoBone target = bone(model);
        saveSnapshots(model.topLevelBones);
        // tick 10 = 动画中点（关键帧在 0.0s / 1.0s，解析后 = 0 / 20 tick，循环动画取模）：
        // 旋转到 -180 度（解析器把旋转关键帧预先换成弧度，-π），位移到 x=2，缩放恒为 0.9
        ArrowProjectileRenderer.applyActiveAnimations(model, file, Arrays.asList(anims), 10.0, false);
        return target;
    }

    /** 前提：解析出来的"没写"通道确实是一个空三轴的 VectorKeyFrameList（这正是旧实现踩的坑）。 */
    @Test
    void unanimatedChannelsArePresentButEmpty() {
        AnimationFile file = ClientModelManager.parseAnimationFileFromJson(ANIM_JSON);
        Animation spin = file.animations.get("spin");
        BoneAnimation spinBone = spin.boneAnimations.get(0);
        assertNotNull(spinBone.scaleKeyFrames, "旋转动画的缩放通道必须存在（解析器行为）");
        assertTrue(spinBone.scaleKeyFrames.xKeyFrames.isEmpty(), "旋转动画的缩放通道不应有关键帧");
        assertTrue(spinBone.scaleKeyFrames.yKeyFrames.isEmpty());
        assertTrue(spinBone.scaleKeyFrames.zKeyFrames.isEmpty());
        assertTrue(spinBone.positionKeyFrames.xKeyFrames.isEmpty(), "旋转动画的位移通道也不应有关键帧");
    }

    /** 只旋转的动画不能把缩放清零（回归：外圈光效骨骼 0.9 → 0）。 */
    @Test
    void rotationOnlyAnimationKeepsScale() throws Exception {
        GeoBone target = apply("glow", "spin");
        assertEquals(0.9f, target.getScaleX(), 1.0e-4f, "缩放必须保持在 0.9，不能被空缩放通道清零");
        assertEquals(0.9f, target.getScaleY(), 1.0e-4f);
        assertEquals(0.9f, target.getScaleZ(), 1.0e-4f);
        assertEquals(-3.14159f, target.getRotationZ(), 1.0e-3f, "旋转本身必须生效");
    }

    /** 反过来也要成立：只缩放的动画不能把旋转清零。 */
    @Test
    void scaleOnlyAnimationKeepsRotation() throws Exception {
        GeoBone target = apply("spin", "glow");
        assertEquals(-3.14159f, target.getRotationZ(), 1.0e-3f, "旋转必须保持在中点 -180 度，不能被空旋转通道清零");
        assertEquals(0.9f, target.getScaleX(), 1.0e-4f);
    }

    /** 只位移的动画不能把缩放/旋转清零。 */
    @Test
    void positionOnlyAnimationKeepsScaleAndRotation() throws Exception {
        GeoBone target = apply("glow", "spin", "move");
        assertEquals(0.9f, target.getScaleX(), 1.0e-4f);
        assertEquals(-3.14159f, target.getRotationZ(), 1.0e-3f);
        assertEquals(2.0f, target.getPositionX(), 1.0e-4f, "位移本身必须生效");
    }

    /** 三轴齐全的通道照常覆盖前一条动画（别把正常覆盖也一起关掉）。 */
    @Test
    void completeChannelsStillOverwrite() throws Exception {
        String json = "{\"format_version\":\"1.19.0\",\"animations\":{"
            + "\"base\":{\"loop\":true,\"bones\":{\"b\":{\"scale\":0.5}}},"
            + "\"override\":{\"loop\":true,\"bones\":{\"b\":{\"scale\":{\"0.0\":2.0,\"1.0\":3.0}}}}}}";
        AnimationFile file = ClientModelManager.parseAnimationFileFromJson(json);
        GeoModel model = loadGeo();
        GeoBone target = bone(model);
        saveSnapshots(model.topLevelBones);

        ArrowProjectileRenderer.applyActiveAnimations(model, file, Arrays.asList("base", "override"), 10.0, false);
        assertEquals(2.5f, target.getScaleX(), 1.0e-4f, "后一条动画的三轴关键帧必须覆盖前一条");
    }
}
