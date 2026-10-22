package com.eliotlash.mclib.math.functions.utility;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

public class DieRollInteger extends Function {

    public java.util.Random random;

    public DieRollInteger(IValue[] values, String name) throws Exception {
        super(values, name);
        this.random = new java.util.Random();
    }

    @Override

    public int getRequiredArguments() {
        return 3;
    }

    @Override

    public double get() {
        // YSM-wiki: molang/ref (math.die_roll_integer) — the sum of `num` integer rolls
        // in [low, high]. Same broken loop as DieRoll before the fix; the roll count is
        // bounded like the controller evaluator's.
        int low = (int) this.getArg(1);
        int high = (int) this.getArg(2);
        if (low > high) {
            int swap = low;
            low = high;
            high = swap;
        }
        int rolls = (int) Math.min(Math.max(0.0d, this.getArg(0)), 100.0d);
        // Promote before subtraction: the full int range has 2^32 possible values.
        long width = (long) high - low + 1L;
        long total = 0L;
        for (int i = 0; i < rolls; i++) {
            total += (long) low + (long) (Math.random() * width);
        }
        return total;
    }
}
