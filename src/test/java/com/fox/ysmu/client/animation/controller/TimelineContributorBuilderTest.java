package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime.TimelineProgram;
import com.fox.ysmu.client.animation.controller.TimelineEventScheduler.Contributor;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.keyframe.EventKeyFrame;

/**
 * Integration glue between the parsed animation file and the bounded scheduler:
 * which animations contribute a timeline, what period they get, how their loop
 * type maps, that out-of-order keyframes are sorted before scheduling, that a huge
 * source list is capped before it is copied, and that the builder's own drops stay
 * visible as diagnostics.
 */
class TimelineContributorBuilderTest {

    private static Animation animation(String name, Double length, ILoopType loop, String animTimeUpdate,
        double[] ticks, String[] data) {
        Animation animation = new Animation();
        animation.animationName = name;
        animation.animationLength = length;
        animation.loop = loop;
        animation.animTimeUpdate = animTimeUpdate;
        animation.customInstructionKeyframes = new ArrayList<>();
        for (int i = 0; i < ticks.length; i++) {
            animation.customInstructionKeyframes.add(new EventKeyFrame<>(ticks[i], data[i]));
        }
        return animation;
    }

    private static TimelineProgram build(List<String> names, List<Animation> animations, double mergedLength) {
        return OpenYsmPlayerControllerRuntime.buildTimelineProgram(names, animations, mergedLength);
    }

    @Test
    void onlyAnimationsWithInstructionsBecomeContributors() {
        Animation idle = animation("idle", 2.0d, ILoopType.EDefaultLoopTypes.LOOP, null, new double[0], new String[0]);
        Animation driver = animation("pre_parallel3", 2.0d, ILoopType.EDefaultLoopTypes.LOOP, null,
            new double[] { 0.0d }, new String[] { "v.a = v.roaming.a" });

        List<Contributor> program = build(Arrays.asList("idle", "pre_parallel3"), Arrays.asList(idle, driver), 2.0d)
            .contributors;

        assertEquals(1, program.size());
        assertEquals("pre_parallel3", program.get(0).getId());
        assertEquals(2.0d, program.get(0).getPeriod(), 0.0d);
    }

    @Test
    void mergedLengthIsTheFallbackPeriodForAnimationsWithoutTiming() {
        // No declared length and no bone channels: playbackLengthTicks() == 0.
        Animation noTiming = animation("driver", null, ILoopType.EDefaultLoopTypes.LOOP, null,
            new double[] { 0.0d }, new String[] { "v.a = 1" });

        List<Contributor> program = build(Collections.singletonList("driver"), Collections.singletonList(noTiming),
            80.0d).contributors;

        assertEquals(1, program.size());
        assertEquals(80.0d, program.get(0).getPeriod(), 0.0d);

        // anim_time_update drives its own clock, so it is also treated as "no
        // computable period" and kept on the merged period rather than firing once.
        Animation customTime = animation("custom", 2.0d, ILoopType.EDefaultLoopTypes.LOOP,
            "query.anim_time + query.delta_time", new double[] { 0.0d }, new String[] { "v.a = 1" });
        List<Contributor> customProgram = build(Collections.singletonList("custom"),
            Collections.singletonList(customTime), 12.0d).contributors;
        assertEquals(12.0d, customProgram.get(0).getPeriod(), 0.0d);
    }

    @Test
    void loopsFlagIsTrueOnlyForLoop() {
        Animation looping = animation("a", 4.0d, ILoopType.EDefaultLoopTypes.LOOP, null,
            new double[] { 0.0d }, new String[] { "x" });
        Animation hold = animation("b", 4.0d, ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME, null,
            new double[] { 0.0d }, new String[] { "y" });
        Animation once = animation("c", 4.0d, ILoopType.EDefaultLoopTypes.PLAY_ONCE, null,
            new double[] { 0.0d }, new String[] { "z" });

        List<Contributor> program = build(Arrays.asList("a", "b", "c"), Arrays.asList(looping, hold, once), 4.0d)
            .contributors;

        assertTrue(program.get(0).isLooping());
        assertFalse(program.get(1).isLooping(), "HOLD_ON_LAST_FRAME must not wrap");
        assertFalse(program.get(2).isLooping(), "PLAY_ONCE must not wrap");
    }

