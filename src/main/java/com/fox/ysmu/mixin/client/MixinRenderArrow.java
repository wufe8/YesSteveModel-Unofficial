package com.fox.ysmu.mixin.client;

import net.minecraft.client.renderer.entity.RenderArrow;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.fox.ysmu.client.renderer.ArrowProjectileRenderer;
import com.fox.ysmu.util.IProjectileModelArrow;

/**
 * Intercepts arrow entity rendering to use a custom projectile model
 * when the arrow has a model ID stored in its datawatcher.
 */
@Mixin(value = RenderArrow.class, priority = 900)
public abstract class MixinRenderArrow {

    /**
     * At the head of doRender, check if this arrow has a custom projectile model.
     * If so, render the custom model and cancel the vanilla render by skipping
     * the original method body — handled via a cancellable injection.
     *
     * Note: We use @Inject with cancellable=true before the method runs.
     * If custom rendering succeeds, we cancel; otherwise vanilla proceeds.
     */
    @Inject(method = "doRender(Lnet/minecraft/entity/Entity;DDDFF)V",
        at = @At("HEAD"), cancellable = true)
    private void ysmu$onRenderArrow(Entity entity, double x, double y, double z, float yaw, float partialTicks,
        CallbackInfo ci) {
        if (!(entity instanceof EntityArrow arrow)) return;

        // Check if this arrow has a projectile model ID in its datawatcher
        String modelIdStr = ((IProjectileModelArrow) arrow).ysmu$getProjectileModelId();
        if (modelIdStr == null || modelIdStr.isEmpty()) return;

        ResourceLocation modelId = new ResourceLocation(modelIdStr);

        // Delegate all lookups (projectile type, geo model, texture) and rendering
        // to ArrowProjectileRenderer, which dynamically searches PROJECTILE_MODEL_IDS
        // for entries containing "arrow" (handles both "minecraft:arrow" and "#arrow").
        @SuppressWarnings("rawtypes")
        software.bernie.geckolib3.geo.GeoReplacedEntityRenderer renderer =
            software.bernie.geckolib3.geo.GeoReplacedEntityRenderer.getRenderer(
                com.fox.ysmu.client.entity.CustomPlayerEntity.class);
        if (renderer == null) return;

        boolean rendered = ArrowProjectileRenderer.render(entity, x, y, z, yaw, partialTicks, modelId, renderer);
        if (rendered) {
            ci.cancel();
        }
    }
}
