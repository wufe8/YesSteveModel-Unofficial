//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package software.bernie.geckolib3.core.keyframe;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.eliotlash.mclib.math.IValue;

import software.bernie.geckolib3.core.ConstantValue;
import software.bernie.geckolib3.core.easing.EasingType;

public class KeyFrame<T> implements Serializable {

    private static final long serialVersionUID = 42L;
    /** Primitive inline of length, avoids boxing overhead. */
    private double primitiveLength;
    /** Primitive inline of startValue when it was a ConstantValue (~99% of cases). */
    private boolean startIsPrimitive;
    private double startPrimitiveValue;
    /** Fallback IValue reference for Molang-expression keyframes. Null when primitive. */
    private T startValue;
    /** Primitive inline of endValue when it was a ConstantValue (~99% of cases). */
    private boolean endIsPrimitive;
    private double endPrimitiveValue;
    /** Fallback IValue reference for Molang-expression keyframes. Null when primitive. */
    private T endValue;
    public EasingType easingType;
    public List<Double> easingArgs;

    private static final List<Double> EMPTY_EASING_ARGS = Collections.emptyList();

    private void initPrimitives(T sv, T ev) {
        if (sv instanceof ConstantValue) {
            this.startIsPrimitive = true;
            this.startPrimitiveValue = ((ConstantValue) sv).get();
            this.startValue = null; // allow GC of ConstantValue
        } else {
            this.startIsPrimitive = false;
            this.startValue = sv;
        }
        if (ev instanceof ConstantValue) {
            this.endIsPrimitive = true;
            this.endPrimitiveValue = ((ConstantValue) ev).get();
            this.endValue = null; // allow GC of ConstantValue
        } else {
            this.endIsPrimitive = false;
            this.endValue = ev;
        }
    }

    public KeyFrame(Double length, T startValue, T endValue) {
        this.primitiveLength = length;
        this.easingType = EasingType.Linear;
        this.easingArgs = EMPTY_EASING_ARGS;
        initPrimitives(startValue, endValue);
    }

    public KeyFrame(Double length, T startValue, T endValue, EasingType easingType) {
        this.primitiveLength = length;
        this.easingType = EasingType.Linear;
        this.easingArgs = EMPTY_EASING_ARGS;
        initPrimitives(startValue, endValue);
        this.easingType = easingType;
    }

    public KeyFrame(Double length, T startValue, T endValue, EasingType easingType, List<Double> easingArgs) {
        this.primitiveLength = length;
        this.easingType = EasingType.Linear;
        initPrimitives(startValue, endValue);
        this.easingType = easingType;
        this.easingArgs = easingArgs;
    }

    // ── Optimised accessors (avoid IValue.get() virtual dispatch) ────────

    /** Returns the start value as a primitive double, avoiding IValue boxing. */
    public double getStartValueDouble() {
        return startIsPrimitive ? startPrimitiveValue : ((IValue) startValue).get();
    }

    /** Returns the end value as a primitive double, avoiding IValue boxing. */
    public double getEndValueDouble() {
        return endIsPrimitive ? endPrimitiveValue : ((IValue) endValue).get();
    }

    /** Whether the start value was inlined from a ConstantValue (pre-converted for rotation). */
    public boolean isStartPrimitive() {
        return startIsPrimitive;
    }

    /** Whether the end value was inlined from a ConstantValue (pre-converted for rotation). */
    public boolean isEndPrimitive() {
        return endIsPrimitive;
    }

    /** Returns the length as a primitive double. */
    public double getLengthPrimitive() {
        return primitiveLength;
    }

    // ── Legacy accessors ────────────────────────────────────────────────
    // 这两个取值器在"内联成 primitive"时不再返回 null：优化前它们返回的就是那个
    // ConstantValue，null 是内联优化引入的可观察行为回归 —— 调用方对端点做数值读取
    // 会直接 NPE。这里按需重建一个等值 ConstantValue，恢复优化前的契约。
    // 渲染热路径走的是 getStartValueDouble()/getEndValueDouble()，不受影响。

