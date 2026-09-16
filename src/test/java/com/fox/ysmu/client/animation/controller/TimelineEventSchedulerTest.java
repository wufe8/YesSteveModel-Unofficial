package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.controller.TimelineEventScheduler.Contributor;
import com.fox.ysmu.client.animation.controller.TimelineEventScheduler.Event;
import com.fox.ysmu.client.animation.controller.TimelineEventScheduler.Sink;

/**
 * Pure tests for the bounded per-contributor timeline scheduler that replaced the
 * per-frame {@code mergedTimeline} flattening + offset expansion in
 * {@code OpenYsmPlayerControllerRuntime.applyAnimations}.
 * <p>
 * These run without a running client: the scheduler advances on a synthetic clock,
 * exactly as the runtime advances it on the controller's real playback tick. They
 * pin the properties the old code could not provide — frame-rate independence,
 * correct loop boundaries, bounded memory/work, HOLD/PLAY_ONCE, cursor
 * preservation across content-identical rebuilds, hash-collision safety, and
 * defined non-looping seek behaviour.
 * <p>
 * They deliberately do <b>not</b> prove anything about rendering: the scheduler is
 * pure logic, and the "the clock is the controller's real final playback tick"
 * claim is covered separately by
 * {@code AnimationControllerTimelineClockTest}.
 */
class TimelineEventSchedulerTest {

    private static final class Recorder implements Sink {

        final List<String> dispatched = new ArrayList<>();

        @Override
        public void dispatch(String instructions) {
            dispatched.add(instructions);
        }

        int count() {
            return dispatched.size();
        }

        int count(String data) {
            int total = 0;
            for (String entry : dispatched) {
                if (entry.equals(data)) {
                    total++;
                }
            }
            return total;
        }
    }

    /** Adapts a delta-only test walk to the position+delta frame API of the runtime. */
    private static final class Walk {

        final TimelineEventScheduler scheduler = new TimelineEventScheduler();
        private double position;

        void advance(double delta, Sink sink) {
            position += delta;
            scheduler.advanceFrame(position, delta, sink);
        }

        /**
         * A restart is a model/state change: the real controller resets its playback
         * tick to 0 in the same step (markNeedsReload / setAnimation), so the next
         * report is anchored at position 0, not at the stale pre-restart position.
         */
        void restart(List<Contributor> program) {
            position = 0.0d;
            scheduler.configure(program, true);
        }
    }

    private static Contributor contributor(String id, double period, boolean loops, double[] ticks, String[] data) {
        List<Event> events = new ArrayList<>(ticks.length);
        for (int i = 0; i < ticks.length; i++) {
            events.add(new Event(ticks[i], data[i]));
        }
        return TimelineEventScheduler.contributor(id, period, loops, events);
    }

    private static Contributor simple(String id, double period, boolean loops, double tick, String data) {
        return contributor(id, period, loops, new double[] { tick }, new String[] { data });
    }

    /**
     * Advances to {@code endClock} in {@code frames} equal steps and returns how many
     * events fired. A period-1 t=0 event must fire exactly {@code floor(end)+1} times
     * whatever the step size — the old per-frame rebuild could not guarantee this.
     */
    private static int countTo(double endClock, int frames) {
        Recorder recorder = new Recorder();
        Walk walk = new Walk();
        walk.scheduler.configure(Collections.singletonList(simple("tick", 1.0d, true, 0.0d, "inc")), true);
        walk.advance(0.0d, recorder);
        for (int i = 1; i <= frames; i++) {
            walk.advance(endClock / frames, recorder);
        }
        return recorder.count();
    }

    @Test
    void sameFinalClockProducesTheSameEventCountAt20_60_144Fps() {
        int at20fps = countTo(80.0d, 80);
        int at60fps = countTo(80.0d, 240);
        int at144fps = countTo(80.0d, 720);

        assertEquals(81, at20fps, "t=0 fires at clock 0..80 inclusive");
        assertEquals(at20fps, at60fps, "frame rate must not change the event count");
        assertEquals(at20fps, at144fps, "frame rate must not change the event count");
    }

