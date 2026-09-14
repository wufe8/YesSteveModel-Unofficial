package com.fox.ysmu.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.util.ModAvailability;

/**
 * {@code Config.HIDDEN_OFFHAND_ITEMS}（副手隐藏名单）的匹配规则。
 *
 * <p>默认名单是 {@code ExtraUtilities:defoliageAxe}（Extra Utilities 的治愈之斧）。匹配忽略大小写与
 * 首尾空白；条目所属 mod 没装时该条目永远匹配不到，应告警一次后跳过（"正确跳过，不误伤"）——
 * 存在性判断走 {@link ModAvailability}，不看版本号。</p>
 */
class BackhandCompatTest {

    @AfterEach
    void restoreProbe() {
        ModAvailability.clearLoadProbe();
    }

    @Test
    void defaultEntryMatchesItsItem() {
        ModAvailability.setLoadProbe("ExtraUtilities"::equals);

        assertTrue(BackhandCompat.matchesHiddenList("ExtraUtilities:defoliageAxe"));
        // 忽略大小写
        assertTrue(BackhandCompat.matchesHiddenList("extrautilities:defoliageaxe"));
        assertTrue(BackhandCompat.matchesHiddenList("EXTRAUTILITIES:DEFOLIAGEAXE"));
    }

    @Test
    void unrelatedItemsDoNotMatch() {
        ModAvailability.setLoadProbe("ExtraUtilities"::equals);

        assertFalse(BackhandCompat.matchesHiddenList("minecraft:diamond_sword"));
        assertFalse(BackhandCompat.matchesHiddenList(null));
        assertFalse(BackhandCompat.matchesHiddenList(""));
    }

    /** 条目所属 mod 没装 → 命中不了（并会告警一次），不能因为"名字对上了"就返回 true。 */
    @Test
    void entriesFromMissingModsNeverMatch() {
        ModAvailability.setLoadProbe(modId -> false);

        assertFalse(BackhandCompat.matchesHiddenList("ExtraUtilities:defoliageAxe"));
    }

    /** 名字必须整体相等，不能是前缀/子串匹配（避免误伤同名物品）。 */
    @Test
    void matchingIsExactNotSubstring() {
        ModAvailability.setLoadProbe("ExtraUtilities"::equals);

        assertFalse(BackhandCompat.matchesHiddenList("ExtraUtilities:defoliageAxeReinforced"));
        assertFalse(BackhandCompat.matchesHiddenList("ExtraUtilities:defoliage"));
    }
}
