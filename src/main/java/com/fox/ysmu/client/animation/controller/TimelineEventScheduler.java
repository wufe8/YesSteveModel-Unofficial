package com.fox.ysmu.client.animation.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Bounded timeline merge. Each contributor stores a source index, local phase and
 * distance to its next event; no expanded cycles, execution history or ever-growing
 * generation counter. The real player supplies a forward delta after time updates.
 * Overflow skips to the current phase and records the skipped advance.
 * <p>
 * The schedule is driven by the forward {@code delta}, not by the absolute position,
 * because the position wraps at the animation length and would otherwise turn a loop
 * into a negative jump. The position is used in exactly three places:
 * <ul>
 *   <li>a first attachment far from 0 ({@code > MAX_CATCH_UP_TICKS}) aligns without
 *       enumerating the missed cycles;</li>
 *   <li>a first attachment at a small non-zero phase (a late join) aligns to that
 *       phase without replaying the events that already passed;</li>
 *   <li>a zero-delta report at a <em>different</em> position is a re-anchor (custom
 *       seek, restart, resume): the cursors rebase to the new phase without replaying
 *       the skipped interval. A zero delta at the <em>same</em> position is a stalled
 *       clock (e.g. HOLD clamped at the last frame) and changes nothing.</li>
 * </ul>
 */
public final class TimelineEventScheduler {
    public interface Sink { void dispatch(String instructions); }

    public static final int MAX_CONTRIBUTORS = 128;
    public static final int MAX_EVENTS_PER_CONTRIBUTOR = 16384;
    public static final int MAX_TOTAL_EVENTS = 65536;
    public static final int MAX_DISPATCH_PER_ADVANCE = 1024;
    public static final int MAX_EVENTS_PER_CONTRIBUTOR_PER_FRAME = 256;
    public static final double MAX_CATCH_UP_TICKS = 40.0d;
    public static final double EPS = 1.0e-6d;
    private static final double CLOCK_REBASE = 4294967296.0d;

