package com.fox.ysmu.compat;

import java.util.Locale;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.fox.ysmu.Config;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameRegistry;
import xonin.backhand.api.core.BackhandUtils;
import xonin.backhand.client.hooks.ItemRendererHooks;

public class BackhandCompat {

    private static final boolean BACKHAND_LOADED = Loader.isModLoaded("backhand");
    private static final java.util.Set<String> COMPAT_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> COMPAT_INFOED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 每条日志只输出一次，避免渲染帧内重复刷屏。 */
    private static void warnOnce(String tag, String message) {
        if (COMPAT_WARNED.add(tag)) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-COMPAT] {}", message);
        }
    }

    private static void infoOnce(String tag, String message) {
        if (COMPAT_INFOED.add(tag)) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-COMPAT] {}", message);
        }
    }

    /** 已加载的 Backhand 版本号（诊断用），读取失败返回 "?"。 */
    private static String backhandVersion() {
        try {
            cpw.mods.fml.common.ModContainer mc = Loader.instance().getIndexedModList().get("backhand");
            return mc == null ? "?" : String.valueOf(mc.getVersion());
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * 检查Backhand mod是否已加载
     *
     * @return 如果Backhand mod已加载则返回true，否则返回false
     */
    public static boolean isBackhandLoaded() {
        return BACKHAND_LOADED;
    }

    /**
     * 获取玩家副手物品
     *
     * @param player 玩家实体
     * @return 如果加载了Backhand则返回副手物品，否则返回null
     */
    public static @Nullable ItemStack getOffhandItem(EntityPlayer player) {
        if (!BACKHAND_LOADED) {
            return null;
        }
        try {
            return BackhandUtils.getOffhandItem(player);
        } catch (Throwable t) {
            // 捕获 NoSuchMethodError / NoClassDefFoundError 等 LinkageError：
            // 安装的 Backhand 版本与本 mod 编译所用的 API 不符时安全跳过。
            warnOnce("backhand-api-getoffhand",
                "Backhand API call failed (incompatible version " + backhandVersion() + "?): " + t);
            return null;
        }
    }

    public static void setOffhandItem(EntityPlayer player, @Nullable ItemStack itemStack) {
        if (!BACKHAND_LOADED) {
            return;
        }
        try {
            BackhandUtils.setPlayerOffhandItem(player, itemStack);
        } catch (Throwable t) {
            warnOnce("backhand-api-setoffhand",
                "Backhand API call failed (incompatible version " + backhandVersion() + "?): " + t);
        }
    }

    /**
     * 获取指定手的物品
     *
     * @param player     玩家实体
     * @param isMainHand 是否为主手
     * @return 对应手的物品
     */
    public static ItemStack getItemInHand(EntityPlayer player, boolean isMainHand) {
        if (BACKHAND_LOADED) {
            if (isMainHand) {
                return player.getHeldItem();
            } else {
                return getOffhandItem(player);
            }
        } else {
            // Vanilla 1.7.10 没有副手槽位，副手查询永远返回 null
            return isMainHand ? player.getHeldItem() : null;
        }
    }

    public static boolean swingingArm(EntityPlayer player) {
        if (BACKHAND_LOADED) {
            try {
                return !BackhandUtils.isUsingOffhand(player); // true表示主手
            } catch (Throwable t) {
                // 与 isRenderingOffhand 一致：Backhand 版本不符（方法缺失/签名变化）时
                // 安全回退到主手，避免在每帧 Molang/动画求值路径上抛 LinkageError。
                warnOnce("backhand-api-usingoffhand-swing",
                    "Backhand API call failed (incompatible version " + backhandVersion() + "?): " + t);
                return true;
            }
        }
        return true;
    }

    public static boolean getUsedItemHand(EntityPlayer player) {
        return swingingArm(player);
    }

    /**
     * 检查当前是否正在渲染副手 (防止递归)
     */
    public static boolean isRenderingOffhand(EntityPlayer player) {
        if (!BACKHAND_LOADED) {
            return false;
        }
        try {
            return BackhandUtils.isUsingOffhand(player);
        } catch (Throwable t) {
            warnOnce("backhand-api-usingoffhand",
                "Backhand API call failed (incompatible version " + backhandVersion() + "?): " + t);
            return false;
        }
    }

    /**
     * 手动触发 Backhand 的副手渲染逻辑
     * 应在主手渲染完成后调用
     */
    public static void renderOffhand(float partialTicks) {
        if (!BACKHAND_LOADED) {
            return;
        }
        try {
            ItemRendererHooks.renderOffhandReturn(partialTicks);
        } catch (Throwable t) {
            // 捕获 LinkageError：Backhand 版本不符导致 renderOffhandReturn 缺失时
            // 安全跳过副手渲染（第一人称手部不会因此崩溃），并告警一次。
            warnOnce("backhand-api-renderoffhand",
                "Backhand offhand render skipped (incompatible version " + backhandVersion() + "?): " + t);
        }
    }

    /**
     * 判断副手物品是否应被 YSM 隐藏渲染（Config.HIDDEN_OFFHAND_ITEMS）。
     * 命中列表（modid:itemname 精确匹配，不区分大小写）的物品在副手时：
     * 第一人称手持、第三人称模型手持物品层、HUD 自拍模型均不渲染该物品。
     */
    public static boolean isHiddenOffhandItem(@Nullable ItemStack itemStack) {
        // 功能总开关（模组设置第二页）：关闭时不隐藏任何副手物品。
        if (!Config.HIDE_OFFHAND_DEFOLIAGE_AXE) {
            return false;
        }
        if (!BACKHAND_LOADED) {
            // 未装 Backhand：1.7.10 原版没有副手槽，隐藏功能无从生效，提示一次即可。
            infoOnce("hidden-offhand-no-backhand",
                "Backhand is not installed — HiddenOffhandItems is ignored (vanilla 1.7.10 has no offhand slot).");
            return false;
        }
        if (itemStack == null || itemStack.getItem() == null) {
            return false;
        }
        GameRegistry.UniqueIdentifier uid = GameRegistry.findUniqueIdentifierFor(itemStack.getItem());
        if (uid == null) {
            return false;
        }
        String id = uid.toString().toLowerCase(Locale.ROOT);
        for (String s : Config.HIDDEN_OFFHAND_ITEMS) {
            if (s == null) {
                continue;
            }
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 条目所属 mod 未安装：该条目永远匹配不到，警告一次（正确跳过，不误伤）。
            int colon = trimmed.indexOf(':');
            if (colon > 0) {
                String modId = trimmed.substring(0, colon);
                if (!Loader.isModLoaded(modId)) {
                    warnOnce("hidden-offhand-mod:" + modId,
                        "HiddenOffhandItems entry '" + trimmed + "' is ineffective: mod '" + modId + "' is not installed.");
                    continue;
                }
            }
            if (id.equals(trimmed.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
