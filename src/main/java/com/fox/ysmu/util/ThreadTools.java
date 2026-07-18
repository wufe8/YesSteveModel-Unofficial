package com.fox.ysmu.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ThreadTools {

    /**
     * Thread pool for background model processing.
     *
     * Uses an unbounded {@link LinkedBlockingQueue} so that tasks are never
     * rejected and submitting threads (typically Netty I/O threads) are
     * never blocked doing model work.  maxPoolSize is effectively unused
     * with an unbounded queue — the real parallelism is {@code corePoolSize}.
     *
     * To control parallelism, adjust the {@code ThreadCount} config option
     * ({@code ysm_sync/ThreadCount} in {@code ysmu.cfg}).
     */
    @SuppressWarnings("all")
    public static final ExecutorService THREAD_POOL = new ThreadPoolExecutor(
        Math.max(1, com.fox.ysmu.Config.THREAD_COUNT),
        // maxPoolSize is decorative with an unbounded LinkedBlockingQueue,
        // but kept at a reasonable upper bound as a safety net.
        Math.max(Math.max(1, com.fox.ysmu.Config.THREAD_COUNT) * 2, 16),
        30,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<>());
}
