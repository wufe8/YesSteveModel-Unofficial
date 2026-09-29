package software.bernie.geckolib3.core.keyframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.ConstantValue;

/**
 * {@link KeyFrame} 的端点访问器与相等性契约。
 *
 * <p>两个独立缺陷（都由"primitive 内联"优化引入，独立探针可复现）：</p>
 * <ol>
 *   <li>{@code setStartValue} 原来调用 {@code initPrimitives(newStart, this.endValue)}：终点被内联
 *       成 primitive 时 {@code endValue} 恰好是 null，于是"只改起点"顺手把终点改成
 *       "非 primitive 且引用为 null" —— 之后对终点做数值读取直接 NPE。{@code setEndValue} 对称。</li>
 *   <li>{@code hashCode} 哈希的是 {@code startValue}/{@code endValue} 这两个**回退引用**，内联后
 *       它们是 null，于是"0→5"和"5→0"哈希相同；{@code equals} 又只比哈希，把它们判成相等。</li>
 * </ol>
 *
 * <p>断言的是**可观察值**，不依赖 T 的具体类型：这里用 {@link ConstantValue} 覆盖内联路径
 * （绝大多数关键帧），并且刻意只通过公开 API 取值。</p>
 */
class KeyFrameEndpointTest {

    private static KeyFrame<ConstantValue> frame(double start, double end) {
        return new KeyFrame<>(1.0d, new ConstantValue(start), new ConstantValue(end));
    }

    @Test
    void constructorsInlineBothEndpoints() {
        KeyFrame<ConstantValue> kf = frame(0, 5);
        assertTrue(kf.isStartPrimitive());
        assertTrue(kf.isEndPrimitive());
        assertEquals(0.0d, kf.getStartValueDouble());
        assertEquals(5.0d, kf.getEndValueDouble());
    }

    @Test
    void settingStartValueKeepsTheEndReadable() {
        KeyFrame<ConstantValue> kf = frame(0, 5);
        kf.setStartValue(new ConstantValue(2));
        // 回归点：终点没有被这次 setter 破坏。
        assertEquals(5.0d, kf.getEndValueDouble(), "untouched end value must stay readable");
        assertNotNull(kf.getEndValue(), "legacy getter must not turn null for an inlined endpoint");
        assertEquals(2.0d, kf.getStartValueDouble());
    }

    @Test
    void settingEndValueKeepsTheStartReadable() {
        KeyFrame<ConstantValue> kf = frame(0, 5);
        kf.setEndValue(new ConstantValue(7));
        assertEquals(0.0d, kf.getStartValueDouble(), "untouched start value must stay readable");
        assertNotNull(kf.getStartValue(), "legacy getter must not turn null for an inlined endpoint");
        assertEquals(7.0d, kf.getEndValueDouble());
    }

    @Test
    void legacyGettersReturnValuesForInlinedEndpoints() {
        // 优化前这两个 getter 返回的就是那个 ConstantValue；返回 null 是可观察行为回归。
        KeyFrame<ConstantValue> kf = frame(1.5d, -2.25d);
        ConstantValue start = kf.getStartValue();
        ConstantValue end = kf.getEndValue();
        assertNotNull(start);
        assertNotNull(end);
        assertEquals(1.5d, start.get());
        assertEquals(-2.25d, end.get());
    }

    @Test
    void differentConstantEndpointsAreNotEqual() {
        KeyFrame<ConstantValue> up = frame(0, 5);
        KeyFrame<ConstantValue> down = frame(5, 0);
        assertNotEquals(up, down, "swapped constant endpoints must not compare equal");
        assertNotEquals(up.hashCode(), down.hashCode(), "constants must take part in hashCode");
        assertFalse(up.equals(down));
    }

    @Test
    void identicalConstantKeyFramesAreEqualAndHashAlike() {
        assertEquals(frame(0, 5), frame(0, 5));
        assertEquals(frame(0, 5).hashCode(), frame(0, 5).hashCode());
    }

    @Test
    void lengthTakesPartInEquality() {
        KeyFrame<ConstantValue> a = new KeyFrame<>(1.0d, new ConstantValue(0), new ConstantValue(5));
        KeyFrame<ConstantValue> b = new KeyFrame<>(2.0d, new ConstantValue(0), new ConstantValue(5));
        assertNotEquals(a, b);
        assertEquals(1.0d, a.getLengthPrimitive());
        assertEquals(1.0d, a.getLength());
    }

    @Test
    void expressionEndpointsCompareByIdentityNotByEvaluating() {
        // Molang 表达式端点没有值语义：相等只按引用，且不得为了比较去求值。
        KeyFrame<Object> a = new KeyFrame<>(1.0d, constantExpr(3), constantExpr(4));
        KeyFrame<Object> b = new KeyFrame<>(1.0d, constantExpr(3), constantExpr(4));
        assertNotEquals(a, b, "distinct expression objects are not the same endpoint");
        KeyFrame<Object> c = a;
        assertEquals(a, c);
    }

    private static Object constantExpr(final double v) {
        // 非 ConstantValue 的 IValue：走"引用回退"分支。
        return new com.eliotlash.mclib.math.IValue() {

            @Override
            public double get() {
                return v;
            }
        };
    }
}