    @Test
    void halfTicksDoNotDoubleFireAOneTickEvent() {
        Walk walk = new Walk();
        walk.scheduler.configure(Collections.singletonList(simple("tick", 1.0d, true, 0.0d, "inc")), true);
        Recorder recorder = new Recorder();

        walk.advance(0.0d, recorder);
        for (int i = 0; i < 10; i++) {
            walk.advance(0.5d, recorder);
        }

        assertEquals(6, recorder.count(), "clock 0,1,2,3,4,5 => 6 fires, not one per half tick");
    }

    @Test
    void loopBoundaryEventsFireBeforeTheNextGenerationsTickZero() {
        Walk walk = new Walk();
        walk.scheduler.configure(
            Collections.singletonList(
                contributor("cycle", 1.0d, true, new double[] { 0.0d, 0.999d },
                    new String[] { "zero", "tail" })),
            true);
        Recorder recorder = new Recorder();

        walk.advance(0.0d, recorder);
        walk.advance(1.0d, recorder);

        assertEquals(Arrays.asList("zero", "tail", "zero"), recorder.dispatched);
    }

    @Test
    void longAndShortContributorsKeepTheirOwnSchedules() {
        List<Contributor> program = new ArrayList<>();
        program.add(simple("long", 80.0d, true, 0.0d, "long"));
        program.add(simple("short", 0.404d, true, 0.0d, "short"));
        Recorder recorder = new Recorder();
        Walk walk = new Walk();
        walk.scheduler.configure(program, true);

        walk.advance(0.0d, recorder);
        for (int i = 0; i < 80; i++) {
            walk.advance(1.0d, recorder);
        }

        // The old offset loop materialised 80/0.404 copies *per frame*; here the
        // short driver fires once per own loop, the long one once per merged loop.
        assertEquals(199, recorder.count("short"), "floor(80/0.404) + 1");
        assertEquals(2, recorder.count("long"));
        assertEquals(2, walk.scheduler.getContributorCount());
        assertEquals(2, walk.scheduler.getTrackedEventCount(), "no future loop copies are stored");
    }

    @Test
    void holdOnLastFrameFiresEachEventOnceAndThenStops() {
        List<Contributor> program = Collections.singletonList(
            contributor("hold", 10.0d, false, new double[] { 0.0d, 5.0d }, new String[] { "a", "b" }));
        Recorder recorder = new Recorder();
        Walk walk = new Walk();
        walk.scheduler.configure(program, true);

        walk.advance(0.0d, recorder);
        for (int i = 0; i < 10; i++) {
            walk.advance(1.0d, recorder);
        }
        int afterFirstPass = recorder.count();
        for (int i = 0; i < 30; i++) {
            walk.advance(1.0d, recorder);
        }

        assertEquals(2, afterFirstPass, "t=0 and t=5 fire once");
        assertEquals(afterFirstPass, recorder.count(), "HOLD does not wrap");
    }

    @Test
    void playOnceAlsoStopsAtItsEnd() {
        Recorder recorder = new Recorder();
        Walk walk = new Walk();
        walk.scheduler.configure(Collections.singletonList(simple("once", 4.0d, false, 0.0d, "x")), true);

        walk.advance(0.0d, recorder);
        for (int i = 0; i < 20; i++) {
            walk.advance(1.0d, recorder);
        }

        assertEquals(1, recorder.count());
    }

