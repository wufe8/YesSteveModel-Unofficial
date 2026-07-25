package com.fox.ysmu.compat;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameRegistry;

/**
 * Tinkers' Construct 十字弩状态检测兼容层
 *
 * <p>TiCon 的 Crossbow 在 GTNH 中注册名为 {@code TConstruct:Crossbow}，
 * 装填状态存储在 NBT {@code InfiTool.Loaded} (boolean) 和
 * {@code InfiTool.Reloading} (int 倒计时刻数) 中。</p>
 *
 * <p>此类提供无编译依赖的纯 NBT 检测方法，不依赖 TiCon 接口。</p>
 */
public final class TinkersCrossbowCompat {

    private static final boolean TCONSTRUCT_LOADED = Loader.isModLoaded("TConstruct");
    private static final String TIC_CROSSBOW_ID = "TConstruct:Crossbow";

    private TinkersCrossbowCompat() {}

    /**
     * 检测物品是否为 TiCon 十字弩。
     */
    public static boolean isTinkersCrossbow(ItemStack stack) {
        if (!TCONSTRUCT_LOADED || stack == null || stack.getItem() == null) {
            return false;
        }
        GameRegistry.UniqueIdentifier uid = GameRegistry.findUniqueIdentifierFor(stack.getItem());
        return uid != null && TIC_CROSSBOW_ID.equals(uid.toString());
    }

    /**
     * 检测 TiCon 十字弩是否已装填（有弹药待发射）。
     * 对应 NBT: {@code InfiTool.Loaded = true}。
     */
    public static boolean isCrossbowLoaded(ItemStack stack) {
        if (!isTinkersCrossbow(stack)) return false;
        NBTTagCompound tags = getInfiTool(stack);
        return tags != null && tags.getBoolean("Loaded");
    }

    /**
     * 检测 TiCon 十字弩是否正在装填中（拉弦动画应播放）。
     * 对应 NBT: {@code InfiTool.Reloading} 键存在。
     */
    public static boolean isCrossbowReloading(ItemStack stack) {
        if (!isTinkersCrossbow(stack)) return false;
        NBTTagCompound tags = getInfiTool(stack);
        return tags != null && tags.hasKey("Reloading");
    }

    /**
     * 获取 InfiTool NBT 标签复合体。
     */
    private static NBTTagCompound getInfiTool(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) return null;
        return stack.getTagCompound().getCompoundTag("InfiTool");
    }
}
