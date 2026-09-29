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

    /**
     * 「新一轮同步」的丢弃契约：{@code clear()} 推进代际后，**已经在锁外等待或正在入队**的生产者
     * 必须被拒绝，而不是把旧会话的解析结果塞进新会话的队列。
     *
     * <p>这是同步重载（{@code /ysm reload}、重连）期间"旧解析结果复活已删除模型"的第一道闸门；
     * 第二道在 {@code ClientModelManager.applyPreParsed}（bundle 自带的代际比对），
     * 因为 {@code clear()} 只能丢掉"已经在队列里"的项，丢不掉"检查通过后、入队前"的那一个。</p>
     */
    @Test
    void producersFromThePreviousEpochAreRejectedAfterClear() throws Exception {
        FrameApplyQueue<Integer> queue = new FrameApplyQueue<>(2);
        // 占满容量，让下一个生产者在 slots.acquire 上等待 —— 模拟"解析完成、正在入队"的瞬间。
        queue.enqueue(1, false);
        queue.enqueue(2, false);

        java.util.concurrent.atomic.AtomicBoolean accepted =
            new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread producer = new Thread(() -> accepted.set(queue.enqueue(99, false)));
        producer.start();
        // 等它真正进入等待（拿不到 permit）。
        Thread.sleep(100);
        // 旧会话结束：清空队列并推进代际。
        queue.clear();
        producer.join(2000);

        assertFalse(producer.isAlive(), "the waiting producer must not hang forever");
        assertFalse(accepted.get(), "a producer from the previous epoch must be rejected, not queued");
        assertTrue(queue.isDrained());

        // 新代际的生产者正常入队。
        assertTrue(queue.enqueue(5, false));
        List<Integer> applied = new ArrayList<>();
        queue.drain(1, Long.MAX_VALUE, applied::add);
        assertEquals(Arrays.asList(5), applied);
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
