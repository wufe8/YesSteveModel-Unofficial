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
    private Double length;
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
        this.length = length;
        this.easingType = EasingType.Linear;
        this.easingArgs = EMPTY_EASING_ARGS;
        initPrimitives(startValue, endValue);
    }

    public KeyFrame(Double length, T startValue, T endValue, EasingType easingType) {
        this.primitiveLength = length;
        this.length = length;
        this.easingType = EasingType.Linear;
        this.easingArgs = EMPTY_EASING_ARGS;
        initPrimitives(startValue, endValue);
        this.easingType = easingType;
    }

    public KeyFrame(Double length, T startValue, T endValue, EasingType easingType, List<Double> easingArgs) {
        this.primitiveLength = length;
        this.length = length;
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

    // ── Legacy accessors (kept for binary compat; may return null when inlined) ──

    public Double getLength() {
        return this.length;
    }

    public void setLength(Double length) {
        this.primitiveLength = length;
        this.length = length;
    }

    public T getStartValue() {
        return this.startValue;
    }

    public void setStartValue(T startValue) {
        initPrimitives(startValue, this.endValue);
    }

    public T getEndValue() {
        return this.endValue;
    }

    public void setEndValue(T endValue) {
        initPrimitives(this.startValue, endValue);
    }

    public int hashCode() {
        return Objects.hash(new Object[] { this.length, this.startValue, this.endValue });
    }

    public boolean equals(Object obj) {
        return obj instanceof KeyFrame && this.hashCode() == obj.hashCode();
    }
}
