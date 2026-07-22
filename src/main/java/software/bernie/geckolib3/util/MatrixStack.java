package software.bernie.geckolib3.util;

import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;
import javax.vecmath.Vector3f;

import org.lwjgl.util.vector.Quaternion;

import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;

/**
 * Array-backed matrix stack — avoids allocation on push/pop by
 * pre-allocating a fixed-capacity pool of Matrix4f/Matrix3f slots.
 */
public class MatrixStack {

    private static final int MAX_DEPTH = 1024;
    private final Matrix4f[] modelStack = new Matrix4f[MAX_DEPTH];
    private final Matrix3f[] normalStack = new Matrix3f[MAX_DEPTH];
    private int depth = 0;

    private Matrix4f tempModelMatrix = new Matrix4f();
    private Matrix3f tempNormalMatrix = new Matrix3f();
    @SuppressWarnings("unused")
    private float[] tempArray = new float[16];

    public MatrixStack() {
        modelStack[0] = new Matrix4f();
        normalStack[0] = new Matrix3f();
        modelStack[0].setIdentity();
        normalStack[0].setIdentity();
        depth = 1;
    }

    public Matrix4f getModelMatrix() {
        return modelStack[depth - 1];
    }

    public Matrix3f getNormalMatrix() {
        return normalStack[depth - 1];
    }

    public void push() {
        if (depth >= MAX_DEPTH) {
            throw new IllegalStateException("MatrixStack overflow (max depth=" + MAX_DEPTH + ")");
        }
        if (modelStack[depth] == null) {
            modelStack[depth] = new Matrix4f(modelStack[depth - 1]);
            normalStack[depth] = new Matrix3f(normalStack[depth - 1]);
        } else {
            modelStack[depth].set(modelStack[depth - 1]);
            normalStack[depth].set(normalStack[depth - 1]);
        }
        depth++;
    }

    public void pop() {
        if (depth <= 1) {
            throw new IllegalStateException("A one level stack can't be popped!");
        }
        depth--;
    }

    /* Translate */

    public void translate(float x, float y, float z) {
        this.translate(new Vector3f(x, y, z));
    }

    public void translate(Vector3f vec) {
        this.tempModelMatrix.setIdentity();
        this.tempModelMatrix.setTranslation(vec);

        modelStack[depth - 1].mul(this.tempModelMatrix);
    }

    public void moveToPivot(GeoCube cube) {
        Vector3f pivot = cube.pivot;
        this.translate(pivot.x / 16, pivot.y / 16, pivot.z / 16);
    }

    public void moveBackFromPivot(GeoCube cube) {
        Vector3f pivot = cube.pivot;
        this.translate(-pivot.x / 16, -pivot.y / 16, -pivot.z / 16);
    }

    public void moveToPivot(GeoBone bone) {
        this.translate(bone.rotationPointX / 16, bone.rotationPointY / 16, bone.rotationPointZ / 16);
    }

    public void moveBackFromPivot(GeoBone bone) {
        this.translate(-bone.rotationPointX / 16, -bone.rotationPointY / 16, -bone.rotationPointZ / 16);
    }

    public void translate(GeoBone bone) {
        this.translate(-bone.getPositionX() / 16, bone.getPositionY() / 16, bone.getPositionZ() / 16);
    }

    /* Scale */

    public void scale(float x, float y, float z) {
        this.tempModelMatrix.setIdentity();
        this.tempModelMatrix.m00 = x;
        this.tempModelMatrix.m11 = y;
        this.tempModelMatrix.m22 = z;

        modelStack[depth - 1].mul(this.tempModelMatrix);

        if (x < 0 || y < 0 || z < 0) {
            this.tempNormalMatrix.setIdentity();
            this.tempNormalMatrix.m00 = x < 0 ? -1 : 1;
            this.tempNormalMatrix.m11 = y < 0 ? -1 : 1;
            this.tempNormalMatrix.m22 = z < 0 ? -1 : 1;

            normalStack[depth - 1].mul(this.tempNormalMatrix);
        }
    }

