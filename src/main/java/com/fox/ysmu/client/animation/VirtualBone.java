package com.fox.ysmu.client.animation;

import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;

/**
 * A lightweight IBone implementation for virtual Molang bones that exist in
 * animation keyframes but not in the model geometry.  These bones are used
 * solely for their Molang expression side effects (e.g. variable assignments
 * like {@code v.idle_time = q.anim_time;}) and should not affect rendering.
 * <p>
 * Instances are created lazily by {@code AnimationController} when a bone
 * animation references a bone name not found in the model's renderer list.
 */
public class VirtualBone implements IBone {

    private final String name;
    private float rotationX, rotationY, rotationZ;
    private float positionX, positionY, positionZ;
    private float scaleX = 1.0f, scaleY = 1.0f, scaleZ = 1.0f;
    private float pivotX, pivotY, pivotZ;
    private BoneSnapshot initialSnapshot;

    public VirtualBone(String name) {
        this.name = name;
    }

    @Override
    public String getName() { return name; }

    @Override
    public float getRotationX() { return rotationX; }
    @Override
    public float getRotationY() { return rotationY; }
    @Override
    public float getRotationZ() { return rotationZ; }
    @Override
    public float getPositionX() { return positionX; }
    @Override
    public float getPositionY() { return positionY; }
    @Override
    public float getPositionZ() { return positionZ; }
    @Override
    public float getScaleX() { return scaleX; }
    @Override
    public float getScaleY() { return scaleY; }
    @Override
    public float getScaleZ() { return scaleZ; }
    @Override
    public float getPivotX() { return pivotX; }
    @Override
    public float getPivotY() { return pivotY; }
    @Override
    public float getPivotZ() { return pivotZ; }

    @Override
    public void setRotationX(float value) { this.rotationX = value; }
    @Override
    public void setRotationY(float value) { this.rotationY = value; }
    @Override
    public void setRotationZ(float value) { this.rotationZ = value; }
    @Override
    public void setPositionX(float value) { this.positionX = value; }
    @Override
    public void setPositionY(float value) { this.positionY = value; }
    @Override
    public void setPositionZ(float value) { this.positionZ = value; }
    @Override
    public void setScaleX(float value) { this.scaleX = value; }
    @Override
    public void setScaleY(float value) { this.scaleY = value; }
    @Override
    public void setScaleZ(float value) { this.scaleZ = value; }
    @Override
    public void setPivotX(float value) { this.pivotX = value; }
    @Override
    public void setPivotY(float value) { this.pivotY = value; }
    @Override
    public void setPivotZ(float value) { this.pivotZ = value; }

    @Override
    public boolean isHidden() { return true; }
    @Override
    public boolean cubesAreHidden() { return true; }
    @Override
    public boolean childBonesAreHiddenToo() { return true; }
    @Override
    public void setHidden(boolean hidden) { /* virtual bones are always hidden */ }
    @Override
    public void setCubesHidden(boolean hidden) { /* no cubes to hide */ }
    @Override
    public void setHidden(boolean selfHidden, boolean skipChildRendering) { /* virtual bones are always hidden */ }

    @Override
    public void setModelRendererName(String modelRendererName) { /* not needed */ }

    @Override
    public void saveInitialSnapshot() {
        this.initialSnapshot = new BoneSnapshot(this);
    }

    @Override
    public BoneSnapshot getInitialSnapshot() {
        if (initialSnapshot == null) {
            initialSnapshot = new BoneSnapshot(this);
        }
        return initialSnapshot;
    }
}
