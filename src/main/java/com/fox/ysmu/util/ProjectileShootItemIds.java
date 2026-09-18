package com.fox.ysmu.util;

import java.util.Locale;

/**
 * {@code ysm.shoot_item_id} 的「1.7.10 注册名 → 现代 id」折算。
 *
 * <p>YSM-wiki: molang/ref（弹射物）—— {@code ysm.shoot_item_id} 是"射出这支箭的物品 id"，
 * 模型用它切换子模型，最常见的写法就是弓/弩二选一：</p>
 *
 * <pre>
 *   "bow":      scale = ysm.shoot_item_id != 'minecraft:crossbow'
 *   "crossbow": scale = ysm.shoot_item_id == 'minecraft:crossbow'
 * </pre>
 *
 * <p>1.7.10 上弩并不叫 {@code minecraft:crossbow}（GTNH 里是 {@code TConstruct:Crossbow} 之类），
 * 如果照搬真实注册名，模型里写的 crossbow 分支就永远不成立（弩射出的箭会显示成弓）。
 * 所以这里按 AGENTS.md 的「把 1.20.1 检查映射到 1.7.10 行为」加一层别名：只把
 * 「明显是一把弩 / 一把弓」的注册名折算成现代名字，其余**原样返回** —— 模型也可以直接写
 * 1.7.10 的真实 id 来判断。</p>
 *
 * <p>纯函数、无 Minecraft 依赖，便于单测。</p>
 */
public final class ProjectileShootItemIds {

    /** 认不出射出物品时（发射器射出的箭、未知来源）的值，对应 {@code MolangStringPool.EMPTY_ID}。 */
    public static final String EMPTY = "";

    public static final String MODERN_BOW = "minecraft:bow";
    public static final String MODERN_CROSSBOW = "minecraft:crossbow";

    private ProjectileShootItemIds() {}

    /**
     * 把 1.7.10 的物品注册名折算成模型会写的现代 id。
     *
     * @param registryId {@code GameRegistry} 注册名（如 {@code TConstruct:Crossbow}）；可为 {@code null}
     * @return 现代 id；认不出时原样返回 {@code registryId}；{@code null}/空串返回 {@link #EMPTY}
     */
    public static String toModernId(String registryId) {
        if (registryId == null || registryId.isEmpty()) {
            return EMPTY;
        }
        int colon = registryId.indexOf(':');
        String path = (colon >= 0 ? registryId.substring(colon + 1) : registryId).toLowerCase(Locale.ROOT);
        // 弩先判：否则 "crossbow" 也会落进下面的弓规则。
        if (path.contains("crossbow")) {
            return MODERN_CROSSBOW;
        }
        // 弓：minecraft:bow、xxx_bow、shortbow/longbow（GTNH 的 TiCon 弓）。
        // 刻意不匹配 bowl / rainbow / elbow 这类同后缀单词。
        if (path.equals("bow") || path.endsWith("_bow") || path.endsWith("shortbow") || path.endsWith("longbow")) {
            return MODERN_BOW;
        }
        return registryId;
    }
}
