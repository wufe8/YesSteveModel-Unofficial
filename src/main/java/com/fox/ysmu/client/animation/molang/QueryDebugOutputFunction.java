package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * {@code query.debug_output(arg1, arg2...)} 的 mclib 实现。
 *
 * <p>YSM-wiki: molang/ref（1.2.0）—— 把参数拼起来输出到聊天框，仅在动画调试模式下有效。
 * 参数按出现顺序**直接拼接**（wiki 的例子把空格写在前一个字符串字面量里：
 * {@code q.debug_output('同步完成！参数为：', args[0], ' 和 ', args[1])}），字符串字面量与
 * 数字混用，格式化和限流都在 {@link MolangDebugOutput}。</p>
 *
 * <p>返回值恒为 0：wiki 的自定义函数示例把它当语句用（{@code q.debug_output('喵');}），
 * 没有可依赖的语义。调试输出未开启时连字符串拼接都不做。</p>
 */
public class QueryDebugOutputFunction extends Function {

    public QueryDebugOutputFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    public double get() {
        try {
            if (!MolangDebugOutput.isEnabled()) {
                return 0.0d;
            }
            StringBuilder message = new StringBuilder();
            for (int i = 0; i < this.args.length; i++) {
                message.append(MolangDebugOutput.formatArg(getArg(i)));
            }
            MolangDebugOutput.emit(message.toString());
        } catch (Exception ignored) {
            // 调试函数本身绝不能把表达式求值带崩。
        }
        return 0.0d;
    }
}