    @Test
    void aLatePlayOnceJoiningPastItsEndDoesNotReplayTheTerminalEvent() {
        // Regression: the previous seek/realign path applied modulo to a non-looping
        // contributor, so a PLAY_ONCE animation that entered the program after it had
        // already ended replayed it. Joining late must leave it terminal.
        Recorder recorder = new Recorder();
        Walk walk = new Walk();
        walk.scheduler.configure(Collections.singletonList(simple("base", 1.0d, true, 0.0d, "inc")), true);

        walk.advance(0.0d, recorder);
        for (int i = 0; i < 10; i++) {
            walk.advance(1.0d, recorder);
        }
        int before = recorder.count();

        // The conditional entry list changes: "opening" joins mid-program while the
        // base program is preserved (restart = false). Its t=0 has already passed, and
        // the deferred t=0 must not be replayed just because the cursor is new.
        walk.scheduler.configure(
            Arrays.asList(
                simple("base", 1.0d, true, 0.0d, "inc"),
                simple("opening", 4.0d, false, 0.0d, "intro")),
            false);
        walk.advance(1.0d, recorder);
        walk.advance(1.0d, recorder);

        assertEquals(before + 2, recorder.count(), "only the preserved base contributor fires");
        assertEquals(0, recorder.count("intro"), "a late PLAY_ONCE whose t=0 passed must not replay");
    }

    @Test
    void rebuiltContributorsWithIdenticalContentDoNotReplay() {
        List<Contributor> program = Collections.singletonList(simple("idle", 1.0d, true, 0.0d, "assign"));
        Recorder recorder = new Recorder();
        Walk walk = new Walk();
        walk.scheduler.configure(program, true);

        walk.advance(0.0d, recorder); // t=0
        walk.advance(0.5d, recorder); // mid-loop, nothing due
        // The runtime rebuilds the program when the conditional animation list
        // changes; the EventKeyFrame objects are brand new, but the content is
        // identical so the cursor must be preserved (this is the old identity bug).
        walk.scheduler.configure(Collections.singletonList(simple("idle", 1.0d, true, 0.0d, "assign")), false);
        walk.advance(0.5d, recorder); // reaches the 1.0 boundary

        assertEquals(2, recorder.count(), "only t=0 and the 1.0 boundary may fire");
        assertEquals(1, walk.scheduler.getContributorCount());
    }

    @Test
    void contributorsWithACollidingContentHashAreStillComparedByContent() {
        // "Aa" and "BB" are the classic Java String hash collision (both 2112), so the
        // events — and therefore the 32-bit contentHash — collide while the instruction
        // text differs. Reusing a cursor by hash alone would silently continue the wrong
        // schedule; contentEquals must fall back to the full content comparison.
        assertEquals("Aa".hashCode(), "BB".hashCode(), "precondition: the instruction hashes collide");
        Contributor alpha = contributor("same", 4.0d, true, new double[] { 0.0d }, new String[] { "Aa" });
        Contributor beta = contributor("same", 4.0d, true, new double[] { 0.0d }, new String[] { "BB" });
        assertEquals(alpha.contentHash, beta.contentHash, "precondition: the content hashes collide");
        assertEquals(false, alpha.contentEquals(beta), "a hash collision must not count as equal content");
        assertEquals(true, alpha.contentEquals(alpha), "precondition: self-comparison matches");

        Recorder recorder = new Recorder();
        Walk walk = new Walk();
        walk.scheduler.configure(Collections.singletonList(alpha), true);
        walk.advance(0.0d, recorder); // alpha @ 0
        assertEquals(Collections.singletonList("Aa"), recorder.dispatched);

        // Reconfigure without restart to the colliding content: it must be treated as
        // a new contributor, not as alpha with a new label.
        walk.scheduler.configure(Collections.singletonList(beta), false);
        walk.advance(2.0d, recorder);

        assertEquals(Arrays.asList("Aa", "BB"), recorder.dispatched,
            "the colliding contributor starts its own schedule and must not replay alpha");
    }

