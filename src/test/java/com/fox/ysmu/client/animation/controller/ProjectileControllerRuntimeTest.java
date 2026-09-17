package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime.ProjectileState;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * 弹射物动画选择：{@code air}/{@code ground}/{@code fire}/{@code water} 是**按实体状态**播放的
 * 状态动画，{@code parallel0..7} 才是恒定播放的并行动画。
 *
 * <p>YSM-wiki: 动画制作/弹射物动画（前身 箭矢动画）给出弹射物的完整可自定义动画清单，并且标注
 * 优先级：{@code air}/{@code ground} 优先度低（会被其他动画覆盖），{@code fire}/{@code water}
 * 优先度高（只被并行动画覆盖），并行动画不可覆盖。</p>
 *
 * <p>回归点：旧实现把"控制器没引用到的动画"全部当直通动画播放，于是四个状态动画同时生效 ——
 * 模型为不同状态准备的子模型（弓、弩、爆开、落地插地）会全部叠在同一个弹射物实体上。
 * 没有控制器的模型（只有状态动画 + 并行动画）受害最明显。</p>
 */
class ProjectileControllerRuntimeTest {

    private static final ResourceLocation NO_CTRL = new ResourceLocation("ysmu", "_test_proj_no_ctrl");
    private static final ResourceLocation WITH_CTRL = new ResourceLocation("ysmu", "_test_proj_ctrl");

    /**
     * 与真实弹射物动画文件同形：四个状态动画 + 8 个并行槽位 + 一个非标准名字。
     * 每个状态动画动一根不同骨骼，方便断言"谁真的生效了"。
     */
    private static final String ANIM_JSON = "{ \"format_version\": \"1.19.0\", \"animations\": {"
        + "\"air\":      {\"loop\":true,\"bones\":{\"air_bone\":{\"scale\":1.0}}},"
        + "\"ground\":   {\"loop\":true,\"bones\":{\"ground_bone\":{\"scale\":1.0}}},"
        + "\"fire\":     {\"loop\":true,\"bones\":{\"fire_bone\":{\"scale\":1.0}}},"
        + "\"water\":    {\"loop\":true,\"bones\":{\"water_bone\":{\"scale\":1.0}}},"
        + "\"parallel0\":{\"loop\":true,\"bones\":{\"p0\":{\"scale\":1.0}}},"
        + "\"parallel3\":{\"loop\":true,\"bones\":{\"p3\":{\"scale\":1.0}}},"
        + "\"parallel7\":{\"loop\":true,\"bones\":{\"p7\":{\"scale\":1.0}}},"
        + "\"weird\":    {\"loop\":true,\"bones\":{\"weird_bone\":{\"scale\":1.0}}},"
        + "\"post_main\":{\"loop\":\"hold_on_last_frame\",\"bones\":{\"main_bone\":{\"scale\":1.0}}}"
        + "}}";

    private static final String CTRL_JSON = "{\"format_version\":\"1.19.0\",\"animation_controllers\":{"
        + "\"projectile.post_main\":{\"initial_state\":\"default\",\"states\":{"
        + "\"default\":{\"animations\":[\"post_main\"]}}}}}";

