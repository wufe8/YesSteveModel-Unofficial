package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.keyframe.EventKeyFrame;
import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.file.AnimationFile;

/**
 * 弹射物时间轴的**时钟**：状态动画的 timeline 从"进入该状态"开始计时，而不是按实体总年龄采样。
 *
 * <p>回归点：{@code post_main}(1.67 tick) / {@code post_ground}(4.17 tick) 都是
 * {@code hold_on_last_frame}，实体总年龄在第二帧就越过了它们的末尾。如果驱动调度器时把
 * "实体年龄"当作首次接入位置上报，{@link TimelineEventScheduler} 会认定这是一次"迟到的接入"
 * 而把所有已过去的事件对齐掉 —— 模型写在 t=0 的 {@code ysm.particle(...)}（飞行拖尾、命中水花）
 * 一条都不会执行。这里锁住正确语义：活动动画集合变化（= 控制器切状态）时时钟归零、t=0 的指令
 * 在那一帧必然派发。</p>
 *
 * <p>弹射物这条链路以前**完全没有**时间轴派发，所以这些断言同时也是"时间轴真的被接上了"的证明。</p>
 */
class ProjectileTimelineClockTest {

    private static final ResourceLocation ANIM_ID = new ResourceLocation("ysmu", "_test_projectile_timeline");
    private static final int ENTITY_ID = 4242;

    private static Animation animation(String name, Double lengthTicks, ILoopType loop,
        double[] ticks, String[] data) {
        Animation animation = new Animation();
        animation.animationName = name;
        animation.animationLength = lengthTicks;
        animation.loop = loop;
        animation.customInstructionKeyframes = new ArrayList<>();
        for (int i = 0; i < ticks.length; i++) {
            animation.customInstructionKeyframes.add(new EventKeyFrame<>(ticks[i], data[i]));
        }
        return animation;
    }

    private static AnimationFile file(Animation... animations) {
        AnimationFile file = new AnimationFile();
        for (Animation animation : animations) {
            file.animations.put(animation.animationName, animation);
        }
        return file;
    }

    private static double probe(String name) {
        return MolangParser.VARIABLES.computeIfAbsent(name, key -> new LazyVariable(key, 0)).get();
    }

    private static void setProbe(String name, double value) {
        MolangParser.VARIABLES.computeIfAbsent(name, key -> new LazyVariable(key, 0)).set(value);
    }

    private static void dispatch(AnimationFile file, List<String> active, double ageInTicks) {
        ProjectileTimelineRuntime.dispatch(ENTITY_ID, ANIM_ID, file, active, ageInTicks);
    }

    @BeforeEach
    void setUp() {
        ProjectileTimelineRuntime.forget(ENTITY_ID, ANIM_ID);
        ProjectileTimelineRuntime.beginRenderFrame();
        setProbe("v.tl_ground", 0);
        setProbe("v.tl_main", 0);
        setProbe("v.tl_loop", 0);
    }

    @AfterEach
    void tearDown() {
        ProjectileTimelineRuntime.forget(ENTITY_ID, ANIM_ID);
    }

    /**
     * 核心回归：实体年龄已经 100 tick 时进入落地状态，t=0 的指令**必须**在那一帧发出。
     * 若把实体年龄当首次接入位置，事件会被"迟到接入"逻辑全部跳过（值恒为 0）。
     */
    @Test
    void stateEntryTimelineFiresEvenWhenTheEntityIsAlreadyOld() {
        AnimationFile file = file(animation("post_ground", 4.0d, ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME,
            new double[] { 0.0d }, new String[] { "v.tl_ground = v.tl_ground + 1;" }));

        dispatch(file, Collections.singletonList("post_ground"), 100.0d);
        assertEquals(1.0d, probe("v.tl_ground"), 1.0e-6d, "落地那一帧必须派发 t=0 的指令");

        // 非循环 + hold：之后不再重复派发
        dispatch(file, Collections.singletonList("post_ground"), 100.5d);
        dispatch(file, Collections.singletonList("post_ground"), 105.0d);
        assertEquals(1.0d, probe("v.tl_ground"), 1.0e-6d, "hold_on_last_frame 的时间轴只能发一次");
    }

