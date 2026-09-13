package com.fox.ysmu.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@code ysm.on_ground_time} 的逐箭计时。
 *
 * <p>wiki: molang/ref（ysm 弹射物）—— 单位是**刻**，箭矢落地后累计、被移动则重置为 0，
 * 落地 1200 刻后消失。渲染器是按帧调用的，所以"一个实体刻只加一次"必须由这里保证，
 * 否则 200 FPS 下同 1 秒会数出 200 刻而不是 20 刻。</p>
 */
class ProjectileGroundTrackerTest {

    @Test
    void notInGroundAlwaysReportsZero() {
        ProjectileGroundTracker tracker = new ProjectileGroundTracker();

        assertEquals(0, tracker.update(7, false, 10));
        assertEquals(0, tracker.update(7, false, 11));
    }

    @Test
    void countsEntityTicksWhileInGround() {
        ProjectileGroundTracker tracker = new ProjectileGroundTracker();

        // 第一次观察到"在地里"就算第 1 刻；
        assertEquals(1, tracker.update(7, true, 100));
        assertEquals(1, tracker.update(7, true, 100), "同一刻重复渲染不重复计数");
        assertEquals(2, tracker.update(7, true, 101));
        assertEquals(3, tracker.update(7, true, 102));
    }

    /** 同一实体刻内的多次渲染调用（高帧率）不能重复计数。 */
    @Test
    void repeatedFramesWithinOneTickCountOnce() {
        ProjectileGroundTracker tracker = new ProjectileGroundTracker();

        assertEquals(1, tracker.update(7, true, 100));
        for (int frame = 0; frame < 15; frame++) {
            assertEquals(1, tracker.update(7, true, 100), "同一 tick 的第 " + frame + " 次渲染");
        }
        assertEquals(2, tracker.update(7, true, 101));
    }

    /** wiki：被移动则重置为 0 —— 回到空中后重新落地应从 1 重新开始。 */
    @Test
    void leavingTheGroundResetsTheCounter() {
        ProjectileGroundTracker tracker = new ProjectileGroundTracker();

        assertEquals(1, tracker.update(7, true, 100));
        assertEquals(2, tracker.update(7, true, 101));
        assertEquals(0, tracker.update(7, false, 102));
        assertEquals(1, tracker.update(7, true, 103));
    }

    @Test
    void arrowsAreTrackedIndependently() {
        ProjectileGroundTracker tracker = new ProjectileGroundTracker();

        assertEquals(1, tracker.update(1, true, 50));
        assertEquals(2, tracker.update(1, true, 51));
        assertEquals(1, tracker.update(2, true, 51));
        assertEquals(3, tracker.update(1, true, 52));
        assertEquals(2, tracker.update(2, true, 52));
    }

    @Test
    void forgettingAnArrowDropsItsState() {
        ProjectileGroundTracker tracker = new ProjectileGroundTracker();

        assertEquals(1, tracker.update(7, true, 100));
        assertEquals(1, tracker.tracked());
        tracker.forget(7);
        assertEquals(0, tracker.tracked());
        assertEquals(1, tracker.update(7, true, 100), "重新出现应从 1 开始");
    }
}
