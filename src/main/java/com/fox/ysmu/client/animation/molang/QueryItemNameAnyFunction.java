package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * Stub for {@code query.is_item_name_any(item_name)}.
 * <p>
 * This query checks whether the player's held item matches any of the given
 * item names.  A full implementation would require item registry name lookup,
 * which is non-trivial in the current Molang evaluation context.
 * <p>
 * Always returns 0 (false) — the item is considered non-matching.
 * TODO: implement proper item name matching if needed.
 */
public class QueryItemNameAnyFunction extends Function {

    public QueryItemNameAnyFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public double get() {
        return 0;
    }
}
