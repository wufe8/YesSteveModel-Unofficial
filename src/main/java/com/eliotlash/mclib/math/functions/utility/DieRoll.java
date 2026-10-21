package com.eliotlash.mclib.math.functions.utility;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

public class DieRoll extends Function {

    public java.util.Random random;

    public DieRoll(IValue[] values, String name) throws Exception {
        super(values, name);
        this.random = new java.util.Random();
    }

    @Override

    public int getRequiredArguments() {
        return 3;
    }

    @Override

    public double get() {
        // YSM-wiki: molang/ref (math.die_roll) — the sum of `num` rolls in [low, high].
        // The vendored copy never advanced its loop counter (`while (i < num)` with `i`
        // never changed) and rolled `high - high`, so any `num` above 0 froze the client
        // on the render thread instead of returning a number. The roll count is bounded
        // the same way the controller evaluator's is.
        double low = this.getArg(1);
        double high = this.getArg(2);
        int rolls = (int) Math.min(Math.max(0.0d, this.getArg(0)), 100.0d);
        double total = 0;
        for (int i = 0; i < rolls; i++) {
            total += low + Math.random() * (high - low);
        }
        return total;
    }
}
