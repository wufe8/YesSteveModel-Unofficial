package com.fox.ysmu.util;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Bounded producer queue with an explicitly frame-driven consumer. Enqueue never schedules
 * a recursive main-thread callback; the owner calls drain once at the start of a render frame.
 */
public final class FrameApplyQueue<T> {
    private final Queue<T> priority = new ConcurrentLinkedQueue<>();
    private final Queue<T> normal = new ConcurrentLinkedQueue<>();
    private final Semaphore slots;
    private final int capacity;
    private final LongSupplier clock;
    private long generation;

    public FrameApplyQueue(int capacity) {
        this(capacity, System::nanoTime);
    }

    FrameApplyQueue(int capacity, LongSupplier clock) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.slots = new Semaphore(capacity);
        this.clock = clock;
    }

    /** Background producers only. An interrupt does not manufacture an unowned permit. */
    public boolean enqueue(T value, boolean highPriority) {
        Objects.requireNonNull(value, "value");
        final long started;
        synchronized (this) {
            started = generation;
        }
        slots.acquireUninterruptibly();
        synchronized (this) {
            if (started != generation) {
                slots.release();
                return false;
            }
            try {
                (highPriority ? priority : normal).add(value);
                return true;
            } catch (RuntimeException | Error failure) {
                slots.release();
                throw failure;
            }
        }
    }

    /** Main thread only. At least one available item may run; a single item is not preemptible. */
    public int drain(int maxItems, long budgetNanos, Consumer<T> apply) {
        long start = clock.getAsLong();
        int applied = 0;
        while (applied < maxItems && (applied == 0 || clock.getAsLong() - start < budgetNanos)) {
            T value;
            synchronized (this) {
                value = priority.poll();
                if (value == null) value = normal.poll();
            }
            if (value == null) break;
            try {
                apply.accept(value);
                applied++;
            } finally {
                slots.release();
            }
        }
        return applied;
    }

    /** Drop queued work and reject producers that were already waiting in the previous epoch. */
    public synchronized void clear() {
        generation++;
        while (priority.poll() != null) slots.release();
        while (normal.poll() != null) slots.release();
    }

    public synchronized boolean isDrained() {
        return priority.isEmpty() && normal.isEmpty() && slots.availablePermits() == capacity;
    }
}
