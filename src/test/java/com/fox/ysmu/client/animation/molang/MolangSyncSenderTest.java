package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.network.message.C2SMolangSync;

/**
 * {@code ysm.sync(...)} 的客户端发起端参数处理（YSM-wiki: molang/script「主动同步」）。
 *
 * <p>wiki：参数只支持数值、最多 16 个，且"一次同步开销相当大，不要频繁发起"，所以除了取整/
 * 截断，还必须有每秒一次的限流 —— 否则模型作者把它写进每帧脚本就能把服务器打爆。</p>
 */
class MolangSyncSenderTest {

    @BeforeEach
    void resetThrottle() {
        MolangSyncSender.resetThrottle();
    }

    @Test
    void scriptArgumentsAreRoundedToInts() {
        assertArrayEquals(new int[] { 12, 0, -3 },
            MolangSyncSender.toIntArray(Arrays.asList(12.7d, 0.0d, -3.9d)));
    }

    @Test
    void nullAndEmptyArgumentsProduceNoArguments() {
        assertArrayEquals(new int[0], MolangSyncSender.toIntArray(null));
        assertArrayEquals(new int[0], MolangSyncSender.toIntArray(Collections.emptyList()));
    }

    /** 超过 16 个参数只取前 16 个（wiki 的硬限制）。 */
    @Test
    void moreThanSixteenArgumentsAreTruncated() {
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            values.add((double) i);
        }

        int[] converted = MolangSyncSender.toIntArray(values);

        assertEquals(C2SMolangSync.MAX_ARGUMENTS, converted.length);
        assertEquals(0, converted[0]);
        assertEquals(15, converted[C2SMolangSync.MAX_ARGUMENTS - 1]);
    }

    @Test
    void throttleAllowsOnePerSecond() {
        assertTrue(MolangSyncSender.allow(50));
        assertFalse(MolangSyncSender.allow(50), "同一秒第二次应被限流");
        assertTrue(MolangSyncSender.allow(51), "跨秒后应重新放行");
    }
}
