package software.bernie.geckolib3.geo.render.built;

import java.io.Serializable;

import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;

import net.minecraft.util.EnumFacing;

import software.bernie.geckolib3.geo.raw.pojo.Cube;
import software.bernie.geckolib3.geo.raw.pojo.FaceUv;
import software.bernie.geckolib3.geo.raw.pojo.ModelProperties;
import software.bernie.geckolib3.geo.raw.pojo.PolyMesh;
import software.bernie.geckolib3.geo.raw.pojo.PolysEnum;
import software.bernie.geckolib3.geo.raw.pojo.UvFaces;
import software.bernie.geckolib3.geo.raw.pojo.UvUnion;
import software.bernie.geckolib3.util.VectorUtils;

public class GeoCube implements Serializable {

    private static final long serialVersionUID = 42L;
    public GeoQuad[] quads = new GeoQuad[6];
    public Vector3f pivot;
    public Vector3f rotation;
    public Vector3f size = new Vector3f();
    public double inflate;
    public Boolean mirror;
    public boolean mesh;

    private GeoCube(double[] size) {
        if (size.length >= 3) {
            this.size.set((float) size[0], (float) size[1], (float) size[2]);
        }
    }

    public static GeoCube createFromPolyMesh(PolyMesh mesh, ModelProperties properties) {
        GeoCube cube = new GeoCube(new double[] { 0, 0, 0 });
        cube.pivot = new Vector3f();
        cube.rotation = new Vector3f();
        cube.inflate = 0;
        cube.mirror = false;
        cube.mesh = true;

        if (mesh == null || mesh.getPositions() == null || mesh.getUvs() == null) {
            return cube;
        }
        if (mesh.getPolys() == null || mesh.getPolys().enumValue != PolysEnum.QUAD_LIST) {
            return cube;
        }

        double[] positions = mesh.getPositions();
        double[] uvs = mesh.getUvs();
        double[] normals = mesh.getNormals();
        int quadCount = Math.min(positions.length / 12, uvs.length / 8);
        GeoQuad[] quads = new GeoQuad[quadCount];
        float textureWidth = properties == null || properties.getTextureWidth() == null ? 64F
            : properties.getTextureWidth()
                .floatValue();
        float textureHeight = properties == null || properties.getTextureHeight() == null ? 64F
            : properties.getTextureHeight()
                .floatValue();
        boolean normalizedUvs = mesh.getNormalizedUvs() != null && mesh.getNormalizedUvs();

        for (int quadIndex = 0; quadIndex < quadCount; quadIndex++) {
            GeoVertex[] vertices = new GeoVertex[4];
            for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
                int posIndex = quadIndex * 12 + vertexIndex * 3;
                int uvIndex = quadIndex * 8 + vertexIndex * 2;
                float u = (float) uvs[uvIndex];
                float v = (float) uvs[uvIndex + 1];
                if (!normalizedUvs) {
                    u /= textureWidth;
                    v /= textureHeight;
                }
                vertices[vertexIndex] = new GeoVertex(
                    positions[posIndex],
                    positions[posIndex + 1],
                    positions[posIndex + 2]).setTextureUV(u, v);
            }
            quads[quadIndex] = new GeoQuad(vertices, getPolyMeshNormal(normals, quadIndex));
        }
        cube.quads = quads;
        return cube;
    }

    private static Vector3f getPolyMeshNormal(double[] normals, int quadIndex) {
        if (normals == null || normals.length < 3) {
            return new Vector3f(0, 1, 0);
        }
        int index = 0;
        if (normals.length >= (quadIndex + 1) * 12) {
            index = quadIndex * 12;
        } else if (normals.length >= (quadIndex + 1) * 3) {
            index = quadIndex * 3;
        }
        return new Vector3f((float) normals[index], (float) normals[index + 1], (float) normals[index + 2]);
    }

    public static GeoCube createFromPojoCube(Cube cubeIn, ModelProperties properties, Double boneInflate,
        Boolean mirror) {
        // Detect negative-size cubes (inside-out shell effect used by some models).
        // Must check raw size before any transformation.
        double[] rawSize = cubeIn.getSize();
        boolean hasNegSize = rawSize != null && rawSize.length >= 3
            && (rawSize[0] < 0 || rawSize[1] < 0 || rawSize[2] < 0);

        GeoCube cube = new GeoCube(cubeIn.getSize());

        UvUnion uvUnion = cubeIn.getUv();
        UvFaces faces = uvUnion.faceUV;
        boolean isBoxUV = uvUnion.isBoxUV;
        cube.mirror = cubeIn.getMirror();
        cube.inflate = cubeIn.getInflate() == null ? (boneInflate == null ? 0 : boneInflate) : cubeIn.getInflate() / 16;

        if (cube.inflate == 0 && (cube.size.x == 0 || cube.size.y == 0 || cube.size.z == 0)) {
            cube.inflate = 0.001;
        }

        float textureHeight = properties.getTextureHeight()
            .floatValue();
        float textureWidth = properties.getTextureWidth()
            .floatValue();

        // Use absolute size so vertex math works correctly for negative-size cubes.
        // Also adjust origin to the true minimum corner.
        double[] rawOrigin = cubeIn.getOrigin();
        double ox = rawOrigin != null && rawOrigin.length >= 1 ? rawOrigin[0] : 0;
        double oy = rawOrigin != null && rawOrigin.length >= 2 ? rawOrigin[1] : 0;
        double oz = rawOrigin != null && rawOrigin.length >= 3 ? rawOrigin[2] : 0;
        double sx = rawSize != null && rawSize.length >= 1 ? Math.abs(rawSize[0]) : 1;
        double sy = rawSize != null && rawSize.length >= 2 ? Math.abs(rawSize[1]) : 1;
        double sz = rawSize != null && rawSize.length >= 3 ? Math.abs(rawSize[2]) : 1;
        // If original size was negative in a dimension, shift origin to the true
        // minimum corner (the normalization that sanitizeGeometryJson would do).
        if (rawSize != null && rawSize.length >= 1 && rawSize[0] < 0) ox += rawSize[0]; // rawSize[0] is negative
        if (rawSize != null && rawSize.length >= 2 && rawSize[1] < 0) oy += rawSize[1];
        if (rawSize != null && rawSize.length >= 3 && rawSize[2] < 0) oz += rawSize[2];

        Vector3d size = new Vector3d(sx, sy, sz);
        Vector3d origin = new Vector3d(-(ox + sx) / 16, oy / 16, oz / 16);

        size.x *= 0.0625f;
        size.y *= 0.0625f;
        size.z *= 0.0625f;

        Vector3f rotation = VectorUtils.convertDoubleToFloat(VectorUtils.fromArray(cubeIn.getRotation()));
        rotation.x *= -1;
        rotation.y *= -1;

        rotation.x = ((float) Math.toRadians(rotation.x));
        rotation.y = ((float) Math.toRadians(rotation.y));
        rotation.z = ((float) Math.toRadians(rotation.z));

        Vector3f pivot = VectorUtils.convertDoubleToFloat(VectorUtils.fromArray(cubeIn.getPivot()));
        pivot.x *= -1;

        cube.pivot = pivot;
        cube.rotation = rotation;

        //
        //
        // P7 P8
        // - - - - - - - - - - - - -
        // | \ | \
        // | \ | \
        // | \ | \
        // | \ | \
        // Y | \ | \
        // | \ | \
        // | \ P3 | \ P4
        // | - - - - - - - - - - - - -
        // | | | |
        // | | | |
        // | | | |
        // P5 - - - - - - - - | - - - - P6 |
        // \ | \ |
        // \ | \ |
        // \ | \ |
        // X \ | \ |
        // \ | \ |
        // \ | \ |
        // \ | \ |
        // - - - - - - - - - - - - -
        // P1 P2
        // Z
        // this drawing corresponds to the points declared below
        //

        // Making all 8 points of the cube using the origin (where the Z, X, and Y
        // values are smallest) and offseting each point by the right size values
        GeoVertex P1 = new GeoVertex(origin.x - cube.inflate, origin.y - cube.inflate, origin.z - cube.inflate);
        GeoVertex P2 = new GeoVertex(
            origin.x - cube.inflate,
            origin.y - cube.inflate,
            origin.z + size.z + cube.inflate);
        GeoVertex P3 = new GeoVertex(
            origin.x - cube.inflate,
            origin.y + size.y + cube.inflate,
            origin.z - cube.inflate);
        GeoVertex P4 = new GeoVertex(
            origin.x - cube.inflate,
            origin.y + size.y + cube.inflate,
            origin.z + size.z + cube.inflate);
        GeoVertex P5 = new GeoVertex(
            origin.x + size.x + cube.inflate,
            origin.y - cube.inflate,
            origin.z - cube.inflate);
        GeoVertex P6 = new GeoVertex(
            origin.x + size.x + cube.inflate,
            origin.y - cube.inflate,
            origin.z + size.z + cube.inflate);
        GeoVertex P7 = new GeoVertex(
            origin.x + size.x + cube.inflate,
            origin.y + size.y + cube.inflate,
            origin.z - cube.inflate);
        GeoVertex P8 = new GeoVertex(
            origin.x + size.x + cube.inflate,
            origin.y + size.y + cube.inflate,
            origin.z + size.z + cube.inflate);

        GeoQuad quadWest;
        GeoQuad quadEast;
        GeoQuad quadNorth;
        GeoQuad quadSouth;
        GeoQuad quadUp;
        GeoQuad quadDown;

        if (!isBoxUV) {
            FaceUv west = faces.getWest();
            FaceUv east = faces.getEast();
            FaceUv north = faces.getNorth();
            FaceUv south = faces.getSouth();
            FaceUv up = faces.getUp();
            FaceUv down = faces.getDown();
            // Pass in vertices starting from the top right corner, then going
            // counter-clockwise
            quadWest = west == null ? null
                : new GeoQuad(
                    new GeoVertex[] { P4, P3, P1, P2 },
                    west.getUv(),
                    west.getUvSize(),
                    west.getRotationEnum()
                        .ordinal() * 90,
                    textureWidth,
                    textureHeight,
                    cubeIn.getMirror(),
                    EnumFacing.WEST);
            quadEast = east == null ? null
                : new GeoQuad(
                    new GeoVertex[] { P7, P8, P6, P5 },
                    east.getUv(),
                    east.getUvSize(),
                    east.getRotationEnum()
                        .ordinal() * 90,
                    textureWidth,
                    textureHeight,
                    cubeIn.getMirror(),
                    EnumFacing.EAST);
            quadNorth = north == null ? null
                : new GeoQuad(
                    new GeoVertex[] { P3, P7, P5, P1 },
                    north.getUv(),
                    north.getUvSize(),
                    north.getRotationEnum()
                        .ordinal() * 90,
                    textureWidth,
                    textureHeight,
                    cubeIn.getMirror(),
                    EnumFacing.NORTH);
            quadSouth = south == null ? null
                : new GeoQuad(
                    new GeoVertex[] { P8, P4, P2, P6 },
                    south.getUv(),
                    south.getUvSize(),
                    south.getRotationEnum()
                        .ordinal() * 90,
                    textureWidth,
                    textureHeight,
                    cubeIn.getMirror(),
                    EnumFacing.SOUTH);
            quadUp = up == null ? null
                : new GeoQuad(
                    new GeoVertex[] { P4, P8, P7, P3 },
                    up.getUv(),
                    up.getUvSize(),
                    up.getRotationEnum()
                        .ordinal() * 90,
                    textureWidth,
                    textureHeight,
                    cubeIn.getMirror(),
                    EnumFacing.UP);
            quadDown = down == null ? null
                : new GeoQuad(
                    new GeoVertex[] { P1, P5, P6, P2 },
                    down.getUv(),
                    down.getUvSize(),
                    down.getRotationEnum()
                        .ordinal() * 90,
                    textureWidth,
                    textureHeight,
                    cubeIn.getMirror(),
                    EnumFacing.DOWN);

            if (cubeIn.getMirror() == Boolean.TRUE || mirror == Boolean.TRUE) {
                quadWest = west == null ? null
                    : new GeoQuad(
                        new GeoVertex[] { P7, P8, P6, P5 },
                        west.getUv(),
                        west.getUvSize(),
                        west.getRotationEnum()
                            .ordinal() * 90,
                        textureWidth,
                        textureHeight,
                        cubeIn.getMirror(),
                        EnumFacing.WEST);
                quadEast = east == null ? null
                    : new GeoQuad(
                        new GeoVertex[] { P4, P3, P1, P2 },
                        east.getUv(),
                        east.getUvSize(),
                        east.getRotationEnum()
                            .ordinal() * 90,
                        textureWidth,
                        textureHeight,
                        cubeIn.getMirror(),
                        EnumFacing.EAST);
                quadNorth = north == null ? null
                    : new GeoQuad(
                        new GeoVertex[] { P3, P7, P5, P1 },
                        north.getUv(),
                        north.getUvSize(),
                        north.getRotationEnum()
                            .ordinal() * 90,
                        textureWidth,
                        textureHeight,
                        cubeIn.getMirror(),
                        EnumFacing.NORTH);
                quadSouth = south == null ? null
                    : new GeoQuad(
                        new GeoVertex[] { P8, P4, P2, P6 },
                        south.getUv(),
                        south.getUvSize(),
                        south.getRotationEnum()
                            .ordinal() * 90,
                        textureWidth,
                        textureHeight,
                        cubeIn.getMirror(),
                        EnumFacing.SOUTH);
                quadUp = up == null ? null
                    : new GeoQuad(
                        new GeoVertex[] { P1, P5, P6, P2 },
                        up.getUv(),
                        up.getUvSize(),
                        up.getRotationEnum()
                            .ordinal() * 90,
                        textureWidth,
                        textureHeight,
                        cubeIn.getMirror(),
                        EnumFacing.UP);
                quadDown = down == null ? null
                    : new GeoQuad(
                        new GeoVertex[] { P4, P8, P7, P3 },
                        down.getUv(),
                        down.getUvSize(),
                        down.getRotationEnum()
                            .ordinal() * 90,
                        textureWidth,
                        textureHeight,
                        cubeIn.getMirror(),
                        EnumFacing.DOWN);
            }
        } else {
            double[] UV = cubeIn.getUv().boxUVCoords;
            Vector3d UVSize = VectorUtils.fromArray(cubeIn.getSize());
            UVSize = new Vector3d(Math.floor(UVSize.x), Math.floor(UVSize.y), Math.floor(UVSize.z));

            quadWest = new GeoQuad(
                new GeoVertex[] { P4, P3, P1, P2 },
                new double[] { UV[0] + UVSize.z + UVSize.x, UV[1] + UVSize.z },
                new double[] { UVSize.z, UVSize.y },
                0,
                textureWidth,
                textureHeight,
                cubeIn.getMirror(),
                EnumFacing.WEST);
            quadEast = new GeoQuad(
                new GeoVertex[] { P7, P8, P6, P5 },
                new double[] { UV[0], UV[1] + UVSize.z },
                new double[] { UVSize.z, UVSize.y },
                0,
                textureWidth,
                textureHeight,
                cubeIn.getMirror(),
                EnumFacing.EAST);
            quadNorth = new GeoQuad(
                new GeoVertex[] { P3, P7, P5, P1 },
                new double[] { UV[0] + UVSize.z, UV[1] + UVSize.z },
                new double[] { UVSize.x, UVSize.y },
                0,
                textureWidth,
                textureHeight,
                cubeIn.getMirror(),
                EnumFacing.NORTH);
            quadSouth = new GeoQuad(
                new GeoVertex[] { P8, P4, P2, P6 },
                new double[] { UV[0] + UVSize.z + UVSize.x + UVSize.z, UV[1] + UVSize.z },
                new double[] { UVSize.x, UVSize.y },
                0,
                textureWidth,
                textureHeight,
                cubeIn.getMirror(),
                EnumFacing.SOUTH);
            quadUp = new GeoQuad(
                new GeoVertex[] { P4, P8, P7, P3 },
                new double[] { UV[0] + UVSize.z, UV[1] },
                new double[] { UVSize.x, UVSize.z },
                0,
                textureWidth,
                textureHeight,
                cubeIn.getMirror(),
                EnumFacing.UP);
            quadDown = new GeoQuad(
                new GeoVertex[] { P2, P6, P5, P1 },
                new double[] { UV[0] + UVSize.z + UVSize.x, UV[1] },
                new double[] { UVSize.x, UVSize.z },
                0,
                textureWidth,
                textureHeight,
                cubeIn.getMirror(),
                EnumFacing.DOWN);

            if (cubeIn.getMirror() == Boolean.TRUE) {
                quadWest = new GeoQuad(
                    new GeoVertex[] { P7, P8, P6, P5 },
                    new double[] { UV[0] + UVSize.z + UVSize.x, UV[1] + UVSize.z },
                    new double[] { UVSize.z, UVSize.y },
                    0,
                    textureWidth,
                    textureHeight,
                    cubeIn.getMirror(),
                    EnumFacing.WEST);
                quadEast = new GeoQuad(
                    new GeoVertex[] { P4, P3, P1, P2 },
                    new double[] { UV[0], UV[1] + UVSize.z },
                    new double[] { UVSize.z, UVSize.y },
                    0,
                    textureWidth,
                    textureHeight,
                    cubeIn.getMirror(),
                    EnumFacing.EAST);
                quadNorth = new GeoQuad(
                    new GeoVertex[] { P3, P7, P5, P1 },
                    new double[] { UV[0] + UVSize.z, UV[1] + UVSize.z },
                    new double[] { UVSize.x, UVSize.y },
                    0,
                    textureWidth,
                    textureHeight,
                    cubeIn.getMirror(),
                    EnumFacing.NORTH);
                quadSouth = new GeoQuad(
                    new GeoVertex[] { P8, P4, P2, P6 },
                    new double[] { UV[0] + UVSize.z + UVSize.x + UVSize.z, UV[1] + UVSize.z },
                    new double[] { UVSize.x, UVSize.y },
                    0,
                    textureWidth,
                    textureHeight,
                    cubeIn.getMirror(),
                    EnumFacing.SOUTH);
                quadUp = new GeoQuad(
                    new GeoVertex[] { P4, P8, P7, P3 },
                    new double[] { UV[0] + UVSize.z, UV[1] },
                    new double[] { UVSize.x, UVSize.z },
                    0,
                    textureWidth,
                    textureHeight,
                    cubeIn.getMirror(),
                    EnumFacing.UP);
                quadDown = new GeoQuad(
                    new GeoVertex[] { P1, P5, P6, P2 },
                    new double[] { UV[0] + UVSize.z + UVSize.x, UV[1] + UVSize.z },
                    new double[] { UVSize.x, -UVSize.z },
                    0,
                    textureWidth,
                    textureHeight,
                    cubeIn.getMirror(),
                    EnumFacing.DOWN);
            }
        }

        cube.quads[0] = quadWest;
        cube.quads[1] = quadEast;
        cube.quads[2] = quadNorth;
        cube.quads[3] = quadSouth;
        cube.quads[4] = quadUp;
        cube.quads[5] = quadDown;

        // Negative-size cubes in BlockBench represent inside-out shells used to
        // create hollow/void areas. They work by having inward-facing normals so
        // that with back-face culling ON, the faces are culled from the outside.
        // However, GeckoLib disables back-face culling (GlStateManager.disableCull()),
        // so rendering negative-size cubes as geometry would make them solid opaque
        // blocks that occlude everything behind them. Skip them entirely.
        if (hasNegSize) {
            for (int i = 0; i < cube.quads.length; i++) {
                cube.quads[i] = null;
            }
        }

        return cube;
    }
}
