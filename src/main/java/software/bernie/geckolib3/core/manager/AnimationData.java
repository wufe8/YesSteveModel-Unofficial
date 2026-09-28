/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package software.bernie.geckolib3.core.manager;

import java.util.HashMap;
import java.util.LinkedHashMap;

import org.apache.commons.lang3.tuple.Pair;

import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;

public class AnimationData {

    // Weak membership lets resource eviction find consumers without owning their entities.
    private static final java.util.Set<AnimationData> LIVE = java.util.Collections.newSetFromMap(
        new java.util.WeakHashMap<AnimationData, Boolean>());
    private net.minecraft.util.ResourceLocation animationFile;
    private HashMap<String, Pair<IBone, BoneSnapshot>> boneSnapshotCollection;
    private HashMap<String, AnimationController> animationControllers = new LinkedHashMap<>();
    public double tick;
    public boolean isFirstTick = true;
    private double resetTickLength = 1;
    public Double startTick;
    public Object ticker;
    public boolean shouldPlayWhilePaused = false;

    /**
     * Instantiates a new Animation controller collection.
     */
    public AnimationData() {
        super();
        boneSnapshotCollection = new HashMap<>();
        synchronized (LIVE) {
            LIVE.add(this);
        }
    }

    /**
     * This method is how you register animation controllers, without this, your
     * AnimationPredicate method will never be called
     *
     * @param value The value
     * @return the animation controller
     */
    public AnimationController addAnimationController(AnimationController value) {
        return this.animationControllers.put(value.getName(), value);
    }

    public HashMap<String, Pair<IBone, BoneSnapshot>> getBoneSnapshotCollection() {
        return boneSnapshotCollection;
    }

    public void setBoneSnapshotCollection(HashMap<String, Pair<IBone, BoneSnapshot>> boneSnapshotCollection) {
        this.boneSnapshotCollection = boneSnapshotCollection;
    }

    public void clearSnapshotCache() {
        this.boneSnapshotCollection = new HashMap<>();
    }

    public void bindAnimationFile(net.minecraft.util.ResourceLocation location) {
        animationFile = location;
    }

    /** Geometry eviction keeps playback clocks, but releases every bone binding to that geometry. */
    public void releaseGeometry(java.util.Set<IBone> bones, java.util.Map<String, IBone> index) {
        boneSnapshotCollection.entrySet().removeIf(entry -> bones.contains(entry.getValue().getLeft()));
        for (AnimationController controller : animationControllers.values()) {
            controller.releaseBoneReferences(index);
        }
    }

    private void releaseAnimationResources() {
        boneSnapshotCollection.clear();
        for (AnimationController controller : animationControllers.values()) {
            controller.releaseAnimationResources();
        }
    }

    public static void releaseAnimationFile(net.minecraft.util.ResourceLocation location) {
        synchronized (LIVE) {
            for (AnimationData data : LIVE) {
                if (location.equals(data.animationFile)) data.releaseAnimationResources();
            }
        }
    }

    /** Used at cache/session teardown, distinct from geometry eviction which preserves the clock. */
    public void dispose() {
        if (ticker instanceof software.bernie.geckolib3.animation.AnimationTicker) {
            ((software.bernie.geckolib3.animation.AnimationTicker) ticker).stop();
        }
        ticker = null;
        releaseAnimationResources();
    }

    public static void disposeAll() {
        synchronized (LIVE) {
            for (AnimationData data : LIVE) data.dispose();
        }
    }

    public double getResetSpeed() {
        return resetTickLength;
    }

    /**
     * This is how long it takes for any bones that don't have an animation to
     * revert back to their original position
     *
     * @param resetTickLength The amount of ticks it takes to reset. Cannot be
     *                        negative.
     */
    public void setResetSpeedInTicks(double resetTickLength) {
        this.resetTickLength = resetTickLength < 0 ? 0 : resetTickLength;
    }

    public HashMap<String, AnimationController> getAnimationControllers() {
        return animationControllers;
    }
}
