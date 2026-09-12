package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.controller.OpenYsmControllerExpressionEvaluator.Argument;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerExpressionEvaluator.ConditionScope;
import com.fox.ysmu.client.animation.molang.MolangFunctionParser;

/**
 * {@code .molang} 条件求值：喂**真实脚本里**的条件，断言选中哪条动画。
 * <p>
 * 这里完全不需要 Minecraft —— 编译后的表达式只依赖 {@link ConditionScope} 的两个查询，
 * 所以"吃饱时播正常待命、饿了播低电量"这种原先只能开游戏看的行为，现在能在
 * {@code gradlew test} 里锁住。
 */
class OpenYsmConditionEvaluationTest {

    private static final String CTRL_PRE_MAIN = "molang/wine_fox_15_kluonoa/@player_ctrl_pre_main.molang";
    private static final String CTRL_MAIN_WALK_BACK =
        "molang/wine_fox_18_wedding/\u7b80\u5355\u5012\u8d70\u52a8\u753b@player_ctrl_main.molang";

    /** 按名字读变量（缺省 0，与 Molang 的"未定义即 0"一致）。 */
    private static ConditionScope scope(Map<String, Double> variables) {
        return new ConditionScope() {

            @Override
            public double variableValue(String name) {
                Double value = variables.get(name);
                return value == null ? 0.0d : value;
            }

            @Override
            public double functionValue(String name, List<Argument> arguments) {
                return 0.0d;
            }
        };
    }