    /**
     * A holding merged program must NOT force its contributors non-looping. The
     * controller stops advancing at the end, so every contributor's delta becomes 0
     * and nothing fires again anyway; forcing the flag would additionally truncate a
     * short looping contributor that is legitimately mid-cycle while the holding
     * parent is still short of its last frame.
     */
    @Test
    void contributorsKeepTheirOwnLoopFlagEvenWhenTheMergedProgramHolds() {
        Animation loopingContributor = animation("looper", 2.0d, ILoopType.EDefaultLoopTypes.LOOP, null,
            new double[] { 0.0d }, new String[] { "x" });

        List<Contributor> program = build(Collections.singletonList("looper"),
            Collections.singletonList(loopingContributor), 80.0d).contributors;

        assertEquals(1, program.size());
        assertTrue(program.get(0).isLooping(), "the contributor keeps its own loop flag");
    }

    /**
     * …and the frozen clock is what actually stops it: once the parent holds, the
     * controller reports delta 0 at a fixed position, which the scheduler treats as a
     * stalled clock (not a rebase), so no further events fire.
     */
    @Test
    void aFrozenClockStopsALoopingContributorWithoutForcingItsLoopFlag() {
        Animation loopingContributor = animation("looper", 2.0d, ILoopType.EDefaultLoopTypes.LOOP, null,
            new double[] { 0.0d }, new String[] { "x" });
        List<Contributor> program = build(Collections.singletonList("looper"),
            Collections.singletonList(loopingContributor), 80.0d).contributors;

        TimelineEventScheduler scheduler = new TimelineEventScheduler();
        scheduler.configure(program, true);
        List<String> dispatched = new ArrayList<>();
        scheduler.advanceFrame(0.0d, 0.0d, dispatched::add); // t=0
        scheduler.advanceFrame(4.0d, 4.0d, dispatched::add); // reaches the merge end (0, 2, 4)
        int beforeHold = dispatched.size();
        for (int i = 0; i < 20; i++) {
            scheduler.advanceFrame(4.0d, 0.0d, dispatched::add); // frozen at the merge end
        }

        assertEquals(3, beforeHold);
        assertEquals(beforeHold, dispatched.size(), "a frozen clock must not keep dispatching");
        assertEquals(0L, scheduler.getRebaseCount(), "a frozen position is not a rebase");
    }

    @Test
    void aHugeSourceListIsCappedBeforeItIsCopied() {
        Animation huge = new Animation();
        huge.animationName = "huge";
        huge.animationLength = 2000.0d;
        huge.loop = ILoopType.EDefaultLoopTypes.LOOP;
        huge.customInstructionKeyframes = new ArrayList<>();
        int declared = TimelineEventScheduler.MAX_EVENTS_PER_CONTRIBUTOR + 500;
        for (int i = 0; i < declared; i++) {
            huge.customInstructionKeyframes.add(new EventKeyFrame<>(i * 0.01d, "v.n" + i + " = 1"));
        }

        TimelineProgram program = build(Collections.singletonList("huge"), Collections.singletonList(huge), 2000.0d);

        assertEquals(1, program.contributors.size());
        assertEquals(TimelineEventScheduler.MAX_EVENTS_PER_CONTRIBUTOR, program.contributors.get(0).getEventCount(),
            "the builder must not materialise the whole declared list");
        // The builder caps before the scheduler sees the program, so the drop is only
        // visible on the program result — the scheduler's own counters read 0.
        assertEquals(500, program.truncatedEvents);
        assertEquals(0, program.truncatedContributors);

        TimelineEventScheduler scheduler = new TimelineEventScheduler();
        scheduler.configure(program.contributors, true);
        assertEquals(TimelineEventScheduler.MAX_EVENTS_PER_CONTRIBUTOR, scheduler.getTrackedEventCount());
        assertEquals(0, scheduler.getTruncatedEvents(),
            "the scheduler never sees the builder-truncated events, which is why the builder reports them");
    }

