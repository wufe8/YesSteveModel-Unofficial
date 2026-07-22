package software.bernie.geckolib3.geo;

import java.util.ArrayList;

import javax.annotation.Nullable;
import javax.vecmath.Vector3f;
import javax.vecmath.Vector4f;

import net.geckominecraft.client.renderer.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import software.bernie.example.config.ConfigHandler;
import software.bernie.geckolib3.core.util.Color;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.geo.render.built.GeoQuad;
import software.bernie.geckolib3.geo.render.built.GeoVertex;
import software.bernie.geckolib3.model.provider.GeoModelProvider;
import software.bernie.geckolib3.util.MatrixStack;

public interface IGeoRenderer<T> {

    public static MatrixStack MATRIX_STACK = new MatrixStack();
    /** Reusable per-quad normal vector — avoids allocating one per cube/quad. */
    Vector3f RENDER_TEMP_NORMAL = new Vector3f();
    /** Reusable per-vertex position vector — avoids allocating one per vertex. */
    Vector4f RENDER_TEMP_VEC = new Vector4f();

    default void render(GeoModel model, T animatable, float partialTicks, float red, float green, float blue,
        float alpha) {
        GlStateManager.disableCull();
        GlStateManager.enableRescaleNormal();
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableBlend();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        renderEarly(model, animatable, partialTicks, red, green, blue, alpha);

        renderLate(model, animatable, partialTicks, red, green, blue, alpha);
        Tessellator tess = Tessellator.instance;
        // BufferBuilder builder = Tessellator.instance.getBuffer();

        // builder.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
        tess.startDrawing(GL11.GL_QUADS);// , DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
        // Render all top level bones
        for (GeoBone group : model.topLevelBones) {
            renderRecursively(tess, animatable, group, red, green, blue, alpha);
        }

        Tessellator.instance.draw();

        renderAfter(model, animatable, partialTicks, red, green, blue, alpha);
        // GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
    }

    default boolean isBoneRenderOverriden(T animatable, GeoBone bone) {
        return false;
    }

    default void drawOverridenBone(T animatable, GeoBone bone) {

    }

    default void renderRecursively(Tessellator builder, T animatable, GeoBone bone, float red, float green, float blue,
        float alpha) {
        MATRIX_STACK.push();

        MATRIX_STACK.translate(bone);
        MATRIX_STACK.moveToPivot(bone);
        MATRIX_STACK.rotate(bone);
        MATRIX_STACK.scale(bone);
        MATRIX_STACK.moveBackFromPivot(bone);

        if (isBoneRenderOverriden(animatable, bone)) {
            drawOverridenBone(animatable, bone);
            MATRIX_STACK.pop();
            return;
        }

        if (!bone.isHidden()) {
            // Match OpenYSM NativeModelRenderer behavior: scale (0,0,0) = hidden
            if (bone.getScaleX() == 0f && bone.getScaleY() == 0f && bone.getScaleZ() == 0f) {
                MATRIX_STACK.pop();
                return;
            }

            // Per-bone texture override: if this bone specifies a different texture,
            // bind it before rendering cubes and restore afterwards.
            int savedTextureId = -1;
            if (bone.textureOverride != null) {
                savedTextureId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                ITextureObject overrideTex = Minecraft.getMinecraft()
                    .getTextureManager().getTexture(bone.textureOverride);
                if (overrideTex != null) {
                    GlStateManager.bindTexture(overrideTex.getGlTextureId());
                }
            }

            // Glow bones (name starts with "ysmGlow"): the negative-size cube
            // encloses the positive-size cube, but its front faces are CLOSER
            // to the camera (origin adjustment pushes them outward). Normal
            // depth testing would let the negative cube occlude the positive.
            //
            // Strategy — render negatives FIRST using CULL_FRONT (only back
            // faces, i.e. faces facing away from the camera), which are at the
            // FARTHEST depth. Then render positives SECOND with default depth
            // testing (LEQUAL) — the positive cube's front faces (closer to
            // camera) pass against the negative's far-depth and render on top.
            // At the silhouette edges no positive geometry exists, so the
            // negative's back-face ring remains visible.
            //
            // NOTE: CULL_FRONT on a standard CCW-wound cube culls front faces
            // (facing camera) and renders back faces (facing away). This gives
            // us the far-side depth without vertex reversal trickery.
            if (bone.isGlow) {
                Tessellator.instance.draw();

                // Single scan: detect whether we have neg/pos cubes at all.
                boolean anyNeg = false, anyPos = false;
                for (GeoCube c : bone.childCubes) {
                    if (c.hasNegSize) anyNeg = true;
                    else anyPos = true;
                    if (anyNeg && anyPos) break;
                }

                // ── Batch 1: negative-size cubes (CULL_FRONT → far faces only) ──
                if (anyNeg) {
                    Tessellator.instance.startDrawing(GL11.GL_QUADS);
                    GL11.glEnable(GL11.GL_CULL_FACE);
                    GL11.glCullFace(GL11.GL_FRONT);
                    for (GeoCube cube : bone.childCubes) {
                        if (!cube.hasNegSize) continue;
                        MATRIX_STACK.push();
                        renderCube(builder, cube, red, green, blue, alpha);
                        MATRIX_STACK.pop();
                    }
                    Tessellator.instance.draw();
                    GL11.glCullFace(GL11.GL_BACK);
                    GL11.glDisable(GL11.GL_CULL_FACE);
                }

                // ── Batch 2: positive-size cubes (normal depth testing) ──
                if (anyPos) {
                    Tessellator.instance.startDrawing(GL11.GL_QUADS);
                    for (GeoCube cube : bone.childCubes) {
                        if (cube.hasNegSize) continue;
                        MATRIX_STACK.push();
                        renderCube(builder, cube, red, green, blue, alpha);
                        MATRIX_STACK.pop();
                    }
                    Tessellator.instance.draw();
                }

                Tessellator.instance.startDrawing(GL11.GL_QUADS);
            } else {
                for (GeoCube cube : bone.childCubes) {
                    MATRIX_STACK.push();
                    renderCube(builder, cube, red, green, blue, alpha);
                    MATRIX_STACK.pop();
                }
            }

            // Restore the original texture binding after rendering this bone's cubes.
            if (savedTextureId >= 0) {
                GlStateManager.bindTexture(savedTextureId);
            }
        }
        if (!bone.childBonesAreHiddenToo()) {
            for (GeoBone childBone : bone.childBones) {
                renderRecursively(builder, animatable, childBone, red, green, blue, alpha);
            }
        }

        MATRIX_STACK.pop();
    }

