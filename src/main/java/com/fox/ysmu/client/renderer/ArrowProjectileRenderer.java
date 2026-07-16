package com.fox.ysmu.client.renderer;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.util.ModelIdUtil;

import software.bernie.geckolib3.core.util.Color;
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

        // Find projectile entity type "minecraft:arrow" for this model
        List<String> projTypes = ClientModelManager.PROJECTILE_MODEL_IDS.get(modelId);
        if (projTypes == null) return false;

        String arrowType = null;
        for (String t : projTypes) {
            if (t.contains("arrow")) {
                arrowType = t;
                break;
            }
        }
        if (arrowType == null) return false;

        // Get the projectile GeoModel
        ResourceLocation projGeoId = ModelIdUtil.getSubModelId(modelId, "projectile_" + arrowType);
        GeoModel projModel = GeckoLibCache.getInstance().getGeoModels().get(projGeoId);
        if (projModel == null) return false;

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
        if (projTexId == null) return false;

        if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] Rendering projectile model {} for entity {} at ({},{},{})",
                projGeoId, entity.getEntityId(), x, y, z);
        }

        // Render at entity position
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(x, y, z);

            // Rotate to match the arrow's flight direction.
            // EntityArrow.rotationYaw = atan2(motionX, motionZ) * 180/π (0 = South/+Z).
            // The projectile model (#arrow) has the arrow along its +X axis.
            // Vanilla RenderArrow uses rotate(yaw - 90, Y). The arrow model's +X needs
            // to point in the direction of travel; empirically rotate(yaw - 90, Y) works.
            float interpYaw = entity.prevRotationYaw
                + (entity.rotationYaw - entity.prevRotationYaw) * partialTicks;
            float interpPitch = entity.prevRotationPitch
                + (entity.rotationPitch - entity.prevRotationPitch) * partialTicks;
            GL11.glRotatef(interpYaw - 90.0F, 0.0F, 1.0F, 0.0F);
            // Pitch: tilt arrow up/down (rotate around Z, matching vanilla RenderArrow convention)
            GL11.glRotatef(interpPitch, 0.0F, 0.0F, 1.0F);

            // Apply model scale (0.7 from ysm.json)
            float scale = 0.7f;
            GL11.glScalef(scale, scale, scale);

            // Bind projectile texture
            Minecraft.getMinecraft().renderEngine.bindTexture(projTexId);

            // Render the model bones
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawing(GL11.GL_QUADS);
            for (GeoBone bone : projModel.topLevelBones) {
                ((IGeoRenderer<Entity>) geoRenderer)
                    .renderRecursively(tessellator, entity, bone, 1.0F, 1.0F, 1.0F, 1.0F);
            }
            tessellator.draw();
        } catch (Exception e) {
            com.fox.ysmu.ysmu.LOG.warn("Failed to render projectile model for entity {}", entity.getEntityId(), e);
        } finally {
            GL11.glPopMatrix();
        }
        return true;
    }
}
