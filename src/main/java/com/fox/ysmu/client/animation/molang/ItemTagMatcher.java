package com.fox.ysmu.client.animation.molang;

import java.util.Locale;

import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.fox.ysmu.client.animation.condition.InnerClassify;

/**
 * {@code query.equipped_item_any_tag} / {@code query.equipped_item_all_tags} 的标签匹配
 * （YSM-wiki: molang/ref，两者都是 1.2.0）。
 *
 * <p><b>1.7.10 没有数据驱动的物品标签</b>，所以这里只把能原生回答的那部分映射出来，其余一律
 * false，并由调用方在 {@code DebugController} 下记一条一次性日志（不要假装匹配，那只会让模型
 * 分支以错误的方式生效）。能回答的两类：</p>
 *
 * <ol>
 *   <li><b>物品类型标签</b>：{@code minecraft:swords} / {@code axes} / {@code pickaxes} /
 *       {@code shovels} / {@code hoes} / {@code bows} / {@code fishing_rods} / {@code tridents}
 *       —— 直接复用 {@link InnerClassify#getItemType}（它按 1.7.10 的物品类 + 名称 + 矿物词典
 *       判定类型），这是 1.7.10 上最接近这些标签语义的东西。</li>
 *   <li><b>材质标签</b>：{@code forge:ingots/iron}、{@code forge:ores/gold}、
 *       {@code forge:storage_blocks/redstone}、{@code c:ingots/copper} 这类现代约定
 *       → 1.7.10 的矿物词典名（{@code ingotIron}、{@code oreGold}、{@code blockRedstone}…）。
 *       也接受"只要材质家族"的写法（{@code forge:ingots} = 任意 {@code ingot*}）。</li>
 * </ol>
 *
 * <p>另外把几个原版方块/材料标签映射到矿物词典名（{@code minecraft:planks} → {@code plankWood}
 * 等）—— 1.7.10 整合包里这些矿物词典名是通用的，比按注册名猜要可靠。</p>
 *
 * <p><b>"没映射"与"模组不在"要分开</b>：标签命名空间对应的模组未安装时（1.7.10 上大量如此，
 * 例如 {@code irons_spellbooks:staff}）永远匹配不到，这是**正确跳过**而不是本模组的缺口 ——
 * 分类见 {@link #statusOf}，判定在 {@link com.fox.ysmu.util.ModAvailability}。</p>
 */
public final class ItemTagMatcher {

    private ItemTagMatcher() {}

    /** 现代材质标签前缀 → 1.7.10 矿物词典前缀。键都是"去掉命名空间后"的写法。 */
    private static final String[][] MATERIAL_PREFIXES = {
        { "ingots/", "ingot" },
        { "nuggets/", "nugget" },
        { "ores/", "ore" },
        { "storage_blocks/", "block" },
        { "gems/", "gem" },
        { "dusts/", "dust" },
        { "raw_materials/", null }, // 1.17+ 才有原矿，1.7.10 无法回答
    };

    /** 原版标签 → 1.7.10 矿物词典名（等价比较，忽略大小写）。 */
    private static final String[][] VANILLA_TAGS = {
        { "planks", "plankWood" },
        { "wooden_slabs", "slabWood" },
        { "logs", "logWood" },
        { "logs_that_burn", "logWood" },
        { "saplings", "treeSapling" },
        { "leaves", "treeLeaves" },
        { "wool", "blockWool" },
        { "coals", "coal" },
        { "stone_bricks", "stonebrick" },
        { "stone_crafting_materials", "cobblestone" },
    };

    /** 物品类型标签（复数）→ {@link InnerClassify} 的物品类型名。 */
    private static final String[][] ITEM_TYPE_TAGS = {
        { "swords", "sword" },
        { "axes", "axe" },
        { "pickaxes", "pickaxe" },
        { "shovels", "shovel" },
        { "hoes", "hoe" },
        { "bows", "bow" },
        { "fishing_rods", "fishing_rod" },
        { "tridents", "spear" },
        { "shields", "shield" },
    };

    /** 标签的三分类：能回答 / 我们能回答的范围之外（值得补映射）/ 来源模组没装（正确跳过）。 */
    public enum Status {
        /** 我们能回答这个标签（至于命不命中看物品）。 */
        SUPPORTED,
        /** 我们能回答的范围之外，但标签来源可用（原版/已装模组）→ 这是本模组的缺口。 */
        UNMAPPED,
        /** 标签命名空间对应的模组没装 → 永远匹配不到，正确跳过，不算缺口。 */
        MOD_ABSENT
    }

    /**
     * 标签分类。判定顺序：能回答优先 → 来源模组没装 → 否则算未映射。
     *
     * <p>"能回答优先"是有意的：万一以后给某个模组的标签加了映射，装了该模组时就走映射，
     * 而不是因为命名空间被判"不在"就跳过。</p>
     */
    public static Status statusOf(String tag) {
        if (tag == null || tag.trim()
            .isEmpty()) {
            return Status.UNMAPPED;
        }
        if (isSupportedTag(tag)) {
            return Status.SUPPORTED;
        }
        return com.fox.ysmu.util.ModAvailability.isUnavailable(tag) ? Status.MOD_ABSENT : Status.UNMAPPED;
    }

