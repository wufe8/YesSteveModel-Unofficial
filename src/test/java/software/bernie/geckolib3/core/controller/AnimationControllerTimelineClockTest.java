package software.bernie.geckolib3.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.controller.TimelineEventScheduler;
import com.eliotlash.mclib.math.IValue;

import software.bernie.geckolib3.core.AnimationState;
import software.bernie.geckolib3.core.ConstantValue;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.keyframe.EventKeyFrame;
import software.bernie.geckolib3.core.keyframe.KeyFrame;
import software.bernie.geckolib3.core.keyframe.KeyFrameLocation;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.processor.AnimationProcessor;
import software.bernie.geckolib3.core.processor.IBone;

/**
 * Integration test for the real {@link AnimationController} playback clock that the
 * YSMU timeline scheduler runs on.
 * <p>
 * {@code TimelineEventSchedulerTest} proves the schedule arithmetic on a synthetic
 * clock; it cannot catch the defect this test exists for — the reported tick used to
 * be sampled <em>before</em> the animation predicate, i.e. before
 * {@code processCurrentAnimation} applies {@code anim_time_update} and the loop
 * wrap / HOLD clamp, so the scheduler was one processing stage behind the animation
 * actually being evaluated. Here a controller is driven through
 * {@link AnimationController#process} with a stub model and no bones, and the
 * listener receives exactly what the controller evaluates the bones with.
 * <p>
 * The listener is installed <em>from inside the animation predicate</em>, because
 * {@code process()} clears it before running the predicate every frame — exactly as
 * the YSMU runtime re-installs it from its slot predicates. Installing it from the
 * test body (as the first draft did) proves nothing: the clear would silently retire
 * it before the frame ran.
 * <p>
 * This does not render anything and does not prove a model looks right; it proves the
 * number handed to the scheduler is the frame's final playback position, that a loop
 * wrap is reported as forward distance rather than a negative jump, that HOLD is
 * clamped, that a PLAY_ONCE still reports its terminal tick, that an animation-speed
 * change reports the position difference, that a restart re-anchors, and that no
 * registration means no callback.
 */
class AnimationControllerTimelineClockTest {

    private static final class StubAnimatable implements IAnimatable {

        @Override
        public void registerControllers(AnimationData data) {
            // the controller is constructed by the test
        }

        @Override
        public AnimationFactory getFactory() {
            return null;
        }
    }

    private static final class StubModel implements IAnimatableModel<StubAnimatable> {

        final HashMap<String, Animation> animations = new HashMap<>();

        @Override
        public void setLivingAnimations(StubAnimatable entity, Integer uniqueID, AnimationEvent customPredicate) {
            // no-op
        }

        @Override
        public AnimationProcessor getAnimationProcessor() {
            return null;
        }

        @Override
        public Animation getAnimation(String name, IAnimatable animatable) {
            return animations.get(name);
        }

        @Override
        public void setMolangQueries(IAnimatable animatable, double currentTick) {
            // no-op
        }
    }

    /** Captures the last playback report and how many were delivered. */
    private static final class Report implements AnimationController.ITimelinePlaybackListener {

        Animation animation;
        double tick;
        double delta;
        boolean wrapped;
        int calls;
        boolean advancedWhileNullAnimation;

        @Override
        public void onTimelinePlayback(Animation animation, double tick, double delta, boolean wrapped) {
            if (animation == null) {
                advancedWhileNullAnimation = true;
            }
            this.animation = animation;
            this.tick = tick;
            this.delta = delta;
            this.wrapped = wrapped;
            this.calls++;
        }
    }

    private final StubModel model = new StubModel();
    private final AnimationController.ModelFetcher<StubAnimatable> fetcher =
        new AnimationController.ModelFetcher<StubAnimatable>() {

            @Override
            @SuppressWarnings("unchecked")
            public IAnimatableModel<StubAnimatable> apply(IAnimatable animatable) {
                return (IAnimatableModel<StubAnimatable>) model;
            }
        };

