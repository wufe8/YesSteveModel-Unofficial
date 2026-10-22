package com.fox.ysmu.client.animation.molang;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.Entity;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;
import com.fox.ysmu.client.particle.ParticleEffectUtil;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/** YSM-wiki: molang/ref — offsets (at most 8 per axis), then one or more exact block registry ids. */
public class RelativeBlockNameAnyFunction extends Function {

    public RelativeBlockNameAnyFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 4;
    }

    @Override
    public double get() {
        List<String> names = new ArrayList<>();
        for (int i = 3; i < args.length; i++) names.add(MolangStringPool.get((int) getArg(i)));
        return matches(ParticleEffectUtil.getCurrentEntity(), getArg(0), getArg(1), getArg(2), names) ? 1.0d : 0.0d;
    }

    public static boolean matches(Entity entity, double dx, double dy, double dz, List<String> names) {
        String blockName = RelativeBlockNameFunction.blockNameAt(entity, dx, dy, dz, 8.0d);
        return matchesName(blockName, names);
    }

    static boolean matchesName(String blockName, List<String> names) {
        if (blockName == null || names == null) return false;
        for (String name : names) if (blockName.equals(name)) return true;
        return false;
    }
}
