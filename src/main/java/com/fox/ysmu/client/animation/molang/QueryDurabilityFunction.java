package com.fox.ysmu.client.animation.molang;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import com.fox.ysmu.client.particle.ParticleEffectUtil;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code query.max_durability(slotType)} / {@code query.remaining_durability(slotType)} 的
 * mclib 实现（同一个类按注册名区分，两个名字的语义只差最后一步）。
 *
 * <p>YSM-wiki: molang/ref（2.2.1）—— 返回指定槽位物品的最大耐久 / 剩余耐久。槽位取值见
 * {@link MolangEquipmentSlots}。OpenYSM 的实现是
 * {@code max = stack.getMaxDamage()}、{@code remaining = stack.getMaxDamage() - stack.getDamageValue()}，
 * 因此非耐久物品（最大耐久 0）两者都返回 0。</p>
 *
 * <p>1.7.10 用 {@link ItemStack#getMaxDamage()} 与 {@link ItemStack#getItemDamageForDisplay()}
 * （即耐久条显示的那个损坏值，模组物品可能重写显示值）。</p>
 */
public class QueryDurabilityFunction extends Function {

    public QueryDurabilityFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    public double get() {
        try {
            String slotType = MolangStringPool.get((int) getArg(0));
            Entity entity = ParticleEffectUtil.getCurrentEntity();
            if (!(entity instanceof EntityPlayer)) {
                return 0.0d;
            }
            ItemStack stack = MolangEquipmentSlots.get((EntityPlayer) entity, slotType);
            if (stack == null || stack.getItem() == null) {
                return 0.0d;
            }
            return durability(isRemainingDurability(this.name), stack.getMaxDamage(),
                stack.getItemDamageForDisplay());
        } catch (Exception e) {
            return 0.0d;
        }
    }

    /** 注册名以 {@code remaining_durability} 结尾即"剩余耐久"语义（{@code q.} 缩写已被改写掉）。 */
    static boolean isRemainingDurability(String functionName) {
        return functionName != null && functionName.endsWith("remaining_durability");
    }

    /**
     * 耐久算术（与物品栏解耦，便于单测）。
     *
     * @param remaining true 求剩余耐久、false 求最大耐久
     * @param maxDamage 物品最大耐久，{@code <= 0} 视为不可损坏
     * @param damage    已损失耐久
     * @return 非耐久物品返回 0；剩余耐久下限为 0（OpenYSM 不做这层保护，这里刻意加，
     *         避免超损坏的模组物品给出负值把模型缩放到负数）
     */
    static int durability(boolean remaining, int maxDamage, int damage) {
        if (maxDamage <= 0) {
            return 0;
        }
        return remaining ? Math.max(0, maxDamage - damage) : maxDamage;
    }
}