    /**
     * A controller plus the report its predicate installs. {@code install} mirrors the
     * runtime's "the slot predicate decides whether this controller has a timeline":
     * flipping it off means the next frame runs with no listener at all.
     */
    private final class Harness {

        final Report report = new Report();
        boolean install = true;
        /** The listener the predicate installs; defaults to the recording report but can
         *  be replaced by a forwarder into a real {@code TimelineEventScheduler}. */
        AnimationController.ITimelinePlaybackListener listener = report;
        final AnimationController<StubAnimatable> controller;

        Harness() {
            this(null);
        }

        @SuppressWarnings("unchecked")
        Harness(AnimationController.ITimelinePlaybackListener custom) {
            if (custom != null) {
                listener = custom;
            }
            AnimationController.addModelFetcher(fetcher);
            final AnimationController<StubAnimatable>[] self = new AnimationController[1];
            StubAnimatable animatable = new StubAnimatable();
            controller = new AnimationController<>(animatable, "test_controller", 0.0f, event -> {
                AnimationController<StubAnimatable> target = self[0];
                if (install && target != null) {
                    target.setTimelinePlaybackListener(listener);
                }
                return null;
            });
            self[0] = controller;
        }
    }

    @AfterEach
    void removeFetcher() {
        while (AnimationController.modelFetchers.remove(fetcher)) {
            // remove every registration this test class may have added
        }
    }

    private static Animation animation(String name, double length, ILoopType loop, String animTimeUpdate) {
        Animation animation = new Animation();
        animation.animationName = name;
        animation.animationLength = length;
        animation.loop = loop;
        animation.animTimeUpdate = animTimeUpdate;
        animation.boneAnimations = new ArrayList<>();
        animation.soundKeyFrames = new ArrayList<>();
        animation.particleKeyFrames = new ArrayList<>();
        animation.customInstructionKeyframes = new ArrayList<>();
        animation.customInstructionKeyframes.add(new EventKeyFrame<>(0.0d, "v.probe = 1"));
        return animation;
    }

    private static AnimationBuilder builder(String name, ILoopType loop) {
        return new AnimationBuilder().addAnimation(name, loop);
    }

    private static void process(AnimationController<StubAnimatable> controller, double seekTime) {
        AnimationEvent<StubAnimatable> event = new AnimationEvent<>(new StubAnimatable(), 0.0f, 0.0f, 0.0f, false,
            new ArrayList<>());
        event.setController(controller);
        controller.process(seekTime, event, new HashMap<>(), new HashMap<>(), new MolangParser(), false);
    }

    @Test
    void theReportedTickIsTheFinalPlaybackTickNotThePredicateTick() {
        Harness harness = new Harness();
        // anim_time_update decouples the playback tick from the raw seek time: the
        // expression returns 40 seconds (= 800 ticks) while the game clock advances 1.
        model.animations.put("driven", animation("driven", 4000.0d, ILoopType.EDefaultLoopTypes.LOOP, "40.0"));
        harness.controller.setAnimation(builder("driven", ILoopType.EDefaultLoopTypes.LOOP));

        process(harness.controller, 1.0d);
        assertNotNull(harness.report.animation, "the listener must fire for the running animation");
        assertEquals(AnimationState.Running, harness.controller.getAnimationState());
        assertEquals(800.0d, harness.report.tick, 1.0e-6d,
            "the reported tick must be the anim_time_update result, not the raw predicate tick");
        assertEquals(harness.report.tick, harness.controller.getSyncTick(), 1.0e-6d);

        process(harness.controller, 2.0d);
        assertEquals(800.0d, harness.report.tick, 1.0e-6d);
        assertEquals(2, harness.report.calls, "one report per running frame");
    }

