package com.fox.ysmu.client.animation.molang;

import java.util.ArrayList;
import java.util.List;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import com.fox.ysmu.compat.KeyboardCompat;

/**
 * {@code ysm.mouse(<按钮>[, <按钮>...])}：鼠标按钮是否按下（wiki: molang 参考表，2.5.0）。
 * <p>
 * 键码是 GLFW 的鼠标按钮号（0=左、1=右、2=中），与 LWJGL2 的编号一致，
 * 所以这里只需要把参数表交给 {@link KeyboardCompat}（多参数同样"任一按下即真"）。
 * 此前 YSMU 完全没有实现这个函数，用到它的控制器条件（例如"按住左/右键投掷"）
 * 会走"未知函数"分支恒为 0。
 */
public class YsmMouseFunction extends Function {

    public YsmMouseFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public double get() {
        if (this.args.length == 0) {
            return 0.0d;
        }
        List<Double> buttons = new ArrayList<>(this.args.length);
        for (IValue value : this.args) {
            buttons.add(value.get());
        }
        return KeyboardCompat.isAnyMouseButtonDown(buttons) ? 1.0d : 0.0d;
    }
}