    /** 去掉命名空间并转小写：{@code minecraft:planks} → {@code planks}。 */
    static String normalize(String tag) {
        if (tag == null) {
            return "";
        }
        String name = tag.trim()
            .toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    /**
     * 这个标签在 1.7.10 上能否被回答。用于决定要不要给模型作者打"未映射标签"的提示。
     */
    public static boolean isSupportedTag(String tag) {
        return mappingOf(tag) != null;
    }

    /**
     * 标签是否命中该物品。
     *
     * @param stack 物品（可为 null，一律 false）
     * @param tag   现代标签名，形如 {@code minecraft:planks} / {@code forge:ingots/iron}
     */
    public static boolean matchesTag(ItemStack stack, String tag) {
        if (stack == null || stack.getItem() == null) {
            return false;
        }
        String name = normalize(tag);
        if (name.isEmpty()) {
            return false;
        }
        // 1) 物品类型标签：复用 InnerClassify（其内部会走 OreDictionary，个别未注册物品可能
        //    抛异常，这里兜住——标签查询不该把渲染带崩）。
        for (String[] pair : ITEM_TYPE_TAGS) {
            if (pair[0].equals(name) || pair[0].equals(name + "s") || stripPlural(pair[0]).equals(stripPlural(name))) {
                String itemType;
                try {
                    itemType = InnerClassify.getItemType(stack);
                } catch (Throwable e) {
                    // InnerClassify 内部会查物品注册名/矿物词典；渲染路径不能因为
                    // 一个奇怪物品（或 OreDictionary 初始化异常）把关键帧求值带崩。
                    itemType = "";
                }
                return pair[1].equals(itemType);
            }
        }
        // 2) 原版方块/材料标签 → 矿物词典名
        for (String[] pair : VANILLA_TAGS) {
            if (pair[0].equals(name)) {
                return hasOreName(stack, pair[1], true);
            }
        }
        // 3) 现代材质标签 → 矿物词典前缀
        for (String[] pair : MATERIAL_PREFIXES) {
            String prefix = pair[0];
            if (pair[1] == null) {
                if (name.startsWith(prefix)) {
                    return false; // 1.7.10 回答不了（原矿之类）
                }
                continue;
            }
            if (name.startsWith(prefix)) {
                String material = name.substring(prefix.length());
                if (material.isEmpty()) {
                    // forge:ingots —— 只要"任意一种 ingot"
                    return hasOreName(stack, pair[1], false);
                }
                return hasOreName(stack, pair[1] + camelCase(material), true);
            }
            // 也接受不带斜杠的简写（forge:ingot_iron 这种不规范写法）
            String flat = prefix.substring(0, prefix.length() - 1) + "_";
            if (name.startsWith(flat)) {
                return hasOreName(stack, pair[1] + camelCase(name.substring(flat.length())), true);
            }
        }
        return false;
    }

    /** 映射表里能查到（含"只要能回答的家族"）则返回其归一化名，否则 null。 */
    private static String mappingOf(String tag) {
        String name = normalize(tag);
        if (name.isEmpty()) {
            return null;
        }
        for (String[] pair : ITEM_TYPE_TAGS) {
            if (pair[0].equals(name)) {
                return pair[0];
            }
        }
        for (String[] pair : VANILLA_TAGS) {
            if (pair[0].equals(name)) {
                return pair[0];
            }
        }
        for (String[] pair : MATERIAL_PREFIXES) {
            if (name.startsWith(pair[0])) {
                // raw_materials 是"认识但这代没有"，也算未映射（提示作者没意义，但不假装命中）。
                return pair[1] == null ? null : name;
            }
        }
        return null;
    }

    /** 物品的矿物词典名里是否存在 {@code name}；{@code exact=false} 时按前缀算。 */
    private static boolean hasOreName(ItemStack stack, String name, boolean exact) {
        int[] ids;
        try {
            ids = OreDictionary.getOreIDs(stack);
        } catch (Throwable e) {
            // OreDictionary 的静态初始化在没有游戏 bootstrap 时会抛 Error；
            // 与仓库"每帧入口接住 Throwable"的约定一致，这里一律退化为"没命中"。
            return false;
        }
        for (int id : ids) {
            String oreName = OreDictionary.getOreName(id);
            if (oreName == null) {
                continue;
            }
            if (exact ? oreName.equalsIgnoreCase(name) : oreName.toLowerCase(Locale.ROOT)
                .startsWith(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** {@code iron} → {@code Iron}；{@code glowstone_dust} → {@code GlowstoneDust}。 */
    static String camelCase(String material) {
        StringBuilder out = new StringBuilder(material.length());
        boolean upper = true;
        for (int i = 0; i < material.length(); i++) {
            char c = material.charAt(i);
            if (c == '_' || c == ' ') {
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }

    private static String stripPlural(String name) {
        return name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
    }
}
