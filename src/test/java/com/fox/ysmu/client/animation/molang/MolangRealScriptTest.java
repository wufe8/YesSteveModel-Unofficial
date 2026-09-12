package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

/**
 * 用**真实脚本**当夹具（内置 {@code wine_fox} 包自带的 {@code functions/*.molang}），
 * 覆盖 {@code .molang} 静态提取的规则。
 * <p>
 * 这些脚本是 YSM 实际在用的写法，比手写片段更能暴露解析器的真实行为；而且它们随仓库走
 * （{@code res/} 是 gitignored，测试不能依赖），所以"没有可测模型"时依然能锁住回归。
 */
class MolangRealScriptTest {

    /** 主动画的动画控制脚本：条件分支 + 结尾 {@code state_bypass}。 */
    private static final String CTRL_PRE_MAIN = "molang/wine_fox_15_kluonoa/@player_ctrl_pre_main.molang";
    /** 主动画脚本，用**复合条件**当守卫（{@code (ctrl.walk && ysm.input_vertical < 0.1)}）。 */
    private static final String CTRL_MAIN_WALK_BACK =
        "molang/wine_fox_18_wedding/\u7b80\u5355\u5012\u8d70\u52a8\u753b@player_ctrl_main.molang";

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = MolangRealScriptTest.class.getResourceAsStream("/" + name)) {
            if (in != null) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        }
        // Gradle 的 test 工作目录就是项目根目录（内置模型也是如此被当作夹具的）。
        return Files.readAllBytes(Paths.get("src/test/resources", name));
    }

    private static List<String> conditions(Map<String, List<Pair<String, String>>> map, String state) {
        List<Pair<String, String>> alternatives = map.get(state);
        assertNotNull(alternatives, "no conditional alternatives for state " + state);
        List<String> result = new ArrayList<>();
        for (Pair<String, String> alt : alternatives) {
            result.add(alt.getKey());
        }
        return result;
    }

    private static Pair<String, String> pair(String condition, String animation) {
        return Pair.of(condition, animation);
    }

    @Test
    void builtInPreMainScriptMapsEveryStateItOverrides() throws IOException {
        Map<String, String> mapping = MolangFunctionParser.parseStateToAnimationMap(fixture(CTRL_PRE_MAIN));

        // 每个 ctrl.<state> ? { ... } 块的第一条 set_animation 就是该状态的动画。
        assertEquals("\u6b63\u5e38_\u5f85\u547d", mapping.get("idle"), "idle");
        assertEquals("\u6b63\u5e38_\u884c\u8d70", mapping.get("walk"), "walk");
        assertEquals("\u6b63\u5e38_\u5954\u8dd1", mapping.get("run"), "run");
        assertEquals("\u6b63\u5e38_\u8df3\u8dc3", mapping.get("jump"), "jump");
        assertEquals("\u6b63\u5e38_\u98de\u884c", mapping.get("fly"), "fly");
        assertEquals("\u6b63\u5e38_\u9798\u7fc5\u98de\u884c", mapping.get("elytra_fly"), "elytra_fly");
        assertEquals("\u6b63\u5e38_\u6c34\u4e2d\u6f02\u6d6e", mapping.get("swim_stand"), "swim_stand");
        assertEquals("\u6b63\u5e38_\u6e38\u6cf3", mapping.get("swim"), "swim");
        assertEquals("\u6b63\u5e38_\u53d7\u4f24", mapping.get("attacked"), "attacked");
        assertEquals("\u6b63\u5e38_\u6b7b\u4ea1", mapping.get("death"), "death");
        assertEquals(10, mapping.size(), "no unexpected states: " + mapping.keySet());
    }

    /**
     * 脚本结尾的 {@code return ctrl.state_bypass;} 是**整段脚本**的兜底（"其它情况交回内置逻辑"），
     * 不是某个状态的无操作标记：它不能把上面的映射吃掉。状态级的 bypass 见
     * {@link #inBlockStateBypassDropsTheDefaultButKeepsConditionalAlternatives()}。
     */
    @Test
    void trailingTopLevelBypassDoesNotDisableTheStateMappings() throws IOException {
        String script = new String(fixture(CTRL_PRE_MAIN), StandardCharsets.UTF_8);
        assertTrue(script.trim()
            .endsWith("return ctrl.state_bypass;"), "the fixture must still end in a bypass");

        Map<String, String> mapping = MolangFunctionParser.parseStateToAnimationMap(fixture(CTRL_PRE_MAIN));
        assertFalse(mapping.isEmpty());
        assertEquals("\u6b63\u5e38_\u5f85\u547d", mapping.get("idle"));
    }

    /**
     * 注释必须先剥掉：条件里混进 {@code // 玩家空闲时} 之后，任何表达式求值都会失败，
     * 于是"吃饱时播正常待命"这条永远不成立，只能靠后面的默认映射兜底。
     */
    @Test
    void conditionsAreExtractedWithoutComments() throws IOException {
        Map<String, List<Pair<String, String>>> conditional =
            MolangFunctionParser.parseConditionalAnimations(fixture(CTRL_PRE_MAIN));

        assertEquals(
            Arrays.asList(
                pair("!v.show_car&&!(ysm.food_level<=6)", "\u6b63\u5e38_\u5f85\u547d"),
                pair("!v.show_car&&(ysm.food_level<=6)&&(ysm.food_level>2)&&!ysm.rendering_in_inventory",
                    "\u4f4e\u7535\u91cf"),
                pair("!v.show_car&&(ysm.food_level<=2)&&!ysm.rendering_in_inventory", "\u7535\u91cf\u8fc7\u4f4e"),
                pair("v.show_car", "\u5f00\u8f66_\u5f85\u547d")),
            conditional.get("idle"));
    }

    @Test
    void everyMovementStateHasBothTheWalkingAndTheDrivingAlternative() throws IOException {
        Map<String, List<Pair<String, String>>> conditional =
            MolangFunctionParser.parseConditionalAnimations(fixture(CTRL_PRE_MAIN));

        assertEquals(Arrays.asList("!v.show_car", "v.show_car"), conditions(conditional, "walk"));
        assertEquals(pair("v.show_car", "\u5f00\u8f66_\u884c\u8d70"), conditional.get("walk").get(1));
        assertEquals(pair("v.show_car", "\u5f00\u8f66_\u5954\u8dd1"), conditional.get("run").get(1));
        assertEquals(pair("v.show_car", "\u5f00\u8f66_\u6b7b\u4ea1"), conditional.get("death").get(1));
        assertNull(conditional.get("sleep"), "the script does not touch sleep");
    }

    /**
     * 复合条件守卫（{@code (ctrl.walk && ysm.input_vertical < 0.1) ? { ... } }）既不是纯
     * {@code ctrl.<state>} 条件（所以进不了状态映射），块内也没有嵌套的三元（所以原来的
     * 条件提取也抓不到）——两条路都漏掉，这个"倒走"动画就永远不会播。
     * 正确的落点是把它当成该状态的**条件替代动画**：守卫比状态本身更窄，先判它。
     */
    @Test
    void compoundGuardBecomesAConditionalAlternative() throws IOException {
        byte[] data = fixture(CTRL_MAIN_WALK_BACK);

        assertTrue(MolangFunctionParser.parseStateToAnimationMap(data).isEmpty(),
            "a compound guard must not be mistaken for a plain ctrl.<state> block");

        Map<String, List<Pair<String, String>>> conditional = MolangFunctionParser.parseConditionalAnimations(data);
        assertEquals(1, conditions(conditional, "walk").size(), "one alternative: " + conditional.get("walk"));
        String guard = conditions(conditional, "walk").get(0);
        assertTrue(guard.startsWith("(") && guard.endsWith(")"),
            "the guard keeps its parentheses: <" + guard + ">");
        assertTrue(guard.contains("ctrl.walk"), "the guard keeps its state check: <" + guard + ">");
        assertTrue(guard.contains("ysm.input_vertical"), "the guard keeps its direction check: <" + guard + ">");
        assertEquals("walkBack", conditional.get("walk").get(0).getValue());
    }

    /**
     * wiki：{@code ctrl.state_bypass} = "当前控制逻辑无操作，交回内置控制逻辑"。
     * 因此写在一个 {@code ctrl.<state>} 块**自己的一层**里的 bypass，表示该状态不由脚本接管：
     * 默认映射要去掉（否则脚本说"我不管"，YSMU 却照搬块里第一条 set_animation），
     * 但块内**有条件守卫**的替代动画仍然有效。
     */
    @Test
    void inBlockStateBypassDropsTheDefaultButKeepsConditionalAlternatives() {
        byte[] data = script(
            "ctrl.idle ? {\n"
                + "    ctrl.set_animation('a');\n"
                + "    return ctrl.state_bypass;\n"
                + "};\n"
                + "ctrl.walk ? {\n"
                + "    v.car ? { ctrl.set_animation('b'); };\n"
                + "    return ctrl.state_bypass;\n"
                + "};\n"
                + "ctrl.run ? {\n"
                + "    ctrl.set_animation('c');\n"
                + "    return ctrl.state_continue;\n"
                + "};\n");

        Map<String, String> mapping = MolangFunctionParser.parseStateToAnimationMap(data);
        assertNull(mapping.get("idle"), "idle hands back to the built-in logic");
        assertNull(mapping.get("walk"), "walk hands back too (its animation is conditional)");
        assertEquals("c", mapping.get("run"), "run is an ordinary override");
        assertEquals(1, mapping.size(), "only run stays mapped: " + mapping);

        // 条件替代动画不受 bypass 影响：脚本在 v.car 时确实要覆盖。
        Map<String, List<Pair<String, String>>> conditional = MolangFunctionParser.parseConditionalAnimations(data);
        assertEquals(Arrays.asList(pair("v.car", "b")), conditional.get("walk"));
    }

    /**
     * {@code state_stop} / {@code state_pause} 与 {@code state_bypass} 不同：它们仍然表示
     * "脚本接管了这个状态"（只是播放方式不同），所以默认映射必须保留。
     */
    @Test
    void stateStopAndStatePauseStillCountAsOverrides() {
        byte[] data = script(
            "ctrl.idle ? {\n"
                + "    ctrl.set_animation('blink');\n"
                + "    return ctrl.state_stop;\n"
                + "};\n"
                + "ctrl.sneak ? {\n"
                + "    ctrl.set_animation('freeze');\n"
                + "    return ctrl.state_pause;\n"
                + "};\n");

        Map<String, String> mapping = MolangFunctionParser.parseStateToAnimationMap(data);
        assertEquals("blink", mapping.get("idle"));
        assertEquals("freeze", mapping.get("sneak"));
    }

    /**
     * 整段脚本就是一个控制器主体：状态块的 bypass 只对它**自己那一层**生效。
     * 嵌套在 {@code q.all_animations_finished ? { ... return ctrl.state_bypass; }} 里的
     * bypass 只是一个分支，不能把该状态在整个脚本里的覆盖全部作废。
     */
    @Test
    void nestedStateBypassIsOnlyABranch() {
        byte[] data = script(
            "ctrl.use ? {\n"
                + "    v.combo ? { ctrl.set_animation('combo'); };\n"
                + "    q.all_animations_finished ? {\n"
                + "        ctrl.indicate_reload();\n"
                + "        return ctrl.state_bypass;\n"
                + "    };\n"
                + "};\n");

        assertEquals("combo", MolangFunctionParser.parseStateToAnimationMap(data).get("use"));
    }

    private static byte[] script(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
