package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * {@code ysm.bone_pivot_abs('BoneName').x/y/z} 的 mclib 实现。
 *
 * <p>返回骨骼旋转中心在模型空间的绝对位置（模型单位，16 单位 = 1 格），
 * 沿 {@code GeoBone} 父链应用每个骨的完整变换（平移/枢轴/旋转/缩放，
 * 与渲染 {@code MatrixStack.transformBone} 一致），语义对齐 OpenYSM
 * 的 {@code bone_pivot_abs}（世界/模型空间绝对枢轴，含父级旋转与动画姿态）。</p>
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
