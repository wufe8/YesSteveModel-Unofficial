package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.junit.jupiter.api.Test;

/**
 * {@code ctrl.hold/use/swing/armor} 的物品匹配规则（YSM-wiki: molang/ref「Ctrl 部分」）。
 *
 * <p>这段规则以前只存在于控制器条件路径，关键帧/时间轴/{@code .molang} 脚本里的同名调用命中的是
 * 恒 0 桩函数，所以同样的表达式"在控制器里能命中、在脚本里永远不成立"。抽成
 * {@link CtrlItemMatcher} 后两条路径共用，这里锁住 matcher 的写法语义。</p>
 *
 * <p>不用 {@code net.minecraft.init.Items}：没有游戏 bootstrap 时那些静态字段是 null。
 * 直接 {@code new Item()} + {@code setUnlocalizedName} 就够——注册名查不到时 {@code itemId}
 * 返回空串，正好用来测"未注册物品"的分支。</p>
 */
class CtrlItemMatcherTest {

    private static ItemStack stack() {
        return new ItemStack(new Item());
    }

    /** 空 matcher = "手里有东西"；这是 wiki 里省略第二个参数时的写法。 */
    @Test
    void emptyMatcherMeansHoldAnything() {
        assertTrue(CtrlItemMatcher.matches(stack(), ""));
        assertTrue(CtrlItemMatcher.matches(stack(), null));
        assertFalse(CtrlItemMatcher.matches(null, ""));
    }

    @Test
    void emptyMatcherKeywordMeansHoldNothing() {
        assertTrue(CtrlItemMatcher.matches(null, "empty"));
        assertFalse(CtrlItemMatcher.matches(stack(), "empty"));
    }

    /** {@code $id}：只认注册名，且忽略大小写/命名空间写法差异由调用方保证（此处比较全名）。 */
    @Test
    void dollarIdMatchesRegistryNameOnly() {
        // 未注册物品的注册名是空串，所以匹配 "$" 之外的具体 id 一定不成立。
        assertFalse(CtrlItemMatcher.matches(stack(), "$minecraft:apple"));
        assertTrue(CtrlItemMatcher.matches(stack(), "$"));
    }

    /** {@code #tag}：1.7.10 没有数据驱动的物品标签，必须恒 false（不能假装命中）。 */
    @Test
    void tagMatcherIsAlwaysFalseOnLegacy() {
        assertFalse(CtrlItemMatcher.matches(stack(), "#minecraft:axes"));
        assertFalse(CtrlItemMatcher.matches(stack(), "#"));
    }

    /** 护甲槽只认 wiki 的四个名字。 */
    @Test
    void armorSlotIndexFollowsWikiNames() {
        assertEquals(3, CtrlItemMatcher.armorSlotIndex("head"));
        assertEquals(2, CtrlItemMatcher.armorSlotIndex("chest"));
        assertEquals(1, CtrlItemMatcher.armorSlotIndex("legs"));
        assertEquals(0, CtrlItemMatcher.armorSlotIndex("feet"));
        assertEquals(3, CtrlItemMatcher.armorSlotIndex("HEAD"));
        // mainhand/offhand 不是护甲槽，未知槽位亦然。
        assertEquals(-1, CtrlItemMatcher.armorSlotIndex("mainhand"));
        assertEquals(-1, CtrlItemMatcher.armorSlotIndex("offhand"));
        assertEquals(-1, CtrlItemMatcher.armorSlotIndex("nonsense"));
        assertEquals(-1, CtrlItemMatcher.armorSlotIndex(null));
    }

    @Test
    void armorMatcherFollowsSameRulesAsHands() {
        assertTrue(CtrlItemMatcher.armorMatches(null, "empty"));
        assertFalse(CtrlItemMatcher.armorMatches(stack(), "empty"));
        assertFalse(CtrlItemMatcher.armorMatches(stack(), "#minecraft:swords"));
        // 空 matcher 在护甲上返回 false（与 handMatch 一致：护甲必须写明 matcher）。
        assertFalse(CtrlItemMatcher.armorMatches(stack(), ""));
    }

    /**
     * 类别匹配：已知类别不走"注册名包含"的模糊回退。
     *
     * <p>用 {@code ItemSword} 命中 {@code instanceof} 分支，避免走
     * {@code InnerClassify} 里依赖物品注册名/OreDictionary 的路径（测试环境没有 bootstrap，
     * 未注册物品在那里会 NPE —— 真实游戏里每个物品都有 unlocalizedName，不构成问题）。</p>
     */
    @Test
    void knownCategoriesDoNotFallBackToSubstringMatch() {
        ItemStack sword = new ItemStack(new net.minecraft.item.ItemSword(
            net.minecraft.item.Item.ToolMaterial.EMERALD));

        assertTrue(CtrlItemMatcher.categoryMatches(sword, "x:anything", "sword"));
        // sword 的物品类型不是 axe，且 axe 属于"已知类别"→ 不允许退回名字包含判断。
        assertFalse(CtrlItemMatcher.categoryMatches(sword, "x:battle_axe_like", "axe"));
    }

    /** trident/spear 互相兼容（1.7.10 没有三叉戟，模组的"长枪"应当也能匹配 trident）。 */
    @Test
    void spearAndTridentAreInterchangeable() {
        ItemStack sword = new ItemStack(new net.minecraft.item.ItemSword(
            net.minecraft.item.Item.ToolMaterial.EMERALD));

        assertTrue(CtrlItemMatcher.categoryMatches(sword, "some_mod:steel_trident", "trident"));
        assertTrue(CtrlItemMatcher.categoryMatches(sword, "some_mod:steel_spear", "spear"));
        assertFalse(CtrlItemMatcher.categoryMatches(sword, "some_mod:steel_sword", "trident"));
    }
}
