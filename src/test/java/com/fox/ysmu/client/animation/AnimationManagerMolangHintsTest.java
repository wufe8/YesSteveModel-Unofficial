package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;

import org.junit.jupiter.api.Test;

/**
 * {@code ctrl.set_beginning_transition_length} 的取值规则：
 * 没有映射不动控制器默认值；脚本给了值就用脚本值；脚本没给这段动画就回默认值
 * （不能让上一段动画的自定义过渡泄漏过来）。
 */
class AnimationManagerMolangHintsTest {

    @Test
    void noScriptedTransitionLeavesTheControllerAlone() {
        assertNull(AnimationManager.resolveMolangTransition(null, "正常_待命", 4.0d));
    }

    @Test
    void scriptedTransitionOverridesTheDefault() {
        Double ticks = AnimationManager
            .resolveMolangTransition(Collections.singletonMap("正常_待命", 2.0d), "正常_待命", 4.0d);
        assertEquals(2.0d, ticks, 1.0e-9);
    }

    @Test
    void animationWithoutItsOwnValueFallsBackToTheControllerDefault() {
        Double ticks = AnimationManager
            .resolveMolangTransition(Collections.singletonMap("正常_待命", 2.0d), "正常_行走", 4.0d);
        assertEquals(4.0d, ticks, 1.0e-9);
    }
}
