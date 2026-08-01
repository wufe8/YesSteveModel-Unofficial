package com.fox.ysmu.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.fox.ysmu.Config;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.util.IProjectileModelArrow;
import com.fox.ysmu.ysmu;

/**
 * Adds a datawatcher to EntityArrow to store the shooting player's model ID,
 * enabling the client to look up the correct projectile sub-entity model.
 */
@Mixin(value = EntityArrow.class, priority = 900)
public abstract class MixinEntityArrow implements IProjectileModelArrow {

    @Unique
    private static final int ysmu$DW_MODEL_ID = 18;

    @Shadow
    public Entity shootingEntity;

    @Shadow
    protected abstract void entityInit();

    @Inject(method = "entityInit", at = @At("TAIL"))
    private void ysmu$onEntityInit(CallbackInfo ci) {
        ((Entity) (Object) this).getDataWatcher().addObject(ysmu$DW_MODEL_ID, "");
    }

    /**
     * After construction with a shooting entity, capture the model ID on the server side.
     * Constructor (World, EntityLivingBase, float) is used for player-shot arrows.
     */
    @Inject(method = "<init>(Lnet/minecraft/world/World;Lnet/minecraft/entity/EntityLivingBase;F)V", at = @At("TAIL"))
    private void ysmu$onConstruct(World world, net.minecraft.entity.EntityLivingBase shooter, float velocity, CallbackInfo ci) {
        if (world.isRemote) return;
        if (shooter instanceof EntityPlayer player) {
            ExtendedModelInfo eep = ExtendedModelInfo.get(player);
            if (eep != null && eep.getModelId() != null) {
                ((Entity) (Object) this).getDataWatcher().updateObject(ysmu$DW_MODEL_ID, eep.getModelId().toString());
                if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_RENDER) {
                    ysmu.LOG.info("[YSMU-ARROW] Set model ID {} on arrow entity", eep.getModelId());
                }
            }
        }
    }

    /**
     * Client-side accessor: get the projectile model ID stored on this arrow.
     * Returns empty string if no custom model is associated.
     */
    @Unique
    public String ysmu$getProjectileModelId() {
        return ((Entity) (Object) this).getDataWatcher().getWatchableObjectString(ysmu$DW_MODEL_ID);
    }
}
