package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * Stub for {@code query.relative_block_has_any_tag(x, y, z, tag)}.
 * <p>
 * This query checks whether a block at a relative position has a given tag.
 * In 1.7.10 the block tag system is very different from modern Minecraft,
 * and the world context is not readily available in the Molang evaluation
 * path, making a full implementation impractical.
 * <p>
 * Always returns 0 (false) — the block is considered absent.
 * TODO: implement proper block tag lookup if world context becomes available.
 */
public class QueryBlockTagFunction extends Function {

    public QueryBlockTagFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public double get() {
        return 0;
    }
}
