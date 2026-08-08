package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * {@code ysm.bone_pivot_abs('BoneName').x/y/z} 的 mclib 实现。
 *
 * <p>注意：OpenYSM 的 {@code bone_pivot_abs} 返回世界空间绝对枢轴
 * （经父级层级变换追踪，{@code getPivotAbsX/Y/Z}）。1.7.10 的 GeckoLib
 * {@code IBone} 只有本地枢轴（{@code getPivotX/Y/Z}），此处作为近似实现，
 * 用于把粒子/特效锚定到骨骼本地位置。与 OpenYSM 的绝对坐标差异已在文档注明。</p>
 *
 * <p>表达式中的 {@code .x/.y/.z} 后缀由 {@code MolangParser.rewriteVectorFunction}
 * 重写为 {@code ysm.bone_pivot_abs_x/y/z(...)} 三个注册名，本类按函数名末字符
 * 返回对应轴。</p>
 */
public class BonePivotAbsFunction extends Function {

    public BonePivotAbsFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    public double get() {
        int nameId = (int) getArg(0);
        return MolangPhysicsRuntime.bonePivot(nameId, axis());
    }

    private char axis() {
        return this.name == null || this.name.isEmpty() ? 'x' : this.name.charAt(this.name.length() - 1);
    }
}