    /** 变量名/取值交替传入（取值给 int 或 double 都行）。 */
    private static Map<String, Double> vars(Object... namesAndValues) {
        Map<String, Double> map = new HashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            map.put(String.valueOf(namesAndValues[i]), ((Number) namesAndValues[i + 1]).doubleValue());
        }
        return map;
    }

    private static boolean eval(String expression, Map<String, Double> variables) {
        return OpenYsmControllerExpressionEvaluator.evaluateCondition(expression, scope(variables));
    }

    private static String pick(List<Pair<String, String>> alternatives, Map<String, Double> variables) {
        assertNotNull(alternatives, "no alternatives");
        ConditionScope scope = scope(variables);
        for (Pair<String, String> alternative : alternatives) {
            if (OpenYsmControllerExpressionEvaluator.evaluateCondition(alternative.getKey(), scope)) {
                return alternative.getValue();
            }
        }
        return null;
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = OpenYsmConditionEvaluationTest.class.getResourceAsStream("/" + name)) {
            if (in != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        }
        return Files.readAllBytes(Paths.get("src/test/resources", name));
    }

    /**
     * 一期的验收：脚本里"吃饱 / 饿 / 很饿 / 开车"四条闲置分支原本因为有运算符而**永远为假**，
     * 于是只有默认映射那条能生效；现在它们按真实变量取值各选各的动画。
     */
    @Test
    void theBuiltInIdleBranchesPickTheRightAnimationPerHungerLevel() throws IOException {
        List<Pair<String, String>> idle =
            MolangFunctionParser.parseConditionalAnimations(fixture(CTRL_PRE_MAIN)).get("idle");
        assertNotNull(idle, "the fixture must still extract the idle branches");
        assertEquals(4, idle.size(), "four idle branches: " + idle);

        // 吃饱、没开车、不在物品栏预览里 → 正常_待命
        assertEquals("\u6b63\u5e38_\u5f85\u547d",
            pick(idle, vars("v.show_car", 0, "ysm.food_level", 10, "ysm.rendering_in_inventory", 0)));
        // 饿（<=6 且 >2）→ 低电量
        assertEquals("\u4f4e\u7535\u91cf",
            pick(idle, vars("v.show_car", 0, "ysm.food_level", 4, "ysm.rendering_in_inventory", 0)));
        // 很饿（<=2）→ 电量过低
        assertEquals("\u7535\u91cf\u8fc7\u4f4e",
            pick(idle, vars("v.show_car", 0, "ysm.food_level", 1, "ysm.rendering_in_inventory", 0)));
        // 开车 → 开车_待命（不论饱食度）
        assertEquals("\u5f00\u8f66_\u5f85\u547d",
            pick(idle, vars("v.show_car", 1, "ysm.food_level", 10, "ysm.rendering_in_inventory", 0)));
    }

    /**
     * 两条饿肚子分支都带 {@code !ysm.rendering_in_inventory} 守卫，所以在物品栏预览里
     * **一条条件都不成立**。此时必须还有东西可播：调用方回落到默认映射
     * （{@code idle → 正常_待命}）。这也是默认映射不能因为"块里的 set_animation 是带守卫的"
     * 就整个删掉的原因 —— 删了会掉进主谓词的状态回退循环，站立时可能播出行走动画。
     */
    @Test
    void whenNoBranchMatchesTheDefaultMappingStillCoversTheState() throws IOException {
        byte[] data = fixture(CTRL_PRE_MAIN);
        List<Pair<String, String>> idle = MolangFunctionParser.parseConditionalAnimations(data).get("idle");

        assertNull(pick(idle, vars("v.show_car", 0, "ysm.food_level", 1, "ysm.rendering_in_inventory", 1)),
            "no conditional branch covers the hungry in-inventory case");
        assertEquals("\u6b63\u5e38_\u5f85\u547d", MolangFunctionParser.parseStateToAnimationMap(data).get("idle"),
            "the default mapping is the fallback the caller uses");
    }

    @Test
    void theDrivingAlternativeWinsForMovementStates() throws IOException {
        Map<String, List<Pair<String, String>>> conditional =
            MolangFunctionParser.parseConditionalAnimations(fixture(CTRL_PRE_MAIN));

        assertEquals("\u5f00\u8f66_\u884c\u8d70", pick(conditional.get("walk"), vars("v.show_car", 1)));
        assertEquals("\u6b63\u5e38_\u884c\u8d70", pick(conditional.get("walk"), vars("v.show_car", 0)));
        // 未定义的 v.* 视为 0（Molang 语义），因此 !v.show_car 成立。
        assertEquals("\u6b63\u5e38_\u884c\u8d70", pick(conditional.get("walk"), vars()));
    }

    /**
     * 复合条件守卫（内置包里的"倒走"脚本）：{@code ctrl.walk} 与方向键一起判断。
     * 这条守卫以前既进不了状态映射、也进不了条件映射，所以那个动画永远不会播。
     */
    @Test
    void theCompoundGuardOfTheWalkBackScriptResolves() throws IOException {
        Map<String, List<Pair<String, String>>> conditional =
            MolangFunctionParser.parseConditionalAnimations(fixture(CTRL_MAIN_WALK_BACK));
        List<Pair<String, String>> walk = conditional.get("walk");
        assertNotNull(walk);
        String guard = walk.get(0).getKey();

        assertTrue(eval(guard, vars("ctrl.walk", 1, "ysm.input_vertical", -1)));
        assertTrue(eval(guard, vars("ctrl.walk", 1, "ysm.input_vertical", 0.05)));
        // 正在前进（input_vertical >= 0.1）不是倒走
        assertFalse(eval(guard, vars("ctrl.walk", 1, "ysm.input_vertical", 1)));
        // 没在走也不是倒走
        assertFalse(eval(guard, vars("ctrl.walk", 0, "ysm.input_vertical", -1)));
    }

    @Test
    void blankConditionIsTrueAndFailingEvaluationIsFalse() {
        assertTrue(OpenYsmControllerExpressionEvaluator.evaluateCondition("", scope(vars())));
        assertTrue(OpenYsmControllerExpressionEvaluator.evaluateCondition("   ", scope(vars())));

        // 没有玩家/事件上下文时不猜：宁可不用替代动画
        assertFalse(OpenYsmControllerExpressionEvaluator.evaluateCondition("v.x", null, null));
    }

    @Test
    void operatorsFollowMolangSemantics() {
        assertTrue(eval("!v.show_car&&!(ysm.food_level<=6)", vars("v.show_car", 0, "ysm.food_level", 9)));
        assertFalse(eval("!v.show_car&&!(ysm.food_level<=6)", vars("v.show_car", 0, "ysm.food_level", 6)));
        assertTrue(eval("(v.a>1||v.b==2)&&v.c!=0", vars("v.a", 0, "v.b", 2, "v.c", 5)));
        assertFalse(eval("(v.a>1||v.b==2)&&v.c!=0", vars("v.a", 0, "v.b", 2, "v.c", 0)));
        // 除零按 0 处理，不应抛异常
        assertFalse(eval("1/v.zero > 100", vars()));
    }
}
