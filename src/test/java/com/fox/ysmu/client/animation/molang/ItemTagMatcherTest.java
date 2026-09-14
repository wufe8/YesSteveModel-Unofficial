package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * {@code query.equipped_item_any_tag} / {@code all_tags} 的标签映射（YSM-wiki: molang/ref 1.2.0）。
 *
 * <p>1.7.10 没有数据驱动的物品标签，只回答两类：物品类型标签（走 {@code InnerClassify}）与
 * 材质标签（走矿物词典）。其余必须 false，并在 {@code DebugController} 下提示一次。</p>
 *
 * <p>用 {@code ItemSword} 命中 {@code instanceof} 分支：测试环境没有游戏 bootstrap，未注册物品
 * 的物品名/OreDictionary 查询会 NPE（真实游戏里不存在这种物品）。</p>
 */
class ItemTagMatcherTest {

    private static ItemStack sword() {
        return new ItemStack(new ItemSword(Item.ToolMaterial.EMERALD));
    }

    private static ItemStack unregistered() {
        return new ItemStack(new Item());
    }

    /** 归一化：去命名空间 + 小写。 */
    @Test
    void normalizeDropsNamespaceAndCase() {
        assertEquals("planks", ItemTagMatcher.normalize("minecraft:PLANKS"));
        assertEquals("ingots/iron", ItemTagMatcher.normalize("forge:ingots/iron"));
        assertEquals("planks", ItemTagMatcher.normalize("planks"));
        assertEquals("", ItemTagMatcher.normalize(null));
    }

    /** 物品类型标签：模型里 `#minecraft:swords` 这类写法在 1.7.10 上要真的能命中剑。 */
    @Test
    void itemTypeTagsMatchViaItemClass() {
        assertTrue(ItemTagMatcher.matchesTag(sword(), "minecraft:swords"));
        assertFalse(ItemTagMatcher.matchesTag(sword(), "minecraft:axes"));
        assertTrue(ItemTagMatcher.isSupportedTag("minecraft:swords"));
        assertTrue(ItemTagMatcher.isSupportedTag("minecraft:tridents"));
    }

    /** 单复数都要认（模型作者两种写法都会出现）。 */
    @Test
    void singularAndPluralAreBothAccepted() {
        assertTrue(ItemTagMatcher.matchesTag(sword(), "minecraft:sword"));
        assertTrue(ItemTagMatcher.matchesTag(sword(), "sword"));
    }

    /** 材质标签：`forge:ingots/iron` → `ingotIron`；`glowstone_dust` 这类多词要转成 CamelCase。 */
    @Test
    void camelCaseConversionFollowsOreDictionaryNaming() {
        assertEquals("Iron", ItemTagMatcher.camelCase("iron"));
        assertEquals("GlowstoneDust", ItemTagMatcher.camelCase("glowstone_dust"));
        assertEquals("Redstone", ItemTagMatcher.camelCase("redstone"));
    }

    /** 未知/无法回答的标签：必须 false，并且不算"已支持"（否则不会提示作者）。 */
    @Test
    void unknownTagsAreUnsupportedAndNeverMatch() {
        // 参考库里实际出现的那个：铁魔法模组的 staff 标签，1.7.10 没有对应物。
        assertFalse(ItemTagMatcher.matchesTag(sword(), "irons_spellbooks:staff"));
        assertFalse(ItemTagMatcher.isSupportedTag("irons_spellbooks:staff"));
        assertFalse(ItemTagMatcher.matchesTag(sword(), "minecraft:wool"));
        assertFalse(ItemTagMatcher.isSupportedTag("minecraft:wool_carpets"));
        // 1.17+ 的原矿：认识前缀但这一代无法回答。
        assertFalse(ItemTagMatcher.isSupportedTag("forge:raw_materials/iron"));
    }

    /** null / 空标签 / 无物品一律 false（不能把"没拿东西"当成命中）。 */
    @Test
    void emptyInputsAreNeverAMatch() {
        assertFalse(ItemTagMatcher.matchesTag(null, "minecraft:swords"));
        assertFalse(ItemTagMatcher.matchesTag(sword(), null));
        assertFalse(ItemTagMatcher.matchesTag(sword(), ""));
        assertFalse(ItemTagMatcher.matchesTag(unregistered(), "minecraft:swords"));
    }

    /**
     * 注册后表达式必须能解析 —— 这条是本次改动的核心动机：未注册函数会让整条关键帧表达式
     * 解析失败，整个 animation 被丢弃（参考库里那个模型整段"铁魔法"动画就是这么消失的）。
     */
    @Test
    void expressionWithItemTagQueryParsesAfterRegistration() throws Exception {
        MolangParser.VARIABLES.clear();
        com.fox.ysmu.client.animation.AnimationRegister.registerMolangHooks();
        MolangParser parser = new MolangParser();

        // q. 缩写 + 字符串参数 + 无实体上下文（优雅降级为 0），关键是不抛。
        assertEquals(0.0d, parser
            .parseExpression("q.equipped_item_any_tag('mainhand', 'irons_spellbooks:staff')")
            .get(), 0.0001d);
        assertEquals(0.0d, parser
            .parseExpression("query.equipped_item_all_tags('mainhand', 'minecraft:swords')")
            .get(), 0.0001d);
    }
}