    public Double getLength() {
        return this.primitiveLength;
    }

    public void setLength(Double length) {
        this.primitiveLength = length;
    }

    @SuppressWarnings("unchecked")
    public T getStartValue() {
        return startIsPrimitive ? (T) new ConstantValue(startPrimitiveValue) : this.startValue;
    }

    /**
     * 只替换起点，**不动终点**。
     *
     * <p>原实现是 {@code initPrimitives(startValue, this.endValue)}：而 endValue 在终点被内联
     * 成 primitive 时正是 null（绝大多数关键帧），于是一次 setStartValue 会把终点改成
     * "非 primitive 且引用为 null" —— 之后 getEndValueDouble() 直接 NPE，getEndValue() 也变成
     * null。setEndValue 对称地破坏起点。改成各改各的一半。</p>
     */
    public void setStartValue(T startValue) {
        if (startValue instanceof ConstantValue) {
            this.startIsPrimitive = true;
            this.startPrimitiveValue = ((ConstantValue) startValue).get();
            this.startValue = null;
        } else {
            this.startIsPrimitive = false;
            this.startValue = startValue;
        }
    }

    @SuppressWarnings("unchecked")
    public T getEndValue() {
        return endIsPrimitive ? (T) new ConstantValue(endPrimitiveValue) : this.endValue;
    }

    /** 只替换终点，不动起点（原因同 {@link #setStartValue}）。 */
    public void setEndValue(T endValue) {
        if (endValue instanceof ConstantValue) {
            this.endIsPrimitive = true;
            this.endPrimitiveValue = ((ConstantValue) endValue).get();
            this.endValue = null;
        } else {
            this.endIsPrimitive = false;
            this.endValue = endValue;
        }
    }

    /**
     * 相等性/哈希按**有效值**定义：内联成 primitive 的常量端点用数值参与，Molang 表达式端点
     * 用引用本身参与（IValue 没有值语义）。
     *
     * <p>原实现哈希的是 {@code startValue}/{@code endValue} 这两个**回退引用**：常量端点被内联后
     * 它们是 null，于是"起点 0 → 终点 5"和"起点 5 → 终点 0"这两个不同关键帧哈希相同；而
     * {@code equals} 又只比哈希，于是它们被判成相等。这里先修正参与运算的字段，
     * 再把 equals 从"哈希相等"改成逐字段比较（哈希相等不等于相等）。</p>
     *
     * <p>刻意不把 easingType/easingArgs 纳入：保持原契约里"相等 = 长与两端点相等"的口径，
     * 不扩大相等关系的含义。</p>
     */
    public int hashCode() {
        int result = Double.hashCode(this.primitiveLength);
        result = 31 * result + endpointHash(startIsPrimitive, startPrimitiveValue, startValue);
        result = 31 * result + endpointHash(endIsPrimitive, endPrimitiveValue, endValue);
        return result;
    }

    private static int endpointHash(boolean primitive, double value, Object fallback) {
        return primitive ? Double.hashCode(value) : Objects.hashCode(fallback);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof KeyFrame)) {
            return false;
        }
        KeyFrame<?> other = (KeyFrame<?>) obj;
        return Double.compare(this.primitiveLength, other.primitiveLength) == 0
            && endpointEquals(startIsPrimitive, startPrimitiveValue, startValue,
                other.startIsPrimitive, other.startPrimitiveValue, other.startValue)
            && endpointEquals(endIsPrimitive, endPrimitiveValue, endValue,
                other.endIsPrimitive, other.endPrimitiveValue, other.endValue);
    }

    private static boolean endpointEquals(boolean primitiveA, double valueA, Object fallbackA, boolean primitiveB,
        double valueB, Object fallbackB) {
        if (primitiveA != primitiveB) {
            // 一边是内联常量、一边是表达式对象：只有"表达式其实就是该常量"才可能相等，
            // 无法在不求值（有副作用/时机依赖）的前提下判定，故判为不等。
            return false;
        }
        return primitiveA ? Double.compare(valueA, valueB) == 0 : fallbackA == fallbackB;
    }
}
