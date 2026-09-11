package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

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
