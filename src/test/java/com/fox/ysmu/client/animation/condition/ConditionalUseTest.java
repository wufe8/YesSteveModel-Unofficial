package com.fox.ysmu.client.animation.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.item.EnumAction;

import org.junit.jupiter.api.Test;

/**
 * 条件使用动画（{@code use_mainhand:*} / {@code use_offhand:*}）的命中顺序与剑格挡别名。
 *
 * <p>YSM-wiki: animation/condition/arm「使用动画」——上游 {@code use_mainhand:block} 来自 1.20 的
 * {@code UseAnim.BLOCK}（只有盾牌返回它）。1.7.10 没有 UseAnim，剑右键格挡的 use action 也是
 * {@code EnumAction.block}，所以 {@code :block} 会同时命中剑和盾；想让剑格挡单独覆盖，要声明
 * 优先级更高的物品类别名 {@code use_mainhand:sword}（该名字在上游永不触发，因为 1.20 的剑没有
 * use action），或者语义更明确的等价别名 {@code use_mainhand:sword_block}。</p>
 *
 * <p>这个测试锁住 {@link ConditionalUse#classifyExtra}：类别名优先于 use action 名，且
 * {@code :sword_block} 在 {@code :sword} 未声明时等价生效。顺序一旦被改反，模型作者显式声明的
 * 剑格挡动画就会被 {@code :block} 顶掉，剑/盾格挡又混在一起。</p>
 *
 * <p>不构造 {@code EntityPlayer}：这里只测"名字如何命中"这一层纯逻辑，物品与格挡状态的判定在
 * {@link InnerClassify} / {@code BlockingCompat} 里各自成立。</p>
 */
class ConditionalUseTest {

    private static ConditionalUse mainhand() {
        return new ConditionalUse(true);
    }

    private static ConditionalUse offhand() {
        return new ConditionalUse(false);
    }

    /** 剑格挡：类别名 {@code :sword} 优先于 use action 的 {@code :block}。 */
    @Test
    void swordCategoryWinsOverBlockAction() {
        ConditionalUse use = mainhand();
        use.addTest("use_mainhand:sword");
        use.addTest("use_mainhand:block");
        assertEquals("use_mainhand:sword", use.classifyExtra("use_mainhand:sword", EnumAction.block));
    }

    /** 只声明别名 {@code :sword_block} 时，它等价于 {@code :sword} 被命中。 */
    @Test
    void swordBlockAliasIsEquivalentWhenSwordNotDeclared() {
        ConditionalUse use = mainhand();
        use.addTest("use_mainhand:sword_block");
        assertEquals("use_mainhand:sword_block", use.classifyExtra("use_mainhand:sword", EnumAction.block));
    }

    /** 两个名字都声明时原名字优先，保证既有模型（只声明 {@code :sword}）的行为不变。 */
    @Test
    void swordKeepsPriorityWhenBothNamesDeclared() {
        ConditionalUse use = mainhand();
        use.addTest("use_mainhand:sword");
        use.addTest("use_mainhand:sword_block");
        assertEquals("use_mainhand:sword", use.classifyExtra("use_mainhand:sword", EnumAction.block));
    }

    /** 盾牌不受剑的别名影响：类别是 {@code :shield}，没声明就退回 use action 的 {@code :block}。 */
    @Test
    void shieldFallsBackToBlockAction() {
        ConditionalUse use = mainhand();
        use.addTest("use_mainhand:sword");
        use.addTest("use_mainhand:sword_block");
        use.addTest("use_mainhand:block");
        assertEquals("use_mainhand:block", use.classifyExtra("use_mainhand:shield", EnumAction.block));
    }

    /** 模型只声明 {@code :block} 时，剑格挡仍然走它（等同高版本的盾牌语义，向后兼容）。 */
    @Test
    void swordStillUsesBlockWhenOnlyBlockDeclared() {
        ConditionalUse use = mainhand();
        use.addTest("use_mainhand:block");
        assertEquals("use_mainhand:block", use.classifyExtra("use_mainhand:sword", EnumAction.block));
    }

    /** 没有声明任何名字时，类别名与 use action 都不命中。 */
    @Test
    void undeclaredNamesNeverMatch() {
        ConditionalUse use = mainhand();
        assertEquals("", use.classifyExtra("use_mainhand:sword", EnumAction.block));
        assertEquals("", use.classifyExtra("use_mainhand:sword_block", EnumAction.block));
    }

    /** 非类别的 use action（吃/喝/弓等）不经过别名，照旧按 EnumAction 名命中。 */
    @Test
    void useActionNameStillMatchesWithoutCategory() {
        ConditionalUse use = mainhand();
        use.addTest("use_mainhand:eat");
        assertEquals("use_mainhand:eat", use.classifyExtra("", EnumAction.eat));
    }

    /** 别名同样适用于副手。 */
    @Test
    void aliasAlsoWorksForOffhand() {
        ConditionalUse use = offhand();
        use.addTest("use_offhand:sword_block");
        assertEquals("use_offhand:sword_block", use.classifyExtra("use_offhand:sword", EnumAction.block));
    }

    /** 名字按前缀分流：副手的声明不会登记进主手实例。 */
    @Test
    void handNamesDoNotLeakAcrossHands() {
        ConditionalUse main = mainhand();
        main.addTest("use_offhand:sword_block");
        assertEquals("", main.classifyExtra("use_mainhand:sword", EnumAction.block));
    }
}