    /** 切状态（活动动画集合变化）时时间轴时钟归零：新状态的 t=0 指令再次派发。 */
    @Test
    void switchingStateRestartsTheTimelineClock() {
        AnimationFile file = file(
            animation("post_main", 2.0d, ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME,
                new double[] { 0.0d }, new String[] { "v.tl_main = v.tl_main + 1;" }),
            animation("post_ground", 4.0d, ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME,
                new double[] { 0.0d }, new String[] { "v.tl_ground = v.tl_ground + 1;" }));

        dispatch(file, Collections.singletonList("post_main"), 10.0d);
        assertEquals(1.0d, probe("v.tl_main"), 1.0e-6d);
        dispatch(file, Collections.singletonList("post_main"), 14.0d);
        assertEquals(1.0d, probe("v.tl_main"), 1.0e-6d, "同一状态内不重启");

        // 落地：集合变化 → post_ground 的 t=0 立即派发（实体年龄已经 14+）
        dispatch(file, Collections.singletonList("post_ground"), 14.5d);
        assertEquals(1.0d, probe("v.tl_ground"), 1.0e-6d, "切状态后新动画的 t=0 指令必须派发");

        // 弹回空中：post_main 再进入一次 → 又要派发一次
        dispatch(file, Collections.singletonList("post_main"), 20.0d);
        assertEquals(2.0d, probe("v.tl_main"), 1.0e-6d, "重新进入同一状态也要重启时间轴");
    }

    /** 循环动画（{@code parallel1} 的周期只有 0.2 tick）按自身周期反复派发。 */
    @Test
    void loopingTimelineKeepsFiringOnItsOwnPeriod() {
        AnimationFile file = file(animation("parallel1", 0.2d, ILoopType.EDefaultLoopTypes.LOOP,
            new double[] { 0.0d }, new String[] { "v.tl_loop = v.tl_loop + 1;" }));

        dispatch(file, Collections.singletonList("parallel1"), 50.0d);
        assertEquals(1.0d, probe("v.tl_loop"), 1.0e-6d, "首帧派发 t=0");

        dispatch(file, Collections.singletonList("parallel1"), 50.5d);
        assertEquals(3.0d, probe("v.tl_loop"), 1.0e-6d, "0.5 tick / 周期 0.2 tick = 再派发 2 次");

        double before = probe("v.tl_loop");
        dispatch(file, Collections.singletonList("parallel1"), 53.0d);
        assertTrue(probe("v.tl_loop") > before, "循环时间轴必须继续派发");
    }

    /** 同一 tick 内渲染两次（增量为 0）不能重复派发。 */
    @Test
    void secondPassInTheSameTickDoesNotDoubleDispatch() {
        AnimationFile file = file(animation("parallel1", 0.2d, ILoopType.EDefaultLoopTypes.LOOP,
            new double[] { 0.0d }, new String[] { "v.tl_loop = v.tl_loop + 1;" }));

        dispatch(file, Collections.singletonList("parallel1"), 60.0d);
        double after = probe("v.tl_loop");
        dispatch(file, Collections.singletonList("parallel1"), 60.0d);
        dispatch(file, Collections.singletonList("parallel1"), 60.0d);
        assertEquals(after, probe("v.tl_loop"), 1.0e-6d, "同一帧多次渲染不能重复派发");
    }

    /** 没有 timeline 的动画不产生调度器贡献（也就不会误开粒子）。 */
    @Test
    void animationsWithoutTimelineContributeNothing() {
        AnimationFile file = file(animation("post_ground", 4.0d, ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME,
            new double[0], new String[0]));

        dispatch(file, Collections.singletonList("post_ground"), 80.0d);
        assertEquals(0.0d, probe("v.tl_ground"), 1.0e-6d);
    }