    @Test
    void aLoopWrapReportsTheAdvanceInsteadOfANegativeJump() {
        Harness harness = new Harness();
        model.animations.put("loop", animation("loop", 4.0d, ILoopType.EDefaultLoopTypes.LOOP, null));
        harness.controller.setAnimation(builder("loop", ILoopType.EDefaultLoopTypes.LOOP));

        process(harness.controller, 0.0d); // transition frame: establishes the animation at tick 0
        process(harness.controller, 3.0d); // tick 3, no wrap yet
        double beforeWrap = harness.report.tick;
        double deltaBeforeWrap = harness.report.delta;

        process(harness.controller, 5.0d); // crosses the 4-tick boundary: tick wraps to 1
        assertTrue(harness.report.tick < beforeWrap, "the playback tick must wrap at the animation length");
        assertEquals(1.0d, harness.report.tick, 1.0e-6d);
        assertTrue(harness.report.wrapped, "the wrap must be flagged");
        assertEquals(2.0d, harness.report.delta, 1.0e-6d,
            "the delta must stay the forward distance across the wrap, not become negative");
        assertEquals(3.0d, deltaBeforeWrap, 1.0e-6d, "0 -> 3 is a 3-tick advance");
    }

    @Test
    void aFrameCrossingSeveralLoopsReportsTheDistanceActuallyElapsed() {
        Harness harness = new Harness();
        model.animations.put("loop", animation("loop", 4.0d, ILoopType.EDefaultLoopTypes.LOOP, null));
        harness.controller.setAnimation(builder("loop", ILoopType.EDefaultLoopTypes.LOOP));

        process(harness.controller, 0.0d);
        process(harness.controller, 10.0d); // two full loops plus 2 ticks

        assertTrue(harness.report.wrapped, "a multi-loop frame wraps too");
        assertEquals(2.0d, harness.report.tick, 1.0e-6d, "10 % 4");
        assertEquals(10.0d, harness.report.delta, 1.0e-6d,
            "the unwrapped forward distance is reported, not the wrapped position difference");
    }

    @Test
    void anAnimationSpeedChangeReportsThePositionDifferenceNotTheWallTime() {
        Harness harness = new Harness();
        model.animations.put("loop", animation("loop", 4000.0d, ILoopType.EDefaultLoopTypes.LOOP, null));
        harness.controller.setAnimation(builder("loop", ILoopType.EDefaultLoopTypes.LOOP));

        process(harness.controller, 0.0d); // tick 0
        process(harness.controller, 3.0d); // tick 3
        assertEquals(3.0d, harness.report.delta, 1.0e-6d);

        // The playback position is animationSpeed * elapsed; the forward distance is
        // the position difference (8 - 3 = 5), not the newly-scaled wall time.
        harness.controller.setAnimationSpeed(2.0d);
        process(harness.controller, 4.0d);
        assertEquals(8.0d, harness.report.tick, 1.0e-6d);
        assertEquals(5.0d, harness.report.delta, 1.0e-6d,
            "a speed change must not rescale the already-elapsed distance");
    }

    @Test
    void aHoldClampsTheReportedTickToTheAnimationLength() {
        Harness harness = new Harness();
        model.animations.put("hold", animation("hold", 4.0d, ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME, null));
        harness.controller.setAnimation(builder("hold", ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME));

        process(harness.controller, 0.0d);
        process(harness.controller, 10.0d);

        assertEquals(AnimationState.Running, harness.controller.getAnimationState(), "HOLD keeps the controller running");
        assertEquals(4.0d, harness.report.tick, 1.0e-6d, "HOLD clamps at the animation length");
        assertEquals(4.0d, harness.report.delta, 1.0e-6d,
            "the advance is the clamped position difference (0 -> 4), not the raw wall time (10)");
    }

    @Test
    void aPlayOnceReportsItsTerminalTickBeforeStopping() {
        Harness harness = new Harness();
        model.animations.put("once", animation("once", 4.0d, ILoopType.EDefaultLoopTypes.PLAY_ONCE, null));
        harness.controller.setAnimation(builder("once", ILoopType.EDefaultLoopTypes.PLAY_ONCE));

        process(harness.controller, 0.0d);
        process(harness.controller, 4.0d);

        assertEquals(AnimationState.Stopped, harness.controller.getAnimationState(),
            "a PLAY_ONCE animation with an empty queue stops");
        assertTrue(harness.report.calls >= 2, "the terminal frame must still be reported");
        assertEquals(4.0d, harness.report.tick, 1.0e-6d, "the terminal report carries the animation length");
    }

