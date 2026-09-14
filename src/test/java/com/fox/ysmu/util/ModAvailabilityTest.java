package com.fox.ysmu.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.molang.ItemTagMatcher;

/**
 * "模组不在"与"我们没实现"的分类。
 *
 * <p>1.7.10 上没有的模组会被模型用命名空间引用（{@code irons_spellbooks:staff} 这类标签）：
 * 这种情况永远匹配不到，属于**正确跳过**；把它报成"没实现"会让人去补一个永远用不上的映射。
 * 反过来，来源可用（原版/已装模组）却没映射的标签才是真缺口。</p>
 *
 * <p>{@link ModAvailability#setLoadProbe} 让这里不依赖游戏启动（{@code Loader} 在单测里不可用）。</p>
 */
class ModAvailabilityTest {

    @AfterEach
    void restoreProbe() {
        ModAvailability.clearLoadProbe();
    }

    @Test
    void namespaceIsParsedFromNamespacedId() {
        assertEquals("irons_spellbooks", ModAvailability.namespaceOf("irons_spellbooks:staff"));
        assertEquals("minecraft", ModAvailability.namespaceOf("minecraft:planks"));
        assertEquals("tacz", ModAvailability.namespaceOf("TACZ:gun"));
        assertEquals("", ModAvailability.namespaceOf("planks"), "没有命名空间时无法归属模组");
        assertEquals("", ModAvailability.namespaceOf(":staff"), "冒号在开头不算命名空间");
        assertEquals("", ModAvailability.namespaceOf(null));
    }

    @Test
    void platformNamespacesAreAlwaysAvailable() {
        assertTrue(ModAvailability.isPlatformNamespace("minecraft"));
        assertTrue(ModAvailability.isPlatformNamespace("Forge"));
        assertTrue(ModAvailability.isPlatformNamespace("c"));
        assertFalse(ModAvailability.isPlatformNamespace("tacz"));
        assertFalse(ModAvailability.isPlatformNamespace(null));
    }

    /** 装了/没装由探针决定；平台与无命名空间的一律算"可用"。 */
    @Test
    void unavailabilityOnlyAppliesToMissingModNamespaces() {
        ModAvailability.setLoadProbe(modId -> "tacz".equals(modId));

        assertTrue(ModAvailability.isUnavailable("irons_spellbooks:staff"), "该 mod 没装 → 正确跳过");
        assertFalse(ModAvailability.isUnavailable("tacz:modern_kinetic_gun"), "该 mod 装了 → 不是跳过理由");
        assertFalse(ModAvailability.isUnavailable("minecraft:planks"), "原版永远可用");
        assertFalse(ModAvailability.isUnavailable("c:ingots/iron"), "通用约定恒可用");
        assertFalse(ModAvailability.isUnavailable("planks"), "没有命名空间不能算成模组不在");
        assertFalse(ModAvailability.isUnavailable(null));
    }


    /** 能力探测：用来代替版本号校验（类在不在才是最可靠的判据）。 */
    @Test
    void classPresenceProbeDistinguishesCapability() {
        assertTrue(ModAvailability.isClassPresent("java.lang.String"));
        // 测试类路径上就有 Baubles-Expanded（devOnlyNonPublishable）
        assertTrue(ModAvailability.isClassPresent("baubles.api.expanded.BaubleExpandedSlots"));
        assertFalse(ModAvailability.isClassPresent("com.example.NoSuchClass"));
        assertFalse(ModAvailability.isClassPresent(null));
        assertFalse(ModAvailability.isClassPresent(""));
    }

    /** 参考库里真实出现的那一例：模组不在 → 标签分类是 MOD_ABSENT，而且匹配恒 false。 */
    @Test
    void absentModTagIsClassifiedAsModAbsent() {
        ModAvailability.setLoadProbe(modId -> false);

        assertEquals(ItemTagMatcher.Status.MOD_ABSENT,
            ItemTagMatcher.statusOf("irons_spellbooks:staff"));
        assertFalse(ItemTagMatcher.matchesTag(
            new net.minecraft.item.ItemStack(new net.minecraft.item.ItemSword(
                net.minecraft.item.Item.ToolMaterial.EMERALD)),
            "irons_spellbooks:staff"));
    }

    /** 同一个标签，模组装上了就是我们真的没映射 → UNMAPPED（值得补映射的信号）。 */
    @Test
    void sameTagBecomesUnmappedWhenTheModIsInstalled() {
        ModAvailability.setLoadProbe("irons_spellbooks"::equals);

        assertEquals(ItemTagMatcher.Status.UNMAPPED,
            ItemTagMatcher.statusOf("irons_spellbooks:staff"));
    }

    /** 能回答的标签优先于"模组不在"判定（将来给某个模组的标签加了映射也不会被跳过）。 */
    @Test
    void supportedTagsWinOverTheModCheck() {
        ModAvailability.setLoadProbe(modId -> false);

        assertEquals(ItemTagMatcher.Status.SUPPORTED, ItemTagMatcher.statusOf("minecraft:swords"));
        assertEquals(ItemTagMatcher.Status.SUPPORTED, ItemTagMatcher.statusOf("forge:ingots/iron"));
    }

    /** 平台命名空间但没映射 → UNMAPPED（真缺口，例如 1.7.10 没有数据驱动标签的那部分）。 */
    @Test
    void platformTagWithoutMappingIsUnmapped() {
        ModAvailability.setLoadProbe(modId -> false);

        assertEquals(ItemTagMatcher.Status.UNMAPPED, ItemTagMatcher.statusOf("minecraft:wool_carpets"));
    }
}
