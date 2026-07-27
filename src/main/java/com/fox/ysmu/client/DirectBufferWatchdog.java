package com.fox.ysmu.client;

import java.lang.management.ManagementFactory;

import javax.management.ObjectName;

import com.fox.ysmu.Config;
import com.fox.ysmu.ysmu;

/**
 * Safety-net watchdog for Direct Buffer memory.
 *
 * Some third-party mods (e.g. Distant Horizons, Angelica) may leak DirectByteBuffers
 * under Java 25+ZGC, because ZGC does not process PhantomReference Cleaners
 * aggressively. This watchdog periodically checks the Direct Buffer pool via JMX MBeans
 * and triggers a GC when usage exceeds the configured threshold, prompting the JVM to
 * run Cleaners and free unreachable native memory.
 *
 * This is NOT a fix for the underlying leak — it is a last-resort safety net so that
 * users can keep playing without hitting OOM or OS-level memory pressure.
 *
 * Enabled by default. Disable via config: {@code B:EnableDirectBufferWatchdog=false}
 * Threshold: {@code I:DirectBufferWatchdogThreshold=1024} (MB)
 */
public class DirectBufferWatchdog {

    private static final ObjectName DIRECT_POOL;
    private static int tickCounter = 0;

    static {
        ObjectName pool = null;
        try {
            pool = new ObjectName("java.nio:type=BufferPool,name=direct");
        } catch (Exception e) {
            // Should never happen with a valid ObjectName string
        }
        DIRECT_POOL = pool;
    }

    /**
     * Called periodically from the client tick loop (e.g. every ~100 ticks).
     * Returns immediately if the watchdog is disabled or the MBean is unavailable.
     */
    public static void tick() {
        if (!Config.ENABLE_DIRECT_BUFFER_WATCHDOG) return;
        if (DIRECT_POOL == null) return;

        // Check at ~5s intervals (every 100 ticks)
        if (++tickCounter < 100) return;
        tickCounter = 0;

        try {
            long used = ((Number) ManagementFactory.getPlatformMBeanServer()
                .getAttribute(DIRECT_POOL, "MemoryUsed")).longValue();
            long thresholdBytes = Config.DIRECT_BUFFER_WATCHDOG_THRESHOLD_MB * 1024L * 1024L;

            if (used > thresholdBytes) {
                long usedMB = used / (1024L * 1024L);
                ysmu.LOG.warn("[YSM-WATCHDOG] DirectBuffer usage {} MB exceeds threshold {} MB, triggering GC",
                    usedMB, Config.DIRECT_BUFFER_WATCHDOG_THRESHOLD_MB);
                System.gc();
            }
        } catch (Exception e) {
            // MBean not available (shouldn't happen on standard JDK), silently ignore
        }
    }
}
