package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

import org.junit.jupiter.api.Test;

/**
 * {@code query.relative_block_has_any_tag} 目前只有 {@code minecraft:replaceable} 一个映射。
 * 这里只测匹配规则本身（空气可替换、石头不可替换、未知标签一律 false），
 * 坐标取整与范围限制在实机用 {@code /ysm debug eval} 验证。
 *
 * <p>不要用 {@code net.minecraft.init.Blocks}：没有游戏 bootstrap 时那些静态字段是 <b>null</b>
 * （方块注册从未运行），测试里直接用 {@code Block} 的子类 + {@code Material} 构造即可。
 * {@code Block.isAir} 在 1.7.10 就是 {@code getMaterial() == Material.air}，
 * {@code isReplaceable} 走 {@code Material.isReplaceable()}，两者都不需要世界对象。</p>
 */
class QueryBlockTagFunctionTest {

    /** 测试用方块：1.7.10 的 {@code Block(Material)} 构造器是 protected。 */
    private static final class TestBlock extends Block {

        TestBlock(Material material) {
            super(material);
        }
    }

    private static final Block REPLACEABLE = new TestBlock(Material.air);
    private static final Block SOLID = new TestBlock(Material.rock);

    @Test
    void replaceableTagMatchesBlocksWhoseMaterialIsReplaceable() {
        assertTrue(QueryBlockTagFunction.matchesTag("minecraft:replaceable", REPLACEABLE, null, 0, 0, 0));
    }

    @Test
    void namespaceMayBeOmitted() {
        assertTrue(QueryBlockTagFunction.matchesTag("replaceable", REPLACEABLE, null, 0, 0, 0));
    }

    @Test
    void solidBlocksAreNotReplaceable() {
        assertFalse(QueryBlockTagFunction.matchesTag("minecraft:replaceable", SOLID, null, 0, 0, 0));
    }

    @Test
    void unknownTagsStayFalseInsteadOfPretendingToMatch() {
        assertFalse(QueryBlockTagFunction.matchesTag("minecraft:logs", REPLACEABLE, null, 0, 0, 0));
        assertFalse(QueryBlockTagFunction.matchesTag("minecraft:replaceable", null, null, 0, 0, 0));
    }
}
