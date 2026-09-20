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
 *
 * YSMU: Added transformBone() combined matrix method and pre-allocated
 * tempTransform/tempNormalTransform fields to eliminate per-bone Matrix4f
 * allocations in the GeckoLib rendering hot path.
 */
public class MatrixStack {

    private static final int MAX_DEPTH = 1024;
    private final Matrix4f[] modelStack = new Matrix4f[MAX_DEPTH];
    private final Matrix3f[] normalStack = new Matrix3f[MAX_DEPTH];
    private int depth = 0;

    private Matrix4f tempModelMatrix = new Matrix4f();
    private Matrix3f tempNormalMatrix = new Matrix3f();
    // YSMU perf: cube 专用矩阵（见 beginCube）——没有自身旋转时直接引用栈顶，不拷贝。
    private final Matrix4f cubeModel = new Matrix4f();
    private final Matrix3f cubeNormal = new Matrix3f();
    private Matrix4f cubeModelRef = cubeModel;
    private Matrix3f cubeNormalRef = cubeNormal;
    // YSMU perf: Pre-allocated temp matrices — avoids 6× new Matrix4f() + 4× new Matrix3f()
    // per bone in transformBone() and rotate(GeoCube).
    private final Matrix4f tempTransform = new Matrix4f();
    /** Reusable temp normal matrix for per-bone normal transforms. */
    private final Matrix3f tempNormalTransform = new Matrix3f();
    @SuppressWarnings("unused")
    private float[] tempArray = new float[16];

    /** 骨骼渲染变换回调（YSMU 粒子/特效 bone_pivot_abs 追踪用，与几何渲染同一路径）。
     *  null 时零开销（渲染热路径仅一次空判断）。
     *  <p>传入骨链累计后的完整矩阵 {@code mat}（blocks，含模型缩放，预 yaw/预玩家位移），
     *  以及该骨骼的枢轴点（blocks）。回调必须同步复制所需数据（Matrix4f 会被后续变换复用）。 */
    public interface BoneTransformSink {
        void onBoneTransform(GeoBone bone, Matrix4f mat, float pivotX, float pivotY, float pivotZ);
    }

    /** 全局回调，由 MolangPhysicsRuntime 注册。 */
    public static BoneTransformSink boneTransformSink = null;

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
     * YSMU perf: Combines bone translate → pivot → rotate → scale →
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

        // 2-7: Build T(pos) × T(pivot) × Rz × Ry × Rx × S × T(-pivot) using
        // a single pre-allocated tempTransform instead of 6 separate Matrix4f allocations.

        // 2. T(pivot)
        this.tempTransform.setIdentity();
        this.tempTransform.m03 = px;
        this.tempTransform.m13 = py;
        this.tempTransform.m23 = pz;
        this.tempModelMatrix.mul(this.tempTransform);

        // 3. Rz
        if (rz != 0f) {
            this.tempTransform.rotZ(rz);
            this.tempModelMatrix.mul(this.tempTransform);
        }

        // 4. Ry
        if (ry != 0f) {
            this.tempTransform.rotY(ry);
            this.tempModelMatrix.mul(this.tempTransform);
        }

        // 5. Rx
        if (rx != 0f) {
            this.tempTransform.rotX(rx);
            this.tempModelMatrix.mul(this.tempTransform);
        }

        // 6. Scale
        this.tempTransform.setIdentity();
        this.tempTransform.m00 = sx;
        this.tempTransform.m11 = sy;
        this.tempTransform.m22 = sz;
        this.tempModelMatrix.mul(this.tempTransform);

        // 7. T(-pivot) — rightmost
        this.tempTransform.setIdentity();
        this.tempTransform.m03 = -px;
        this.tempTransform.m13 = -py;
        this.tempTransform.m23 = -pz;
        this.tempModelMatrix.mul(this.tempTransform);

        // Apply combined matrix to stack top
        modelStack[depth - 1].mul(this.tempModelMatrix);

        // YSMU: 可选骨骼变换追踪——与几何渲染同一路径，供 bone_pivot_abs 读取。
        // 传入骨链累计后的完整矩阵（含本骨骼自身 T·R·S·T 的影响），bone_pivot_abs
        // 用它计算枢轴点的世界位置（M×pivot），避免骨骼自身缩放/旋转污染平移列。
        BoneTransformSink sink = boneTransformSink;
        if (sink != null) {
            sink.onBoneTransform(bone, modelStack[depth - 1], px, py, pz);
        }

