package com.fox.ysmu.client.animation.molang;

import java.util.HashMap;
import java.util.Map;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.fox.ysmu.client.particle.ParticleEffectUtil;
import com.fox.ysmu.compat.BackhandCompat;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code ysm.equipped_enchantment_level(slotType, enchantmentId...)} 的 mclib 实现。
 *
 * <p>OpenYSM 语义（见 OpenYSM {@code EquippedEnchantmentLevel}）：
 * 返回指定槽位物品上所有给定附魔的等级之和。slotType 取值：
 * mainhand / offhand / head / chest / legs / feet。</p>
 *
 * <p>1.7.10 附魔没有 {@code ForgeRegistries.ENCHANTMENTS} 注册表，这里用
 * 1.19+ 注册名（snake_case，如 {@code fire_aspect}）→ 1.7.10 {@link Enchantment}
 * id（{@code effectId}）的映射表，再经 {@link EnchantmentHelper#getEnchantmentLevel}
 * 取等级。</p>
 *
 * <p>字符串参数（slotType / 附魔名）在解析时被 {@code MolangParser.replaceStringLiterals}
 * 池化为 int id，本类在 get() 时用 {@link MolangStringPool#get(int)} 还原。</p>
 */
public class EquippedEnchantmentLevelFunction extends Function {

    /** 1.19+ 附魔注册名（snake_case）→ 1.7.10 附魔 id（effectId）。 */
    private static final Map<String, Integer> ENCHANTMENT_IDS = new HashMap<>();

    static {
        // 保护类
        ENCHANTMENT_IDS.put("protection", 0);
        ENCHANTMENT_IDS.put("fire_protection", 1);
        ENCHANTMENT_IDS.put("feather_falling", 2);
        ENCHANTMENT_IDS.put("blast_protection", 3);
        ENCHANTMENT_IDS.put("projectile_protection", 4);
        ENCHANTMENT_IDS.put("respiration", 5);
        ENCHANTMENT_IDS.put("aqua_affinity", 6);
        ENCHANTMENT_IDS.put("thorns", 7);
        // 剑类
        ENCHANTMENT_IDS.put("sharpness", 16);
        ENCHANTMENT_IDS.put("smite", 17);
        ENCHANTMENT_IDS.put("bane_of_arthropods", 18);
        ENCHANTMENT_IDS.put("knockback", 19);
        ENCHANTMENT_IDS.put("fire_aspect", 20);
        ENCHANTMENT_IDS.put("looting", 21);
        // 工具类
        ENCHANTMENT_IDS.put("efficiency", 32);
        ENCHANTMENT_IDS.put("silk_touch", 33);
        ENCHANTMENT_IDS.put("unbreaking", 34);
        ENCHANTMENT_IDS.put("fortune", 35);
        // 弓类
        ENCHANTMENT_IDS.put("power", 48);
        ENCHANTMENT_IDS.put("punch", 49);
        ENCHANTMENT_IDS.put("flame", 50);
        ENCHANTMENT_IDS.put("infinity", 51);
        // 钓鱼类
        ENCHANTMENT_IDS.put("luck_of_the_sea", 61);
        ENCHANTMENT_IDS.put("lure", 62);
    }

    public EquippedEnchantmentLevelFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 2;
    }

    /** 限流：每次附魔查询打印一次结果（DEBUG_CONTROLLER）。 */
    private static final java.util.Set<String> LOGGED_ENCH = java.util.Collections
        .newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    @Override
    public double get() {
        try {
            String slotType = MolangStringPool.get((int) getArg(0));
            Entity entity = ParticleEffectUtil.getCurrentEntity();
            if (slotType == null || !(entity instanceof EntityPlayer)) {
                if (com.fox.ysmu.Config.DEBUG_CONTROLLER) {
                    ysmu.LOG.info("[YSMU-ENCH] slotType={} entity={} -> 0 (bad ctx)",
                        slotType, entity == null ? "null" : entity.getClass().getSimpleName());
                }
                return 0.0d;
            }
            ItemStack stack = getStack((EntityPlayer) entity, slotType);
            if (stack == null) {
                if (com.fox.ysmu.Config.DEBUG_CONTROLLER) {
                    ysmu.LOG.info("[YSMU-ENCH] slotType={} -> 0 (no stack)", slotType);
                }
                return 0.0d;
            }
            int total = 0;
            StringBuilder dbg = new StringBuilder();
            for (int i = 1; i < this.args.length; i++) {
                String enchName = MolangStringPool.get((int) getArg(i));
                Integer enchId = enchName == null ? null : ENCHANTMENT_IDS.get(stripNamespace(enchName));
                if (enchId != null && enchId >= 0 && enchId < Enchantment.enchantmentsList.length) {
                    Enchantment ench = Enchantment.enchantmentsList[enchId];
                    if (ench != null) {
                        int lvl = EnchantmentHelper.getEnchantmentLevel(ench.effectId, stack);
                        total += lvl;
                        if (com.fox.ysmu.Config.DEBUG_CONTROLLER) {
                            dbg.append("[").append(enchName).append(" id=").append(enchId)
                               .append(" lvl=").append(lvl).append("]");
                        }
                    }
                }
            }
            if (com.fox.ysmu.Config.DEBUG_CONTROLLER) {
                String key = slotType + "|" + stack.getUnlocalizedName() + "|" + dbg;
                if (LOGGED_ENCH.add(key)) {
                    ysmu.LOG.info("[YSMU-ENCH] slot={} stack={} ench={} total={}",
                        slotType, stack.getUnlocalizedName(), dbg, total);
                }
            }
            return total;
        } catch (Exception e) {
            return 0.0d;
        }
    }

    private static String stripNamespace(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static ItemStack getStack(EntityPlayer player, String slotType) {
        switch (slotType) {
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
}
