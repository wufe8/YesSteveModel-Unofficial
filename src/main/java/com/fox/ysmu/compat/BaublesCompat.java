package com.fox.ysmu.compat;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.fox.ysmu.client.animation.molang.QueryItemNameAnyFunction;
import com.fox.ysmu.util.ModAvailability;
import com.fox.ysmu.ysmu;

import baubles.api.BaublesApi;
import baubles.api.expanded.BaubleExpandedSlots;

/**
 * {@code ysm.has_any_curios} 的 1.7.10 兼容层：Curios → **Baubles**。
 *
 * <p>Curios（1.20.1 的饰品栏 mod）在 1.7.10 上不存在，但它的前身是 Azanor 的 Baubles，GTNH 用的是
 * 扩展版 <b>Baubles-Expanded</b>（modid 仍是 {@code Baubles}，随 GTNH 2.8+ 提供）。YSM-wiki 的
 * {@code ysm.has_any_curios(槽位, 物品id...)} 语义是"某个饰品槽里是否有匹配的物品"，在 1.7.10 上
 * 就用 Baubles-Expanded 的类型化槽位实现（{@code BaubleExpandedSlots}）。</p>
 *
 * <p><b>槽位名映射</b>：Curios 的标准槽位标识（{@code necklace}/{@code ring}/{@code belt}/
 * {@code charm}/{@code head}/{@code body}/{@code hands}）映射到 Baubles-Expanded 的类型
 * （{@code amulet}/{@code ring}/{@code belt}/{@code charm}/{@code head}/{@code body}/{@code gauntlet}）；
 * 名字本身就是 Baubles 类型时直接使用。**没有对应物的槽位（{@code back}、{@code spellbook}、
 * {@code curio} 等模组自建槽位）返回 false 并各提示一次** —— 不拿"任意饰品"冒充，那会让模型
 * 在戴着无关戒指时切到错误的形态。</p>
 *
 * <p><b>1.7.10 上有三个都叫 {@code Baubles} 的版本，槽位结构完全不同</b>（clone 到 {@code local/clones}
 * 核对过）：</p>
 * <table>
 * <tr><td>Azanor/Baubles @1.7.10</td><td>{@code BaubleType} = RING/AMULET/BELT，背包 {@code new ItemStack[4]}</td></tr>
 * <tr><td>GTNewHorizons/Baubles</td><td>同上，多一个 {@code UNIVERSAL}（能放进任何槽）</td></tr>
 * <tr><td>GTNewHorizons/Baubles-Expanded</td><td>背包 {@code BaubleExpandedSlots.slotLimit}</td></tr>
 * </table>
 * <p>前两者的槽位布局是硬编码的固定 4 格：{@code 0=AMULET, 1=RING, 2=RING, 3=BELT}
 * （{@code ContainerPlayerExpanded} 里两版逐行一致），**没有**类型化槽位 API；只有 Expanded 提供
 * {@code BaubleExpandedSlots}（20 格、类型可配置，所以是"十几个不同槽位"）。因此这里按
 * {@link #apiFlavour()} 分流：Expanded 走类型化查询，普通版走固定布局表 —— 否则装上 GTNH 的
 * 普通 fork 时只会静默失效。</p>
 *
 * <p>编译依赖是 {@code Baubles-Expanded:2.1.5-GTNH}（用到的 API 在 2.1.5 就已存在，运行时兼容
 * 任意 2.x）；未安装时 {@link #isLoaded()} 为 false，调用方按"模组不在"处理（正确跳过）。</p>
 */
public final class BaublesCompat {

    /** Baubles 的 modid（原版与 Expanded 都是这个）。 */
    private static final String MOD_ID = "Baubles";

    /** Curios 标准槽位标识 → Baubles(-Expanded) 槽位类型。 */
    private static final Map<String, String> CURIO_TO_BAUBLE;

