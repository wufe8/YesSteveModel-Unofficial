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
import software.bernie.geckolib3.geo.render.built.GeoModel;
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

        // === DIAGNOSTIC: render a solid red cube at entity position ===
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(x, y, z);

            float interpYaw = entity.prevRotationYaw
                + (entity.rotationYaw - entity.prevRotationYaw) * partialTicks;
            float interpPitch = entity.prevRotationPitch
                + (entity.rotationPitch - entity.prevRotationPitch) * partialTicks;
            GL11.glRotatef(interpYaw - 90.0F, 0.0F, 1.0F, 0.0F);
            GL11.glRotatef(interpPitch, 0.0F, 0.0F, 1.0F);
            GL11.glScalef(0.7f, 0.7f, 0.7f);

            // GL state setup
            net.geckominecraft.client.renderer.GlStateManager.disableCull();
            GL11.glEnable(GL11.GL_TEXTURE_2D);

            // Bind the projectile texture
            Minecraft.getMinecraft().renderEngine.bindTexture(projTexId);

            // Manual quad: render a unit quad facing +Z at origin with the bound texture
            Tessellator tess = Tessellator.instance;
            tess.startDrawing(GL11.GL_QUADS);
            tess.setColorRGBA_F(1.0F, 1.0F, 1.0F, 1.0F);
            // A simple 1x1 quad centered at origin
            tess.addVertexWithUV(-0.5, -0.5, 0, 0, 1);
            tess.addVertexWithUV(-0.5,  0.5, 0, 0, 0);
            tess.addVertexWithUV( 0.5,  0.5, 0, 1, 0);
            tess.addVertexWithUV( 0.5, -0.5, 0, 1, 1);
            tess.draw();

            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] Diagnostic quad rendered at ({},{},{})", x, y, z);

            // However, the actual model render is still needed
            // (even if invisible, the vanilla render was already cancelled)
        } catch (Exception e) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-ARROW] Diagnostic render failed", e);
        } finally {
            GL11.glPopMatrix();
        }

        // === Try the actual GeckoLib model render ===
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(x, y, z);
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

            Tessellator tess = Tessellator.instance;
            tess.startDrawing(GL11.GL_QUADS);
            for (GeoBone bone : projModel.topLevelBones) {
                ((IGeoRenderer<Entity>) geoRenderer)
                    .renderRecursively(tess, entity, bone, 1.0F, 1.0F, 1.0F, 1.0F);
            }
            tess.draw();
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] GeckoLib model render completed");
        } catch (Exception e) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-ARROW] GeckoLib model render failed", e);
        } finally {
            GL11.glPopMatrix();
        }
        return true;
    }
}
