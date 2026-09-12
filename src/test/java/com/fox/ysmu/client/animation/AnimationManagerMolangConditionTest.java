package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

/**
 * {@code .molang} 名称映射的第二档：**条件替代动画**的选取规则。
 * <p>
 * 规则来自脚本本身的写法：一个 {@code ctrl.<state>} 块里可能有多个条件分支，脚本顺序就是
 * 优先级，第一个成立的那条决定动画名；一条都不成立才回落到该状态的默认映射
 * （{@link AnimationManager#pickConditionalAnimation} 的调用点）。
 */
class AnimationManagerMolangConditionTest {

    private static Pair<String, String> pair(String condition, String animation) {
        return Pair.of(condition, animation);
    }

    @Test
    void theFirstMatchingConditionWins() {
        List<Pair<String, String>> alternatives = Arrays.asList(
            pair("v.show_car", "driving"),
            pair("!v.show_car", "walking"));

        assertEquals("driving", pick(alternatives, "v.show_car"));
        assertEquals("walking", pick(alternatives, "!v.show_car"));
    }

    @Test
    void noMatchFallsThroughToNullSoTheCallerCanUseTheDefault() {
        List<Pair<String, String>> alternatives = Collections.singletonList(pair("v.never", "unused"));

        assertNull(pick(alternatives, "v.other"));
    }

    @Test
    void missingAlternativesAndNullEntriesAreIgnored() {
        assertNull(AnimationManager.pickConditionalAnimation(null, condition -> true));
        assertNull(AnimationManager.pickConditionalAnimation(Collections.emptyList(), condition -> true));
        assertNull(AnimationManager.pickConditionalAnimation(
            Collections.singletonList(pair(null, null)), condition -> true));
        assertEquals("b", AnimationManager.pickConditionalAnimation(
            Arrays.asList(pair("a", null), pair("b", "b")), condition -> true));
    }

    /** 只有一个条件成立时，不管它在列表的哪个位置都取它。 */
    private static String pick(List<Pair<String, String>> alternatives, String... trueConditions) {
        Set<String> truthy = new HashSet<>(Arrays.asList(trueConditions));
        return AnimationManager.pickConditionalAnimation(alternatives, truthy::contains);
    }
}