        // Normal matrix: rotation + sign-flip for negative scales
        this.tempNormalMatrix.setIdentity();
        if (rz != 0f) {
            this.tempNormalTransform.rotZ(rz);
            this.tempNormalMatrix.mul(this.tempNormalTransform);
        }
        if (ry != 0f) {
            this.tempNormalTransform.rotY(ry);
            this.tempNormalMatrix.mul(this.tempNormalTransform);
        }
        if (rx != 0f) {
            this.tempNormalTransform.rotX(rx);
            this.tempNormalMatrix.mul(this.tempNormalTransform);
        }
        if (sx < 0 || sy < 0 || sz < 0) {
            this.tempNormalTransform.setIdentity();
            this.tempNormalTransform.m00 = sx < 0 ? -1 : 1;
            this.tempNormalTransform.m11 = sy < 0 ? -1 : 1;
            this.tempNormalTransform.m22 = sz < 0 ? -1 : 1;
            this.tempNormalMatrix.mul(this.tempNormalTransform);
        }
        normalStack[depth - 1].mul(this.tempNormalMatrix);
    }

    /**
     * YSMU perf: 算出这个 cube 的最终矩阵（栈顶 × 它的局部变换 {@code T(pivot)·Rz·Ry·Rx·T(-pivot)}），
     * **不改动栈**，随后用 {@link #getCubeModelMatrix()}/{@link #getCubeNormalMatrix()} 取。
     *
     * <p>为什么不让 cube 入栈：旧实现是 {@code push() → moveToPivot → rotate → moveBackFromPivot → pop()}
     * —— 每个 cube 一次 16+9 个浮点拷贝的入栈、两次多余的平移乘法、三次旋转乘法（外加法线侧
     * 六次），而 cube 的数量通常比骨骼多一到两个数量级，一次烘焙要发几百到几千个。合成之后：
     * <ul>
     *   <li>自身旋转为 0 的 cube（绝大多数）→ 局部变换恰好是单位矩阵，**直接引用栈顶，零拷贝零乘法**；</li>
     *   <li>有旋转的 cube → 1 次 4x4 乘法 + 1 次 3x3 乘法，拷进专用的 cube 矩阵。</li>
     * </ul>
     *
     * <p>对调用方语义不变：改前"push + renderCube + pop"得到的最终矩阵，与这里算出的
     * {@code 栈顶 × 局部} 完全相同；而 renderCube 不再改栈，所以外层的 push/pop 变成可省的
     * 冗余（{@code IGeoRenderer.renderBoneCubes} 已去掉）。
     */
    public void beginCube(GeoCube cube) {
        float rx = cube.rotation.x;
        float ry = cube.rotation.y;
        float rz = cube.rotation.z;
        if (rx == 0f && ry == 0f && rz == 0f) {
            // T(p) × T(-p) = I：栈顶就是它。
            this.cubeModelRef = modelStack[depth - 1];
            this.cubeNormalRef = normalStack[depth - 1];
            return;
        }
        float px = cube.pivot.x / 16f;
        float py = cube.pivot.y / 16f;
        float pz = cube.pivot.z / 16f;

        this.tempModelMatrix.setIdentity();
        this.tempModelMatrix.m03 = px;
        this.tempModelMatrix.m13 = py;
        this.tempModelMatrix.m23 = pz;
        this.tempTransform.rotZ(rz);
        this.tempModelMatrix.mul(this.tempTransform);
        this.tempTransform.rotY(ry);
        this.tempModelMatrix.mul(this.tempTransform);
        this.tempTransform.rotX(rx);
        this.tempModelMatrix.mul(this.tempTransform);
        this.tempTransform.setIdentity();
        this.tempTransform.m03 = -px;
        this.tempTransform.m13 = -py;
        this.tempTransform.m23 = -pz;
        this.tempModelMatrix.mul(this.tempTransform);

        this.cubeModel.set(modelStack[depth - 1]);
        this.cubeModel.mul(this.tempModelMatrix);
        this.cubeModelRef = this.cubeModel;

        this.tempNormalMatrix.setIdentity();
        this.tempNormalTransform.rotZ(rz);
        this.tempNormalMatrix.mul(this.tempNormalTransform);
        this.tempNormalTransform.rotY(ry);
        this.tempNormalMatrix.mul(this.tempNormalTransform);
        this.tempNormalTransform.rotX(rx);
        this.tempNormalMatrix.mul(this.tempNormalTransform);

        this.cubeNormal.set(normalStack[depth - 1]);
        this.cubeNormal.mul(this.tempNormalMatrix);
        this.cubeNormalRef = this.cubeNormal;
    }

    /** 当前 cube 的最终模型矩阵（{@link #beginCube} 之后有效）。只读。 */
    public Matrix4f getCubeModelMatrix() {
        return cubeModelRef;
    }

    /** 当前 cube 的最终法线矩阵（{@link #beginCube} 之后有效）。只读。 */
    public Matrix3f getCubeNormalMatrix() {
        return cubeNormalRef;
    }

    public void rotate(GeoCube bone) {
        Vector3f rotation = bone.rotation;

        this.tempModelMatrix.setIdentity();
        this.tempTransform.rotZ(rotation.z);
        this.tempModelMatrix.mul(this.tempTransform);

        this.tempTransform.rotY(rotation.y);
        this.tempModelMatrix.mul(this.tempTransform);

        this.tempTransform.rotX(rotation.x);
        this.tempModelMatrix.mul(this.tempTransform);

        this.tempNormalMatrix.setIdentity();
        this.tempNormalTransform.rotZ(rotation.z);
        this.tempNormalMatrix.mul(this.tempNormalTransform);

        this.tempNormalTransform.rotY(rotation.y);
        this.tempNormalMatrix.mul(this.tempNormalTransform);

        this.tempNormalTransform.rotX(rotation.x);
        this.tempNormalMatrix.mul(this.tempNormalTransform);

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
