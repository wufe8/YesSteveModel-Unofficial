package software.bernie.geckolib3.geo;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.vecmath.Vector3f;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoQuad;
import software.bernie.geckolib3.geo.render.built.GeoVertex;
import software.bernie.geckolib3.model.provider.GeoModelProvider;

/** Exercises real vertex emission and Tessellator resets, replacing only the GL submission. */
class GeoRenderBatchTest {

    private static Object get(Tessellator tess, String name) {
        try {
            Field field = Tessellator.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(tess);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void set(Tessellator tess, String name, Object value) {
        try {
            Field field = Tessellator.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(tess, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class Renderer implements IGeoRenderer<Object> {
        boolean offset;
        final List<Boolean> stateChanges = new ArrayList<>();

        @Override
        public void setCubePolygonOffset(boolean flat) {
            offset = flat;
            stateChanges.add(flat);
        }

        @Override
        public GeoModelProvider getGeoModelProvider() { return null; }

        @Override
        public ResourceLocation getTextureLocation(Object entity) { return null; }
    }

    private static final class RecordingTessellator extends Tessellator {
        final Renderer renderer;
        final List<Boolean> offsets = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();
        int expectedColor;
        int vertices;

        RecordingTessellator(Renderer renderer) { this.renderer = renderer; }

        @Override
        public int draw() {
            assertTrue((Boolean) get(this, "isDrawing"));
            int count = (Integer) get(this, "vertexCount");
            if (count > 0) {
                assertTrue((Boolean) get(this, "hasColor"), "Every restarted batch needs RGBA");
                assertTrue((Boolean) get(this, "hasNormals"));
                assertTrue((Boolean) get(this, "hasTexture"));
                assertTrue((Integer) get(this, "rawBufferSize") <= 0x20000);
                int[] raw = (int[]) get(this, "rawBuffer");
                for (int vertex = 0; vertex < count; vertex++) {
                    assertEquals(expectedColor, raw[vertex * 8 + 5], "RGBA must be written for every vertex");
                }
                counts.add(count);
                offsets.add(renderer.offset);
                vertices += count;
            }
            set(this, "isDrawing", false);
            set(this, "vertexCount", 0);
            set(this, "rawBufferIndex", 0);
            set(this, "addedVertices", 0);
            return count * 32;
        }
    }

    private static GeoCube cube(int faces, boolean flat) {
        GeoCube cube = GeoCube.createFromPolyMesh(null, null);
        cube.mesh = !flat;
        cube.size.set(1, 1, flat ? 0 : 1);
        GeoQuad quad = new GeoQuad(new GeoVertex[] {
            new GeoVertex(0, 0, 0), new GeoVertex(1, 0, 0),
            new GeoVertex(1, 1, 0), new GeoVertex(0, 1, 0)
        }, new Vector3f(0, 0, 1));
        cube.quads = new GeoQuad[faces];
        Arrays.fill(cube.quads, quad);
        return cube;
    }

    private static RecordingTessellator begin(Renderer renderer) {
        RecordingTessellator tess = new RecordingTessellator(renderer);
        tess.startDrawing(7);
        tess.setColorRGBA_F(.2f, .4f, .6f, .5f);
        tess.expectedColor = (Integer) get(tess, "color");
        return tess;
    }

    @Test
    void largeMeshKeepsColorAndAlphaAcrossFaceBoundaryFlushes() {
        Renderer renderer = new Renderer();
        RecordingTessellator tess = begin(renderer);
        IGeoRenderer.MATRIX_STACK.push();
        try {
            renderer.renderCube(tess, cube(4000, false), .2f, .4f, .6f, .5f);
            tess.draw();
            assertEquals(16000, tess.vertices);
            assertTrue(tess.counts.size() >= 2, "Fixture must cross the actual batch threshold");
        } finally {
            IGeoRenderer.MATRIX_STACK.pop();
        }
    }

    @Test
    void flatRunsSubmitOnlyUnderTheirOwnOffsetAndStayBatched() {
        Renderer renderer = new Renderer();
        RecordingTessellator tess = begin(renderer);
        GeoBone bone = new GeoBone();
        bone.childCubes.add(cube(1, false));
        bone.childCubes.add(cube(2, true));
        bone.childCubes.add(cube(3, true));
        bone.childCubes.add(cube(1, false));
        IGeoRenderer.MATRIX_STACK.push();
        try {
            renderer.renderCubeGroup(tess, bone, 0, .2f, .4f, .6f, .5f);
            tess.draw();
            assertEquals(Arrays.asList(false, true, false), tess.offsets);
            assertEquals(Arrays.asList(4, 20, 4), tess.counts);
            assertEquals(Arrays.asList(true, false), renderer.stateChanges);
            assertFalse(renderer.offset);
        } finally {
            IGeoRenderer.MATRIX_STACK.pop();
        }
    }
}