    @Test
    void differentContributorsAreIsolatedAcrossSchedulers() {
        List<Contributor> program = Collections.singletonList(simple("idle", 1.0d, true, 0.0d, "inc"));
        Walk playerA = new Walk();
        Walk playerB = new Walk();
        playerA.scheduler.configure(program, true);
        playerB.scheduler.configure(program, true);
        Recorder recorderA = new Recorder();
        Recorder recorderB = new Recorder();

        playerA.advance(0.0d, recorderA);
        playerA.advance(1.0d, recorderA);
        playerB.advance(0.0d, recorderB);

        assertEquals(2, recorderA.count());
        assertEquals(1, recorderB.count(), "B's cursor must not advance with A's clock");
    }

    @Test
    void configureWithRestartResetsTheProgramForModelOrStateChanges() {
        List<Contributor> program = Collections.singletonList(simple("idle", 1.0d, true, 0.0d, "inc"));
        Walk walk = new Walk();
        walk.scheduler.configure(program, true);
        Recorder recorder = new Recorder();
        walk.advance(0.0d, recorder);
        walk.advance(1.0d, recorder);
        assertEquals(2, recorder.count());

        // Model/state switch: hard restart drops every cursor.
        walk.restart(program);
        assertFalse(walk.scheduler.isStarted(), "restart clears the started flag");
        walk.advance(0.0d, recorder);

        assertEquals(3, recorder.count(), "one fresh t=0 dispatch after the restart");
    }

    @Test
    void returningToAnUnchangedProgramAfterAStateChangeRestartsTheClock() {
        // The old fast path reused the program when the animation list was unchanged,
        // so default -> attack -> default resumed the previous phase and the state's
        // entry event never fired. A restart must reset the clock even though the
        // contributor content is identical.
        List<Contributor> idle = Collections.singletonList(simple("idle", 2.0d, true, 0.0d, "enter"));
        Recorder recorder = new Recorder();
        Walk walk = new Walk();
        walk.scheduler.configure(idle, true);
        walk.advance(0.0d, recorder); // enter @ 0
        walk.advance(1.0d, recorder); // mid-loop
        assertEquals(1, recorder.count());

        // State change with an identical list: the runtime passes restart = true.
        walk.restart(idle);
        walk.advance(0.0d, recorder);

        assertEquals(2, recorder.count(), "the entry event must fire again on re-entry");
    }

    @Test
    void catchUpJumpsToPhaseInsteadOfReplayingUnboundedHistory() {
        TimelineEventScheduler scheduler = new TimelineEventScheduler();
        scheduler.configure(Collections.singletonList(simple("driver", 0.404d, true, 0.0d, "inc")), true);
        Recorder recorder = new Recorder();
        scheduler.advance(0.0d, recorder);

        int before = recorder.count();
        scheduler.advance(1000.0d, recorder);

        assertEquals(before, recorder.count(), "a long freeze must not replay the queue");
        assertEquals(1L, scheduler.getSkippedAdvances());
    }

    /**
     * A 0.01-tick period with a 5-tick frame delta is the pathological short-period case
     * the budget exists for. Regression: the over-budget branch realigned the cursor but
     * left it in the due-event search, and {@code align()} resets {@code next}, so the
     * same cursor matched again every iteration — the advance livelocked for minutes
     * instead of returning. The test used to take ~235 seconds; it must stay milliseconds.
     */
    @Test
    void oneFrameWithManyLoopGenerationsIsBoundedAndCounted() {
        TimelineEventScheduler scheduler = new TimelineEventScheduler();
        scheduler.configure(Collections.singletonList(simple("fast", 0.01d, true, 0.0d, "inc")), true);
        Recorder recorder = new Recorder();
        scheduler.advance(0.0d, recorder);
        int before = recorder.count();

        scheduler.advance(5.0d, recorder);

        int dispatched = recorder.count() - before;
        assertTrue(
            dispatched <= TimelineEventScheduler.MAX_EVENTS_PER_CONTRIBUTOR_PER_FRAME + 1,
            "the per-contributor frame budget must bound one advance, got " + dispatched);
        assertTrue(dispatched >= 1, "the budget must not degenerate into dispatching nothing");
        assertTrue(scheduler.getSkippedAdvances() >= 1L);
        assertEquals(1, scheduler.getContributorCount());
        // The skipped cursor was realigned to the current phase, so the next frame resumes
        // from there instead of replaying the dropped generations.
        scheduler.advance(0.25d, recorder);
        assertTrue(recorder.count() - before > dispatched, "the next frame must resume dispatching");
    }

