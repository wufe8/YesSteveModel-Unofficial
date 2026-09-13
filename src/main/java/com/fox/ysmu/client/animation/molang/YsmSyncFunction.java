package com.fox.ysmu.client.animation.molang;

import java.util.ArrayList;
import java.util.List;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * {@code ysm.sync(int1, int2...)} 的 mclib 实现（YSM-wiki: molang/script「主动同步」）。
 *
 * <p>关键帧/时间轴通道上的入口：收集数值实参交给 {@link MolangSyncSender} 发起同步，
 * 恒返回 0（wiki：「发起同步后将立刻结束并返回 null」，Molang 的数值形态就是 0）。</p>
 */
public class YsmSyncFunction extends Function {

    public YsmSyncFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    public double get() {
        try {
            List<Double> arguments = new ArrayList<>(this.args.length);
            for (int i = 0; i < this.args.length; i++) {
                arguments.add(getArg(i));
            }
            return MolangSyncSender.request(arguments);
        } catch (Exception e) {
            return 0.0d;
        }
    }
}
