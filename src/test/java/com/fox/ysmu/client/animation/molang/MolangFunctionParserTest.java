package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;

import org.junit.jupiter.api.Test;

/**
 * {@code .molang} 动画控制脚本里 {@code ctrl.set_animation} 周围的 ctrl.* 调用：
 * {@code ctrl.set_beginning_transition_length(秒)} 与 {@code ctrl.indicate_reload}
 * （见 wiki「自定义函数」页的动画控制一节）。
 */
class MolangFunctionParserTest {

    private static byte[] script(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void transitionLengthIsCapturedPerAnimation() {
        MolangFunctionParser.AnimationHints hints = MolangFunctionParser.parseAnimationHints(script(
            "ctrl.idle ? {\n"
                + "    !v.show_car ? {\n"
                + "        ctrl.set_beginning_transition_length(0.1);\n"
                + "        ctrl.set_animation('正常_待命');\n"
                + "        return ctrl.state_continue;\n"
                + "    };\n"
                + "    v.show_car ? {\n"
                + "        ctrl.set_beginning_transition_length(0);\n"
                + "        ctrl.set_animation('开车_待命');\n"
                + "        return ctrl.state_continue;\n"
                + "    };\n"
                + "};\n"));

        assertEquals(2.0d, hints.transitionTicks.get("正常_待命"), 1.0e-9);
        assertEquals(0.0d, hints.transitionTicks.get("开车_待命"), 1.0e-9);
        assertFalse(hints.transitionTicks.containsKey("开车_行走"));
    }

    @Test
    void anAnimationWithoutItsOwnTransitionDoesNotInheritThePreviousOne() {
        MolangFunctionParser.AnimationHints hints = MolangFunctionParser.parseAnimationHints(script(
            "ctrl.idle ? {\n"
                + "    ctrl.set_beginning_transition_length(0.25);\n"
                + "    ctrl.set_animation('a');\n"
                + "    ctrl.set_animation('b');\n"
                + "};\n"));

        assertEquals(5.0d, hints.transitionTicks.get("a"), 1.0e-9);
        assertFalse(hints.transitionTicks.containsKey("b"));
    }

    @Test
    void indicateReloadIsCapturedForTheFollowingAnimation() {
        MolangFunctionParser.AnimationHints hints = MolangFunctionParser.parseAnimationHints(script(
            "ctrl.use ? {\n"
                + "    ctrl.indicate_reload();\n"
                + "    ctrl.set_animation('发射');\n"
                + "    return ctrl.state_continue;\n"
                + "};\n"));

        assertTrue(hints.reloadAnimations.contains("发射"));
        assertFalse(hints.reloadAnimations.contains("待命"));
    }

    /**
     * 复合守卫 + 内层条件动画的写法（碰墙抬手的形状）：只能登记**内层**条件，不能把外层守卫
     * 当成替代动画的守卫 —— 否则外层守卫（ctrl.run || ctrl.walk）在走路时无条件成立，内层的
     * 相对方块/朝向判定会被整个绕过（空旷超平坦四个方向都没墙也会命中 defWall）。
     */
    @Test
    void compoundGuardWithInnerConditionsKeepsOnlyTheInnerConditions() {
        Map<String, List<Pair<String, String>>> parsed = MolangFunctionParser.parseConditionalAnimations(script(
            "ctrl.run || ctrl.walk ? { "
                + "((v.east == false) && (query.cardinal_facing_2d == 5)) ? { ctrl.set_animation('defWall'); return ctrl.state_continue; }; "
                + "(v.north == false) && (query.cardinal_facing_2d == 2) ? { ctrl.set_animation('defWall'); return ctrl.state_continue; }; "
                + "}; return ctrl.state_stop;"));

        List<Pair<String, String>> entries = parsed.get("walk");
        assertFalse(entries == null || entries.isEmpty(), "内层条件必须被登记: " + parsed);
        for (Pair<String, String> entry : entries) {
            assertTrue(entry.getKey()
                .contains("cardinal_facing_2d"),
                "守卫里必须保留墙/朝向判定，不能只剩外层 ctrl.run||ctrl.walk: " + entry.getKey());
            assertEquals("defWall", entry.getValue());
        }
        assertEquals(2, entries.size());
    }

    /** 复合守卫 + **直接** set_animation（倒走动画的形状）仍要登记，否则会丢掉这条兜底。 */
    @Test
    void compoundGuardWithDirectSetAnimationIsStillCaptured() {
        Map<String, List<Pair<String, String>>> parsed = MolangFunctionParser.parseConditionalAnimations(script(
            "(ctrl.walk && (ysm.input_vertical < 0.1)) ? { ctrl.set_animation('walkBack'); return ctrl.state_continue; }; "
                + "return ctrl.state_bypass;"));

        assertEquals(1, parsed.get("walk")
            .size(), parsed.toString());
        assertEquals("walkBack", parsed.get("walk")
            .get(0)
            .getValue());
        assertTrue(parsed.get("walk")
            .get(0)
            .getKey()
            .contains("input_vertical"));
    }

    @Test
    void twoArgumentSetAnimationIsParsed() {
        // wiki 的第二种写法：ctrl.set_animation('walk', ctrl.loop)
        Map<String, String> mapping = MolangFunctionParser.parseStateToAnimationMap(script(
            "ctrl.walk ? {\n"
                + "    ctrl.set_animation('正常_行走', ctrl.loop);\n"
                + "    return ctrl.state_continue;\n"
                + "};\n"));

        assertEquals("正常_行走", mapping.get("walk"));
    }
}
