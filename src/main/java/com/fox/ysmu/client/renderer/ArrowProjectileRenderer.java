package com.fox.ysmu.client.renderer;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.util.ModelIdUtil;

import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.geo.render.built.GeoQuad;
import software.bernie.geckolib3.geo.render.built.GeoVertex;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Renders a projectile sub-entity GeoModel (e.g. #arrow) in place of a vanilla entity.
 * Called from the RenderArrow mixin when the arrow has a custom model ID.
 */
public class ArrowProjectileRenderer {

    /**
     * Try to render a custom projectile model for the given entity and model ID.
     * @return true if a custom model was rendered (vanilla rendering should be skipped),
     *         false if no custom model is available (vanilla should proceed).
     */
    @SuppressWarnings("unchecked")
    public static boolean render(Entity entity, double x, double y, double z, float yaw, float partialTicks,
        ResourceLocation modelId, IGeoRenderer<?> geoRenderer) {

        if (modelId == null || entity == null) return false;

        com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] render start: modelId={}, entity={}", modelId, entity.getEntityId());

        // Find projectile entity type "minecraft:arrow" for this model
        List<String> projTypes = ClientModelManager.PROJECTILE_MODEL_IDS.get(modelId);
        if (projTypes == null) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-ARROW] no PROJECTILE_MODEL_IDS for {}", modelId);
            return false;
        }
        com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] projTypes={}", projTypes);

        String arrowType = null;
        for (String t : projTypes) {
            if (t.contains("arrow")) {
                arrowType = t;
                break;
            }
        }
        if (arrowType == null) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-ARROW] no arrowType found in {}", projTypes);
            return false;
        }

        // Get the projectile GeoModel
        ResourceLocation projGeoId = ModelIdUtil.getSubModelId(modelId, "projectile_" + arrowType);
        com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] looking for geo: {}", projGeoId);
        GeoModel projModel = GeckoLibCache.getInstance().getGeoModels().get(projGeoId);
        if (projModel == null) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-ARROW] GeoModel not found: {}", projGeoId);
            return false;
        }
        com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] GeoModel found: topLevelBones={}", 
            projModel.topLevelBones != null ? projModel.topLevelBones.size() : 0);
        if (projModel.topLevelBones != null) {
            for (GeoBone b : projModel.topLevelBones) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW]   bone '{}': cubes={}, childBones={}, hidden={}",
                    b.name,
                    b.childCubes != null ? b.childCubes.size() : 0,
                    b.childBones != null ? b.childBones.size() : 0,
                    b.isHidden());
            }
        }

        // Find projectile texture
        List<ResourceLocation> projTexList = ClientModelManager.PROJECTILE_TEXTURE_IDS.get(modelId);
        ResourceLocation projTexId = null;
        if (projTexList != null) {
            String prefix = "projectile_" + arrowType + "_";
            for (ResourceLocation tid : projTexList) {
                if (tid.getResourcePath().contains(prefix)) {
                    projTexId = tid;
                    break;
                }
            }
        }
        if (projTexId == null) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-ARROW] no texture found for {}", modelId);
            return false;
        }
        com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] texture: {}", projTexId);

        // === Do a quick sanity check: dump bone tree in debug mode ===
        if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] === Full bone tree dump for {} ===", projGeoId);
            dumpBoneTree(projModel.topLevelBones, 0);
        }

        // === Render the actual projectile GeoModel ===
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(x, y, z);

            // Rotate to match arrow flight direction (vanilla convention)
            float interpYaw = entity.prevRotationYaw
                + (entity.rotationYaw - entity.prevRotationYaw) * partialTicks;
            float interpPitch = entity.prevRotationPitch
                + (entity.rotationPitch - entity.prevRotationPitch) * partialTicks;
            GL11.glRotatef(interpYaw - 90.0F, 0.0F, 1.0F, 0.0F);
            GL11.glRotatef(interpPitch, 0.0F, 0.0F, 1.0F);
            GL11.glScalef(0.7f, 0.7f, 0.7f);

            net.geckominecraft.client.renderer.GlStateManager.disableCull();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            Minecraft.getMinecraft().renderEngine.bindTexture(projTexId);

            // Render all bone cubes through GeckoLib's renderRecursively
            // which properly applies MATRIX_STACK bone transforms (pivot/rotation).
            Tessellator tess = Tessellator.instance;
            tess.startDrawing(GL11.GL_QUADS);
            for (GeoBone bone : projModel.topLevelBones) {
                ((IGeoRenderer<Entity>) geoRenderer)
                    .renderRecursively(tess, entity, bone, 1.0F, 1.0F, 1.0F, 1.0F);
            }
            tess.draw();
        } catch (Exception e) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-ARROW] Model render failed", e);
        } finally {
            GL11.glPopMatrix();
        }
        return true;
    }

    /**
     * Dump the full bone tree structure to the log for debugging.
     */
    private static void dumpBoneTree(List<GeoBone> bones, int indent) {
        if (bones == null) return;
        for (GeoBone bone : bones) {
            if (bone == null) continue;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < indent; i++) sb.append("  ");
            sb.append("bone '").append(bone.name).append("'");
            sb.append(" cubes=").append(bone.childCubes != null ? bone.childCubes.size() : 0);
            sb.append(" childBones=").append(bone.childBones != null ? bone.childBones.size() : 0);
            sb.append(" hidden=").append(bone.isHidden());
            if (bone.childCubes != null) {
                for (int ci = 0; ci < bone.childCubes.size(); ci++) {
                    GeoCube c = bone.childCubes.get(ci);
                    sb.append("\n");
                    for (int j = 0; j < indent + 1; j++) sb.append("  ");
                    sb.append("cube[").append(ci).append("]: mesh=").append(c != null ? c.mesh : "null");
                    sb.append(" quads=").append((c != null && c.quads != null) ? c.quads.length : 0);
                    sb.append(" pivot=").append(c != null && c.pivot != null ? 
                        String.format("(%.3f,%.3f,%.3f)", c.pivot.x, c.pivot.y, c.pivot.z) : "null");
                    sb.append(" size=").append(c != null && c.size != null ? 
                        String.format("(%.3f,%.3f,%.3f)", c.size.x, c.size.y, c.size.z) : "null");
                    if (c != null && c.quads != null) {
                        for (int qi = 0; qi < c.quads.length; qi++) {
                            GeoQuad q = c.quads[qi];
                            if (q != null && q.vertices != null) {
                                sb.append("\n");
                                for (int j = 0; j < indent + 2; j++) sb.append("  ");
                                sb.append("quad[").append(qi).append("]: verts=").append(q.vertices.length);
                                if (q.vertices.length > 0 && q.vertices[0] != null) {
                                    sb.append(" pos0=(")
                                        .append(String.format("%.4f", q.vertices[0].position.x)).append(",")
                                        .append(String.format("%.4f", q.vertices[0].position.y)).append(",")
                                        .append(String.format("%.4f", q.vertices[0].position.z)).append(")");
                                }
                            } else {
                                sb.append(" [null quad]");
                            }
                        }
                    }
                }
            }
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] {}", sb.toString());
            dumpBoneTree(bone.childBones, indent + 1);
        }
    }

}
