package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/**
 * {@code query.position_delta(axis)}：返回当前渲染实体本 tick 的位置增量按轴分量
 * （0=X, 1=Y, 2=Z，单位 blocks）。
 *
 * <p>mclib 的 {@link Function} 实例在解析期构造，拿不到实体上下文，所以这里和
 * {@link QueryPositionFunction} 一样用一份静态槽；区别是槽由
 * {@code AnimationRegister.setEntityQueryValues} **每帧主动写入**。</p>
 *
 * <p><b>不要把写入放进 {@code parser.setValue("query.position_delta", …)} 的 supplier。</b>
 * 那是 {@code LazyVariable}，只在被读到时才求值：模型如果只用函数版
 * {@code q.position_delta(0)}（例如把位移增量累加成轮胎转速），变量版永远没人读，
 * 槽里就会一直是 0，函数版跟着恒为 0 —— 表现为"位移存在但车轮不转"。</p>
 */
public class QueryPositionDeltaFunction extends Function {

    private static double dx = 0;
    private static double dy = 0;
    private static double dz = 0;

    private final IValue[] arguments;

    // 必须实现这个特定签名的构造函数，供 MathBuilder 反射调用
    public QueryPositionDeltaFunction(IValue[] values, String name) throws Exception {
        super(values, name);
        this.arguments = values;
    }

    /** 每帧写入当前位置增量（{@code AnimationRegister.setEntityQueryValues}）。 */
    public static void update(double deltaX, double deltaY, double deltaZ) {
        dx = deltaX;
        dy = deltaY;
        dz = deltaZ;
    }

    /** 归零：GUI 预览渲染前调用（见 {@code AnimationRegister.setPreviewParserValues}）。 */
    public static void clear() {
        update(0.0d, 0.0d, 0.0d);
    }

    /** 按轴取增量：关键帧函数版与脚本/控制器条件版共用这一份。 */
    public static double delta(int axis) {
        if (axis == 0) return dx;
        if (axis == 1) return dy;
        if (axis == 2) return dz;
        return 0.0d;
    }

    @Override
    public double get() {
        // 安全检查：如果没有传参数，默认返回 0
        if (this.arguments == null || this.arguments.length == 0) {
            return 0.0d;
        }
        // 获取模型公式里传进来的第一个参数：query.position_delta(轴)
        return delta((int) this.arguments[0].get());
    }
}
