package software.bernie.geckolib3.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.vecmath.Matrix3f;
import javax.vecmath.Matrix4f;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;
import software.bernie.geckolib3.geo.raw.tree.RawGeometryTree;
import software.bernie.geckolib3.geo.render.GeoBuilder;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * {@link MatrixStack#beginCube(GeoCube)} 必须与旧的三步序列
 * {@code push() → moveToPivot(cube) → rotate(cube) → moveBackFromPivot(cube)} 得到同一个矩阵。
 *
 * <p>这是几何提交优化里唯一"改了数学实现"的地方（其余是去掉冗余调用），而且它同时去掉了
 * cube 级的 push/pop，所以值得用数值对照锁住。两种实现的乘法结合顺序不同（合成后是一次
 * 局部矩阵乘法），浮点末位可能不同，所以用 1e-5 容差比较。
 */
class MatrixStackCubeTransformTest {

    private static final String GEO_JSON = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},"
        + "\"bones\":[{\"name\":\"b\",\"pivot\":[0,0,0],"
        + "\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";

    private static GeoCube cube(float px, float py, float pz, float rx, float ry, float rz) throws Exception {
        RawGeoModel raw = Converter.fromJsonString(GEO_JSON);
        GeoModel model = GeoBuilder.getGeoBuilder("ysmu")
            .constructGeoModel(RawGeometryTree.parseHierarchy(raw));
        GeoCube cube = model.topLevelBones.get(0).childCubes.get(0);
        cube.pivot.set(px, py, pz);
        cube.rotation.set(rx, ry, rz);
        return cube;
    }

    /** 给栈顶一个非平凡的骨骼级变换（平移+三轴旋转+非均匀缩放），否则测试太弱。 */
    private static MatrixStack stackWithBoneTransform() {
        MatrixStack stack = new MatrixStack();
        stack.translate(1.5f, -2.0f, 0.25f);
        stack.rotateZ(0.3f);
        stack.rotateY(-0.7f);
        stack.rotateX(1.1f);
        stack.scale(1.2f, 0.8f, 2.0f);
        return stack;
    }

    private static void assertModelEquals(Matrix4f expected, Matrix4f actual, String what) {
        assertEquals(expected.m00, actual.m00, 1.0e-5f, what + ".m00");
        assertEquals(expected.m01, actual.m01, 1.0e-5f, what + ".m01");
        assertEquals(expected.m02, actual.m02, 1.0e-5f, what + ".m02");
        assertEquals(expected.m03, actual.m03, 1.0e-5f, what + ".m03");
        assertEquals(expected.m10, actual.m10, 1.0e-5f, what + ".m10");
        assertEquals(expected.m11, actual.m11, 1.0e-5f, what + ".m11");
        assertEquals(expected.m12, actual.m12, 1.0e-5f, what + ".m12");
        assertEquals(expected.m13, actual.m13, 1.0e-5f, what + ".m13");
        assertEquals(expected.m20, actual.m20, 1.0e-5f, what + ".m20");
        assertEquals(expected.m21, actual.m21, 1.0e-5f, what + ".m21");
        assertEquals(expected.m22, actual.m22, 1.0e-5f, what + ".m22");
        assertEquals(expected.m23, actual.m23, 1.0e-5f, what + ".m23");
    }

    private static void assertNormalEquals(Matrix3f expected, Matrix3f actual, String what) {
        assertEquals(expected.m00, actual.m00, 1.0e-5f, what + ".m00");
        assertEquals(expected.m01, actual.m01, 1.0e-5f, what + ".m01");
        assertEquals(expected.m02, actual.m02, 1.0e-5f, what + ".m02");
        assertEquals(expected.m10, actual.m10, 1.0e-5f, what + ".m10");
        assertEquals(expected.m11, actual.m11, 1.0e-5f, what + ".m11");
        assertEquals(expected.m12, actual.m12, 1.0e-5f, what + ".m12");
        assertEquals(expected.m20, actual.m20, 1.0e-5f, what + ".m20");
        assertEquals(expected.m21, actual.m21, 1.0e-5f, what + ".m21");
        assertEquals(expected.m22, actual.m22, 1.0e-5f, what + ".m22");
    }

    /** 旧的三步序列（现在只有测试在用，用来当参考实现）。 */
    private static void legacyCubeTransform(MatrixStack stack, GeoCube cube) {
        stack.push();
        stack.moveToPivot(cube);
        stack.rotate(cube);
        stack.moveBackFromPivot(cube);
    }

    private void compare(GeoCube cube, String label) {
        MatrixStack legacy = stackWithBoneTransform();
        legacyCubeTransform(legacy, cube);
        Matrix4f expectedModel = new Matrix4f(legacy.getModelMatrix());
        Matrix3f expectedNormal = new Matrix3f(legacy.getNormalMatrix());

        MatrixStack fused = stackWithBoneTransform();
        fused.beginCube(cube);
        Matrix4f actualModel = new Matrix4f(fused.getCubeModelMatrix());
        Matrix3f actualNormal = new Matrix3f(fused.getCubeNormalMatrix());

        assertModelEquals(expectedModel, actualModel, label + " model");
        assertNormalEquals(expectedNormal, actualNormal, label + " normal");
    }

    @Test
    void noRotationIsTheCommonCase() throws Exception {
        compare(cube(0f, 24f, 0f, 0f, 0f, 0f), "零旋转/非零轴心");
        compare(cube(0f, 0f, 0f, 0f, 0f, 0f), "零旋转/零轴心");
    }

    @Test
    void singleAxisRotations() throws Exception {
        compare(cube(1f, 2f, 3f, 0.5f, 0f, 0f), "仅 X");
        compare(cube(-4f, 0.5f, 2f, 0f, -0.9f, 0f), "仅 Y");
        compare(cube(0f, -1f, 5f, 0f, 0f, 1.3f), "仅 Z");
    }

    @Test
    void allThreeAxesAtOnce() throws Exception {
        compare(cube(3f, -7f, 11f, 0.4f, -1.2f, 2.2f), "三轴");
    }

    /** beginCube 不改栈：调用前后栈顶必须一致（这是去掉 push/pop 的依据）。 */
    @Test
    void beginCubeLeavesTheStackUntouched() throws Exception {
        MatrixStack stack = stackWithBoneTransform();
        Matrix4f before = new Matrix4f(stack.getModelMatrix());
        Matrix3f beforeNormal = new Matrix3f(stack.getNormalMatrix());

        stack.beginCube(cube(2f, 3f, 4f, 0.6f, 0.7f, 0.8f));
        stack.beginCube(cube(0f, 0f, 0f, 0f, 0f, 0f));

        assertModelEquals(before, stack.getModelMatrix(), "栈顶");
        assertNormalEquals(beforeNormal, stack.getNormalMatrix(), "栈顶法线");
    }
}
