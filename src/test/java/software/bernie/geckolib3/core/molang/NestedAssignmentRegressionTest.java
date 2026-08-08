package software.bernie.geckolib3.core.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.eliotlash.mclib.math.IValue;

class NestedAssignmentRegressionTest {

    /** MolangParser.VARIABLES 是全局静态 map，测试间必须隔离，否则 v.wet 等值互相污染。 */
    @BeforeEach
    void clearGlobalVariables() {
        MolangParser.VARIABLES.clear();
    }

    /**
     * 回归测试：OpenYSM 模型 parallel 动画 timeline 里的嵌套赋值
     * {@code (cond) ? (v.wet = 30) : 0} 必须真正写回变量（副作用），
     * 而不是被 mclib 当作纯数值运算（返回右值但不写回）。
     */
    @Test
    void nestedAssignmentInTernaryWritesVariable() throws Exception {
        MolangParser parser = new MolangParser();
        // ysm.eye_in_water 未注册时是 0；先手动注册为 1 模拟"眼睛在水里"
        parser.register(new LazyVariable("ysm.eye_in_water", 1));

        IValue expr = parser.parseExpression("(ysm.eye_in_water) ? (v.wet = 30):0;");
        double result = expr.get();

        assertEquals(30.0D, result, 0.0001D, "ternary 应返回 30");
        // v.wet 应被真正写回
        LazyVariable wet = MolangParser.VARIABLES.get("v.wet");
        assertEquals(30.0D, wet.get(), 0.0001D, "v.wet 应被赋值为 30");
    }

    /**
     * 回归测试：复杂条件嵌套赋值 {@code (a && b < 30) ? (v.wet = v.wet + 1) : 0}
     */
    @Test
    void nestedAssignmentWithComplexCondition() throws Exception {
        MolangParser parser = new MolangParser();
        parser.register(new LazyVariable("query.is_in_water_or_rain", 1));
        parser.register(new LazyVariable("v.wet", 5));

        IValue expr = parser.parseExpression(
            "(query.is_in_water_or_rain && v.wet < 30) ? (v.wet = v.wet + 1):0;");
        double result = expr.get();

        assertEquals(6.0D, result, 0.0001D);
        assertEquals(6.0D, MolangParser.VARIABLES.get("v.wet").get(), 0.0001D);
    }

    /**
     * 回归测试：顶层赋值仍应正常工作。
     * 注意：parseExpression 会 lowerCase 整个表达式，所以 v.fireP 存为 v.firep。
     */
    @Test
    void topLevelAssignmentStillWorks() throws Exception {
        MolangParser parser = new MolangParser();

        IValue expr = parser.parseExpression("v.firep = v.wet ? 0 : math.max(v.firep-0.1,0);");
        expr.get();

        assertEquals(0.0D, MolangParser.VARIABLES.get("v.firep").get(), 0.0001D);
    }

    /**
     * 回归测试：条件为 false 时不执行赋值分支（保持原值）。
     */
    @Test
    void nestedAssignmentNotExecutedWhenConditionFalse() throws Exception {
        MolangParser parser = new MolangParser();
        parser.register(new LazyVariable("ysm.eye_in_water", 0));
        parser.register(new LazyVariable("v.wet", 7));

        IValue expr = parser.parseExpression("(ysm.eye_in_water) ? (v.wet = 30):0;");
        double result = expr.get();

        assertEquals(0.0D, result, 0.0001D);
        assertEquals(7.0D, MolangParser.VARIABLES.get("v.wet").get(), 0.0001D, "条件为 false 时 v.wet 不变");
    }
}