    /**
     * The total-event budget is shared across contributors: once it is spent, later
     * contributors are dropped whole and the builder reports them, because the scheduler
     * (which repeats the same caps) would have seen an already-curtailed program.
     */
    @Test
    void theTotalEventBudgetIsSharedAcrossContributorsAndReported() {
        Animation huge = new Animation();
        huge.animationName = "huge";
        huge.animationLength = 2000.0d;
        huge.loop = ILoopType.EDefaultLoopTypes.LOOP;
        huge.customInstructionKeyframes = new ArrayList<>();
        for (int i = 0; i < TimelineEventScheduler.MAX_EVENTS_PER_CONTRIBUTOR; i++) {
            huge.customInstructionKeyframes.add(new EventKeyFrame<>(i * 0.01d, "v.n" + i + " = 1"));
        }
        // The same (already parsed) animation object is reused for every contributor, so
        // only the builder's event lists are allocated here.
        List<Animation> animations = Arrays.asList(huge, huge, huge, huge, huge);
        List<String> names = Arrays.asList("a", "b", "c", "d", "e");

        TimelineProgram program = build(names, animations, 2000.0d);

        int perContributor = TimelineEventScheduler.MAX_EVENTS_PER_CONTRIBUTOR;
        int fitting = TimelineEventScheduler.MAX_TOTAL_EVENTS / perContributor;
        assertEquals(fitting, program.contributors.size(), "only as many contributors as the total budget allows");
        assertEquals(names.size() - fitting, program.truncatedContributors);
        assertEquals(perContributor, program.truncatedEvents, "the dropped contributor's events are reported");

        TimelineEventScheduler scheduler = new TimelineEventScheduler();
        scheduler.configure(program.contributors, true);
        assertEquals(TimelineEventScheduler.MAX_TOTAL_EVENTS, scheduler.getTrackedEventCount());
    }

    @Test
    void theContributorCapIsReportedByTheBuilderAsWell() {
        int over = 3;
        List<Animation> animations = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < TimelineEventScheduler.MAX_CONTRIBUTORS + over; i++) {
            names.add("c" + i);
            animations.add(animation("c" + i, 20.0d, ILoopType.EDefaultLoopTypes.LOOP, null,
                new double[] { 0.0d }, new String[] { "e" + i }));
        }

        TimelineProgram program = build(names, animations, 20.0d);

        assertEquals(TimelineEventScheduler.MAX_CONTRIBUTORS, program.contributors.size());
        assertEquals(over, program.truncatedContributors);
        assertEquals(over, program.truncatedEvents, "one event each was dropped with its contributor");
    }

    @Test
    void nullKeyframesAreSkippedWithoutShrinkingTheStoredSchedule() {
        Animation mixed = new Animation();
        mixed.animationName = "mixed";
        mixed.animationLength = 4.0d;
        mixed.loop = ILoopType.EDefaultLoopTypes.LOOP;
        mixed.customInstructionKeyframes = new ArrayList<>();
        mixed.customInstructionKeyframes.add(new EventKeyFrame<>(0.0d, "first"));
        mixed.customInstructionKeyframes.add(null);
        mixed.customInstructionKeyframes.add(new EventKeyFrame<>(2.0d, "second"));

        List<Contributor> program = build(Collections.singletonList("mixed"), Collections.singletonList(mixed), 4.0d)
            .contributors;

        assertEquals(1, program.size());
        assertEquals(2, program.get(0).getEventCount());
    }

    @Test
    void outOfOrderKeyframesAreSortedBeforeScheduling() {
        Animation shuffled = animation("shuffled", 1.0d, ILoopType.EDefaultLoopTypes.LOOP, null,
            new double[] { 0.8d, 0.0d, 0.4d }, new String[] { "late", "first", "middle" });

        List<Contributor> program = build(Collections.singletonList("shuffled"), Collections.singletonList(shuffled),
            1.0d).contributors;
        TimelineEventScheduler scheduler = new TimelineEventScheduler();
        scheduler.configure(program, true);
        final List<String> dispatched = new ArrayList<>();
        scheduler.advanceFrame(0.0d, 0.0d, dispatched::add);
        scheduler.advanceFrame(1.0d, 1.0d, dispatched::add);

        assertEquals(Arrays.asList("first", "middle", "late", "first"), dispatched);
    }

    @Test
    void missingAnimationObjectsAreSkippedWithoutFailing() {
        TimelineProgram program = build(Arrays.asList("a", "b"), Arrays.asList(null, null), 5.0d);
        assertTrue(program.contributors.isEmpty());
        assertEquals(0, program.truncatedContributors);
        assertEquals(0, program.truncatedEvents);
    }

    /** A null contributor list is scanned without failing and drops nothing. */
    @Test
    void nullContributorListIsScannedSafely() {
        TimelineProgram program = build(Collections.singletonList("a"), null, 5.0d);
        assertTrue(program.contributors.isEmpty());
        assertEquals(0, program.truncatedContributors);
        assertEquals(0, program.truncatedEvents);
    }
}
