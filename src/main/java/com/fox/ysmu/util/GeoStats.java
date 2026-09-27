package com.fox.ysmu.util;

import com.fox.ysmu.Config;
import com.fox.ysmu.ysmu;

/**
 * 几何提交与动画 tick 的调用次数诊断（常驻，但默认关闭）。
 *
 * <p>spark 的采样器只记时间、不记次数，"每个模型一次烘焙到底发了多少个立方体/顶点、
 * 刷了几次 Tessellator、tick 了几次动画"从采样里推不出来。排查几何提交要不要走批量写入
 * （省掉每顶点的分支与游标维护）时需要这些数，所以这里自己数：
 * <ul>
 *   <li>{@code cube} / {@code vert} —— 每个 cube 与它发出的顶点数（{@code quads × 4}）；</li>
 *   <li>{@code flush} —— 我们自己调 {@code Tessellator.draw()} 的次数（每次 flush 在
 *       Angelica 下都是一次流式缓冲提交 + VAO 绑定）；</li>
 *   <li>{@code animTick} —— {@code AnimationProcessor.tickAnimation} 次数。</li>
 * </ul>
 *
 * <p>按 AGENTS.md 的诊断约定保留：**只在 {@code Config.DEBUG_MODEL_LOAD + DEBUG_MODEL_RENDER}
 * 同时打开时计数与打点**（这两个开关的既有语义就是"子域需要主开关"，见 Config 里的说明），
 * 且每 5 秒最多一行 INFO（一次 {@code System.currentTimeMillis()} + 比较，其余都是自增）。
 * 开关关闭时每个 note 方法只做一次静态字段读取 + 提前返回，F3 明细读到的是最后一次窗口的值。
 */
public final class GeoStats {

    private static final long WINDOW_MS = 5000L;

    private static long windowStart;
    private static long cubes;
    private static long vertices;
    private static long flushes;
    private static long animTicks;
    private static long poseChecks;
    private static long poseSkips;

    private static int cubesPerSec = -1;
    private static int vertsPerSec = -1;
    private static int flushesPerSec = -1;
    private static int animTicksPerSec = -1;

    private GeoStats() {}

    /** 只在调试开关打开时计数；关闭时所有 note 都是"读一个静态字段 + 返回"。 */
    private static boolean enabled() {
        return Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_RENDER;
    }

    /** 一个 cube 被提交（{@code vertexCount} = 它发出的顶点数）。 */
    public static void noteCube(int vertexCount) {
        if (!enabled()) {
            return;
        }
        cubes++;
        vertices += vertexCount;
    }

    /** 我们自己调了一次 {@code Tessellator.draw()}。 */
    public static void noteFlush() {
        if (!enabled()) {
            return;
        }
        flushes++;
    }

    /** 一次姿态检查（缩略图烘焙时算签名的次数）。 */
    public static void notePoseCheck() {
        if (!enabled()) {
            return;
        }
        poseChecks++;
    }

    /** 姿态签名与上次相同 → 跳过了几何提交。 */
    public static void notePoseSkip() {
        if (!enabled()) {
            return;
        }
        poseSkips++;
    }

    /** 一次 {@code AnimationProcessor.tickAnimation}。 */
    public static void noteAnimTick() {
        if (!enabled()) {
            return;
        }
        animTicks++;
    }

    /** 每帧调一次；满 5 秒才真正算一次平均并打日志。 */
    public static void publishIfDue() {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (windowStart == 0L) {
            windowStart = now;
            return;
        }
        long elapsed = now - windowStart;
        if (elapsed < WINDOW_MS) {
            return;
        }
        double seconds = elapsed / 1000.0d;
        cubesPerSec = (int) (cubes / seconds);
        vertsPerSec = (int) (vertices / seconds);
        flushesPerSec = (int) (flushes / seconds);
        animTicksPerSec = (int) (animTicks / seconds);
        ysmu.LOG.info(
            "[YSMU-GEO-PROBE] {}s: cube={}/s vert={}/s flush={}/s animTick={}/s poseSkip={}/{} "
                + "(totals cube={} vert={} flush={} tick={})",
            String.format(java.util.Locale.ROOT, "%.1f", seconds), cubesPerSec, vertsPerSec, flushesPerSec,
            animTicksPerSec, poseSkips, poseChecks, cubes, vertices, flushes, animTicks);
        windowStart = now;
        cubes = 0L;
        vertices = 0L;
        flushes = 0L;
        animTicks = 0L;
        poseChecks = 0L;
        poseSkips = 0L;
    }

    public static int cubesPerSec() {
        return cubesPerSec;
    }

    public static int vertsPerSec() {
        return vertsPerSec;
    }

    public static int flushesPerSec() {
        return flushesPerSec;
    }

    public static int animTicksPerSec() {
        return animTicksPerSec;
    }
}