    @Test
    void contributorAndEventCapsAreEnforcedWithoutMaterialisingFutureLoops() {
        TimelineEventScheduler scheduler = new TimelineEventScheduler();
        List<Contributor> many = new ArrayList<>();
        for (int i = 0; i < TimelineEventScheduler.MAX_CONTRIBUTORS + 40; i++) {
            many.add(simple("c" + i, 20.0d, true, 0.0d, "e" + i));
        }
        scheduler.configure(many, true);

        assertEquals(TimelineEventScheduler.MAX_CONTRIBUTORS, scheduler.getContributorCount());
        assertEquals(40, scheduler.getTruncatedContributors());

        List<Event> huge = new ArrayList<>();
        for (int i = 0; i < TimelineEventScheduler.MAX_EVENTS_PER_CONTRIBUTOR + 25; i++) {
            huge.add(new Event(i * 0.001d, "e" + i));
        }
        TimelineEventScheduler capped = new TimelineEventScheduler();
        capped.configure(
            Collections.singletonList(
                TimelineEventScheduler.contributor("huge", 100.0d, true, huge)),
            true);
        assertEquals(TimelineEventScheduler.MAX_EVENTS_PER_CONTRIBUTOR, capped.getTrackedEventCount());
        assertEquals(25, capped.getTruncatedEvents());
    }

    @Test
    void tinyEpsilonAtAnEventTickDoesNotDoubleFire() {
        Walk walk = new Walk();
        walk.scheduler.configure(Collections.singletonList(simple("mid", 1.0d, true, 0.5d, "atHalf")), true);
        Recorder recorder = new Recorder();

        walk.advance(0.0d, recorder);
        walk.advance(0.5d, recorder);
        walk.advance(1.0e-9d, recorder);
        walk.advance(1.0e-9d, recorder);

        assertEquals(1, recorder.count(), "an event at exactly 0.5 fires once");
    }

    @Test
    void nonFiniteAndNegativeDeltasAreIgnored() {
        TimelineEventScheduler scheduler = new TimelineEventScheduler();
        scheduler.configure(Collections.singletonList(simple("idle", 1.0d, true, 0.0d, "inc")), true);
        Recorder recorder = new Recorder();

        scheduler.advance(Double.NaN, recorder);
        scheduler.advance(Double.POSITIVE_INFINITY, recorder);
        scheduler.advance(-5.0d, recorder);
        scheduler.advance(0.0d, recorder);

        assertEquals(1, recorder.count());
        assertEquals(0.0d, scheduler.getClock(), 0.0d);
    }

    @Test
    void seekRepositionsALoopingContributorWithoutReplayingTheSkippedInterval() {
        Recorder recorder = new Recorder();
        Walk walk = new Walk();
        walk.scheduler.configure(Collections.singletonList(simple("seek", 10.0d, true, 5.0d, "mid")), true);
        walk.advance(0.0d, recorder); // t=0 (no event there)
        walk.advance(2.0d, recorder); // clock 2, event at 5 not reached

        walk.scheduler.seekTo(1.0d);
        assertEquals(0, recorder.count());

        walk.scheduler.advanceFrame(6.0d, 5.0d, recorder); // position 6, crosses 5 once
        assertEquals(1, recorder.count());
        assertEquals(1L, walk.scheduler.getSeekCount());
    }

