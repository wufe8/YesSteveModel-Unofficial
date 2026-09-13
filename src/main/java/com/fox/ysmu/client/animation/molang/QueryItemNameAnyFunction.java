package com.fox.ysmu.client.animation.molang;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import com.fox.ysmu.client.particle.ParticleEffectUtil;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code query.is_item_name_any(slotType, id1, id2...)} 的 mclib 实现。
 *
 * <p>YSM-wiki: molang/ref —— 判断指定槽位（{@code mainhand}/{@code offhand}/{@code head}/
 * {@code chest}/{@code legs}/{@code feet}）里物品的 id 是否命中列表中的任意一个。</p>
 *
 * <p>字符串参数（slotType 与每个 id）在解析时被 {@code MolangParser.replaceStringLiterals}
 * 池化成 int id，这里用 {@link MolangStringPool#get(int)} 还原。物品 id 取自 FML 物品注册表
 * （形如 {@code minecraft:diamond_sword}），比较忽略大小写且允许任意一侧省略命名空间
 * （{@code 'diamond_sword'} 等价于 {@code 'minecraft:diamond_sword'}）。</p>
 *
 * <p>物品标签类查询（{@code equipped_item_any_tag} 等）不在本类范围内：1.7.10 没有数据驱动的
 * 物品标签，只能用矿物词典近似。</p>
 */
public class QueryItemNameAnyFunction extends Function {

    public QueryItemNameAnyFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 2;
    }

    @Override
    public double get() {
        try {
            String slotType = MolangStringPool.get((int) getArg(0));
            if (slotType == null) {
                return 0.0d;
            }
            Entity entity = ParticleEffectUtil.getCurrentEntity();
            if (!(entity instanceof EntityPlayer)) {
                return 0.0d;
            }
            ItemStack stack = getStack((EntityPlayer) entity, slotType.toLowerCase(java.util.Locale.ROOT));
            if (stack == null || stack.getItem() == null) {
                return 0.0d;
            }
            String registryName = Item.itemRegistry.getNameForObject(stack.getItem());
            if (registryName == null) {
                return 0.0d;
            }
            for (int i = 1; i < this.args.length; i++) {
                String candidate = MolangStringPool.get((int) getArg(i));
                if (matches(registryName, candidate)) {
                    return 1.0d;
                }
            }
            return 0.0d;
        } catch (Exception e) {
            return 0.0d;
        }
    }

    /** 物品 id 比较：忽略大小写，允许任意一侧省略命名空间。 */
    static boolean matches(String registryName, String candidate) {
        if (registryName == null || candidate == null || candidate.isEmpty()) {
            return false;
        }
        String left = registryName.toLowerCase(java.util.Locale.ROOT);
        String right = candidate.toLowerCase(java.util.Locale.ROOT);
        return left.equals(right) || stripNamespace(left).equals(stripNamespace(right));
    }

    private static String stripNamespace(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    /** 与 {@code ysm.equipped_enchantment_level} 使用同一套槽位语义（见 {@link MolangEquipmentSlots}）。 */
    private static ItemStack getStack(EntityPlayer player, String slotType) {
        return MolangEquipmentSlots.get(player, slotType);
    }
}