    default void renderCube(Tessellator builder, GeoCube cube, float red, float green, float blue, float alpha) {
        MATRIX_STACK.moveToPivot(cube);
        MATRIX_STACK.rotate(cube);
        MATRIX_STACK.moveBackFromPivot(cube);

        boolean flat = !cube.mesh && (cube.size.x == 0 || cube.size.y == 0 || cube.size.z == 0);
        if (flat) {
            GlStateManager.enablePolygonOffset();
            GlStateManager.doPolygonOffset(-1.0F, -10.0F);
        }

        for (GeoQuad quad : cube.quads) {
            if (quad == null) continue;
            // Fresh copy for normal transforms + flat shading workaround
            if (quad.normalVector == null) {
                RENDER_TEMP_NORMAL.set(quad.normal.getX(), quad.normal.getY(), quad.normal.getZ());
            } else {
                RENDER_TEMP_NORMAL.set(quad.normalVector);
            }

            MATRIX_STACK.getNormalMatrix()
                .transform(RENDER_TEMP_NORMAL);

            /*
             * Fix shading dark shading for flat cubes + compatibility wish Optifine shaders
             */
            if (!cube.mesh && (cube.size.y == 0 || cube.size.z == 0) && RENDER_TEMP_NORMAL.x < 0) {
                RENDER_TEMP_NORMAL.x *= -1;
            }
            if (!cube.mesh && (cube.size.x == 0 || cube.size.z == 0) && RENDER_TEMP_NORMAL.y < 0) {
                RENDER_TEMP_NORMAL.y *= -1;
            }
            if (!cube.mesh && (cube.size.x == 0 || cube.size.y == 0) && RENDER_TEMP_NORMAL.z < 0) {
                RENDER_TEMP_NORMAL.z *= -1;
            }

            for (GeoVertex vertex : quad.vertices) {
                RENDER_TEMP_VEC.set(vertex.position.x, vertex.position.y, vertex.position.z, 1.0F);

                MATRIX_STACK.getModelMatrix()
                    .transform(RENDER_TEMP_VEC);
                builder.setColorRGBA_F(red, green, blue, alpha);
                builder.setNormal(RENDER_TEMP_NORMAL.x, RENDER_TEMP_NORMAL.y, RENDER_TEMP_NORMAL.z);
                builder.addVertexWithUV(RENDER_TEMP_VEC.x, RENDER_TEMP_VEC.y, RENDER_TEMP_VEC.z,
                    vertex.textureU, vertex.textureV);
            }
        }

        if (flat) {
            GlStateManager.disablePolygonOffset();
            GlStateManager.doPolygonOffset(0.0F, 0.0F);
        }
    }

    /*
     * (-0.4095761, 0.5882118, 0.70710677, 1.0)
     * (0.409576, 1.1617882, 0.70710677, 1.0)
     * (0.0039961934, 1.7410161, -5.9604645E-8, 1.0)
     * (-0.81515586, 1.1674397, -5.9604645E-8, 1.0)
     * (-0.003996223, 0.008983791, -5.9604645E-8, 1.0)
     * (0.81515586, 0.5825603, -5.9604645E-8, 1.0)
     * (0.40957603, 1.1617882, -0.7071068, 1.0)
     * (-0.40957603, 0.5882118, -0.7071068, 1.0)
     */
    @SuppressWarnings("rawtypes")
    GeoModelProvider getGeoModelProvider();

    ResourceLocation getTextureLocation(T instance);

    @Nullable
    default GeoModel getGeoModel() {
        return null;
    }

    default void renderEarly(GeoModel model, T animatable, float ticks, float red, float green, float blue,
        float alpha) {
        float width = getWidthScale(animatable);
        float height = getHeightScale(animatable);
        MATRIX_STACK.push();
        MATRIX_STACK.scale(width, height, width);
    }

    default void renderLate(GeoModel model, T animatable, float ticks, float red, float green, float blue,
        float alpha) {}

    default void renderAfter(GeoModel model, T animatable, float ticks, float red, float green, float blue,
        float alpha) {
        MATRIX_STACK.pop();
    }

    default Color getRenderColor(T animatable, float partialTicks) {
        return Color.ofRGBA(255, 255, 255, 255);
    }

    default Integer getUniqueID(T animatable) {
        return animatable.hashCode();
    }

    default GeoBone[] getPathFromRoot(GeoBone bone) {
        ArrayList<GeoBone> bones = new ArrayList<>();
        while (bone != null) {
            bones.add(0, bone);
            bone = bone.parent;
        }
        return bones.toArray(new GeoBone[0]);
    }

    default float getWidthScale(T animatable) {
        return 1F;
    }

    default float getHeightScale(T entity) {
        return 1F;
    }
}
