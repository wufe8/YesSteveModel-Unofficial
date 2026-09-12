package com.fox.ysmu.client.animation.molang;

import java.util.ArrayList;
import java.util.List;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import com.fox.ysmu.compat.KeyboardCompat;

/**
 * {@code ysm.keyboard(<键码>[, <键码>...])}：按键是否按下。
 * <p>
 * 这是**时间轴 / 关键帧 Molang** 这条路上的实现（控制器条件里的同名函数由
 * {@code OpenYsmControllerExpressionEvaluator} 处理）。两处都委托给 {@link KeyboardCompat}，
 * 因为 wiki 的键码是 **GLFW** 的，而 1.7.10 的 LWJGL2 键码完全不同、表还只有 256 项
 * （GLFW 的上箭头 265 在 LWJGL2 里越界）。
 * <p>
 * 此前这里注册的是恒返回 0 的占位实现（和 {@code ctrl.*} 共用），所以按键驱动的模型
 * 全部收不到输入。
 * <p>
 * wiki：支持多个参数，"只要有一个按键按下，则返回 true"。
 */
public class YsmKeyboardFunction extends Function {

    public YsmKeyboardFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public double get() {
        if (this.args.length == 0) {
            return 0.0d;
        }
        List<Double> codes = new ArrayList<>(this.args.length);
        for (IValue value : this.args) {
            codes.add(value.get());
        }
        return KeyboardCompat.isAnyKeyDown(codes) ? 1.0d : 0.0d;
    }
}
