package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * 头部旋转查询的轴向绑定。
 *
 * <p>YSM-wiki: molang/ref 明写「{@code ysm.head_yaw} **与 query.head_x_rotation 相同**、
 * {@code ysm.head_pitch} **与 query.head_y_rotation 相同**」，参考实现的 QueryBinding 同为
 * {@code head_x_rotation -> netHeadYaw}、{@code head_y_rotation -> headPitch}。
 * 即头部这一对是 <b>x = 左右视角(yaw)、y = 上下视角(pitch)</b>，与 {@code query.eye_target_*} /
 * {@code body_*}（x = pitch、y = yaw）刻意不同。</p>
 *
 * <p>回归点：两者一度被绑反，模型用 {@code query.head_x_rotation} 做的左右摆动（头发/披风的
 * 侧向甩动）拿到的是俯仰角 —— 平视时看不出来，抬头到顶/低头到底时头发被塞进 ±90 的左右旋转而
 * 过度翻转卷曲。</p>
 */
class AnimationRegisterHeadQueryTest {

    private static MolangParser parserWithHeadVariables() {
        MolangParser parser = new MolangParser();
        parser.register(new LazyVariable("query.head_x_rotation", 0));
        parser.register(new LazyVariable("query.head_y_rotation", 0));
        parser.register(new LazyVariable("ysm.head_yaw", 0));
        parser.register(new LazyVariable("ysm.head_pitch", 0));
        return parser;
    }

    private static double eval(MolangParser parser, String expression) {
        try {
            return parser.parseExpression(expression)
                .get();
        } catch (Exception e) {
            throw new AssertionError("求值失败: " + expression, e);
        }
    }

    /** 两个值刻意取成不相等的数：任何 x/y 再互换都会让断言失败。 */
    @Test
    void headXRotationIsTheYawAndHeadYIsThePitch() {
        MolangParser parser = parserWithHeadVariables();
        AnimationRegister.bindHeadRotationQueries(parser, () -> 37.5d, () -> -81.25d);

        assertEquals(37.5d, eval(parser, "q.head_x_rotation"), 1.0e-9, "head_x_rotation 必须是左右视角(yaw)");
        assertEquals(-81.25d, eval(parser, "q.head_y_rotation"), 1.0e-9, "head_y_rotation 必须是上下视角(pitch)");
        assertEquals(37.5d, eval(parser, "query.head_x_rotation"), 1.0e-9);
        assertEquals(-81.25d, eval(parser, "query.head_y_rotation"), 1.0e-9);
    }

    /** wiki 的等价关系：query.head_?_rotation 与对应的 ysm.head_* 永远同值。 */
    @Test
    void headQueriesEqualTheirYsmCounterparts() {
        MolangParser parser = parserWithHeadVariables();
        AnimationRegister.bindHeadRotationQueries(parser, () -> -12.0d, () -> 64.0d);

        assertEquals(eval(parser, "ysm.head_yaw"), eval(parser, "q.head_x_rotation"), 1.0e-9);
        assertEquals(eval(parser, "ysm.head_pitch"), eval(parser, "q.head_y_rotation"), 1.0e-9);
    }

    /** 两边确实读同一对 supplier：supplier 变化后两个名字一起变。 */
    @Test
    void headQueriesShareTheirSuppliers() {
        MolangParser parser = parserWithHeadVariables();
        double[] yaw = { 10.0d };
        double[] pitch = { 20.0d };
        AnimationRegister.bindHeadRotationQueries(parser, () -> yaw[0], () -> pitch[0]);

        assertEquals(10.0d, eval(parser, "q.head_x_rotation"), 1.0e-9);
        yaw[0] = 55.0d;
        pitch[0] = -40.0d;
        assertEquals(55.0d, eval(parser, "q.head_x_rotation"), 1.0e-9);
        assertEquals(-40.0d, eval(parser, "q.head_y_rotation"), 1.0e-9);
    }
}
