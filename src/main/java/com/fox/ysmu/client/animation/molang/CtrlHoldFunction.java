package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

public class CtrlHoldFunction extends Function {
    public CtrlHoldFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public double get() {
        return 0; // 暂时总是返回0，避免日志刷屏
    }
}
