package com.fox.ysmu.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.util.ModAvailability;

/**
 * Curios 槽位 → Baubles 槽位类型的映射（{@code ysm.has_any_curios} 的 1.7.10 实现）。
 *
 * <p>Curios 在 1.7.10 上不存在，其前身是 Baubles（GTNH 是 Baubles-Expanded）。映射规则必须明确：
 * Curios 标准槽位映射到对应类型；名字本身就是 Baubles 类型时直接可用；**模组自建槽位
 * （{@code back}/{@code spellbook} 等）没有对应物 → null**，绝不能拿"任意饰品"冒充，
 * 否则戴着无关戒指也会命中断言。真实的 Baubles 调用不在单测范围内（没有游戏上下文）。</p>
 */
class BaublesCompatTest {

    @AfterEach
    void restoreProbe() {
        ModAvailability.clearLoadProbe();
        BaublesCompat.clearReported();
    }

    @Test
    void curiosStandardSlotsMapToBaubleTypes() {
        assertEquals("amulet", BaublesCompat.baubleTypeFor("necklace"));
        assertEquals("ring", BaublesCompat.baubleTypeFor("ring"));
        assertEquals("belt", BaublesCompat.baubleTypeFor("belt"));
        assertEquals("charm", BaublesCompat.baubleTypeFor("charm"));
        assertEquals("head", BaublesCompat.baubleTypeFor("head"));
        assertEquals("body", BaublesCompat.baubleTypeFor("body"));
        assertEquals("gauntlet", BaublesCompat.baubleTypeFor("hands"));
    }

    /** 模型直接写 Baubles-Expanded 的类型名时也要能用（cape/quiver/wings 这些 Curios 没有）。 */
    @Test
    void baubleTypeNamesPassThrough() {
        assertEquals("cape", BaublesCompat.baubleTypeFor("cape"));
        assertEquals("quiver", BaublesCompat.baubleTypeFor("quiver"));
        assertEquals("wings", BaublesCompat.baubleTypeFor("wings"));
        assertEquals("universal", BaublesCompat.baubleTypeFor("universal"));
        assertEquals("gauntlet", BaublesCompat.baubleTypeFor("gauntlet"));
    }

    @Test
    void namesAreCaseInsensitiveAndTrimmed() {
        assertEquals("amulet", BaublesCompat.baubleTypeFor("  NECKLACE "));
        assertEquals("ring", BaublesCompat.baubleTypeFor("Ring"));
    }

    /** 模组自建槽位：1.7.10 上没有对应物，必须返回 null（调用方提示一次后按 false 处理）。 */
    @Test
    void modSpecificSlotsHaveNoAnalogue() {
        assertNull(BaublesCompat.baubleTypeFor("back"));
        assertNull(BaublesCompat.baubleTypeFor("spellbook"));
        assertNull(BaublesCompat.baubleTypeFor("curio"));
        assertNull(BaublesCompat.baubleTypeFor(""));
        assertNull(BaublesCompat.baubleTypeFor("   "));
        assertNull(BaublesCompat.baubleTypeFor(null));
    }


    /**
     * 普通版（Azanor 原版与 GTNH fork）的固定 4 槽布局：0=AMULET、1/2=RING、3=BELT。
     * 证据来自两版 {@code ContainerPlayerExpanded}（逐行一致）与硬编码的 {@code ItemStack[4]}。
     */
    @Test
    void plainBaublesHasAFixedFourSlotLayout() {
        assertArrayEquals(new int[] { 0 }, BaublesCompat.plainSlotIndexesFor("amulet"));
        assertArrayEquals(new int[] { 1, 2 }, BaublesCompat.plainSlotIndexesFor("ring"));
        assertArrayEquals(new int[] { 3 }, BaublesCompat.plainSlotIndexesFor("belt"));
        // 普通版没有这些类型（要 Baubles-Expanded），必须返回 null 以便提示"需要 Expanded"，
        // 而不是拿某个槽位顶替。
        assertNull(BaublesCompat.plainSlotIndexesFor("charm"));
        assertNull(BaublesCompat.plainSlotIndexesFor("head"));
        assertNull(BaublesCompat.plainSlotIndexesFor("gauntlet"));
        assertNull(BaublesCompat.plainSlotIndexesFor(null));
    }

    /** 三个版本 modid 都是 Baubles：不能靠 modid 区分，判据是 Expanded 的类在不在。 */
    @Test
    void apiFlavourIsDetectedByClassPresenceNotByModId() {
        BaublesCompat.clearApiFlavourCache();
        ModAvailability.setLoadProbe(modId -> false);
        assertEquals(0, BaublesCompat.apiFlavour(), "没装 Baubles 时是 UNKNOWN");

        // 测试类路径上就有 Baubles-Expanded（devOnlyNonPublishable）→ 应识别为 EXPANDED，
        // 而不是因为 modid 相同就把普通版也当成 Expanded。
        BaublesCompat.clearApiFlavourCache();
        ModAvailability.setLoadProbe("Baubles"::equals);
        assertEquals(2, BaublesCompat.apiFlavour());
    }


    /**
     * 槽位类型解析不出来、但给了显式 id 时退化为"任意槽里有没有这个物品"（遇到问题再回退）；
     * 没有 id 时无法退化（问的是"这个槽里有没有东西"），只能 false。
     */
    @Test
    void unresolvableSlotFallsBackToScanningAllSlotsOnlyWithExplicitIds() {
        java.util.List<String> ids = java.util.Arrays.asList("some_mod:trinket");
        java.util.List<String> none = Collections.emptyList();

        assertTrue(BaublesCompat.shouldScanAllSlots(ids, null), "槽位解析失败 + 有 id → 退化扫描");
        assertTrue(BaublesCompat.shouldScanAllSlots(ids, new int[0]), "空槽位数组同样退化");
        assertFalse(BaublesCompat.shouldScanAllSlots(ids, new int[] { 1, 2 }), "解析成功就不退化");
        assertFalse(BaublesCompat.shouldScanAllSlots(none, null), "没有 id 无法退化");
        assertFalse(BaublesCompat.shouldScanAllSlots(null, null));
    }

    /** 没装 Baubles 时一律 false（"模组不在"= 正确跳过），且不去碰 Baubles 的类。 */
    @Test
    void withoutBaublesNothingMatches() {
        ModAvailability.setLoadProbe(modId -> false);

        assertFalse(BaublesCompat.isLoaded());
        assertFalse(BaublesCompat.hasAnyCurio(null, "ring", Collections.emptyList()));
    }

    /** 玩家为空（预览/GUI 上下文）时也不能掉进 Baubles 调用。 */
    @Test
    void withoutPlayerNothingMatches() {
        ModAvailability.setLoadProbe("Baubles"::equals);

        assertTrue(BaublesCompat.isLoaded());
        assertFalse(BaublesCompat.hasAnyCurio(null, "ring", Collections.emptyList()));
    }
}
