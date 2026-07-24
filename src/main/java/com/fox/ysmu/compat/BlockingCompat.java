package com.fox.ysmu.compat;

import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 通用盾牌/格挡状态检测兼容层
 *
 * <p>整合多种 Mod 的盾牌格挡检测方式，提供统一的是否正在格挡判断：</p>
 * <ul>
 *   <li><b>标准物品使用链</b>（{@code EnumAction.block}）：剑右键格挡、Et-Futurum 盾牌</li>
 *   <li><b>Battlegear2</b>：反射调用 {@code IBattlePlayer.battlegear2$isBlockingWithShield()}。</li>
 *   <li><b>右键按下兜底</b>：当标准链和 Battlegear2 都不命中时，检查本地玩家
 *       是否按住右键且副手持有盾牌类物品。弥补 Battlegear2 ASM 格挡标记仅持续 1-2 tick 的缺陷。</li>
 * </ul>
 *
 * @see com.fox.ysmu.client.animation.condition.InnerClassify
 */
@SideOnly(Side.CLIENT)
public final class BlockingCompat {

    private static final boolean BATTLEGEAR2_LOADED = Loader.isModLoaded("battlegear2");
    private static Method isBlockingWithShieldMethod;

    static {
        if (BATTLEGEAR2_LOADED) {
            try {
                Class<?> ibpClass = Class.forName("mods.battlegear2.api.core.IBattlePlayer");
                isBlockingWithShieldMethod = ibpClass.getMethod("battlegear2$isBlockingWithShield");
            } catch (Exception e) {
                // 反射失败时禁用 Battlegear2 路径，静默降级
            }
        }
    }

    private BlockingCompat() {}

    /**
     * 统一检测玩家是否正在格挡（持盾/剑右键）。
     */
    public static boolean isBlocking(EntityPlayer player) {
        // 1. 标准物品使用链：EnumAction.block（剑、Et-Futurum 盾牌等）
        if (player.isUsingItem() && player.getItemInUse() != null
            && player.getItemInUse().getItemUseAction() == EnumAction.block) {
            return true;
        }

        // 2. Battlegear2：IBattlePlayer.battlegear2$isBlockingWithShield()
        if (isBlockingWithShieldMethod != null) {
            try {
                if ((boolean) isBlockingWithShieldMethod.invoke(player)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 反射调用失败，静默忽略
            }
        }

        // 3. 右键按下兜底（仅本地玩家）：Battlegear2 的 isShielding 标记只闪 1-2 tick，
        //    直接检查右键是否按住 + 副手持有盾牌类物品。
        if (player == Minecraft.getMinecraft().thePlayer
            && Minecraft.getMinecraft().gameSettings.keyBindUseItem.getIsKeyPressed()
            && !player.isUsingItem()) {
            ItemStack offhand = com.fox.ysmu.compat.BackhandCompat.getOffhandItem(player);
            if (offhand != null && "shield".equals(
                com.fox.ysmu.client.animation.condition.InnerClassify.getItemType(offhand))) {
                return true;
            }
        }

        return false;
    }
}