    @Test
    void restartingTheAnimationReanchorsTheClock() {
        Harness harness = new Harness();
        model.animations.put("loop", animation("loop", 4000.0d, ILoopType.EDefaultLoopTypes.LOOP, null));
        model.animations.put("next", animation("next", 4000.0d, ILoopType.EDefaultLoopTypes.LOOP, null));
        harness.controller.setAnimation(builder("loop", ILoopType.EDefaultLoopTypes.LOOP));

        process(harness.controller, 0.0d); // anchor
        process(harness.controller, 5.0d);
        assertEquals(5.0d, harness.report.delta, 1.0e-6d);

        // Switching to a different animation restarts the controller clock; the next
        // report must re-anchor (delta 0), not report the cross-animation jump.
        harness.controller.setAnimation(builder("next", ILoopType.EDefaultLoopTypes.LOOP));
        process(harness.controller, 6.0d);
        assertEquals(0.0d, harness.report.delta, 1.0e-6d, "a restart re-anchors instead of reporting a jump");
    }

    /** The runtime clears the listener before every predicate; no registration, no report. */
    @Test
    void noRegistrationMeansNoCallback() {
        Harness harness = new Harness();
        harness.install = false;
        model.animations.put("idle", animation("idle", 40.0d, ILoopType.EDefaultLoopTypes.LOOP, null));
        harness.controller.setAnimation(builder("idle", ILoopType.EDefaultLoopTypes.LOOP));

        process(harness.controller, 0.0d);
        process(harness.controller, 1.0d);

        assertEquals(0, harness.report.calls, "a controller whose predicate registers nothing must not report");
        assertEquals(false, harness.report.advancedWhileNullAnimation);
    }

    @Test
    void retiringTheListenerStopsTheCallbacks() {
        Harness harness = new Harness();
        model.animations.put("idle", animation("idle", 40.0d, ILoopType.EDefaultLoopTypes.LOOP, null));
        harness.controller.setAnimation(builder("idle", ILoopType.EDefaultLoopTypes.LOOP));

        process(harness.controller, 0.0d);
        int afterInstall = harness.report.calls;
        assertTrue(afterInstall >= 1);

        // Stop registering: the controller retires it at the start of the frame.
        harness.install = false;
        process(harness.controller, 1.0d);

        assertEquals(afterInstall, harness.report.calls, "a retired listener must not receive further reports");
        assertSame(AnimationController.ITimelinePlaybackListener.NONE,
            harness.controller.getTimelinePlaybackListener());
    }

    @Test
    void aReportedSequenceMatchesThePositionAndDeltaContract() {
        Harness harness = new Harness();
        model.animations.put("walk", animation("walk", 20.0d, ILoopType.EDefaultLoopTypes.LOOP, null));
        harness.controller.setAnimation(builder("walk", ILoopType.EDefaultLoopTypes.LOOP));

        double deltaSum = 0.0d;
        double last = -1.0d;
        for (int i = 0; i <= 10; i++) {
            process(harness.controller, i * 1.0d);
            deltaSum += harness.report.delta;
            assertTrue(harness.report.delta >= 0.0d, "a report must never carry a negative distance");
            if (i == 10) {
                last = harness.report.tick;
            }
        }

        assertEquals(11, harness.report.calls, "one report per running frame");
        assertEquals(10.0d, deltaSum, 1.0e-6d, "the deltas must sum to the elapsed distance");
        assertEquals(10.0d, last, 1.0e-6d, "the final playback tick is the elapsed distance on a looping animation");
        assertEquals(false, harness.report.advancedWhileNullAnimation, "a report always names the animation it measured");
    }

