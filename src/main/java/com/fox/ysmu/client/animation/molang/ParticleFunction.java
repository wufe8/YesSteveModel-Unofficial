package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import net.minecraft.entity.Entity;

import com.fox.ysmu.client.particle.ParticleEffectUtil;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code particle(id, ox, oy, oz, dx, dy, dz, speed, count, lifetime)} /
 * {@code abs_particle(...)} 的 mclib 实现（动画关键帧 / {@code .molang} 指令路径）。
 *
 * <p>字符串参数（粒子 id）经 {@code MolangParser.replaceStringLiterals} 池化为
 * 整数 id（{@link MolangStringPool}），这里在求值时刻用
 * {@link MolangStringPool#get(int)} 还原字符串。实体上下文由
 * {@link ParticleEffectUtil#setCurrentEntity} 每帧写入
 * （见 {@code AnimationRegister.setParserValue}），在 get() 时读取。</p>
 *
 * <p>mclib Function 是无状态的（解析时经反射创建、每帧重复 get()），因此
 * 不能在构造时捕获实体，必须读取静态上下文。</p>
 */
public class ParticleFunction extends Function {

    private final boolean absolute;

    public ParticleFunction(IValue[] values, String name) throws Exception {
        super(values, name);
        // abs_particle / ysm.abs_particle → 绝对模式
        this.absolute = name != null && name.contains("abs_");
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    /** 限流：每个 id 只打印一次粒子函数调用状态（DEBUG_CONTROLLER）。 */
    private static final java.util.Set<String> LOGGED_PARTICLE_CALL = java.util.Collections
        .newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    @Override
    public double get() {
        try {
            String id = MolangStringPool.get((int) getArg(0));
            Entity entity = ParticleEffectUtil.getCurrentEntity();
            if (com.fox.ysmu.Config.DEBUG_CONTROLLER && id != null) {
                if (LOGGED_PARTICLE_CALL.add(id)) {
                    com.fox.ysmu.ysmu.LOG.info("[YSMU-PFUNC] called: id='{}' (arg0={}) entity={}",
                        id, getArg(0), entity == null ? "null" : entity.getClass().getSimpleName());
                }
            }
            if (id == null || entity == null) {
                if (com.fox.ysmu.Config.DEBUG_CONTROLLER && id == null) {
                    com.fox.ysmu.ysmu.LOG.info("[YSMU-PFUNC] EARLY RETURN: id=null (arg0={})",
                        getArg(0));
                }
                return 0.0d;
            }
            double ox = arg(1);
            double oy = arg(2);
            double oz = arg(3);
            double dx = arg(4);
            double dy = arg(5);
            double dz = arg(6);
            double speed = arg(7);
            int count = (int) arg(8);
            // OpenYSM 默认 lifetime=20
            int lifetime = this.args.length > 9 ? (int) arg(9) : 20;
            boolean ok = ParticleEffectUtil.handleParticle(entity, id,
                ox, oy, oz, dx, dy, dz, speed, count, lifetime, absolute);
            return ok ? 1.0d : 0.0d;
        } catch (Exception e) {
            if (com.fox.ysmu.Config.DEBUG_CONTROLLER) {
                com.fox.ysmu.ysmu.LOG.warn("[YSMU-PFUNC] exception in particle(): {}", e.toString());
            }
            return 0.0d;
        }
    }

    /** 越界参数返回 0（OpenYSM 的缺省默认值）。 */
    private double arg(int index) {
        return getArg(index);
    }
}
