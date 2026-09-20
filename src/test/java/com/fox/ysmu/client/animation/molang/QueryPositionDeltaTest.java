package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.eliotlash.mclib.math.IValue;
import com.fox.ysmu.client.animation.AnimationRegister;

import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * {@code query.position_delta(axis)} 的取值契约。
 *
 * <p>回归的是一条"写入口是惰性的"的 bug：位置增量来自
 * {@link QueryPositionDeltaFunction} 的静态槽，而槽原来只在
 * {@code parser.setValue("query.position_delta", …)} 这个 {@code LazyVariable} 的 supplier 里写 ——
 * 模型只用**函数版** {@code q.position_delta(0)} 时（例如把位移增量累加成轮胎转速），
 * 变量版永远没人读，槽里恒为 0，函数版跟着恒为 0：<b>位移存在但车轮不转</b>。
 * 现在槽由 {@code AnimationRegister.setEntityQueryValues} 每帧主动写。</p>
 *
 * <p>这里钉住的是"函数版确实读那份槽、槽确实由 {@link QueryPositionDeltaFunction#update} 决定"；
 * "每帧真的调用了 update"在 {@code AnimationRegister} 里，需要真实 {@code EntityPlayer}，
 * 只能在实机验证。</p>
 */
class QueryPositionDeltaTest {

    @BeforeAll
    static void installHooks() {
        // query.position_delta 由宿主 mod 通过 ysmFunctionRegistrar 注入，测试里手动装一次。
        AnimationRegister.registerMolangHooks();
    }

    @AfterEach
    void resetSlots() {
        QueryPositionDeltaFunction.clear();
    }

    @Test
    void exposedDeltaReadsTheFrameWrittenSlot() {
        QueryPositionDeltaFunction.update(1.5d, -0.25d, 3.0d);
        assertEquals(1.5d, QueryPositionDeltaFunction.delta(0), 1e-9);
        assertEquals(-0.25d, QueryPositionDeltaFunction.delta(1), 1e-9);
        assertEquals(3.0d, QueryPositionDeltaFunction.delta(2), 1e-9);
        // 未知轴与未写入时都是 0，不能抛异常。
        assertEquals(0.0d, QueryPositionDeltaFunction.delta(3), 1e-9);
        QueryPositionDeltaFunction.clear();
        assertEquals(0.0d, QueryPositionDeltaFunction.delta(0), 1e-9);
        assertEquals(0.0d, QueryPositionDeltaFunction.delta(2), 1e-9);
    }

    @Test
    void molangFunctionFormReadsTheSameSlot() {
        // 走真正的 mclib 解析 + 求值，确认注册名与轴参数都对得上。
        IValue axis0 = parse("query.position_delta(0)");
        IValue axis2 = parse("query.position_delta(2)");
        assertEquals(0.0d, axis0.get(), 1e-9);
        assertEquals(0.0d, axis2.get(), 1e-9);

        QueryPositionDeltaFunction.update(0.4d, 0.0d, -0.7d);
        assertEquals(0.4d, axis0.get(), 1e-9);
        assertEquals(-0.7d, axis2.get(), 1e-9);
    }

    private static IValue parse(String expression) {
        return assertDoesNotThrow(() -> new MolangParser().parseExpression(expression), expression);
    }
}
