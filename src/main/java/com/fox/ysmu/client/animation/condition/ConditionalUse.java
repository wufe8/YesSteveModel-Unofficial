package com.fox.ysmu.client.animation.condition;

import java.util.List;
import java.util.Locale;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.fox.ysmu.compat.BackhandCompat;
import com.google.common.collect.Lists;

import cpw.mods.fml.common.registry.GameRegistry;

public class ConditionalUse {

    private static final String EMPTY = "";
    private final int preSize;
    private final String idPre;
    private final String oreDictPre;
    private final String extraPre;
    // 1.7.10: 使用String存储物品ID ("modid:name")
    private final List<String> idTest = Lists.newArrayList();
    // 1.7.10: 使用String存储矿物词典名称
    private final List<String> oreDictTest = Lists.newArrayList();
    // 1.7.10: 使用EnumAction替代UseAnim
    private final List<EnumAction> extraTest = Lists.newArrayList();
    private final List<String> innerTest = Lists.newArrayList();

    public ConditionalUse(boolean isMainHand) {
        if (isMainHand) {
            idPre = "use_mainhand$";
            oreDictPre = "use_mainhand#";
            extraPre = "use_mainhand:";
            preSize = 13;
        } else {
            idPre = "use_offhand$";
            oreDictPre = "use_offhand#";
            extraPre = "use_offhand:";
            preSize = 12;
        }
    }

    public void addTest(String name) {
        if (name.length() <= preSize) {
            return;
        }
        String substring = name.substring(preSize);
        if (name.startsWith(idPre)) {
            // 1.7.10: 简单验证格式即可，不再有 isValidResourceLocation 方法
            if (substring.contains(":")) {
                idTest.add(substring);
            }
        }
        if (name.startsWith(oreDictPre)) {
            // 1.7.10: 这里处理的是矿物词典名称
            oreDictTest.add(substring);
        }
        if (name.startsWith(extraPre)) {
            // 1.7.10: 使用EnumAction替代UseAnim
            if (substring.equals(
                EnumAction.none.name()
                    .toLowerCase(Locale.US))) {
                return;
            }
            // 1.7.10: 使用传统方式查找EnumAction
            for (EnumAction action : EnumAction.values()) {
                if (action.name()
                    .toLowerCase(Locale.US)
                    .equals(substring)) {
                    extraTest.add(action);
                    break;
                }
            }
            innerTest.add(name);
        }
    }

    public String doTest(EntityPlayer player, boolean isMainHand) {
        if (BackhandCompat.getItemInHand(player, isMainHand) == null) {
            return EMPTY;
        }
        String result = doIdTest(player, isMainHand);
        if (result.isEmpty()) {
            result = doOreDictTest(player, isMainHand);
            if (result.isEmpty()) {
                return doExtraTest(player, isMainHand);
            }
        }
        return result;
    }

    private String doIdTest(EntityPlayer player, boolean isMainHand) {
        if (idTest.isEmpty()) {
            return EMPTY;
        }
        ItemStack itemInHand = BackhandCompat.getItemInHand(player, isMainHand);
        // 1.7.10: 使用 GameRegistry 获取物品的唯一标识符
        GameRegistry.UniqueIdentifier uid = GameRegistry.findUniqueIdentifierFor(itemInHand.getItem());
        if (uid == null) {
            return EMPTY;
        }
        String registryName = uid.toString(); // 格式为 "modid:name"
        if (idTest.contains(registryName)) {
            return idPre + registryName;
        }
        return EMPTY;
    }

    private String doOreDictTest(EntityPlayer player, boolean isMainHand) {
        if (oreDictTest.isEmpty()) {
            return EMPTY;
        }
        ItemStack itemInHand = BackhandCompat.getItemInHand(player, isMainHand);
        // 获取物品堆栈对应的所有矿辞ID
        int[] oreIDs = OreDictionary.getOreIDs(itemInHand);
        if (oreIDs.length == 0) {
            return EMPTY;
        }

        // 遍历物品拥有的所有矿辞
        for (int oreID : oreIDs) {
            String oreName = OreDictionary.getOreName(oreID);
            // 检查这个矿辞名称是否在我们需要测试的列表里
            if (oreDictTest.contains(oreName)) {
                return oreDictPre + oreName; // 找到匹配，返回结果
            }
        }

        return EMPTY; // 未找到匹配
    }

    // 1.7.10: 使用EnumAction替代UseAnim
    private String doExtraTest(EntityPlayer player, boolean isMainHand) {
        if (extraTest.isEmpty() && innerTest.isEmpty()) {
            return EMPTY;
        }
        String innerName = InnerClassify.doClassifyTest(extraPre, player, isMainHand);
        // 1.7.10: 使用getItemUseAction替代getUseAnimation
        EnumAction action = BackhandCompat.getItemInHand(player, isMainHand)
            .getItemUseAction();
        return classifyExtra(innerName, action);
    }

    /**
     * 条件使用动画的命中顺序（独立方法，便于单测）：物品类别名（{@code :sword}/{@code :shield}/…）
     * 优先于 {@code EnumAction} 名（{@code :block}/{@code :bow}/…）。
     *
     * // YSM-wiki: animation/condition/arm —— 上游 {@code use_mainhand:block} 来自 1.20 的
     * {@code UseAnim.BLOCK}（只有盾牌会返回它）。1.7.10 没有 UseAnim，剑右键格挡返回的是
     * {@code EnumAction.block}，所以 {@code :block} 会同时命中剑和盾；剑想单独覆盖必须用会
     * 先命中的 {@code :sword}（别名见 {@link #resolveCategoryAlias}）。
     */
    String classifyExtra(String innerName, EnumAction action) {
        String categoryName = resolveCategoryAlias(innerName);
        if (categoryName != null && !categoryName.isEmpty() && innerTest.contains(categoryName)) {
            return categoryName;
        }
        if (extraTest.contains(action)) {
            return extraPre + action.name()
                .toLowerCase(Locale.US);
        }
        return EMPTY;
    }

    /**
     * 物品类别名的等价别名解析：{@code <hand>:sword_block} 与 {@code <hand>:sword} 等价，
     * 剑格挡时声明任意一个都能覆盖。{@code :sword_block} 是 YSMU 扩展名，语义比"用剑"更明确，
     * 也能在上游改变 {@code :sword} 语义时提供一个稳定的名字。
     *
     * <p>原名字优先：两个都声明时返回 {@code :sword}，既有模型的行为不变。</p>
     */
    private String resolveCategoryAlias(String innerName) {
        if (innerName == null) {
            return null;
        }
        if (!innerTest.contains(innerName) && (extraPre + "sword").equals(innerName)) {
            String alias = extraPre + "sword_block";
            if (innerTest.contains(alias)) {
                return alias;
            }
        }
        return innerName;
    }
}
