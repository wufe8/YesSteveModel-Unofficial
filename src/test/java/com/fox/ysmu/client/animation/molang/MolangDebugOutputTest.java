package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code q.debug_output(...)} 的参数格式化与限流。
 *
 * <p>格式化是这里唯一有真实歧义的地方：字符串字面量在解析期被池化成整数，和真正的数字
 * 共用同一个 double 通道（{@code q.debug_output('level', 1)}）。测试同时锁住两件事：
 * 池化 id 从 {@link MolangStringPool#STRING_ID_BASE} 起编号（小整数不再撞车），
 * 以及数字的显示格式（整数不带 {@code .0}）。</p>
 */
class MolangDebugOutputTest {

    @BeforeEach
    void resetThrottle() {
        MolangDebugOutput.resetThrottle();
    }

    @Test
    void pooledStringIdIsRenderedAsText() {
        int id = MolangStringPool.intern("同步完成！");
        assertTrue(MolangStringPool.isStringId(id), "池 id 必须落在字符串区间");
        assertEquals("同步完成！", MolangDebugOutput.formatArg(id));
    }

    /** 关键回归：数字 1 不能被当成第一个池化的字符串。 */
    @Test
    void smallNumbersAreNotMistakenForStrings() {
        MolangStringPool.intern("level");
        assertEquals("1", MolangDebugOutput.formatArg(1.0d));
        assertEquals("0", MolangDebugOutput.formatArg(0.0d));
        assertEquals("42", MolangDebugOutput.formatArg(42.0d));
    }

    @Test
    void decimalsKeepFourPlaces() {
        assertEquals("1.5000", MolangDebugOutput.formatArg(1.5d));
        assertEquals("-0.2500", MolangDebugOutput.formatArg(-0.25d));
    }

    @Test
    void nonFiniteValuesArePrintedVerbatim() {
        assertEquals("NaN", MolangDebugOutput.formatArg(Double.NaN));
        assertEquals("Infinity", MolangDebugOutput.formatArg(Double.POSITIVE_INFINITY));
    }

    /** 令牌桶：同一秒内前 20 条通过，之后被丢弃；进入下一秒后重新放行。 */
    @Test
    void throttleAllowsTwentyPerSecond() {
        for (int i = 0; i < 20; i++) {
            assertTrue(MolangDebugOutput.allow(100), "第 " + (i + 1) + " 条应通过");
        }
        assertFalse(MolangDebugOutput.allow(100), "第 21 条应被限流");
        assertTrue(MolangDebugOutput.allow(101), "跨秒后应重新放行");
    }

    /** 空池 id（EMPTY_ID）不能是"字符串区间"，否则空串会污染判断。 */
    @Test
    void emptyIdIsNotAStringRangeId() {
        assertFalse(MolangStringPool.isStringId(MolangStringPool.EMPTY_ID));
    }
}
