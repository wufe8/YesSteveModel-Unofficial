package com.fox.ysmu.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ThreadTools {

    /**
     * Thread pool for background model processing.
     * corePoolSize = 0 was a bug — with an unbounded LinkedBlockingQueue,
     * the pool never creates more than 1 thread, making all callers
     * effectively sequential.  Use Config.THREAD_COUNT as core so up to
     * that many models are processed in parallel during reload.
     */
    @SuppressWarnings("all")
    public static final ExecutorService THREAD_POOL = new ThreadPoolExecutor(
        Math.max(1, com.fox.ysmu.Config.THREAD_COUNT),
        10,
        30,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue());
}