    /** Baubles-Expanded 预注册的槽位类型（同名直接可用，避免依赖类的静态状态）。 */
    static final Set<String> BAUBLE_TYPES = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                "ring",
                "amulet",
                "belt",
                "universal",
                "head",
                "body",
                "charm",
                "cape",
                "shield",
                "quiver",
                "gauntlet",
                "earring",
                "wings")));

    static {
        Map<String, String> map = new HashMap<>();
        map.put("necklace", "amulet");
        map.put("ring", "ring");
        map.put("belt", "belt");
        map.put("charm", "charm");
        map.put("head", "head");
        map.put("body", "body");
        // Curios 的 hands 槽放手套/护手，Baubles-Expanded 的对应物是 gauntlet（近似）。
        map.put("hands", "gauntlet");
        CURIO_TO_BAUBLE = Collections.unmodifiableMap(map);
    }

    /** API 版本未知 / 普通版（固定 4 槽）/ Expanded（类型化 20 槽）。 */
    private static final int API_UNKNOWN = 0;
    private static final int API_PLAIN = 1;
    private static final int API_EXPANDED = 2;

    private static volatile int apiFlavour = API_UNKNOWN;

    /**
     * 普通版（Azanor 原版与 GTNH fork 都是）的固定槽位布局。
     *
     * <p>证据：两版的 {@code ContainerPlayerExpanded} 里逐行一致地
     * {@code SlotBauble(baubles, AMULET, 0) / RING,1 / RING,2 / BELT,3}，
     * 且 {@code InventoryBaubles} 的数组长度硬编码为 4。</p>
     */
    private static final Map<String, int[]> PLAIN_SLOTS;

    static {
        Map<String, int[]> plain = new HashMap<>();
        plain.put("amulet", new int[] { 0 });
        plain.put("ring", new int[] { 1, 2 });
        plain.put("belt", new int[] { 3 });
        PLAIN_SLOTS = Collections.unmodifiableMap(plain);
    }

    /** 已提示过的槽位名，避免刷屏。 */
    private static final Set<String> REPORTED = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private BaublesCompat() {}

    /** Baubles(-Expanded) 是否已安装。 */
    public static boolean isLoaded() {
        return ModAvailability.isLoaded(MOD_ID);
    }

    /**
     * 运行时装的是哪个 Baubles：{@link #API_EXPANDED} 有类型化槽位 API，
     * {@link #API_PLAIN} 是固定 4 槽的普通版（Azanor 原版或 GTNH fork）。
     *
     * <p>不能用 modid 区分 —— 三个版本都叫 {@code Baubles}。判据是
     * {@code baubles.api.expanded.BaubleExpandedSlots} 这个类在不在。</p>
     */
    static int apiFlavour() {
        int flavour = apiFlavour;
        if (flavour != API_UNKNOWN) {
            return flavour;
        }
        if (!isLoaded()) {
            return API_UNKNOWN;
        }
        // 能力探测（类在不在）而不是版本号：三个 Baubles 的版本号互不可比，见类注释。
        flavour = ModAvailability.isClassPresent("baubles.api.expanded.BaubleExpandedSlots")
            ? API_EXPANDED
            : API_PLAIN;
        apiFlavour = flavour;
        return flavour;
    }

    /**
     * 槽位类型解析不出来、但调用方给了具体物品 id 时，是否退化为"任意饰品槽里有没有这个物品"。
     *
     * <p>这是"遇到问题再回退"的那一步：给不出"哪个槽"时，至少还能回答"身上戴着这个饰品吗"——
     * 对显式 id 的查询来说这是可靠近似（id 来自具体模组，误命中的可能性极低）。没有 id 时
     * 无法退化（{@code has_any_curios('spellbook')} 问的是"这个槽里有没有东西"），只能 false。</p>
     */
    static boolean shouldScanAllSlots(List<String> itemIds, int[] resolvedSlots) {
        if (resolvedSlots != null && resolvedSlots.length > 0) {
            return false;
        }
        return itemIds != null && !itemIds.isEmpty();
    }

    /** 普通版固定布局下该类型占哪几格；类型不存在（charm/head/body/… 需要 Expanded）返回 null。 */
    static int[] plainSlotIndexesFor(String baubleType) {
        return baubleType == null ? null : PLAIN_SLOTS.get(baubleType);
    }

    /** 单测/资源重载用：清掉版本探测缓存。 */
    static void clearApiFlavourCache() {
        apiFlavour = API_UNKNOWN;
    }

    /**
     * Curios 槽位名 → Baubles 槽位类型（纯函数，便于单测）；无法对应时返回 {@code null}。
     *
     * <p>先查 Curios 标准槽位的映射表，再看名字本身是否就是 Baubles 类型。</p>
     */
    public static String baubleTypeFor(String curioSlot) {
        if (curioSlot == null) {
            return null;
        }
        String name = curioSlot.trim()
            .toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return null;
        }
        String mapped = CURIO_TO_BAUBLE.get(name);
        if (mapped != null) {
            return mapped;
        }
        return BAUBLE_TYPES.contains(name) ? name : null;
    }

    /**
     * {@code ysm.has_any_curios(槽位, 物品id...)}：该饰品槽里是否有物品（给了 id 时要求匹配）。
     *
     * @param itemIds 为空表示"该槽里有任何东西即真"
     */
    public static boolean hasAnyCurio(EntityPlayer player, String curioSlot, List<String> itemIds) {
        if (player == null) {
            return false;
        }
        if (!isLoaded()) {
            // 只在模型真的用到这个查询时提示一次（纯模型不该被噪音打扰）。
            // Baubles 在 GTNH 里随包提供；纯净端/低版本整合包可能没有。
            if (REPORTED.add("missing")) {
                ysmu.LOG.warn(
                    "[YSMU-COMPAT] ysm.has_any_curios is ineffective: Baubles (or Baubles-Expanded) is not installed");
            }
            return false;
        }
        String type = baubleTypeFor(curioSlot);
        if (type == null) {
            reportUnmappedSlot(curioSlot);
            return false;
        }
        int[] slots = slotIndexesFor(type);
        boolean scanAll = shouldScanAllSlots(itemIds, slots);
        if (!scanAll && (slots == null || slots.length == 0)) {
            return false;
        }
        if (scanAll) {
            reportOnce("fallback|" + type + "|" + curioSlot,
                "curio slot type '" + curioSlot + "' cannot be resolved here; falling back to matching the given item id in any Baubles slot");
        }
        try {
            IInventory inventory = BaublesApi.getBaubles(player);
            if (inventory == null) {
                return false;
            }
            int size = inventory.getSizeInventory();
            if (scanAll) {
                slots = new int[size];
                for (int i = 0; i < size; i++) {
                    slots[i] = i;
                }
            }
            for (int slot : slots) {
                if (slot < 0 || slot >= size) {
                    continue;
                }
                ItemStack stack = inventory.getStackInSlot(slot);
                if (stack == null || stack.getItem() == null) {
                    continue;
                }
                if (itemIds == null || itemIds.isEmpty()) {
                    return true;
                }
                String registryName = Item.itemRegistry == null ? null
                    : registryNameOf(stack);
                for (String id : itemIds) {
                    if (QueryItemNameAnyFunction.matches(registryName, id)) {
                        return true;
                    }
                }
            }
        } catch (Throwable e) {
            // Baubles API 本身走反射，且可能在没有玩家上下文时失败：饰品查询绝不能把求值带崩。
            reportOnce("error|" + type, "Baubles slot query for type '" + type
                + "' failed (" + e.getClass().getSimpleName() + "); treating as empty");
        }
        return false;
    }

    /**
     * 该 Baubles 类型占哪几格：Expanded 走类型化查询，普通版走固定布局表。
     *
     * <p>普通版没有 charm/head/body/gauntlet 这些类型，会返回 null 并提示一次"需要 Expanded"
     * —— 这是可执行的建议，而不是静默失效。</p>
     */
    private static int[] slotIndexesFor(String baubleType) {
        if (apiFlavour() == API_EXPANDED) {
            try {
                return BaubleExpandedSlots.getIndexesOfAssignedSlotsOfType(baubleType);
            } catch (Throwable e) {
                reportOnce("error|expanded|" + baubleType,
                    "Baubles-Expanded slot lookup for type '" + baubleType + "' failed; treating as empty");
                return null;
            }
        }
        int[] plain = plainSlotIndexesFor(baubleType);
        if (plain == null) {
            reportOnce("plain-missing|" + baubleType,
                "curio slot type '" + baubleType + "' needs Baubles-Expanded; plain Baubles only has amulet/ring/belt");
        }
        return plain;
    }

    /** 一次性提示（按 key 去重）。 */
    private static void reportOnce(String key, String message) {
        if (REPORTED.add(key)) {
            ysmu.LOG.warn("[YSMU-COMPAT] {}", message);
        }
    }

    private static String registryNameOf(ItemStack stack) {
        Object name = Item.itemRegistry.getNameForObject(stack.getItem());
        return name == null ? null : name.toString();
    }

    /**
     * 槽位没有 1.7.10 对应物：每个槽位名提示一次。
     *
     * <p>这类槽位（{@code back}/{@code spellbook} 等）是别的模组在 Curios 上自建的，1.7.10 上
     * 既没有 Curios 也没有那些模组 —— 属于"正确跳过"，与
     * {@code BackhandCompat} 里 HiddenOffhandItems 的处理同一原则。</p>
     */
    private static void reportUnmappedSlot(String curioSlot) {
        String name = curioSlot == null ? "" : curioSlot.trim()
            .toLowerCase(Locale.ROOT);
        if (name.isEmpty() || !REPORTED.add("slot|" + name)) {
            return;
        }
        ysmu.LOG.warn(
            "[YSMU-COMPAT] curio slot '{}' has no Baubles analogue on 1.7.10; Baubles-Expanded types are {} (explicit item ids are still checked against every slot)",
            name, BAUBLE_TYPES);
    }

    /** 测试/诊断用：已提示过的条目数。 */
    static int reportedCount() {
        return REPORTED.size();
    }

    static void clearReported() {
        REPORTED.clear();
    }
}
