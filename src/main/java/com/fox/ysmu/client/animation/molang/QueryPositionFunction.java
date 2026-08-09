package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import net.minecraft.entity.Entity;

import com.fox.ysmu.client.particle.ParticleEffectUtil;

/**
 * {@code query.position(axis)}：返回当前渲染实体的绝对位置按轴分量
 * （0=X, 1=Y, 2=Z，单位 blocks），对齐 BE wiki 语义。
 * 实体上下文由 {@link ParticleEffectUtil#setCurrentEntity} 每帧写入
 * （见 {@code AnimationRegister.setParserValue}），无上下文时返回 0。
 * Y 用脚底（boundingBox.minY）对齐 OpenYSM/现代 {@code getY()}，避免 1.7.10
 * 玩家 {@code posY} 含 yOffset(1.62) 导致位置偏高。
 */
public class QueryPositionFunction extends Function {

    public QueryPositionFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 0;
    }

    @Override
    public double get() {
        if (this.args == null || this.args.length == 0) {
            return 0.0D;
        }
        Entity entity = ParticleEffectUtil.getCurrentEntity();
        if (entity == null) {
            return 0.0D;
        }
        int axis = (int) getArg(0);
        switch (axis) {
            case 0: return entity.posX;
            case 1: return entity.boundingBox.minY;
            case 2: return entity.posZ;
            default: return 0.0D;
        }
    }
}