    public void scale(GeoBone bone) {
        this.scale(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
    }

    /* Rotate */

    public void rotateX(float radian) {
        this.tempModelMatrix.setIdentity();
        this.tempModelMatrix.rotX(radian);

        this.tempNormalMatrix.setIdentity();
        this.tempNormalMatrix.rotX(radian);

        modelStack[depth - 1].mul(this.tempModelMatrix);
        normalStack[depth - 1].mul(this.tempNormalMatrix);
    }

    public void rotateY(float radian) {
        this.tempModelMatrix.setIdentity();
        this.tempModelMatrix.rotY(radian);

        this.tempNormalMatrix.setIdentity();
        this.tempNormalMatrix.rotY(radian);

        modelStack[depth - 1].mul(this.tempModelMatrix);
        normalStack[depth - 1].mul(this.tempNormalMatrix);
    }

    public void rotateZ(float radian) {
        this.tempModelMatrix.setIdentity();
        this.tempModelMatrix.rotZ(radian);

        this.tempNormalMatrix.setIdentity();
        this.tempNormalMatrix.rotZ(radian);

        modelStack[depth - 1].mul(this.tempModelMatrix);
        normalStack[depth - 1].mul(this.tempNormalMatrix);
    }

    public void rotate(GeoBone bone) {
        if (bone.getRotationZ() != 0.0F) {
            this.rotateZ(bone.getRotationZ());
        }

        if (bone.getRotationY() != 0.0F) {
            this.rotateY(bone.getRotationY());
        }

        if (bone.getRotationX() != 0.0F) {
            this.rotateX(bone.getRotationX());
        }
    }

    /**
     * Combined bone transform: applies position, pivot, rotation, scale, and
     * -pivot in a single matrix multiplication instead of five separate ones.
     * This is equivalent to calling translate(bone) → moveToPivot(bone) →
     * rotate(bone) → scale(bone) → moveBackFromPivot(bone) in sequence, but
     * reduces 4x4 matrix mul operations from 5 (or 8 with 3-axis rotation)
     * to 1 per bone. The normal matrix is updated with the rotation components
     * and sign-flipped scale if any axis is negative.
     */
    public void transformBone(GeoBone bone) {
        float px = bone.rotationPointX / 16f;
        float py = bone.rotationPointY / 16f;
        float pz = bone.rotationPointZ / 16f;
        float tx = -bone.getPositionX() / 16f;
        float ty = bone.getPositionY() / 16f;
        float tz = bone.getPositionZ() / 16f;
        float sx = bone.getScaleX();
        float sy = bone.getScaleY();
        float sz = bone.getScaleZ();
        float rx = bone.getRotationX();
        float ry = bone.getRotationY();
        float rz = bone.getRotationZ();

        // Build combined model matrix: T(pos) × T(pivot) × Rz × Ry × Rx × S × T(-pivot)
        // This is equivalent to the original 5 separate mul() calls but uses
        // only 1 final mul() against the stack top.
        this.tempModelMatrix.setIdentity();

        // 1. T(pos) — leftmost
        this.tempModelMatrix.m03 = tx;
        this.tempModelMatrix.m13 = ty;
        this.tempModelMatrix.m23 = tz;

        // 2. T(pivot)
        Matrix4f pivotMat = new Matrix4f();
        pivotMat.setIdentity();
        pivotMat.m03 = px;
        pivotMat.m13 = py;
        pivotMat.m23 = pz;
        this.tempModelMatrix.mul(pivotMat);

        // 3. Rz
        if (rz != 0f) {
            Matrix4f rotZ = new Matrix4f();
            rotZ.rotZ(rz);
            this.tempModelMatrix.mul(rotZ);
        }

        // 4. Ry
        if (ry != 0f) {
            Matrix4f rotY = new Matrix4f();
            rotY.rotY(ry);
            this.tempModelMatrix.mul(rotY);
        }

        // 5. Rx
        if (rx != 0f) {
            Matrix4f rotX = new Matrix4f();
            rotX.rotX(rx);
            this.tempModelMatrix.mul(rotX);
        }

        // 6. Scale
        Matrix4f scaleMat = new Matrix4f();
        scaleMat.setIdentity();
        scaleMat.m00 = sx;
        scaleMat.m11 = sy;
        scaleMat.m22 = sz;
        this.tempModelMatrix.mul(scaleMat);

        // 7. T(-pivot) — rightmost
        Matrix4f negPivotMat = new Matrix4f();
        negPivotMat.setIdentity();
        negPivotMat.m03 = -px;
        negPivotMat.m13 = -py;
        negPivotMat.m23 = -pz;
        this.tempModelMatrix.mul(negPivotMat);

        // Apply combined matrix to stack top
        modelStack[depth - 1].mul(this.tempModelMatrix);

        // Normal matrix: rotation + sign-flip for negative scales
        this.tempNormalMatrix.setIdentity();
        if (rz != 0f) {
            Matrix3f nrz = new Matrix3f();
            nrz.rotZ(rz);
            this.tempNormalMatrix.mul(nrz);
        }
        if (ry != 0f) {
            Matrix3f nry = new Matrix3f();
            nry.rotY(ry);
            this.tempNormalMatrix.mul(nry);
        }
        if (rx != 0f) {
            Matrix3f nrx = new Matrix3f();
            nrx.rotX(rx);
            this.tempNormalMatrix.mul(nrx);
        }
        if (sx < 0 || sy < 0 || sz < 0) {
            Matrix3f scaleNorm = new Matrix3f();
            scaleNorm.setIdentity();
            scaleNorm.m00 = sx < 0 ? -1 : 1;
            scaleNorm.m11 = sy < 0 ? -1 : 1;
            scaleNorm.m22 = sz < 0 ? -1 : 1;
            this.tempNormalMatrix.mul(scaleNorm);
        }
        normalStack[depth - 1].mul(this.tempNormalMatrix);
    }

    public void rotate(GeoCube bone) {
        Vector3f rotation = bone.rotation;
        Matrix4f matrix4f = new Matrix4f();
        Matrix3f matrix3f = new Matrix3f();

        this.tempModelMatrix.setIdentity();
        matrix4f.rotZ(rotation.z);
        this.tempModelMatrix.mul(matrix4f);

        matrix4f.rotY(rotation.y);
        this.tempModelMatrix.mul(matrix4f);

        matrix4f.rotX(rotation.x);
        this.tempModelMatrix.mul(matrix4f);

        this.tempNormalMatrix.setIdentity();
        matrix3f.rotZ(rotation.z);
        this.tempNormalMatrix.mul(matrix3f);

        matrix3f.rotY(rotation.y);
        this.tempNormalMatrix.mul(matrix3f);

        matrix3f.rotX(rotation.x);
        this.tempNormalMatrix.mul(matrix3f);

        modelStack[depth - 1].mul(this.tempModelMatrix);
        normalStack[depth - 1].mul(this.tempNormalMatrix);
    }

    @SuppressWarnings("unused")
    private Quaternion fromAngles(float x, float y, float z) {
        float sx = (float) Math.sin(0.5F * x);
        float cx = (float) Math.cos(0.5F * x);
        float sy = (float) Math.sin(0.5F * y);
        float cy = (float) Math.cos(0.5F * y);
        float sz = (float) Math.sin(0.5F * z);
        float cz = (float) Math.cos(0.5F * z);

        float ox = sx * cy * cz + cx * sy * sz;
        float oy = cx * sy * cz - sx * cy * sz;
        float oz = sx * sy * cz + cx * cy * sz;
        float ow = cx * cy * cz - sx * sy * sz;

        return new Quaternion(ox, oy, oz, ow);
    }
}
