package com.fox.ysmu.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code ysm.shoot_item_id} 的弩/弓折算（{@link ProjectileShootItemIds}）。
 *
 * <p>背景：模型用 {@code ysm.shoot_item_id == 'minecraft:crossbow'} 切换弩的子模型，
 * 而 1.7.10（GTNH）里弩叫 {@code TConstruct:Crossbow}，不折算的话弩射出的箭永远显示成弓。</p>
 */
class ProjectileShootItemIdsTest {

    @Test
    void emptyWhenNothingShotTheArrow() {
        assertEquals(ProjectileShootItemIds.EMPTY, ProjectileShootItemIds.toModernId(null));
        assertEquals(ProjectileShootItemIds.EMPTY, ProjectileShootItemIds.toModernId(""));
        // 发射器射出的箭没有发射物品
        assertEquals(ProjectileShootItemIds.EMPTY, ProjectileShootItemIds.toModernId(null));
    }

    @Test
    void modernIdsPassThrough() {
        assertEquals("minecraft:crossbow", ProjectileShootItemIds.toModernId("minecraft:crossbow"));
        assertEquals("minecraft:bow", ProjectileShootItemIds.toModernId("minecraft:bow"));
    }

    @Test
    void crossbowsMapToMinecraftCrossbow() {
        assertEquals(ProjectileShootItemIds.MODERN_CROSSBOW, ProjectileShootItemIds.toModernId("TConstruct:Crossbow"));
        assertEquals(ProjectileShootItemIds.MODERN_CROSSBOW, ProjectileShootItemIds.toModernId("tconstruct:crossbow"));
        assertEquals(ProjectileShootItemIds.MODERN_CROSSBOW, ProjectileShootItemIds.toModernId("someMod:steel_crossbow"));
    }

    @Test
    void bowsMapToMinecraftBow() {
        assertEquals(ProjectileShootItemIds.MODERN_BOW, ProjectileShootItemIds.toModernId("TConstruct:Shortbow"));
        assertEquals(ProjectileShootItemIds.MODERN_BOW, ProjectileShootItemIds.toModernId("TConstruct:Longbow"));
        assertEquals(ProjectileShootItemIds.MODERN_BOW, ProjectileShootItemIds.toModernId("someMod:composite_bow"));
    }

    @Test
    void similarlyNamedItemsAreNotBows() {
        // 后缀相近但不是弓：不能把它们算成 minecraft:bow
        assertEquals("minecraft:bowl", ProjectileShootItemIds.toModernId("minecraft:bowl"));
        assertEquals("someMod:rainbow", ProjectileShootItemIds.toModernId("someMod:rainbow"));
    }

    @Test
    void otherItemsKeepTheirRealId() {
        // 认不出的原样返回，模型可以直接写 1.7.10 的真实 id 判断
        assertEquals("minecraft:stone_sword", ProjectileShootItemIds.toModernId("minecraft:stone_sword"));
        assertEquals("TConstruct:Bolt", ProjectileShootItemIds.toModernId("TConstruct:Bolt"));
    }

    @Test
    void mappingIsIdempotent() {
        String[] ids = { null, "", "minecraft:bow", "TConstruct:Crossbow", "TConstruct:Shortbow",
            "someMod:rainbow", "minecraft:stone_sword" };
        for (String id : ids) {
            String once = ProjectileShootItemIds.toModernId(id);
            assertEquals(once, ProjectileShootItemIds.toModernId(once), "二次折算应保持不变: " + id);
        }
    }

    /**
     * 真正的契约：折算结果必须和模型表达式里那个**字面量**落在同一个池化 id 上 ——
     * 否则 {@code == 'minecraft:crossbow'} 依然不成立，整个修复就等于没做。
     */
    @Test
    void pooledIdMatchesTheLiteralTheModelComparesAgainst() {
        int literal = MolangStringPool.intern("minecraft:crossbow");
        assertEquals(literal, MolangStringPool.intern(ProjectileShootItemIds.toModernId("TConstruct:Crossbow")));
        assertEquals(literal, MolangStringPool.intern(ProjectileShootItemIds.toModernId("minecraft:crossbow")));
        assertEquals(MolangStringPool.intern("minecraft:bow"),
            MolangStringPool.intern(ProjectileShootItemIds.toModernId("TConstruct:Shortbow")));
        // 空串仍然是 EMPTY_ID（发射器 / 未知来源）
        assertEquals(MolangStringPool.EMPTY_ID, MolangStringPool.intern(ProjectileShootItemIds.toModernId(null)));
    }
}