    @Test
    void seekPastTheEndOfANonLoopingContributorLeavesItTerminal() {
        TimelineEventScheduler scheduler = new TimelineEventScheduler();
        scheduler.configure(Collections.singletonList(simple("once", 10.0d, false, 5.0d, "hit")), true);
        Recorder recorder = new Recorder();

        scheduler.advance(0.0d, recorder);
        scheduler.advance(6.0d, recorder);
        assertEquals(1, recorder.count(), "the one-shot event fires once");

        // Rewind and seek forward again: a repeated rewind/seek cycle used to apply
        // floor(clock / period) to a non-looping contributor, replaying it forever.
        scheduler.seekTo(2.0d);
        scheduler.seekTo(20.0d);
        scheduler.advance(20.0d, recorder);

        assertEquals(1, recorder.count(), "a non-looping contributor must not replay after a seek");
    }

    @Test
    void sameInstantEventsFollowContributorDeclarationOrder() {
        TimelineEventScheduler scheduler = new TimelineEventScheduler();
        List<Contributor> program = new ArrayList<>();
        program.add(simple("first", 2.0d, true, 0.0d, "a"));
        program.add(simple("second", 2.0d, true, 0.0d, "b"));
        scheduler.configure(program, true);
        Recorder recorder = new Recorder();

        scheduler.advance(0.0d, recorder);

        assertEquals(Arrays.asList("a", "b"), recorder.dispatched);
    }

    @Test
    void positionAndDeltaReportTheSamePlaybackAcrossAFrame() {
        // Mirrors what the controller listener delivers: an absolute position plus the
        // forward distance that produced it. A wrap in the reported position must not
        // lose the distance, because the delta (not the difference of positions) is
        // what drives the schedule.
        //
        // Cumulative elapsed distance: 0 + 0.5 + 0.75 + 0.5 = 1.75 ticks, so a period-1
        // t=0 event fires at clock 0 and clock 1 — twice, not once per frame.
        TimelineEventScheduler scheduler = new TimelineEventScheduler();
        scheduler.configure(Collections.singletonList(simple("tick", 1.0d, true, 0.0d, "inc")), true);
        Recorder recorder = new Recorder();

        scheduler.advanceFrame(0.0d, 0.0d, recorder); // anchor at 0, t=0 fires
        scheduler.advanceFrame(0.5d, 0.5d, recorder); // clock 0.5, nothing due
        // Loop wrap inside one frame: the position report restarts at 0.25 while the
        // unwrapped delta is the 0.75 that really elapsed. The clock passes 1.
        scheduler.advanceFrame(0.25d, 0.75d, recorder);
        scheduler.advanceFrame(0.75d, 0.5d, recorder); // clock 1.75, 2 not reached

        assertEquals(2, recorder.count(), "positions crossing 0 and 1 => 2 fires");
    }

    /**
     * A first attachment at a small non-zero phase is a late join: the cursors align
     * to that phase and the events that already passed are not replayed, so a model
     * that joins mid-state does not re-run its entry side effects.
     */
    @Test
    void firstAttachmentAtANonZeroPositionAlignsWithoutReplaying() {
        TimelineEventScheduler scheduler = new TimelineEventScheduler();
        scheduler.configure(
            Collections.singletonList(
                contributor("cycle", 4.0d, true, new double[] { 0.0d, 2.0d }, new String[] { "zero", "two" })),
            true);
        Recorder recorder = new Recorder();

        // Late join at phase 3: "zero" (t=0) and "two" (t=2) already passed.
        scheduler.advanceFrame(3.0d, 0.0d, recorder);
        assertEquals(Collections.emptyList(), recorder.dispatched, "a late join must not replay passed events");

        // Next due event is the following cycle's t=0, one tick later.
        scheduler.advanceFrame(4.0d, 1.0d, recorder);
        assertEquals(Collections.singletonList("zero"), recorder.dispatched);
    }

