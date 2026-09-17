package com.fox.ysmu.client.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

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
 * 以"阶梯关键帧"（{@code {"pre":x,"post":y}}）结尾的动画必须能停在 post 值上。
 *
 * <p>Bedrock 的阶梯关键帧在解析时被展开成两个间隔 1e-7 秒的关键帧，所以关键帧跨度会比
 * JSON 里的 {@code animation_length} 大 2e-6 tick。采样 tick 被钳到 {@code animation_length}
 * 时就落在了最后一段阶梯**内部**（约 2% 处），"停在最后一帧"永远拿不到 post 值。
 * 真实案例：某弹射物把箭身缩放到 0.8 的阶梯放在 {@code post_main} 末尾，实测只剩 0.015，
 * 整套光效骨骼被压到 2%，看起来像"动画没生效、模型停在绑定姿势"。</p>
 */
class ArrowProjectileStepKeyFrameTest {

    private static final double TICKS_PER_SECOND = 20.0;

    private static final String GEO_JSON = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},"
        + "\"bones\":[{\"name\":\"b\",\"pivot\":[0,0,0],"
        + "\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";

    private static final String ANIM_JSON = "{\"format_version\":\"1.19.0\",\"animations\":{"
        + "\"step\":{\"animation_length\":0.1,\"loop\":\"hold_on_last_frame\",\"bones\":{"
        + "\"b\":{\"scale\":{\"0.0\":0.0,\"0.1\":{\"pre\":0.0,\"post\":1.0}}}}}}}";

    private static GeoModel loadGeo() throws Exception {
        RawGeoModel raw = Converter.fromJsonString(GEO_JSON);
        RawGeometryTree tree = RawGeometryTree.parseHierarchy(raw);
        return GeoBuilder.getGeoBuilder("ysmu").constructGeoModel(tree);
    }

    private static GeoBone bone(GeoModel model, String name) {
        for (GeoBone b : model.topLevelBones) {
            if (name.equals(b.name)) return b;
            GeoBone found = child(b, name);
            if (found != null) return found;
        }
        return null;
    }

    private static GeoBone child(GeoBone bone, String name) {
        if (bone.childBones == null) return null;
        for (GeoBone b : bone.childBones) {
            if (name.equals(b.name)) return b;
            GeoBone found = child(b, name);
            if (found != null) return found;
        }
        return null;
    }

    private static void saveSnapshots(java.util.List<GeoBone> bones) {
        if (bones == null) return;
        for (GeoBone bone : bones) {
            bone.saveInitialSnapshot();
            saveSnapshots(bone.childBones);
        }
    }

    /** 解析后的长度至少要覆盖最后一个关键帧的结束时间。 */
    @Test
    void parsedAnimationLengthCoversTheStepKeyFrameSpan() {
        AnimationFile file = ClientModelManager.parseAnimationFileFromJson(ANIM_JSON);
        assertNotNull(file);
        Animation step = file.animations.get("step");
        assertNotNull(step);
        assertTrue(step.animationLength >= 2.0 + 2.0e-6,
            "animation_length 必须覆盖阶梯关键帧跨度，实际 " + step.animationLength);
    }

    /** 行为断言：age 远超时长时，骨骼必须停在阶梯的 post 值（1.0），而不是插值到 0。 */
    @Test
    void holdOnLastFrameReachesTheStepPostValue() throws Exception {
        AnimationFile file = ClientModelManager.parseAnimationFileFromJson(ANIM_JSON);
        GeoModel model = loadGeo();
        GeoBone target = bone(model, "b");
        assertNotNull(target);
        saveSnapshots(model.topLevelBones);

        ArrowProjectileRenderer.applyActiveAnimations(model, file, Collections.singletonList("step"),
            100.0 * TICKS_PER_SECOND, false);

        assertEquals(1.0f, target.getScaleX(), 1.0e-4f,
            "停在最后一帧必须拿到 post 值；落在阶梯内部会插值出 ~0.02 倍");
        assertEquals(1.0f, target.getScaleY(), 1.0e-4f);
    }

    /** 中段的阶梯关键帧行为不能被顺手改坏：它仍然是"瞬间跳变"，不是斜坡。 */
    @Test
    void midTimelineStepStillJumps() throws Exception {
        // 0.0s: 0 -> 0.05s 阶梯(pre 0, post 1) -> 0.1s: 1
        String json = "{\"format_version\":\"1.19.0\",\"animations\":{\"step\":{"
            + "\"animation_length\":0.1,\"loop\":true,\"bones\":{\"b\":{\"scale\":{"
            + "\"0.0\":0.0,\"0.05\":{\"pre\":0.0,\"post\":1.0},\"0.1\":1.0}}}}}}";
        AnimationFile file = ClientModelManager.parseAnimationFileFromJson(json);
        GeoModel model = loadGeo();
        GeoBone target = bone(model, "b");
        assertNotNull(target);
        saveSnapshots(model.topLevelBones);

        // 0.5 tick = 0.025s：阶梯之前 → pre 值 0.0
        ArrowProjectileRenderer.applyActiveAnimations(model, file, Collections.singletonList("step"), 0.5, false);
        assertEquals(0.0f, target.getScaleX(), 1.0e-3f, "阶梯之前保持 pre 值");

        // 1.5 tick = 0.075s：阶梯之后 → post 值 1.0（瞬间跳变，不是线性上升）
        saveSnapshots(model.topLevelBones);
        ArrowProjectileRenderer.applyActiveAnimations(model, file, Collections.singletonList("step"), 1.5, false);
        assertEquals(1.0f, target.getScaleX(), 1.0e-3f, "阶梯之后必须是 post 值");
    }

    /** 检查解析器没有把"只有 timeline、没有关键帧"的动画长度改写成 MAX_VALUE。 */
    @Test
    void timelineOnlyAnimationLengthIsUntouched() {
        String json = "{\"format_version\":\"1.19.0\",\"animations\":{\"fx\":{"
            + "\"animation_length\":0.5,\"loop\":true,\"timeline\":{\"0\":[\"1+1\"]}}}}";
        AnimationFile file = ClientModelManager.parseAnimationFileFromJson(json);
        assertNotNull(file);
        assertEquals(0.5 * TICKS_PER_SECOND, file.animations.get("fx").animationLength, 1.0e-6);
    }

    /** 阶梯关键帧展开后的 x/y/z 三轴长度一致（三轴才能采到同一个进度）。 */
    @Test
    void stepExpansionKeepsAxesAligned() {
        AnimationFile file = ClientModelManager.parseAnimationFileFromJson(ANIM_JSON);
        Animation step = file.animations.get("step");
        for (BoneAnimation animation : step.boneAnimations) {
            int x = animation.scaleKeyFrames.xKeyFrames.size();
            int y = animation.scaleKeyFrames.yKeyFrames.size();
            int z = animation.scaleKeyFrames.zKeyFrames.size();
            assertEquals(x, y);
            assertEquals(x, z);
            assertEquals(3, x);
        }
    }
}
