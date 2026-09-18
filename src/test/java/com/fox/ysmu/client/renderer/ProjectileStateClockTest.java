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
import org.junit.jupiter.api.BeforeEach;
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
 * 控制器状态动画必须从**进入该状态**的那一刻开始计时，而且渲染器拿到的"时钟原点"只能被减一次。
 *
 * <p>回归点（由一次实机日志定位）：运行时曾把"已播放时长 = age - enteredTick"当原点交给渲染器，
 * 而渲染器统一用 {@code animAge = age - 原点} 采样 —— 两次相减得到的是 {@code enteredTick} 本身：</p>
 * <ul>
 *   <li>飞行期（出生帧就进入 default，enteredTick≈0）：{@code animAge≈0} → 箭身停在动画**第一帧**
 *       （缩放 0），官方那种"飞行时的蓝色方块"根本不出现；</li>
 *   <li>落地：{@code animAge = 落地时的实体年龄}。远距离射箭落地晚 → 被钳到末帧（箭身收掉，看着"正常"）；
 *       近距离 3~5 格落地早 → 采样落在爆开动画中段，箭身明显可见且"停住不动"。</li>
 * </ul>
 *
 * <p>现在原点 = 进入状态时的实体年龄，{@code animAge} = 进入状态后经过的时长。本测试直接断言
 * **骨骼最终值**（不是内部字段），上面两种错误都会让它失败。</p>
 */
class ProjectileStateClockTest {

    private static final ResourceLocation ANIM_ID = new ResourceLocation("ysmu", "_test_state_clock");
    private static final int ENTITY_ID = 7331;

