package com.fox.ysmu.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link TickScheduler}：把同步看门狗 / 完成等待这类轮询从解析线程池搬到"被外部驱动的计时器"，
 * 是本轮修复 R1（ThreadCount=1 时看门狗占住唯一工作线程、把生产者饿死）的结构依据。
 *
 * <p>用假时钟做确定性验证：间隔到达才执行、取消幂等、任务体内自取消生效、
 * 抛出异常的任务自动注销而不是每 tick 刷屏、{@code clear()} 之后不再触发。</p>
 */
class TickSchedulerTest {

    private static final class FakeClock implements java.util.function.LongSupplier {

        long now;

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(long ms) {
            now += ms;
        }
    }

    @Test
    void runsOnlyAfterTheIntervalElapses() {
        FakeClock clock = new FakeClock();
        TickScheduler scheduler = new TickScheduler(clock);
        List<String> runs = new ArrayList<>();
        scheduler.scheduleEvery(250L, () -> runs.add("tick"));

        // 未到间隔：一次都不跑（首次执行在一个间隔之后，而不是注册即执行）。
        scheduler.runDueTasks();
        assertTrue(runs.isEmpty());
        clock.advance(249);
        scheduler.runDueTasks();
        assertTrue(runs.isEmpty());
        clock.advance(1);
        scheduler.runDueTasks();
        assertEquals(1, runs.size());

        // 到点后每个间隔一次，多个间隔合并成一次（不补跑）。
        clock.advance(1000);
        scheduler.runDueTasks();
        assertEquals(2, runs.size());
    }

    @Test
    void cancelStopsFutureRunsAndIsIdempotent() {
        FakeClock clock = new FakeClock();
        TickScheduler scheduler = new TickScheduler(clock);
        List<String> runs = new ArrayList<>();
        TickScheduler.Handle handle = scheduler.scheduleEvery(50L, () -> runs.add("x"));

        clock.advance(50);
        scheduler.runDueTasks();
        assertEquals(1, runs.size());

        handle.cancel();
        handle.cancel();
        assertTrue(handle.isCancelled());
        clock.advance(500);
        scheduler.runDueTasks();
        assertEquals(1, runs.size(), "cancelled task must not run again");
    }

    @Test
    void aTaskCanCancelItselfFromInsideItsBody() {
        // 生产代码大量使用这个形状：等待条件满足后在自己的任务体里注销（等世界加载、
        // 等密码、等实体出现）。自取消必须生效，否则会一直空转。
        FakeClock clock = new FakeClock();
        TickScheduler scheduler = new TickScheduler(clock);
        final TickScheduler.Handle[] self = new TickScheduler.Handle[1];
        int[] runs = { 0 };
        self[0] = scheduler.scheduleEvery(100L, () -> {
            runs[0]++;
            self[0].cancel();
        });

        clock.advance(100);
        scheduler.runDueTasks();
        clock.advance(500);
        scheduler.runDueTasks();
        assertEquals(1, runs[0]);
    }

    @Test
    void throwingTaskIsRetiredInsteadOfRepeatingEveryTick() {
        FakeClock clock = new FakeClock();
        TickScheduler scheduler = new TickScheduler(clock);
        int[] runs = { 0 };
        TickScheduler.Handle handle = scheduler.scheduleEvery(10L, () -> {
            runs[0]++;
            throw new IllegalStateException("boom");
        });

        clock.advance(10);
        scheduler.runDueTasks();
        assertTrue(handle.isCancelled());
        clock.advance(1000);
        scheduler.runDueTasks();
        assertEquals(1, runs[0], "a failing timer must not be re-fired every interval");
    }

    @Test
    void clearDropsEveryTask() {
        // 会话结束（断线/退世界）时必须能整体清空，不能留下继续跑旧会话逻辑的计时器。
        FakeClock clock = new FakeClock();
        TickScheduler scheduler = new TickScheduler(clock);
        List<String> runs = new ArrayList<>();
        scheduler.scheduleEvery(10L, () -> runs.add("a"));
        scheduler.scheduleEvery(20L, () -> runs.add("b"));
        assertEquals(2, scheduler.pendingCount());

        scheduler.clear();
        clock.advance(10_000);
        scheduler.runDueTasks();
        assertTrue(runs.isEmpty());
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    void independentTasksKeepTheirOwnIntervals() {
        FakeClock clock = new FakeClock();
        TickScheduler scheduler = new TickScheduler(clock);
        int[] fast = { 0 };
        int[] slow = { 0 };
        scheduler.scheduleEvery(50L, () -> fast[0]++);
        scheduler.scheduleEvery(200L, () -> slow[0]++);

        for (int i = 0; i < 4; i++) {
            clock.advance(50);
            scheduler.runDueTasks();
        }
        assertEquals(4, fast[0]);
        assertEquals(1, slow[0]);
    }
}
