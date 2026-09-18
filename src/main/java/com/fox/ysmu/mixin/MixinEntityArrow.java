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

    /**
     * 射出这支箭的物品注册名（{@code ysm.shoot_item_id}）。
     *
     * <p>1.7.10 的 {@code EntityArrow.shootingEntity} 只在服务端赋值（字段是 public 但客户端
     * 构造出的实体里恒为 null），弩/弓的判断必须在服务端取手持物再带过来。额外占一个
     * datawatcher id 是这里既有的做法（模型 id 用 18），19 是紧随其后的空闲 id。</p>
     */
    @Unique
    private static final int ysmu$DW_SHOOT_ITEM = 19;

    @Shadow
    public Entity shootingEntity;

    @Shadow
    protected abstract void entityInit();

    @Inject(method = "entityInit", at = @At("TAIL"))
    private void ysmu$onEntityInit(CallbackInfo ci) {
        ((Entity) (Object) this).getDataWatcher().addObject(ysmu$DW_MODEL_ID, "");
        ((Entity) (Object) this).getDataWatcher().addObject(ysmu$DW_SHOOT_ITEM, "");
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
        ysmu$captureShootItem(shooter);
    }

    /**
     * 同一件事的另一条构造路径：{@code (World, EntityLivingBase shooter, EntityLivingBase target, float, float)}
     * —— 生物（骷髅）瞄准实体射箭走的是它，模组也可能直接用。
     */
    @Inject(method = "<init>(Lnet/minecraft/world/World;Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/entity/EntityLivingBase;FF)V",
        at = @At("TAIL"))
    private void ysmu$onConstructTargeted(World world, net.minecraft.entity.EntityLivingBase shooter,
        net.minecraft.entity.EntityLivingBase target, float velocity, float inaccuracy, CallbackInfo ci) {
        ysmu$captureShootItem(shooter);
    }

    /**
     * 服务端把射手当时的手持物品注册名写进 datawatcher。
     *
     * <p>只在构造时取一次（那正是"射出这一箭"的瞬间），之后存档/切换物品都不会改变它 ——
     * 模型依据的也正是射出时的武器。拿起物品是空的（发射器射出的箭没有射手）就写空串。</p>
     */
    @Unique
    private void ysmu$captureShootItem(net.minecraft.entity.EntityLivingBase shooter) {
        if (shooter == null || ((Entity) (Object) this).worldObj.isRemote) {
            return;
        }
        String itemId = "";
        net.minecraft.item.ItemStack held = shooter.getHeldItem();
        if (held != null && held.getItem() != null) {
            cpw.mods.fml.common.registry.GameRegistry.UniqueIdentifier uid =
                cpw.mods.fml.common.registry.GameRegistry.findUniqueIdentifierFor(held.getItem());
            if (uid != null) {
                itemId = uid.toString();
            }
        }
        ((Entity) (Object) this).getDataWatcher().updateObject(ysmu$DW_SHOOT_ITEM, itemId);
    }

    /**
     * Client-side accessor: get the projectile model ID stored on this arrow.
     * Returns empty string if no custom model is associated.
     */
    @Unique
    public String ysmu$getProjectileModelId() {
        return ((Entity) (Object) this).getDataWatcher().getWatchableObjectString(ysmu$DW_MODEL_ID);
    }

    /** Client-side accessor: 射出这支箭的物品注册名（没有则为空串）。 */
    @Unique
    public String ysmu$getShootItemId() {
        return ((Entity) (Object) this).getDataWatcher().getWatchableObjectString(ysmu$DW_SHOOT_ITEM);
    }
}