    public static final class Event {
        final double time;
        final String data;
        public Event(double time, String data) {
            this.time = Double.isFinite(time) && time > 0 ? time : 0;
            this.data = data == null ? "" : data;
        }
        public double getTime() { return time; }
        public String getData() { return data; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Event)) return false;
            Event e = (Event) o;
            return Double.compare(time, e.time) == 0 && data.equals(e.data);
        }
        @Override public int hashCode() { return Objects.hash(time, data); }
    }

    public static final class Contributor {
        final String id;
        final double period;
        final boolean loops;
        final Event[] events;
        final int contentHash;
        Contributor(String id, double period, boolean loops, Event[] events) {
            this.id = id == null ? "" : id;
            this.period = Double.isFinite(period) && period > 0 ? period : 0;
            this.loops = loops && this.period > EPS;
            this.events = events;
            this.contentHash = Objects.hash(this.id, this.period, this.loops, java.util.Arrays.hashCode(events));
        }
        public String getId() { return id; }
        public double getPeriod() { return period; }
        public boolean isLooping() { return loops; }
        public int getEventCount() { return events.length; }
        Contributor truncated(int n) {
            return n >= events.length ? this : new Contributor(id, period, loops, java.util.Arrays.copyOf(events, n));
        }
        boolean contentEquals(Contributor other) {
            return other != null && contentHash == other.contentHash && id.equals(other.id)
                && Double.compare(period, other.period) == 0 && loops == other.loops
                && java.util.Arrays.equals(events, other.events);
        }
    }

    public static Contributor contributor(String id, double period, boolean loops, List<Event> events) {
        List<Event> copy = new ArrayList<>();
        if (events != null) for (Event e : events) if (e != null) copy.add(e);
        Collections.sort(copy, Comparator.comparingDouble(e -> e.time));
        return new Contributor(id, period, loops, copy.toArray(new Event[0]));
    }

    private static final class Cursor {
        final Contributor source;
        int index;
        int emitted;
        double phase;
        double targetPhase;
        double next;
        boolean fresh = true;
        /** Set when this cursor used up its per-frame budget and was realigned to the
         *  current phase: it must not be reconsidered in the same advance, otherwise the
         *  realign would reset {@code next} and the due-event test would match forever
         *  (a livelock that froze the render thread for minutes). */
        boolean skippedThisAdvance;
        Cursor(Contributor source) { this.source = source; }
        void atStart() {
            phase = 0;
            index = 0;
            next = source.events.length == 0 ? Double.POSITIVE_INFINITY : source.events[0].time;
            fresh = false;
            checkEnd();
        }
        void checkEnd() {
            if (index >= source.events.length || (source.period > 0 && source.events[index].time > source.period + EPS))
                next = Double.POSITIVE_INFINITY;
        }
        void step() {
            double old = source.events[index].time;
            index++;
            if (index == source.events.length) {
                if (!source.loops) { next = Double.POSITIVE_INFINITY; return; }
                index = 0;
                next += source.period - old + source.events[0].time;
            } else next += source.events[index].time - old;
            checkEnd();
        }
        void align(double time) {
            phase = source.loops ? time % source.period : time;
            index = firstAfter(source, phase);
            if (index == source.events.length) {
                if (source.loops) {
                    index = 0;
                    next = source.period - phase + source.events[0].time;
                } else next = Double.POSITIVE_INFINITY;
            } else next = source.events[index].time - phase;
            fresh = false;
            checkEnd();
        }
    }

    private final List<Cursor> cursors = new ArrayList<>();
    private boolean started;
    private double clock;
    private long skippedEvents;
    private long skippedAdvances;
    private long seeks;
    /** Last playback position handed to {@link #advanceFrame}. Tells a zero-delta
     *  re-anchor (seek/restart/resume) from a genuinely stalled clock: only a position
     *  change together with delta 0 rebases the cursors. */
    private double lastPosition = Double.NaN;
    /** Implicit rebases applied from the position+delta stream (see
     *  {@link #advanceFrame}); explicit {@link #seekTo} calls are counted by
     *  {@link #getSeekCount()}. */
    private long rebases;
    private int truncatedContributors;
    private int truncatedEvents;

    public void configure(List<Contributor> program, boolean restart) {
        List<Cursor> previous = restart ? new ArrayList<>() : new ArrayList<>(cursors);
        if (restart) reset();
        cursors.clear();
        truncatedContributors = truncatedEvents = 0;
        int total = 0;
        if (program == null) return;
        for (Contributor raw : program) {
            if (raw == null || raw.events.length == 0) continue;
            if (cursors.size() >= MAX_CONTRIBUTORS || total >= MAX_TOTAL_EVENTS) {
                truncatedContributors++; continue;
            }
            int allowed = Math.min(raw.events.length, Math.min(MAX_EVENTS_PER_CONTRIBUTOR, MAX_TOTAL_EVENTS - total));
            truncatedEvents += raw.events.length - allowed;
            Contributor source = raw.truncated(allowed);
            total += allowed;
            Cursor reused = null;
            for (Cursor old : previous) if (old.source.contentEquals(source)) { reused = old; break; }
            if (reused != null) { previous.remove(reused); cursors.add(reused); }
            else cursors.add(new Cursor(source));
        }
    }

    /** Position is relevant only on first attachment; subsequent deltas include wraps. */
    public int advanceFrame(double position, double delta, Sink sink) {
        if (!Double.isFinite(delta) || delta < 0) return 0;
        if (!started) {
            started = true;
            if (Double.isFinite(position) && position > MAX_CATCH_UP_TICKS) {
                // A fresh playback that begins far from 0: position without
                // enumerating the missed cycles.
                clock = position;
                for (Cursor c : cursors) c.align(position);
                skippedAdvances++;
                lastPosition = position;
                return 0;
            }
            if (Double.isFinite(position) && position > EPS) {
                // First attachment at a small non-zero phase (a late join): align to
                // the reported position without replaying the events that already
                // passed. position == 0 still falls through to the t=0 dispatch below,
                // so a state's entry events are never lost.
                clock = position;
                for (Cursor c : cursors) c.align(position);
                lastPosition = position;
                return 0;
            }
        } else if (delta == 0.0d && Double.isFinite(position) && Double.isFinite(lastPosition)
            && Math.abs(position - lastPosition) > EPS) {
            // A zero-delta report at a different position is a re-anchor (custom seek,
            // restart or resume), not a stalled clock: rebase every cursor to the new
            // phase without replaying the skipped interval. The schedule stays
            // delta-driven afterwards, so this only moves where the next events are
            // measured from.
            clock = Math.max(0.0d, position);
            for (Cursor c : cursors) c.align(clock);
            lastPosition = clock;
            rebases++;
            return 0;
        }
        for (Cursor c : cursors) {
            c.emitted = 0;
            c.skippedThisAdvance = false;
            if (c.fresh) {
                if (clock == 0) c.atStart();
                else if (!c.source.loops) c.align(clock);
                else {
                    c.atStart();
                    c.phase = clock % c.source.period;
                    c.next -= c.phase; // late looping contributor establishes current-cycle values once
                }
            }
        }
        if (delta > MAX_CATCH_UP_TICKS) {
            for (Cursor c : cursors) c.align(advancedPhase(c, delta));
            skippedAdvances++;
            skippedEvents = saturatingIncrement(skippedEvents);
            advanceClock(delta);
            return 0;
        }
        for (Cursor c : cursors) c.targetPhase = advancedPhase(c, delta);
        int count = 0;
        boolean skipped = false;
        while (true) {
            Cursor best = null;
            for (Cursor c : cursors) {
                if (c.skippedThisAdvance) continue;
                if (c.next <= delta + EPS && (best == null || c.next < best.next - EPS)) best = c;
            }
            if (best == null) break;
            if (count >= MAX_DISPATCH_PER_ADVANCE) {
                for (Cursor c : cursors) if (c.next <= delta + EPS) {
                    c.align(advancedPhase(c, delta));
                    c.next += delta; // adjusted below with all other cursor distances
                    skippedEvents = saturatingIncrement(skippedEvents);
                }
                skipped = true;
                break;
            }
            if (best.emitted >= MAX_EVENTS_PER_CONTRIBUTOR_PER_FRAME) {
                // Realign this contributor to the phase it should resume at and drop it
                // from this advance. `+= delta` is cancelled by the final `-= delta`
                // below, leaving the distance measured from the new phase. The cursor
                // must be excluded from the due-event search, because align() resets
                // `next` and the same test would match again forever.
                best.align(advancedPhase(best, delta));
                best.next += delta;
                best.skippedThisAdvance = true;
                skippedEvents = saturatingIncrement(skippedEvents);
                skipped = true;
                continue;
            }
            if (sink != null) sink.dispatch(best.source.events[best.index].data);
            count++;
            best.emitted++;
            best.step();
        }
        for (Cursor c : cursors) {
            // align() above already advanced overloaded cursors: phase is updated
            // using the frame's saved phase below, rather than accumulating forever.
            c.next -= delta;
        }
        advanceClock(delta);
        for (Cursor c : cursors) c.phase = c.targetPhase;
        if (skipped) skippedAdvances++;
        // Remember the reported position even when the clock is unwrapped, so the next
        // zero-delta comparison can tell a stalled clock from a rebase.
        if (Double.isFinite(position)) lastPosition = position;
        return count;
    }

    private double advancedPhase(Cursor c, double delta) {
        if (c.source.loops) return (c.phase + delta % c.source.period) % c.source.period;
        return Math.min(Double.MAX_VALUE, c.phase + delta);
    }
    private void advanceClock(double delta) {
        clock += delta;
        if (!Double.isFinite(clock) || clock >= CLOCK_REBASE) {
            // Existing cursors retain local phase and next-event distance. Only the
            // late-join reference origin resets (after ~6.8 years at normal speed).
            clock = 0;
        }
    }
    public int advance(double delta, Sink sink) { return advanceFrame(clock + delta, delta, sink); }
    public void start(Sink sink) { if (!started) advance(0, sink); }
    public void seekTo(double position) {
        if (!Double.isFinite(position)) return;
        clock = Math.max(0, position);
        for (Cursor c : cursors) c.align(clock);
        started = true;
        lastPosition = clock;
        seeks++;
    }
    private static int firstAfter(Contributor c, double phase) {
        int low = 0, high = c.events.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (c.events[mid].time <= phase + EPS) low = mid + 1; else high = mid;
        }
        return low;
    }
    private static long saturatingIncrement(long n) { return n == Long.MAX_VALUE ? n : n + 1; }
    public void reset() {
        cursors.clear(); started = false; clock = 0;
        skippedEvents = skippedAdvances = seeks = rebases = 0;
        lastPosition = Double.NaN;
        truncatedEvents = truncatedContributors = 0;
    }
    public boolean isStarted() { return started; }
    public double getClock() { return clock; }
    public int getContributorCount() { return cursors.size(); }
    public int getCursorCount() { return cursors.size(); }
    public int getTrackedEventCount() { int n = 0; for (Cursor c : cursors) n += c.source.events.length; return n; }
    /** Lower bound of dropped event batches; never enumerates missed cycles to count them. */
    public long getSkippedEvents() { return skippedEvents; }
    public long getSkippedAdvances() { return skippedAdvances; }
    public long getSeekCount() { return seeks; }
    /** Implicit rebases applied because a zero-delta report carried a new position. */
    public long getRebaseCount() { return rebases; }
    public int getTruncatedContributors() { return truncatedContributors; }
    public int getTruncatedEvents() { return truncatedEvents; }
}
