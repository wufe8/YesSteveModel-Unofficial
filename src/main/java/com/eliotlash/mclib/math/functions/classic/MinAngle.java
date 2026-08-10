package com.eliotlash.mclib.math.functions.classic;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * {@code math.min_angle(value)}：把角度归一化到 {@code (-180, 180]} 内的等效角度。
 *
 * <p>语义对齐 Bedrock / OpenYSM（OpenYSM MathBinding.MinAngle）：单参数，
 * {@code value % 360} 后把 {@code [180, 360)} 折到 {@code [-180, 0)}、
 * 把 {@code [-360, -180)} 折到 {@code [0, 180)}。与 GeckoLib 老式双参数
 * "角度差"用法不同——那些应改用 {@code math.lerprotate} / 自定义表达式。</p>
 */
public class MinAngle extends Function {

    public MinAngle(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    public double get() {
        double angle = getArg(0) % 360.0d;
        if (angle >= 180.0d) {
            return angle - 360.0d;
        } else if (angle < -180.0d) {
            return angle + 360.0d;
        }
        return angle;
    }
}
