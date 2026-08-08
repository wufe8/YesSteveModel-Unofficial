package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;

import com.fox.ysmu.client.particle.ParticleEffectUtil;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code ysm.relative_block_name(dx, dy, dz)} 的 mclib 实现。
 *
 * <p>OpenYSM 语义（见 OpenYSM {@code RelativeBlockName}）：返回玩家相对偏移处
 * 方块的注册名（如 {@code minecraft:campfire}）。相对坐标以玩家位置为基准：
 * {@code round((entityPos + delta) - 0.5)}（OpenYSM 同款取整）。</p>
 *
 * <p><b>范围限制</b>：与 OpenYSM 一致，任一轴 |delta| &gt; 5.0 时返回 0（空）。
 * 1.7.10 的 {@code World.getBlock} 走 chunk 缓存为 O(1)，该限制主要用于
 * 防止模型作者误传超大坐标导致无意义的坐标运算。</p>
 *
 * <p><b>字符串比较机制</b>：表达式中的字符串字面量（如 {@code 'minecraft:campfire'}）
 * 在解析时被 {@code MolangParser.replaceStringLiterals} 池化为 int id，因此本函数
 * 返回 {@link MolangStringPool#intern(String)} 的 int id（转为 double），
 * {@code ysm.relative_block_name(...) == 'minecraft:campfire'} 即变为两个 int id
 * 的数值比较，语义等价于 OpenYSM 的字符串相等。</p>
 */
public class RelativeBlockNameFunction extends Function {

    /** 与 OpenYSM 一致的最大相对坐标范围（格）。 */
    private static final double MAX_RANGE = 5.0d;

    public RelativeBlockNameFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 3;
    }

    @Override
    public double get() {
        try {
            double dx = getArg(0);
            double dy = getArg(1);
            double dz = getArg(2);
            if (Math.abs(dx) > MAX_RANGE || Math.abs(dy) > MAX_RANGE || Math.abs(dz) > MAX_RANGE) {
                return 0.0d;
            }
            Entity entity = ParticleEffectUtil.getCurrentEntity();
            if (entity == null || entity.worldObj == null) {
                return 0.0d;
            }
            int x = (int) Math.round((entity.posX + dx) - 0.5d);
            // OpenYSM 用 entity.getY()（脚底）；1.7.10 玩家 posY = 脚底 + yOffset(1.62)，
            // 需用 boundingBox.minY（脚底）对齐，否则相对方块检测整体上移约一个眼睛高度
            // （如站在 campfire 旁会检测到头顶的方块）。
            int y = (int) Math.round((entity.boundingBox.minY + dy) - 0.5d);
            int z = (int) Math.round((entity.posZ + dz) - 0.5d);
            Block block = entity.worldObj.getBlock(x, y, z);
            String name = block == null ? null : Block.blockRegistry.getNameForObject(block);
            if (name == null) {
                return 0.0d;
            }
            return MolangStringPool.intern(name);
        } catch (Exception e) {
            return 0.0d;
        }
    }
}