    private static final String GEO_JSON = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},"
        + "\"bones\":[{\"name\":\"b\",\"pivot\":[0,0,0],"
        + "\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";

    /**
     * post_main：2 tick 内 0 → 2.0 后停住（飞行期保持 2.0 = "飞行时的蓝色方块"）。
     * post_ground：2 tick 内 0.5 → 2.5 后停住（起点不等于终点，便于分辨"从头播"和"停在末帧"）。
     */
    private static final String ANIM_JSON = "{\"format_version\":\"1.19.0\",\"animations\":{"
        + "\"post_main\":{\"animation_length\":0.1,\"loop\":\"hold_on_last_frame\",\"bones\":{"
        + "\"b\":{\"scale\":{\"0.0\":0.0,\"0.1\":2.0}}}},"
        + "\"post_ground\":{\"animation_length\":0.1,\"loop\":\"hold_on_last_frame\",\"bones\":{"
        + "\"b\":{\"scale\":{\"0.0\":0.5,\"0.1\":2.5}}}}"
        + "}}";

    /** 与真实弹射物控制器同形：default --ysm.in_ground--> 箭矢落地。 */
    private static final String CTRL_JSON = "{\"animation_controllers\":{"
        + "\"projectile.post_main\":{\"initial_state\":\"default\",\"states\":{"
        + "\"default\":{\"animations\":[\"post_main\"],\"transitions\":[{\"箭矢落地\":\"ysm.in_ground\"}]},"
        + "\"箭矢落地\":{\"animations\":[\"post_ground\"],\"transitions\":[{\"default\":\"!ysm.in_ground\"}]}"
        + "}}},\"format_version\":\"1.19.0\"}";

    private AnimationFile file;
    private GeoModel model;

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

    private static void reset(GeoModel model) {
        for (GeoBone b : model.topLevelBones) {
            var snap = b.getInitialSnapshot();
            if (snap != null) {
                b.setScaleX((float) snap.scaleValueX);
                b.setScaleY((float) snap.scaleValueY);
                b.setScaleZ((float) snap.scaleValueZ);
            }
        }
    }

    private static String namesOf(List<ActiveAnimation> entries) {
        List<String> names = new ArrayList<>();
        for (ActiveAnimation entry : entries) {
            names.add(entry.name);
        }
        return names.toString();
    }

    private static double startTick(List<ActiveAnimation> entries, String name) {
        for (ActiveAnimation entry : entries) {
            if (entry.name.equals(name)) return entry.startTick;
        }
        throw new IllegalStateException("missing " + name + " in " + namesOf(entries));
    }

    /** 走完整的渲染路径：取活动动画 + 时钟原点 → 复位 → 写关键帧，返回骨骼缩放。 */
    private float frame(double ageInTicks, boolean inGround) {
        var("ysm.in_ground", inGround ? 1.0 : 0.0);
        List<ActiveAnimation> entries = ProjectileControllerRuntime.getActiveAnimationEntries(
            ENTITY_ID, ANIM_ID, ageInTicks, ProjectileState.of(inGround, false, false));
        List<String> names = new ArrayList<>();
        Map<String, Double> origins = new HashMap<>();
        for (ActiveAnimation entry : entries) {
            names.add(entry.name);
            if (entry.startTick != 0.0d) {
                origins.put(entry.name, entry.startTick);
            }
        }
        reset(model);
        ArrowProjectileRenderer.applyActiveAnimations(model, file, names, origins, ageInTicks, false);
        return bone(model).getScaleX();
    }

    @BeforeEach
    void setUp() throws Exception {
        file = ClientModelManager.parseAnimationFileFromJson(ANIM_JSON);
        GeckoLibCache.getInstance().getAnimations().put(ANIM_ID, file);
        OpenYsmAnimationControllerRegistry.register(ANIM_ID,
            Collections.singletonList(CTRL_JSON.getBytes(StandardCharsets.UTF_8)));
        RawGeoModel raw = Converter.fromJsonString(GEO_JSON);
        model = GeoBuilder.getGeoBuilder("ysmu").constructGeoModel(RawGeometryTree.parseHierarchy(raw));
        assertNotNull(model.topLevelBones);
        saveSnapshots(model);
        var("ysm.in_ground", 0.0);
    }

    @AfterEach
    void tearDown() {
        GeckoLibCache.getInstance().getAnimations().remove(ANIM_ID);
        OpenYsmAnimationControllerRegistry.clear();
        var("ysm.in_ground", 0.0);
    }

    /** 时钟原点是"进入状态时的实体年龄"（供渲染器做 {@code 年龄 - 原点}），不是已播放时长。 */
    @Test
    void clockOriginIsTheAgeAtStateEntry() {
        var("ysm.in_ground", 0.0);
        List<ActiveAnimation> atEntry = ProjectileControllerRuntime.getActiveAnimationEntries(
            ENTITY_ID + 1, ANIM_ID, 5.0, ProjectileState.of(false, false, false));
        assertEquals("post_main", atEntry.get(0).name, namesOf(atEntry));
        assertEquals(5.0, atEntry.get(0).startTick, 1.0e-9, "原点 = 进入状态的实体年龄");

        List<ActiveAnimation> later = ProjectileControllerRuntime.getActiveAnimationEntries(
            ENTITY_ID + 1, ANIM_ID, 8.0, ProjectileState.of(false, false, false));
        assertEquals(5.0, later.get(0).startTick, 1.0e-9, "同一状态内原点不变（此时已播放 3 tick）");

        var("ysm.in_ground", 1.0);
        List<ActiveAnimation> landed = ProjectileControllerRuntime.getActiveAnimationEntries(
            ENTITY_ID + 1, ANIM_ID, 8.0, ProjectileState.of(true, false, false));
        assertEquals("post_ground", landed.get(0).name, namesOf(landed));
        assertEquals(8.0, landed.get(0).startTick, 1.0e-9, "切状态后原点 = 切换时的年龄");
    }

    /** 非控制器动画原点 0：渲染器减 0 就是"按实体年龄采样"。 */
    @Test
    void implicitAnimationsKeepEntityAgeClock() {
        AnimationFile withImplicit = ClientModelManager.parseAnimationFileFromJson(ANIM_JSON
            .replace("\"post_ground\":{",
                "\"parallel0\":{\"loop\":true,\"bones\":{\"b\":{\"scale\":1.0}}},\"post_ground\":{"));
        GeckoLibCache.getInstance().getAnimations().put(ANIM_ID, withImplicit);
        var("ysm.in_ground", 0.0);

        List<ActiveAnimation> entries = ProjectileControllerRuntime.getActiveAnimationEntries(
            ENTITY_ID + 2, ANIM_ID, 43.0, ProjectileState.of(false, false, false));
        assertEquals(0.0, startTick(entries, "parallel0"), 1.0e-9, "并行动画原点恒为 0");
        assertEquals(43.0, startTick(entries, "post_main"), 1.0e-9, "状态动画原点是进入时刻");
    }

    /**
     * 核心回归（飞行）：出生帧进入 default、之后一直飞 —— 箭身必须停在动画末帧 2.0。
     * 双重相减时会采样到第一帧的 0.0（表现为"飞行时看不到蓝色方块"）。
     */
    @Test
    void flightBodyHoldsItsLastFrameInsteadOfRestartingAtZero() {
        assertEquals(0.0f, frame(0.0d, false), 1.0e-3f, "出生帧 = 动画第一帧");
        assertEquals(2.0f, frame(2.0d, false), 1.0e-3f, "2 tick 后到动画末帧");
        assertEquals(2.0f, frame(40.0d, false), 1.0e-3f,
            "一直在飞行时必须保持 2.0（双重相减会退化成 0.0）");
    }

    /**
     * 核心回归（近距离落地）：只飞了 3 tick 就落地的箭，"落地后经过的时长"应从 0 开始。
     * 双重相减时 {@code animAge} = 落地年龄（3）→ 采样停在爆开动画中段，箭身可见且不再变化。
     */
    @Test
    void earlyLandingPlaysTheImpactAnimationFromItsStart() {
        frame(0.0d, false);
        frame(3.0d, false);

        assertEquals(0.5f, frame(3.0d, true), 1.0e-3f, "落地帧 = 落地动画第一帧");
        assertEquals(1.0f, frame(3.5d, true), 1.0e-3f, "0.5/2 tick = 25% 进度（0.5 → 2.5）");
        assertEquals(2.5f, frame(5.0d, true), 1.0e-3f, "之后停在末帧");
        assertEquals(2.5f, frame(30.0d, true), 1.0e-3f, "长时间保持落地状态也停在末帧");
    }

    /** 远距离落地（很晚才落地）同样从动画第一帧开始。 */
    @Test
    void lateLandingPlaysTheImpactAnimationFromItsStart() {
        frame(0.0d, false);
        frame(20.0d, false);
        assertEquals(0.5f, frame(20.0d, true), 1.0e-3f, "远距离落地同样从第一帧开始");
        assertEquals(2.5f, frame(22.0d, true), 1.0e-3f);
    }

    /** 采样顺序：状态动画排在前面（后面的动画才能覆盖它）。 */
    @Test
    void activeListKeepsStateAnimationFirst() {
        var("ysm.in_ground", 0.0);
        List<ActiveAnimation> entries = ProjectileControllerRuntime.getActiveAnimationEntries(
            ENTITY_ID + 3, ANIM_ID, 4.0, ProjectileState.of(false, false, false));
        assertTrue(entries.size() >= 1);
        assertEquals("post_main", entries.get(0).name);
    }
}
