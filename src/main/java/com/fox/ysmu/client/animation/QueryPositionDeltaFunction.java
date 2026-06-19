package com.fox.ysmu.client.animation;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function; // 请根据 IDE 提示导入正确的 Function 类

public class QueryPositionDeltaFunction extends Function {
    // 定义三个静态变量，用于接收每帧动态传入的玩家位移
    public static double dx = 0;
    public static double dy = 0;
    public static double dz = 0;

    private final IValue[] arguments;

    // 必须实现这个特定签名的构造函数，供 MathBuilder 反射调用
    public QueryPositionDeltaFunction(IValue[] values, String name) throws Exception {
        super(values, name);
        this.arguments = values;
    }

    @Override
    public double get() {
        // 安全检查：如果没有传参数，默认返回 0
        if (this.arguments == null || this.arguments.length == 0) {
            return 0.0;
        }

        // 获取模型公式里传进来的第一个参数：query.position_delta(轴)
        int axis = (int) this.arguments[0].get();

        // 根据参数返回对应的轴向位移增量
        if (axis == 0) return dx; // X轴
        if (axis == 1) return dy; // Y轴
        if (axis == 2) return dz; // Z轴

        return 0.0;
    }
}
