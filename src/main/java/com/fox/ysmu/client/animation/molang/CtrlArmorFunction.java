package com.fox.ysmu.client.animation.molang;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import com.fox.ysmu.client.particle.ParticleEffectUtil;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code ctrl.armor(slot, matcher)} 的 mclib 实现（YSM-wiki: molang/ref「Ctrl 部分」）。
 *
 * <p>与 {@link CtrlItemFunction} 同样的理由：以前关键帧/脚本里的 {@code ctrl.armor} 命中恒 0 桩函数，
 * 真实现只在控制器条件路径。槽位只认 {@code head}/{@code chest}/{@code legs}/{@code feet}
 * （{@code mainhand}/{@code offhand} 不属于护甲），matcher 只认 {@code $物品ID} 与 {@code empty}
 * —— 与 {@code OpenYsmControllerExpressionEvaluator.armorMatch} 完全一致。</p>
 */
public class CtrlArmorFunction extends Function {

    public CtrlArmorFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 2;
    }

    @Override
    public double get() {
        try {
            Entity entity = ParticleEffectUtil.getCurrentEntity();
            if (!(entity instanceof EntityPlayer)) {
                return 0.0d;
            }
            EntityPlayer player = (EntityPlayer) entity;
            String slot = stringArg(0);
            int index = CtrlItemMatcher.armorSlotIndex(slot);
            if (index < 0 || player.inventory == null) {
                return 0.0d;
            }
            ItemStack stack = player.inventory.armorInventory[index];
            return CtrlItemMatcher.armorMatches(stack, stringArg(1)) ? 1.0d : 0.0d;
        } catch (Exception e) {
            return 0.0d;
        }
    }

    private String stringArg(int index) {
        if (index >= this.args.length) {
            return "";
        }
        String value = MolangStringPool.get((int) getArg(index));
        return value == null ? "" : value;
    }
}
