package com.fox.ysmu.client.animation.molang;

import java.util.Locale;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.fox.ysmu.client.animation.condition.InnerClassify;

/**
 * {@code ctrl.hold(slot, matcher)} / {@code ctrl.use} / {@code ctrl.swing} / {@code ctrl.armor}
 * 共用的**物品匹配**规则（YSM-wiki: molang/ref「Ctrl 部分」）。
 *
 * <p>wiki 的 matcher 写法：{@code $物品ID}、{@code #物品tag}、{@code :特殊类别}（也可以直接写类别）。
 * 1.7.10 没有数据驱动的物品标签，所以 {@code #} 一律 false（在
 * {@code DebugController} 下由调用方按需提示，不要假装匹配）。</p>
 *
 * <p>这段规则原本只写在 {@code OpenYsmControllerExpressionEvaluator.Context} 里（控制器条件路径），
 * 于是关键帧/时间轴与 {@code .molang} 脚本里的同名调用只会命中恒 0 的桩函数。抽出来之后两条路径
 * 用同一套判断，避免"控制器里能识别、脚本里识别不了"这种漂移。</p>
 */
public final class CtrlItemMatcher {

    private CtrlItemMatcher() {}

    /** 主手 / 副手的护甲数组下标（wiki 的槽位名）；不是护甲槽返回 -1。 */
    public static int armorSlotIndex(String slot) {
        if (slot == null) {
            return -1;
        }
        switch (slot.toLowerCase(Locale.ROOT)) {
            case "head":
                return 3;
            case "chest":
                return 2;
            case "legs":
                return 1;
            case "feet":
                return 0;
            default:
                return -1;
        }
    }

    /** 物品注册名（小写）；取不到返回空串。 */
    public static String itemId(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "";
        }
        Object rawName = Item.itemRegistry == null ? null : Item.itemRegistry.getNameForObject(stack.getItem());
        return rawName == null ? "" : rawName.toString()
            .toLowerCase(Locale.ROOT);
    }

    /**
     * {@code ctrl.hold}/{@code ctrl.use}/{@code ctrl.swing} 的物品判断。
     *
     * @param matcher 空 = "手里有东西"；{@code empty} = "手里什么都没有"
     */
    public static boolean matches(ItemStack stack, String matcher) {
        if (matcher == null || matcher.isEmpty()) {
            return stack != null;
        }
        if ("empty".equals(matcher)) {
            return stack == null;
        }
        if (stack == null || stack.getItem() == null) {
            return false;
        }
        String id = itemId(stack);
        if (matcher.startsWith("$")) {
            return id.equals(matcher.substring(1)
                .toLowerCase(Locale.ROOT));
        }
        if (matcher.startsWith("#")) {
            // 1.7.10 没有物品标签系统。
            return false;
        }
        String category = matcher.startsWith(":") ? matcher.substring(1) : matcher;
        return categoryMatches(stack, id, category.toLowerCase(Locale.ROOT));
    }

    /** {@code ctrl.armor(slot, matcher)} 的判断；不支持槽位返回 false。 */
    public static boolean armorMatches(ItemStack stack, String matcher) {
        if (matcher == null || matcher.isEmpty()) {
            return false;
        }
        if ("empty".equals(matcher)) {
            return stack == null;
        }
        if (stack == null || stack.getItem() == null) {
            return false;
        }
        if (matcher.startsWith("$")) {
            return itemId(stack).equals(matcher.substring(1)
                .toLowerCase(Locale.ROOT));
        }
        // 与 handMatch 一致：护甲槽只认 $id 与 empty。
        return false;
    }

    /** 类别匹配：先问 {@link InnerClassify} 的物品类型，再退回"注册名里包含类别"。 */
    static boolean categoryMatches(ItemStack stack, String id, String category) {
        String itemType = InnerClassify.getItemType(stack);
        if (category.equals(itemType)) {
            return true;
        }
        if ("trident".equals(category) && "spear".equals(itemType)) {
            return true;
        }
        if ("spear".equals(category) || "trident".equals(category)) {
            return id.contains("spear") || id.contains("trident");
        }
        if (isKnownItemCategory(category)) {
            return false;
        }
        return id.contains(category);
    }

    /**
     * 已知类别集合：命中它就**不再**退回"注册名包含"的模糊匹配，避免把
     * {@code sword} 匹配到名字里带 "sword" 的非剑物品（例如某些模组的剑鞘/剑柄）。
     */
    static boolean isKnownItemCategory(String category) {
        switch (category) {
            case "sword":
            case "axe":
            case "pickaxe":
            case "shovel":
            case "hoe":
            case "bow":
            case "crossbow":
            case "shield":
            case "spear":
            case "trident":
            case "fishing_rod":
            case "throwable_potion":
                return true;
            default:
                return false;
        }
    }
}
