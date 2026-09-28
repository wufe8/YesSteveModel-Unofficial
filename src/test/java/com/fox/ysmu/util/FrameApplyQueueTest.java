package com.fox.ysmu.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class FrameApplyQueueTest {
    @Test
    void enqueueDoesNotExecuteAndEachFrameHasItsOwnCountBound() {
        FrameApplyQueue<Integer> queue = new FrameApplyQueue<>(4);
        List<Integer> applied = new ArrayList<>();
        queue.enqueue(1, false);
        queue.enqueue(2, false);
        queue.enqueue(9, true);
        assertTrue(applied.isEmpty());
        assertFalse(queue.isDrained());
        assertEquals(2, queue.drain(2, Long.MAX_VALUE, applied::add));
        assertEquals(Arrays.asList(9, 1), applied);
        assertFalse(queue.isDrained(), "The next frame's work must not run recursively");
        assertEquals(1, queue.drain(2, Long.MAX_VALUE, applied::add));
        assertEquals(Arrays.asList(9, 1, 2), applied);
        assertTrue(queue.isDrained());
    }

    @Test
    void elapsedBudgetStopsAfterTheCurrentItemRatherThanStartingAnother() {
        AtomicLong clock = new AtomicLong();
        FrameApplyQueue<Integer> queue = new FrameApplyQueue<>(3, clock::get);
        queue.enqueue(1, false);
        queue.enqueue(2, false);
        List<Integer> applied = new ArrayList<>();
        assertEquals(1, queue.drain(3, 10, value -> {
            applied.add(value);
            clock.addAndGet(11);
        }));
        assertEquals(Arrays.asList(1), applied);
        assertEquals(1, queue.drain(3, 10, applied::add));
        assertTrue(queue.isDrained());
    }

    @Test
    void failedConsumerAndClearReturnOnlyOwnedPermits() {
        FrameApplyQueue<Integer> queue = new FrameApplyQueue<>(2);
        queue.enqueue(1, false);
        queue.enqueue(2, true);
        assertThrows(IllegalStateException.class, () -> queue.drain(2, Long.MAX_VALUE, value -> {
            throw new IllegalStateException("consumer failed");
        }));
        queue.clear();
        assertTrue(queue.isDrained());
        queue.enqueue(3, false);
        queue.enqueue(4, true);
        List<Integer> applied = new ArrayList<>();
        queue.drain(2, Long.MAX_VALUE, applied::add);
        assertEquals(Arrays.asList(4, 3), applied);
        assertTrue(queue.isDrained());
    }

    @Test
    void interruptedProducerDoesNotInflateCapacity() {
        FrameApplyQueue<Integer> queue = new FrameApplyQueue<>(1);
        Thread.currentThread().interrupt();
        try {
            assertTrue(queue.enqueue(1, false));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(queue.isDrained());
            queue.drain(1, Long.MAX_VALUE, value -> {});
            assertTrue(queue.isDrained());
        } finally {
            Thread.interrupted();
        }
    }
}
