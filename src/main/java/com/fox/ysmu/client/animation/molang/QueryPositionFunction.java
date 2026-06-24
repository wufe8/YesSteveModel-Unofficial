package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * Stub for {@code query.position(axis)}.
 * Returns 0 — full implementation requires player position context.
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
        // TODO: return actual player position on the requested axis when context is available
        return 0;
    }
}
