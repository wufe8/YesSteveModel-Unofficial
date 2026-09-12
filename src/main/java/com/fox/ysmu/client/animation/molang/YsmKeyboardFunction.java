package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * {@code ysm.keyboard(<键码>)}：按键是否按下（wiki: molang/script 的输入通道）。
 * <p>
 * 这是**时间轴 / 关键帧 Molang** 这条路上的实现。控制器条件里的同名函数由
 * {@code OpenYsmControllerExpressionEvaluator} 处理；而关键帧表达式走
 * {@code MolangParser}，此前把 {@code ysm.keyboard} 注册成了恒返回 0 的占位实现
 * （和 {@code ctrl.*} 共用），于是所有靠按键驱动的模型（例如用方向键玩的小游戏、
 * 用 Tab 键鸣笛的车辆脚本）都收不到输入。
 * <p>
 * 没有 LWJGL（单测 / 专用服务器）时返回 0，不抛异常。
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
        try {
            return org.lwjgl.input.Keyboard.isKeyDown((int) getArg(0)) ? 1.0d : 0.0d;
        } catch (Throwable ignored) {
            // Keyboard 未初始化（无显示环境）时按"没按下"处理。
            return 0.0d;
        }
    }
}
