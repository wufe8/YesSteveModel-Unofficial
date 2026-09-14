package com.fox.ysmu.client.animation.molang;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import com.fox.ysmu.client.particle.ParticleEffectUtil;
import com.fox.ysmu.compat.BaublesCompat;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code ysm.has_any_curios(槽位, 物品id...)} 的 mclib 实现。
 *
 * <p>wiki 语义：某个 Curios 饰品槽里是否存在（匹配指定 id 的）物品。1.7.10 没有 Curios，用它的前身
 * <b>Baubles</b>（GTNH 是 Baubles-Expanded）实现，映射规则与"没有对应物的槽位怎么处理"见
 * {@link BaublesCompat}。参考库里的用法：{@code ysm.has_any_curios('back', 'sophisticatedbackpacks:backpack', ...)}
 * 与 {@code ysm.has_any_curios('spellbook')}（后者无 id = "该槽里有东西即真"）。</p>
 *
 * <p>必须注册：名字不认识时控制器条件只是恒 false，但**关键帧表达式里出现未注册函数会让整条
 * 表达式解析失败、整个动画被丢弃**。</p>
 */
public class QueryHasAnyCuriosFunction extends Function {

    public QueryHasAnyCuriosFunction(IValue[] values, String name) throws Exception {
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
            String slot = stringArg(0);
            List<String> ids = new ArrayList<>();
            for (int i = 1; i < this.args.length; i++) {
                String id = stringArg(i);
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
            return BaublesCompat.hasAnyCurio((EntityPlayer) entity, slot, ids) ? 1.0d : 0.0d;
        } catch (Exception e) {
            return 0.0d;
        }
    }

    /** 字符串实参：解析期池化成 id，这里还原。 */
    private String stringArg(int index) {
        if (index >= this.args.length) {
            return "";
        }
        String value = MolangStringPool.get((int) getArg(index));
        return value == null ? "" : value;
    }
}
