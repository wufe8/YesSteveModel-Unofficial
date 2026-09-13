package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code query.max_durability} / {@code query.remaining_durability} 的取值规则。
 *
 * <p>OpenYSM（{@code MaxDurability}/{@code RemainingDurability}）语义：
 * {@code max = getMaxDamage()}、{@code remaining = getMaxDamage() - damage}，
 * 槽位名不认识或物品不可损坏时返回 0。</p>
 */
class QueryDurabilityFunctionTest {

    @Test
    void maxDurabilityIsTheItemMaximum() {
        assertEquals(1561, QueryDurabilityFunction.durability(false, 1561, 0));
        assertEquals(1561, QueryDurabilityFunction.durability(false, 1561, 900));
    }

    @Test
    void remainingDurabilitySubtractsDamage() {
        assertEquals(1561, QueryDurabilityFunction.durability(true, 1561, 0));
        assertEquals(661, QueryDurabilityFunction.durability(true, 1561, 900));
    }

    /** 非耐久物品（最大耐久 0）两者都必须是 0，否则模型会拿到虚假耐久。 */
    @Test
    void nonDamageableItemsReportZero() {
        assertEquals(0, QueryDurabilityFunction.durability(false, 0, 0));
        assertEquals(0, QueryDurabilityFunction.durability(true, 0, 0));
    }

    /** 超损坏的模组物品不能给出负剩余耐久。 */
    @Test
    void remainingDurabilityNeverGoesNegative() {
        assertEquals(0, QueryDurabilityFunction.durability(true, 100, 120));
    }

    /** 两个名字共用一个类，必须按注册名区分语义。 */
    @Test
    void functionNameSelectsSemantics() {
        assertTrue(QueryDurabilityFunction.isRemainingDurability("query.remaining_durability"));
        assertTrue(QueryDurabilityFunction.isRemainingDurability("q.remaining_durability"));
        assertFalse(QueryDurabilityFunction.isRemainingDurability("query.max_durability"));
        assertFalse(QueryDurabilityFunction.isRemainingDurability(null));
    }

    /** 槽位名：忽略大小写、允许任意一侧写命名空间。 */
    @Test
    void slotNamesAreNormalized() {
        assertEquals("mainhand", MolangEquipmentSlots.normalize("MAINHAND"));
        assertEquals("offhand", MolangEquipmentSlots.normalize("minecraft:offhand"));
        assertEquals("head", MolangEquipmentSlots.normalize(" Head "));
        assertNull(MolangEquipmentSlots.normalize(null));
        assertNull(MolangEquipmentSlots.normalize("   "));
    }
}
