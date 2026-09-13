package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@code ysm.effect_level(id...)} 的药水名解析。
 *
 * <p>OpenYSM 的 {@code EffectLevel} 用 {@code getAmplifier() + 1}：**1 级药水返回 1**，
 * 所以映射表只负责"名字 → 1.7.10 potion id"，等级换算在函数里做。</p>
 */
class YsmEffectLevelFunctionTest {

    @Test
    void modernNamesMapTo17PotionIds() {
        assertEquals(Integer.valueOf(1), YsmEffectLevelFunction.effectId("speed"));
        assertEquals(Integer.valueOf(9), YsmEffectLevelFunction.effectId("nausea"));
        assertEquals(Integer.valueOf(21), YsmEffectLevelFunction.effectId("health_boost"));
        assertEquals(Integer.valueOf(23), YsmEffectLevelFunction.effectId("saturation"));
    }

    @Test
    void namesAreCaseInsensitiveAndNamespaceIsOptional() {
        assertEquals(Integer.valueOf(10), YsmEffectLevelFunction.effectId("minecraft:REGENERATION"));
        assertEquals(Integer.valueOf(16), YsmEffectLevelFunction.effectId(" Night_Vision "));
    }

    /** 1.20.5 之前的写法 damage_resistance 与 jump 要和现代名等价。 */
    @Test
    void legacyAliasesResolveToo() {
        assertEquals(YsmEffectLevelFunction.effectId("resistance"), YsmEffectLevelFunction.effectId("damage_resistance"));
        assertEquals(YsmEffectLevelFunction.effectId("jump_boost"), YsmEffectLevelFunction.effectId("jump"));
    }

    /** 1.7.10 不存在的效果（1.13+ 才加入）必须返回 null，不能瞎映射到别的药水。 */
    @Test
    void unknownOrModernOnlyEffectsReturnNull() {
        assertNull(YsmEffectLevelFunction.effectId("slow_falling"));
        assertNull(YsmEffectLevelFunction.effectId("conduit_power"));
        assertNull(YsmEffectLevelFunction.effectId("bad_omen"));
        assertNull(YsmEffectLevelFunction.effectId("not_a_potion"));
        assertNull(YsmEffectLevelFunction.effectId(null));
        assertNull(YsmEffectLevelFunction.effectId("  "));
    }

    /** 映射到的 id 在 1.7.10 的药水表里必须真的有药水（0 号是空位，绝不能映射过去）。 */
    @Test
    void mappedIdsResolveToActualPotions() {
        assertNotNull(YsmEffectLevelFunction.potionFor("speed"));
        assertNotNull(YsmEffectLevelFunction.potionFor("saturation"));
        assertNull(YsmEffectLevelFunction.potionFor("slow_falling"));
    }
}