    /**
     * End-to-end player clock → scheduler wiring, without Minecraft: the controller's own
     * playback report (the runtime's {@code handleTimelinePlayback} path) drives a real
     * {@link TimelineEventScheduler}, and the same elapsed distance produces the same
     * dispatch count whether it arrives in 2-tick or 1-tick frames.
     */
    @Test
    void theSchedulerDispatchesOnTheControllersRealClock() {
        // Coarse: 2-tick frames (0, 2, 4, 8). Fine: every tick from 0 to 8.
        TimelineEventScheduler coarse = new TimelineEventScheduler();
        ArrayList<String> coarseOut = new ArrayList<>();
        Harness coarseHarness = new Harness(
            (animation, tick, delta, wrapped) -> coarse.advanceFrame(tick, delta, coarseOut::add));
        configureLoopingScheduler(coarseHarness, coarse, "coarse");
        for (double t : new double[] { 0.0d, 2.0d, 4.0d, 8.0d }) {
            process(coarseHarness.controller, t);
        }

        TimelineEventScheduler fine = new TimelineEventScheduler();
        ArrayList<String> fineOut = new ArrayList<>();
        // The raw (tick@delta) stream is kept in the assertion message: a mismatch here is
        // almost always a clock defect, not a scheduler one.
        ArrayList<String> fineStream = new ArrayList<>();
        Harness fineHarness = new Harness((animation, tick, delta, wrapped) -> {
            fineStream.add(tick + "@" + delta + (wrapped ? "W" : ""));
            fine.advanceFrame(tick, delta, fineOut::add);
        });
        configureLoopingScheduler(fineHarness, fine, "fine");
        for (int i = 0; i <= 8; i++) {
            process(fineHarness.controller, i);
        }

        assertEquals(3, coarseOut.size(), "t=0, 4 and 8 => 3 dispatches");
        assertEquals(coarseOut, fineOut, "the frame size must not change what the scheduler dispatches; fine=" + fineStream);
        assertEquals(1, coarse.getContributorCount());
        assertEquals(0L, coarse.getRebaseCount());
    }

    /**
     * Regression: after a loop wrap the adjusted playback tick and the raw seek tick
     * diverge (the controller moves {@code tickOffset}), and the end-of-frame re-anchor
     * compared those two, so every frame after the first wrap reported delta 0 — the
     * scheduler stopped advancing and the model's timeline froze after one loop.
     */
    @Test
    void aLoopWrapDoesNotStopTheDeltasAfterIt() {
        Harness harness = new Harness();
        model.animations.put("loop", animation("loop", 4.0d, ILoopType.EDefaultLoopTypes.LOOP, null));
        harness.controller.setAnimation(builder("loop", ILoopType.EDefaultLoopTypes.LOOP));

        double deltaSum = 0.0d;
        for (int i = 0; i <= 8; i++) {
            process(harness.controller, i);
            deltaSum += harness.report.delta;
        }

        assertEquals(8.0d, deltaSum, 1.0e-6d, "the reported deltas must cover every elapsed tick across wraps");
        assertEquals(0.0d, harness.report.tick, 1.0e-6d, "tick 8 wrapped back to 0");
        assertTrue(harness.report.wrapped, "the last frame wrapped");
    }

    /** A 4-tick looping animation whose scheduler program fires "inc" at its t=0. */
    private void configureLoopingScheduler(Harness harness, TimelineEventScheduler scheduler, String animName) {
        model.animations.put(animName, animation(animName, 4.0d, ILoopType.EDefaultLoopTypes.LOOP, null));
        harness.controller.setAnimation(builder(animName, ILoopType.EDefaultLoopTypes.LOOP));
        scheduler.configure(
            java.util.Collections.singletonList(
                TimelineEventScheduler.contributor("inc",
                    4.0d,
                    true,
                    java.util.Collections.singletonList(
                        new TimelineEventScheduler.Event(0.0d, "inc")))),
            true);
    }

