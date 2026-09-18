package com.fox.ysmu.client.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.controller.OpenYsmAnimationControllerRegistry;
import com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime;
import com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime.ActiveAnimation;
import com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime.ProjectileState;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;
import software.bernie.geckolib3.geo.raw.tree.RawGeometryTree;
import software.bernie.geckolib3.geo.render.GeoBuilder;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * 控制器状态动画必须从**进入该状态**的那一刻开始计时。
 *
 * <p>{@code post_main} / {@code post_ground} 这类 {@code hold_on_last_frame} 动画只在开头几 tick
 * 把骨骼放大（"爆开"）。旧实现拿实体总年龄去采样，动画在第二帧就被钳到末尾：0.2 秒长的落地
 * 动画从第一帧起就停在最后一帧的"缩放 0"上，整段爆开效果被跳过（实测落地箭矢的箭身缩放
 * 永远到不了 2.4）。</p>
 *
 * <p>修法：{@link ProjectileControllerRuntime#getActiveAnimationEntries} 为每条状态动画带回
 * "相对状态进入时刻的偏移"，渲染器用 {@code ageInTicks - tickOffset} 采样；状态再次切换时
 * 偏移归零，动画从头播。</p>
 */
class ProjectileStateClockTest {

    private static final ResourceLocation ANIM_ID = new ResourceLocation("ysmu", "_test_state_clock");

    private static final String GEO_JSON = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},"
        + "\"bones\":[{\"name\":\"b\",\"pivot\":[0,0,0],"
        + "\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";

    /**
     * post_main：1 tick 内从 0 放大到 2.0 后停住（飞行期一直保持 2.0）。
     * post_ground：4 tick 内 0 -> 2.4（爆开）-> 0（收掉箭身，只留外层光效）。
     */
    private static final String ANIM_JSON = "{\"format_version\":\"1.19.0\",\"animations\":{"
        + "\"post_main\":{\"animation_length\":0.05,\"loop\":\"hold_on_last_frame\",\"bones\":{"
        + "\"b\":{\"scale\":{\"0.0\":0.0,\"0.05\":2.0}}}},"
        + "\"post_ground\":{\"animation_length\":0.2,\"loop\":\"hold_on_last_frame\",\"bones\":{"
        + "\"b\":{\"scale\":{\"0.0\":0.0,\"0.0833\":2.4,\"0.2083\":0.0}}}},"
        + "\"air\":{\"loop\":true,\"bones\":{\"b\":{\"scale\":1.0}}},"
        + "\"parallel0\":{\"loop\":true,\"bones\":{\"b\":{\"scale\":1.0}}}"
        + "}}";

    /** 与真实弹射物控制器同形：default --ysm.in_ground--> 箭矢落地。 */
    private static final String CTRL_JSON = "{\"animation_controllers\":{"
        + "\"projectile.post_main\":{\"initial_state\":\"default\",\"states\":{"
        + "\"default\":{\"animations\":[\"post_main\"],\"transitions\":[{\"箭矢落地\":\"ysm.in_ground\"}]},"
        + "\"箭矢落地\":{\"animations\":[\"post_ground\"],\"transitions\":[{\"default\":\"!ysm.in_ground\"}]}"
        + "}}},\"format_version\":\"1.19.0\"}";

    private static void var(String name, double value) {
        MolangParser.VARIABLES.computeIfAbsent(name, k -> new LazyVariable(k, () -> 0.0)).set(value);
    }

    private static GeoBone bone(GeoModel model) {
        for (GeoBone b : model.topLevelBones) {
            if ("b".equals(b.name)) return b;
        }
        throw new IllegalStateException("bone 'b' missing");
    }

    private static void saveSnapshots(GeoModel model) {
        for (GeoBone b : model.topLevelBones) {
            b.saveInitialSnapshot();
        }
    }

    private static Map<String, Double> offsetsOf(List<ActiveAnimation> entries) {
        Map<String, Double> offsets = new HashMap<>();
        for (ActiveAnimation entry : entries) {
            offsets.put(entry.name, entry.tickOffset);
        }
        return offsets;
    }

    private static List<String> namesOf(List<ActiveAnimation> entries) {
        List<String> names = new ArrayList<>();
        for (ActiveAnimation entry : entries) {
            names.add(entry.name);
        }
        return names;
    }

    @AfterEach
    void tearDown() {
        GeckoLibCache.getInstance().getAnimations().remove(ANIM_ID);
        OpenYsmAnimationControllerRegistry.clear();
        var("ysm.in_ground", 0.0);
    }

    /** 状态刚进入时偏移为 0，随后随实体年龄一起增长。 */
    @Test
    void tickOffsetStartsAtStateEntryAndGrowsWithAge() {
        AnimationFile file = ClientModelManager.parseAnimationFileFromJson(ANIM_JSON);
        GeckoLibCache.getInstance().getAnimations().put(ANIM_ID, file);
        OpenYsmAnimationControllerRegistry.register(ANIM_ID,
            Collections.singletonList(CTRL_JSON.getBytes(StandardCharsets.UTF_8)));
        var("ysm.in_ground", 0.0);

        // 第 4 支箭（独立 entityId）：首次求值 = 进入 default 状态
        List<ActiveAnimation> atEntry = ProjectileControllerRuntime.getActiveAnimationEntries(4, ANIM_ID, 5.0,
            ProjectileState.of(false, false, false));
        assertEquals("post_main", atEntry.get(0).name, namesOf(atEntry).toString());
        assertEquals(0.0, atEntry.get(0).tickOffset, 1.0e-9, "进入状态那一帧偏移必须是 0");

        List<ActiveAnimation> later = ProjectileControllerRuntime.getActiveAnimationEntries(4, ANIM_ID, 8.0,
            ProjectileState.of(false, false, false));
        assertEquals("post_main", later.get(0).name);
        assertEquals(3.0, later.get(0).tickOffset, 1.0e-9, "偏移 = 实体年龄 - 状态进入时刻");

        // 飞行中再次求值不能把偏移重置（状态没变）
        List<ActiveAnimation> again = ProjectileControllerRuntime.getActiveAnimationEntries(4, ANIM_ID, 9.0,
            ProjectileState.of(false, false, false));
        assertEquals(4.0, again.get(0).tickOffset, 1.0e-9);
    }

    /** 落地切换状态后，落地动画的偏移必须从 0 重新开始。 */
    @Test
    void tickOffsetResetsWhenControllerEntersAnotherState() {
        AnimationFile file = ClientModelManager.parseAnimationFileFromJson(ANIM_JSON);
        GeckoLibCache.getInstance().getAnimations().put(ANIM_ID, file);
        OpenYsmAnimationControllerRegistry.register(ANIM_ID,
            Collections.singletonList(CTRL_JSON.getBytes(StandardCharsets.UTF_8)));
        var("ysm.in_ground", 0.0);

        ProjectileControllerRuntime.getActiveAnimationEntries(5, ANIM_ID, 30.0,
            ProjectileState.of(false, false, false));

        // 第 32 tick 落地：状态切换，落地动画从 0 开始
        var("ysm.in_ground", 1.0);
        List<ActiveAnimation> landing = ProjectileControllerRuntime.getActiveAnimationEntries(5, ANIM_ID, 32.0,
            ProjectileState.of(true, false, false));
        assertEquals("post_ground", landing.get(0).name, "落地后默认状态动画必须换成落地动画: " + namesOf(landing));
        assertTrue(!namesOf(landing).contains("post_main"), namesOf(landing).toString());
        assertEquals(0.0, landing.get(0).tickOffset, 1.0e-9, "状态切换后偏移必须归零");

        List<ActiveAnimation> half = ProjectileControllerRuntime.getActiveAnimationEntries(5, ANIM_ID, 32.5,
            ProjectileState.of(true, false, false));
        assertEquals(0.5, half.get(0).tickOffset, 1.0e-9);
    }

    /** 非控制器动画（air/parallel*）不受状态时钟影响，偏移恒为 0。 */
    @Test
    void implicitAnimationsKeepEntityAgeClock() {
        AnimationFile file = ClientModelManager.parseAnimationFileFromJson(ANIM_JSON);
        GeckoLibCache.getInstance().getAnimations().put(ANIM_ID, file);
        OpenYsmAnimationControllerRegistry.register(ANIM_ID,
            Collections.singletonList(CTRL_JSON.getBytes(StandardCharsets.UTF_8)));
        var("ysm.in_ground", 0.0);

        ProjectileControllerRuntime.getActiveAnimationEntries(6, ANIM_ID, 40.0,
            ProjectileState.of(false, false, false));
        List<ActiveAnimation> entries = ProjectileControllerRuntime.getActiveAnimationEntries(6, ANIM_ID, 43.0,
            ProjectileState.of(false, false, false));

        assertEquals(3.0, entryOffset(entries, "post_main"), 1.0e-9, "状态动画跟随状态时钟");
        assertEquals(0.0, entryOffset(entries, "air"), 1.0e-9, "飞行状态动画仍按实体年龄播放");
        assertEquals(0.0, entryOffset(entries, "parallel0"), 1.0e-9, "并行动画仍按实体年龄播放");
    }

    private static double entryOffset(List<ActiveAnimation> entries, String name) {
        for (ActiveAnimation entry : entries) {
            if (entry.name.equals(name)) return entry.tickOffset;
        }
        throw new IllegalStateException("missing animation " + name + " in " + namesOf(entries));
    }

    /** 渲染侧回归：带偏移采样时，落地动画能播到"爆开"的那一帧。 */
    @Test
    void rendererReachesTheImpactPopOnlyWithStateOffsets() throws Exception {
        AnimationFile file = ClientModelManager.parseAnimationFileFromJson(ANIM_JSON);
        RawGeoModel raw = Converter.fromJsonString(GEO_JSON);
        GeoModel model = GeoBuilder.getGeoBuilder("ysmu").constructGeoModel(RawGeometryTree.parseHierarchy(raw));
        GeoBone target = bone(model);
        assertNotNull(target);
        saveSnapshots(model);

        // 落地后已经过了 100 tick（实体年龄），实际落地那一刻的实体年龄是 99.5：
        // 状态时钟 → 采样 0.5 tick；实体年龄时钟 → 采样 100 tick（被钳到末尾 = 0.0）
        double entityAge = 100.0;
        double sinceLanding = 0.5;
        Map<String, Double> offsets = Collections.singletonMap("post_ground", entityAge - sinceLanding);

        ArrowProjectileRenderer.applyActiveAnimations(model, file, Collections.singletonList("post_ground"), offsets,
            entityAge, false);
        assertEquals(0.72f, target.getScaleX(), 1.0e-3f,
            "按状态进入时刻采样时必须落在爆开过程中（0.5 tick / 1.666 tick * 2.4 = 0.72）");

        ArrowProjectileRenderer.applyActiveAnimations(model, file, Collections.singletonList("post_ground"),
            entityAge, false);
        assertEquals(0.0f, target.getScaleX(), 1.0e-3f,
            "按实体年龄采样会直接停在最后一帧（缩放 0）—— 这正是被修掉的旧行为");
    }

    /** 飞行期 post_main 的"停在最后一帧"语义不能被状态时钟改坏。 */
    @Test
    void flightAnimationStillHoldsItsLastFrame() throws Exception {
        AnimationFile file = ClientModelManager.parseAnimationFileFromJson(ANIM_JSON);
        RawGeoModel raw = Converter.fromJsonString(GEO_JSON);
        GeoModel model = GeoBuilder.getGeoBuilder("ysmu").constructGeoModel(RawGeometryTree.parseHierarchy(raw));
        GeoBone target = bone(model);
        saveSnapshots(model);

        // 控制器在出生帧进入 default：偏移 = 0 表示"从出生起算"
        ArrowProjectileRenderer.applyActiveAnimations(model, file, Collections.singletonList("post_main"),
            Collections.singletonMap("post_main", 0.0), 40.0, false);
        assertEquals(2.0f, target.getScaleX(), 1.0e-4f, "1 tick 后必须停在 2.0（hold_on_last_frame）");

        // 出生那一刻（tick 0）应为动画起点值
        ArrowProjectileRenderer.applyActiveAnimations(model, file, Collections.singletonList("post_main"),
            Collections.singletonMap("post_main", 0.0), 0.0, false);
        assertEquals(0.0f, target.getScaleX(), 1.0e-4f, "tick 0 是动画起点（隐藏），不能提前跳到终点");

    }
}
