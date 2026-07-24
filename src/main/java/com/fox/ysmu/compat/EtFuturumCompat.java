package com.fox.ysmu.compat;

import java.lang.reflect.Method;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Et-Futurum-Requiem 兼容层
 *
 * <p>用于检测玩家是否正在使用鞘翅飞行（isFallFlying）。</p>
 *
 * <p>当 Et-Futurum 已加载时，通过反射调用 {@code IElytraPlayer.etfu$isElytraFlying()}。
 * 否则使用运动推断回退（检查下落状态 + 胸甲槽是否为鞘翅）。</p>
 *
 * <p>同时也提供 {@link #hasElytraEquipped(EntityPlayer)} 来检查玩家是否装备了鞘翅，
 * 不依赖 Et-Futurum（通过物品注册名中的 "elytra" 关键字匹配）。</p>
 *
 * @see ganymedes01.etfuturum.api.elytra.IElytraPlayer
 */
@SideOnly(Side.CLIENT)
public final class EtFuturumCompat {

    private static final boolean ETFUTURUM_LOADED = Loader.isModLoaded("etfuturum");
    private static Method isElytraFlyingMethod;

    static {
        if (ETFUTURUM_LOADED) {
            try {
                Class<?> iepClass = Class.forName("ganymedes01.etfuturum.api.elytra.IElytraPlayer");
                isElytraFlyingMethod = iepClass.getMethod("etfu$isElytraFlying");
            } catch (Exception e) {
                // 反射失败时禁用 Et-Futurum 路径，静默降级到运动推断
            }
        }
    }

    private EtFuturumCompat() {}

    /**
     * 判断玩家是否正在鞘翅滑翔
     *
     * <p>当 Et-Futurum 加载时使用其 API 准确检测，
     * 否则使用运动推断（不在地面 + 下落 + 装备鞘翅）做基础判断。</p>
     *
     * @param player 玩家实体
     * @return 是否正在鞘翅滑翔
     */
    public static boolean isElytraFlying(EntityPlayer player) {
        if (player == null) return false;

        // 优先使用 Et-Futurum 的准确检测
        if (ETFUTURUM_LOADED && isElytraFlyingMethod != null) {
            try {
                if (isElytraFlyingMethod.getDeclaringClass().isInstance(player)) {
                    return (boolean) isElytraFlyingMethod.invoke(player);
                }
            } catch (Exception e) {
                // 反射调用失败，降级到推断
            }
        }

        // 运动推断回退：检查是否在下落且装备了鞘翅
        if (!player.onGround && !player.isInWater() && player.motionY < 0) {
            return hasElytraEquipped(player);
        }
        return false;
    }

    /**
     * 判断玩家是否在胸甲槽装备了鞘翅
     *
     * <p>不依赖 Et-Futurum，通过物品注册名中是否包含 "elytra" 来判断。</p>
     *
     * @param player 玩家实体
     * @return 是否装备了鞘翅
     */
    public static boolean hasElytraEquipped(EntityPlayer player) {
        if (player == null) return false;
        ItemStack chest = player.getEquipmentInSlot(3); // 胸甲槽
        if (chest == null || chest.getItem() == null) return false;
        Object rawName = net.minecraft.item.Item.itemRegistry.getNameForObject(chest.getItem());
        return rawName != null && rawName.toString().contains("elytra");
    }

    /**
     * 获取玩家鞘翅飞行的进度分数（用于 Molang 变量）
     *
     * <p>基于垂直速度归一化到 [0, 1] 范围，方便动画混合。
     * 当 Et-Futurum 加载时根据其飞行状态计算，否则按运动推断。</p>
     *
     * @param player 玩家实体
     * @return 飞行进度 (0=未飞行, 0~1=飞行中)
     */
    public static double getElytraFlightProgress(EntityPlayer player) {
        if (player == null || !isElytraFlying(player)) return 0.0d;
        // 基于下落速度估算飞行强度
        double speed = MathHelper.sqrt_double(
            player.motionX * player.motionX +
            player.motionY * player.motionY +
            player.motionZ * player.motionZ);
        return MathHelper.clamp_double(speed * 0.5, 0.0, 1.0);
    }
}