    private static void register(ResourceLocation id, boolean withController) {
        AnimationFile file = ClientModelManager.parseAnimationFileFromJson(ANIM_JSON);
        GeckoLibCache.getInstance().getAnimations().put(id, file);
        if (withController) {
            OpenYsmAnimationControllerRegistry.register(id,
                Collections.singletonList(CTRL_JSON.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @BeforeEach
    void setUp() {
        register(NO_CTRL, false);
    }

    @AfterEach
    void tearDown() {
        GeckoLibCache.getInstance().getAnimations().remove(NO_CTRL);
        GeckoLibCache.getInstance().getAnimations().remove(WITH_CTRL);
        OpenYsmAnimationControllerRegistry.clear();
    }

    private static List<String> select(boolean inGround, boolean inWater, boolean onFire) {
        return ProjectileControllerRuntime.getActiveAnimations(1, NO_CTRL, 5.0,
            ProjectileState.of(inGround, inWater, onFire));
    }

    @Test
    void flyingArrowPlaysOnlyAirAndParallels() {
        List<String> active = select(false, false, false);
        assertTrue(active.contains("air"), active.toString());
        assertFalse(active.contains("ground"), "落地动画不能在飞行时播放: " + active);
        assertFalse(active.contains("fire"), active.toString());
        assertFalse(active.contains("water"), active.toString());
    }

    @Test
    void landedArrowPlaysGroundNotAir() {
        List<String> active = select(true, false, false);
        assertTrue(active.contains("ground"), active.toString());
        assertFalse(active.contains("air"), active.toString());
    }

    @Test
    void burningOrWetArrowLayersOnTopOfAir() {
        // air 优先度低：入水/着火时它仍在（空白骨骼由高优先度的状态动画覆盖）。
        List<String> wet = select(false, true, false);
        assertTrue(wet.contains("water"), wet.toString());
        assertTrue(wet.contains("air"), wet.toString());

        List<String> burning = select(false, false, true);
        assertTrue(burning.contains("fire"), burning.toString());
        assertTrue(burning.contains("air"), burning.toString());
    }

    /**
     * 核心回归：**每个状态动画只允许在它对应的状态成立时出现**。
     * 旧实现无条件播放全部四个，所以"飞行中同时播 ground/fire/water"是常态。
     */
    @Test
    void eachStateAnimationRequiresItsOwnState() {
        for (boolean inGround : new boolean[] { false, true }) {
            for (boolean inWater : new boolean[] { false, true }) {
                for (boolean onFire : new boolean[] { false, true }) {
                    List<String> active = select(inGround, inWater, onFire);
                    String where = " (落地=" + inGround + ", 水=" + inWater + ", 火=" + onFire + "): " + active;
                    assertEquals(!inGround, active.contains("air"), "air 只在飞行(未落地)时播放" + where);
                    assertEquals(inGround, active.contains("ground"), "ground 只在落地时播放" + where);
                    assertEquals(onFire, active.contains("fire"), "fire 只在上火时播放" + where);
                    assertEquals(inWater, active.contains("water"), "water 只在水中播放" + where);
                    // 落地与飞行互斥 —— 永远不会四个状态动画一起生效。
                    assertFalse(inGround && active.contains("air"), where);
                }
            }
        }
    }

    @Test
    void parallelSlotsAlwaysPlayInNumericOrderAndLast() {
        List<String> active = select(false, false, false);
        List<String> parallels = new ArrayList<>();
        for (String s : active) {
            if (s.startsWith("parallel")) parallels.add(s);
        }
        assertEquals(Arrays.asList("parallel0", "parallel3", "parallel7"), parallels,
            "并行动画恒定播放且按槽位号升序（后者覆盖前者）");
        // 并行动画优先级最高 → 必须排在状态动画之后。
        assertTrue(active.indexOf("parallel0") > active.indexOf("air"), active.toString());
    }

    /** 不在弹射物动画清单里的名字不再无条件播放（旧实现把这类动画一起播了）。 */
    @Test
    void unmanagedNonStateAnimationsAreNotPlayed() {
        List<String> active = select(false, false, false);
        assertFalse(active.contains("weird"), active.toString());
    }

    /** 控制器自己决定的动画照常出现，并且排在状态动画之前。 */
    @Test
    void controllerAnimationsStillTakePart() {
        register(WITH_CTRL, true);
        List<String> active = ProjectileControllerRuntime.getActiveAnimations(2, WITH_CTRL, 5.0,
            ProjectileState.of(false, false, false));
        assertTrue(active.contains("post_main"), active.toString());
        assertTrue(active.indexOf("post_main") < active.indexOf("air"), active.toString());
        assertTrue(active.contains("parallel0"), active.toString());
        assertFalse(active.contains("ground"), active.toString());
    }

    /** 兼容入口（不传状态）等价于"在空中飞行"。 */
    @Test
    void legacyOverloadDefaultsToAirborne() {
        assertEquals(select(false, false, false),
            ProjectileControllerRuntime.getActiveAnimations(1, NO_CTRL, 5.0));
    }

    /** 确保 ysm.* 变量不会因为选择逻辑改动而不再被读取（控制器条件依赖它们）。 */
    @Test
    void controllerConditionsStillReadYsmVariables() {
        register(WITH_CTRL, true);
        MolangParser.VARIABLES.computeIfAbsent("ysm.in_ground", k -> new LazyVariable(k, () -> 0.0)).set(1.0);
        try {
            List<String> active = ProjectileControllerRuntime.getActiveAnimations(3, WITH_CTRL, 5.0,
                ProjectileState.of(true, false, false));
            assertTrue(active.contains("post_main"), active.toString());
        } finally {
            MolangParser.VARIABLES.get("ysm.in_ground").set(0.0);
        }
    }

    /** 动画文件缺失时返回空列表而不是抛异常。 */
    @Test
    void missingAnimationFileIsTolerated() {
        List<String> active = ProjectileControllerRuntime.getActiveAnimations(1,
            new ResourceLocation("ysmu", "_test_proj_absent"), 5.0, ProjectileState.AIRBORNE);
        assertEquals(0, active.size());
    }
}

