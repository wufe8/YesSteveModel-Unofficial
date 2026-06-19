package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

public class QueryPositionDeltaFunction extends Function {
    public QueryPositionDeltaFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public double get() {
        return 0; // 占位，避免日志刷屏
    }
}