    /** 时间轴派发预算存在上限（模型写死循环式短周期也不会无限执行）。 */
    @Test
    void dispatchBudgetIsBounded() {
        AnimationFile file = file(animation("parallel1", 0.01d, ILoopType.EDefaultLoopTypes.LOOP,
            new double[] { 0.0d }, new String[] { "v.tl_loop = v.tl_loop + 1;" }));

        dispatch(file, Collections.singletonList("parallel1"), 0.0d);
        // 一次推进 30 tick（周期 0.01 tick → 本可派发 3000 次），必须被每帧上限截住。
        dispatch(file, Collections.singletonList("parallel1"), 30.0d);
        assertTrue(probe("v.tl_loop") <= ProjectileTimelineRuntime.MAX_DISPATCHES_PER_FRAME + 1,
            "派发次数必须被每帧预算截住，实际 " + probe("v.tl_loop"));
    }

    /** 预算每渲染帧重置，不会因为一次用尽而永久停摆。 */
    @Test
    void budgetResetsEachRenderFrame() {
        AnimationFile file = file(animation("parallel1", 0.01d, ILoopType.EDefaultLoopTypes.LOOP,
            new double[] { 0.0d }, new String[] { "v.tl_loop = v.tl_loop + 1;" }));

        dispatch(file, Collections.singletonList("parallel1"), 0.0d);
        dispatch(file, Collections.singletonList("parallel1"), 30.0d);
        double used = probe("v.tl_loop");

        ProjectileTimelineRuntime.beginRenderFrame();
        dispatch(file, Collections.singletonList("parallel1"), 60.0d);
        assertTrue(probe("v.tl_loop") > used, "新的一帧必须重新获得预算");
    }

    /**
     * 整条链路：模型 JSON 里的 {@code timeline} 必须被解析成 {@code customInstructionKeyframes}，
     * 否则运行时再怎么派发也没有东西可发（弹射物动画以前连"有 timeline"这一步都没接上）。
     */
    @Test
    void timelineSurvivesJsonParsingAndExecutes() {
        String json = "{\"format_version\":\"1.19.0\",\"animations\":{"
            + "\"post_ground\":{\"animation_length\":0.1,\"loop\":\"hold_on_last_frame\","
            + "\"bones\":{\"b\":{\"scale\":{\"0.0\":0.5,\"0.1\":2.5}}},"
            + "\"timeline\":{\"0.0\":[\"v.tl_json = v.tl_json + 1;\"]}}}}";
        AnimationFile parsed = com.fox.ysmu.client.ClientModelManager.parseAnimationFileFromJson(json);
        Animation postGround = parsed.animations.get("post_ground");
        assertNotNull(postGround, "动画必须解析出来");
        assertNotNull(postGround.customInstructionKeyframes, "timeline 必须变成 customInstructionKeyframes");
        assertEquals(1, postGround.customInstructionKeyframes.size());
        assertTrue(postGround.customInstructionKeyframes.get(0).getEventData().contains("v.tl_json"),
            postGround.customInstructionKeyframes.get(0).getEventData());

        setProbe("v.tl_json", 0);
        ProjectileTimelineRuntime.forget(ENTITY_ID, ANIM_ID);
        ProjectileTimelineRuntime.beginRenderFrame();
        dispatch(parsed, Collections.singletonList("post_ground"), 77.0d);
        assertEquals(1.0d, probe("v.tl_json"), 1.0e-6d, "解析出来的 timeline 指令必须真的执行");
        ProjectileTimelineRuntime.forget(ENTITY_ID, ANIM_ID);
    }

    /** 不同实体互不干扰（各自的时钟与游标）。 */
    @Test
    void entitiesAreIsolated() {
        AnimationFile file = file(animation("post_ground", 4.0d, ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME,
            new double[] { 0.0d }, new String[] { "v.tl_ground = v.tl_ground + 1;" }));

        ProjectileTimelineRuntime.dispatch(1, ANIM_ID, file, Collections.singletonList("post_ground"), 100.0d);
        ProjectileTimelineRuntime.dispatch(2, ANIM_ID, file, Collections.singletonList("post_ground"), 100.0d);
        assertEquals(2.0d, probe("v.tl_ground"), 1.0e-6d, "两支箭各自派发一次");

        ProjectileTimelineRuntime.forget(1, ANIM_ID);
        ProjectileTimelineRuntime.forget(2, ANIM_ID);
    }
}
