package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code query.is_item_name_any} 的物品 id 匹配规则。模型作者写的多数是
 * {@code minecraft:diamond_sword}，而 1.7.10 的注册名可能带别的命名空间，
 * 因此比较时忽略大小写并允许任意一侧省略命名空间。
 */
class QueryItemNameAnyFunctionTest {

    @Test
    void fullRegistryNameMatches() {
        assertTrue(QueryItemNameAnyFunction.matches("minecraft:diamond_sword", "minecraft:diamond_sword"));
    }

    @Test
    void namespaceMayBeOmittedOnEitherSide() {
        assertTrue(QueryItemNameAnyFunction.matches("minecraft:diamond_sword", "diamond_sword"));
        assertTrue(QueryItemNameAnyFunction.matches("diamond_sword", "minecraft:diamond_sword"));
    }

    @Test
    void comparisonIsCaseInsensitive() {
        assertTrue(QueryItemNameAnyFunction.matches("minecraft:diamond_sword", "DIAMOND_SWORD"));
    }

    @Test
    void differentItemsDoNotMatch() {
        assertFalse(QueryItemNameAnyFunction.matches("minecraft:diamond_sword", "minecraft:iron_sword"));
        assertFalse(QueryItemNameAnyFunction.matches("minecraft:diamond_sword", null));
        assertFalse(QueryItemNameAnyFunction.matches(null, "minecraft:diamond_sword"));
        assertFalse(QueryItemNameAnyFunction.matches("minecraft:diamond_sword", ""));
    }
}
