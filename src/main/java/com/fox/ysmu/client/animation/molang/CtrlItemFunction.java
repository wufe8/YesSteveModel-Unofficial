package com.fox.ysmu.client.animation.molang;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import com.fox.ysmu.client.particle.ParticleEffectUtil;
import com.fox.ysmu.compat.BackhandCompat;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code ctrl.hold(slot, matcher)} / {@code ctrl.use} / {@code ctrl.swing} 的 mclib 实现
 * （YSM-wiki: molang/ref「Ctrl 部分」）。
 *
 * <p>以前这三个名字注册的是恒 0 的 {@code CtrlHoldFunction} 桩，所以**关键帧、timeline 与
 * {@code .molang} 脚本**里的 `ctrl.hold('mainhand', '$minecraft:apple')` 永远不成立；真实现只在
 * 控制器条件路径（{@code OpenYsmControllerExpressionEvaluator}）。这里用抽取出来的
 * {@link CtrlItemMatcher} 补上，两条路径的判断规则因此完全一致。</p>
 *
 * <p>参数与 wiki 一致：第一个是手（{@code mainhand}/{@code offhand}），第二个是 matcher
 * （{@code $物品ID} / {@code #物品tag} / {@code :类别} / {@code empty}，空串 = 手里有东西）。
 * {@code ctrl.use} 额外要求"正在使用该手"，{@code ctrl.swing} 额外要求"该手正在挥动"。</p>
 */
public class CtrlItemFunction extends Function {

    public CtrlItemFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    public double get() {
        try {
            Entity entity = ParticleEffectUtil.getCurrentEntity();
            if (!(entity instanceof EntityPlayer)) {
                return 0.0d;
            }
            EntityPlayer player = (EntityPlayer) entity;
            String hand = stringArg(0);
            String matcher = stringArg(1);
            boolean mainHand = !"offhand".equals(hand);
            if (!mainHand && !BackhandCompat.isBackhandLoaded()) {
                return 0.0d;
            }
            String function = this.name == null ? "" : this.name;
            if (function.endsWith("use")
                && (!player.isUsingItem() || BackhandCompat.getUsedItemHand(player) != mainHand)) {
                return 0.0d;
            }
            if (function.endsWith("swing")
                && (!player.isSwingInProgress || BackhandCompat.swingingArm(player) != mainHand)) {
                return 0.0d;
            }
            ItemStack stack = handStack(player, mainHand);
            return CtrlItemMatcher.matches(stack, matcher) ? 1.0d : 0.0d;
        } catch (Exception e) {
            return 0.0d;
        }
    }

    /** 字符串实参：解析期被池化成 id，这里还原（空串/缺参数都是空串）。 */
    private String stringArg(int index) {
        if (index >= this.args.length) {
            return "";
        }
        String value = MolangStringPool.get((int) getArg(index));
        return value == null ? "" : value;
    }

    static ItemStack handStack(EntityPlayer player, boolean mainHand) {
        return mainHand ? player.getHeldItem() : BackhandCompat.getOffhandItem(player);
    }
}