    /**
     * {@link AnimationController#getCurrentKeyFrameLocation} 的 KfCache 索引缓存。
     *
     * <p>回归点（VisualVM 采样）：命中缓存的分支里原本写的是
     * {@code kfCache.computeIfAbsent(frames, k -> new KfCacheEntry()).set(...)} —— 手里
     * 已经拿到了 entry，却又查一次表、还分配一个 lambda（实测独占客户端线程 9.2ms/s）。
     * 这里钉住重构后**结果完全不变**：单调推进、同一关键帧内推进、跳到后面的关键帧、
     * 超过末尾、以及 tick 回退（循环回绕）后重新全扫。</p>
     */
    @Test
    void keyFrameLocationCacheFollowsTheSameFramesAsAFullScan() {
        Harness harness = new Harness();
        // 三个关键帧，长度 1 / 2 / 3 → 累计结束时刻 1 / 3 / 6
        ArrayList<KeyFrame<IValue>> frames = new ArrayList<>();
        frames.add(frame(1.0d));
        frames.add(frame(2.0d));
        frames.add(frame(3.0d));

        // 首次调用：从 0 全扫
        assertEquals(frames.get(0), harness.controller.getCurrentKeyFrameLocation(frames, 0.5d).currentFrame);
        assertEquals(0.5d, harness.controller.getCurrentKeyFrameLocation(frames, 0.5d).currentTick, 1.0e-9d);

        // 同一关键帧内往前推（走过的就是原来 computeIfAbsent 那一支）
        KeyFrameLocation<KeyFrame<IValue>> same = harness.controller.getCurrentKeyFrameLocation(frames, 0.9d);
        assertSame(frames.get(0), same.currentFrame, "同一关键帧内推进不能换帧");
        assertEquals(0.9d, same.currentTick, 1.0e-9d);

        // 进入第二个关键帧（累计 1）
        KeyFrameLocation<KeyFrame<IValue>> second = harness.controller.getCurrentKeyFrameLocation(frames, 1.5d);
        assertSame(frames.get(1), second.currentFrame);
        assertEquals(0.5d, second.currentTick, 1.0e-9d, "tick 是相对本关键帧起点的偏移");

        // 跳到第三个关键帧（累计 3）
        KeyFrameLocation<KeyFrame<IValue>> third = harness.controller.getCurrentKeyFrameLocation(frames, 5.0d);
        assertSame(frames.get(2), third.currentFrame);
        assertEquals(2.0d, third.currentTick, 1.0e-9d);

        // 超过末尾：停在最后一帧，tick 就是传入的绝对 tick
        KeyFrameLocation<KeyFrame<IValue>> past = harness.controller.getCurrentKeyFrameLocation(frames, 7.0d);
        assertSame(frames.get(2), past.currentFrame);
        assertEquals(7.0d, past.currentTick, 1.0e-9d);

        // tick 回退（动画循环回绕）：缓存条目被丢掉，重新全扫回第一帧
        KeyFrameLocation<KeyFrame<IValue>> rewound = harness.controller.getCurrentKeyFrameLocation(frames, 0.25d);
        assertSame(frames.get(0), rewound.currentFrame, "回绕后必须从第一帧重新开始");
        assertEquals(0.25d, rewound.currentTick, 1.0e-9d);

        // 边界：判定是"累计结束时刻严格大于 ageInTicks"，所以 age == 上一帧的累计结束时刻
        // （这里 1.0，也就是第一帧的结束）就已经进入下一帧，且偏移为 0。
        KeyFrameLocation<KeyFrame<IValue>> boundary = harness.controller.getCurrentKeyFrameLocation(frames, 1.0d);
        assertSame(frames.get(1), boundary.currentFrame, "age == 第一帧累计结束时刻时应进入第二帧");
        assertEquals(0.0d, boundary.currentTick, 1.0e-9d);
        // 紧接着用同一个 age 再问一次，结果必须一样（缓存刚被回绕清掉，走的是全扫路径）
        assertSame(frames.get(1), harness.controller.getCurrentKeyFrameLocation(frames, 1.0d).currentFrame);
    }

    private static KeyFrame<IValue> frame(double length) {
        return new KeyFrame<>(length, ConstantValue.fromDouble(length), ConstantValue.fromDouble(length));
    }
}