    /** A far first attachment (long freeze) also positions without enumerating cycles. */
    @Test
    void firstAttachmentFarFromZeroPositionsWithoutReplayingHistory() {
        TimelineEventScheduler scheduler = new TimelineEventScheduler();
        scheduler.configure(Collections.singletonList(simple("driver", 0.404d, true, 0.0d, "inc")), true);
        Recorder recorder = new Recorder();

        scheduler.advanceFrame(1000.0d, 0.0d, recorder);

        assertEquals(0, recorder.count());
        assertEquals(1L, scheduler.getSkippedAdvances());
        assertEquals(1000.0d, scheduler.getClock(), 1.0e-6d);
    }

    /**
     * A zero-delta report at a different position is a re-anchor (custom seek /
     * restart / resume): the cursors rebase to the new phase without replaying the
     * skipped interval, then keep scheduling on the following forward deltas.
     */
    @Test
    void aZeroDeltaReportAtANewPositionRebasesWithoutReplaying() {
        TimelineEventScheduler scheduler = new TimelineEventScheduler();
        scheduler.configure(Collections.singletonList(simple("seek", 10.0d, true, 5.0d, "mid")), true);
        Recorder recorder = new Recorder();

        scheduler.advanceFrame(0.0d, 0.0d, recorder); // t=0, event at 5 not reached
        scheduler.advanceFrame(7.0d, 7.0d, recorder); // crosses 5 once
        assertEquals(1, recorder.count());

        // Backward re-anchor to phase 1 with no forward distance.
        scheduler.advanceFrame(1.0d, 0.0d, recorder);
        assertEquals(1, recorder.count(), "the rebase itself must not dispatch");
        assertEquals(1L, scheduler.getRebaseCount());

        // From phase 1 the event at 5 is 4 ticks away again.
        scheduler.advanceFrame(5.0d, 4.0d, recorder);
        assertEquals(2, recorder.count());
    }

    /**
     * A zero delta at the SAME position is a stalled clock (HOLD clamped, paused
     * animation), not a re-anchor: nothing is realigned and nothing fires.
     */
    @Test
    void aZeroDeltaAtTheSamePositionIsNotARebase() {
        TimelineEventScheduler scheduler = new TimelineEventScheduler();
        scheduler.configure(Collections.singletonList(simple("tick", 1.0d, true, 0.0d, "inc")), true);
        Recorder recorder = new Recorder();

        scheduler.advanceFrame(0.0d, 0.0d, recorder); // t=0
        scheduler.advanceFrame(0.5d, 0.5d, recorder);
        scheduler.advanceFrame(0.5d, 0.0d, recorder); // stalled
        assertEquals(1, recorder.count());
        assertEquals(0L, scheduler.getRebaseCount());

        scheduler.advanceFrame(1.0d, 0.5d, recorder); // resumed, crosses 1
        assertEquals(2, recorder.count());
        assertEquals(0L, scheduler.getRebaseCount());
    }

    @Test
    void absolutePositionsDoNotChangeTheScheduleComparedToDeltas() {
        List<Contributor> program = new ArrayList<>();
        program.add(simple("long", 8.0d, true, 0.0d, "long"));
        program.add(simple("short", 0.5d, true, 0.0d, "short"));

        Walk walk = new Walk();
        walk.scheduler.configure(program, true);
        Recorder recorder = new Recorder();
        walk.advance(0.0d, recorder);
        for (int i = 0; i < 24; i++) {
            walk.advance(1.0d, recorder);
        }
        int shortCount = recorder.count("short");
        int longCount = recorder.count("long");

        TimelineEventScheduler absolute = new TimelineEventScheduler();
        absolute.configure(program, true);
        Recorder absoluteRecorder = new Recorder();
        absolute.advanceFrame(0.0d, 0.0d, absoluteRecorder);
        for (int i = 1; i <= 24; i++) {
            absolute.advanceFrame(i, 1.0d, absoluteRecorder);
        }

        assertEquals(absoluteRecorder.count("short"), shortCount);
        assertEquals(absoluteRecorder.count("long"), longCount);
        assertEquals(49, shortCount, "floor(24/0.5) + 1");
        assertEquals(4, longCount, "floor(24/8) + 1");
    }
}
