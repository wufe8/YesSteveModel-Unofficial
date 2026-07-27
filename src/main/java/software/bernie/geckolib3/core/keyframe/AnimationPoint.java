/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package software.bernie.geckolib3.core.keyframe;

import java.util.ArrayList;

import com.eliotlash.mclib.math.IValue;

public class AnimationPoint {

    /**
     * The current tick in the animation to lerp from
     */
    public Double currentTick;
    /**
     * The tick that the current animation should end at
     */
    public Double animationEndTick;
    /**
     * The Animation start value.
     */
    public Double animationStartValue;
    /**
     * The Animation end value.
     */
    public Double animationEndValue;

    /**
     * The current keyframe.
     */
    public KeyFrame<IValue> keyframe;

    public AnimationPoint() {}

    public AnimationPoint(KeyFrame<IValue> keyframe, Double currentTick, Double animationEndTick,
        Double animationStartValue, Double animationEndValue) {
        this.keyframe = keyframe;
        this.currentTick = currentTick;
        this.animationEndTick = animationEndTick;
        this.animationStartValue = animationStartValue;
        this.animationEndValue = animationEndValue;
    }

    public AnimationPoint(KeyFrame<IValue> keyframe, double tick, double animationEndTick, float animationStartValue,
        double animationEndValue) {
        this.keyframe = keyframe;
        this.currentTick = tick;
        this.animationEndTick = animationEndTick;
        this.animationStartValue = Double.valueOf(animationStartValue);
        this.animationEndValue = animationEndValue;
    }

    // ---- Object pooling ----

    private static final int POOL_MAX_SIZE = 2048;
    private static final ArrayList<AnimationPoint> POOL = new ArrayList<>(POOL_MAX_SIZE);

    /** Obtain an AnimationPoint from the pool, or create new if pool is empty. */
    public static AnimationPoint obtain(KeyFrame<IValue> keyframe, double currentTick,
        double animationEndTick, double animationStartValue, double animationEndValue) {
        AnimationPoint p;
        synchronized (POOL) {
            if (!POOL.isEmpty()) {
                p = POOL.remove(POOL.size() - 1);
            } else {
                p = new AnimationPoint();
            }
        }
        p.keyframe = keyframe;
        p.currentTick = currentTick;
        p.animationEndTick = animationEndTick;
        p.animationStartValue = animationStartValue;
        p.animationEndValue = animationEndValue;
        return p;
    }

    /** Return this AnimationPoint to the pool for reuse. */
    public void recycle() {
        synchronized (POOL) {
            if (POOL.size() < POOL_MAX_SIZE) {
                this.keyframe = null;
                this.currentTick = null;
                this.animationEndTick = null;
                this.animationStartValue = null;
                this.animationEndValue = null;
                POOL.add(this);
            }
        }
    }

    @Override
    public String toString() {
        return "Tick: " + currentTick
            + " | End Tick: "
            + animationEndTick
            + " | Start Value: "
            + animationStartValue
            + " | End Value: "
            + animationEndValue;
    }
}
