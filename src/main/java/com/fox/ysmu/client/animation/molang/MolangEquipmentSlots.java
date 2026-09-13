package com.fox.ysmu.client.animation.molang;

import java.util.Locale;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.fox.ysmu.compat.BackhandCompat;

/**
 * wiki: molang/ref —— 装备槽位名的统一解析。
 *
 * <p>OpenYSM 的 {@code MolangUtils.SLOT_MAP} 只认 {@code chest}/{@code feet}/{@code head}/
 * {@code legs}/{@code mainhand}/{@code offhand} 六个名字（全部小写、无命名空间）。
 * {@code query.is_item_name_any}、{@code query.max_durability}、
 * {@code query.remaining_durability}、{@code ysm.equipped_enchantment_level} 都用同一套，
 * 所以集中在这里，避免每个函数各抄一遍 switch 后出现行为漂移。</p>
 *
 * <p>1.7.10 没有 {@code EquipmentSlot}，映射到原版物品栏：主手 {@link EntityPlayer#getHeldItem()}、
 * 副手走 {@link BackhandCompat}（无 Backhand 时返回 null）、护甲数组下标 3/2/1/0。</p>
 */
public final class MolangEquipmentSlots {

    private MolangEquipmentSlots() {}

    /** 槽位名归一化：去掉命名空间并转小写；{@code null} 原样返回。 */
    public static String normalize(String slotType) {
        if (slotType == null) {
            return null;
        }
        String name = stripNamespace(slotType).trim();
        return name.isEmpty() ? null : name.toLowerCase(Locale.ROOT);
    }

    /** 取该槽位的物品；槽位名不认识或未装备时返回 {@code null}。 */
    public static ItemStack get(EntityPlayer player, String slotType) {
        if (player == null || player.inventory == null) {
            return null;
        }
        String slot = normalize(slotType);
        if (slot == null) {
            return null;
        }
        switch (slot) {
            case "mainhand":
                return player.getHeldItem();
            case "offhand":
                return BackhandCompat.getOffhandItem(player);
            case "head":
                return player.inventory.armorInventory[3];
            case "chest":
                return player.inventory.armorInventory[2];
            case "legs":
                return player.inventory.armorInventory[1];
            case "feet":
                return player.inventory.armorInventory[0];
            default:
                return null;
        }
    }

    /** 去掉命名空间（{@code minecraft:apple} → {@code apple}），用于附魔/药水等 id 比较。 */
    public static String stripNamespace(String name) {
        if (name == null) {
            return null;
        }
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }
}
