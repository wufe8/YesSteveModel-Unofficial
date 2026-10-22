package com.fox.ysmu.client.animation.molang;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangStringPool;

public class QueryBiomeTagFunction extends Function {

    private static final Set<String> REPORTED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public QueryBiomeTagFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    public double get() {
        for (int i = 0; i < args.length; i++) reportUnsupported(MolangStringPool.get((int) getArg(i)));
        return 0.0d;
    }

    public static boolean matchesTags(List<String> tags) {
        if (tags != null) for (String tag : tags) reportUnsupported(tag);
        return false;
    }

    private static void reportUnsupported(String tag) {
        if (tag == null || tag.trim().isEmpty()) return;
        String key = tag.trim().toLowerCase(Locale.ROOT);
        // YSM-wiki: molang/misc — even 1.16 has only a placeholder; BiomeDictionary is not modern biome tags.
        if (REPORTED.add(key)) ysmu.LOG.warn(
            "[YSMU-COMPAT] biome tag '{}' has no data-driven biome-tag analogue on 1.7.10; treating as false", key);
    }

    static int reportedTagCount() {
        return REPORTED.size();
    }

    static void clearReportedTags() {
        REPORTED.clear();
    }
}
