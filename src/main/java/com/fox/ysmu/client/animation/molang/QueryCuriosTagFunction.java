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

/** YSM-wiki: molang/misc — one item in the named slot must satisfy all/any of the supplied tags. */
public class QueryCuriosTagFunction extends Function {

    public QueryCuriosTagFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 2;
    }

    @Override
    public double get() {
        Entity entity = ParticleEffectUtil.getCurrentEntity();
        if (!(entity instanceof EntityPlayer)) return 0.0d;
        List<String> tags = new ArrayList<>();
        for (int i = 1; i < args.length; i++) tags.add(MolangStringPool.get((int) getArg(i)));
        return BaublesCompat.hasAnyCurioWithTags((EntityPlayer) entity,
            MolangStringPool.get((int) getArg(0)), tags, name.endsWith("all_tags")) ? 1.0d : 0.0d;
    }
}
