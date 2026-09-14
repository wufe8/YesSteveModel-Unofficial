package com.fox.ysmu.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import cpw.mods.fml.common.Loader;

/**
 * 模组可用性判定，以及"这个引用是不是来自未安装的模组"的分类。
 *
 * <p>模型里会用带命名空间的 id 引用别的模组：物品/方块标签（{@code irons_spellbooks:staff}）、
 * 变量（{@code ctrl.tac_hold_gun}）。这些模组在 1.7.10 上大多不存在，此时"匹配不到"是
 * <b>正确跳过</b>，不是 YSMU 的缺口 —— 与 {@code BackhandCompat} 里 HiddenOffhandItems 的
 * 处理同一原则（"条目所属 mod 未安装：该条目永远匹配不到，警告一次（正确跳过，不误伤）"）。</p>
 *
 * <p>区分两者很重要：把"模组不在"报成"我们没实现"会污染排查信号，让人去补一个永远用不上的映射；
 * 反过来把"我们没实现"当成"模组不在"静默掉，又会漏掉真正该做的兼容。</p>
 */
public final class ModAvailability {

    /** 原版/平台命名空间：这些永远可用，不属于"未安装的模组"。 */
    private static final Set<String> PLATFORM_NAMESPACES = new HashSet<>(
        Arrays.asList("minecraft", "forge", "c", "neoforge", "fml"));

    /** {@link Loader#isModLoaded} 的包装点；单测可替换（没有游戏启动时 Loader 会抛异常）。 */
    private static volatile Predicate<String> loadProbe = ModAvailability::loaderIsModLoaded;

    private ModAvailability() {}

    /** 该 modId 是否已加载。查询异常/没有游戏启动都按"未加载"处理。 */
    public static boolean isLoaded(String modId) {
        if (modId == null || modId.isEmpty()) {
            return false;
        }
        try {
            return loadProbe.test(modId);
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean loaderIsModLoaded(String modId) {
        return Loader.isModLoaded(modId);
    }

    /** 平台命名空间（原版方块/物品、Forge 与通用 {@code c:} 约定）恒可用。 */
    public static boolean isPlatformNamespace(String namespace) {
        return namespace != null && PLATFORM_NAMESPACES.contains(namespace.toLowerCase(Locale.ROOT));
    }

    /** 带命名空间的 id → 命名空间（小写）；没有冒号返回空串。 */
    public static String namespaceOf(String namespacedId) {
        if (namespacedId == null) {
            return "";
        }
        String trimmed = namespacedId.trim();
        int colon = trimmed.indexOf(':');
        return colon > 0 ? trimmed.substring(0, colon)
            .toLowerCase(Locale.ROOT) : "";
    }

    /**
     * 这个带命名空间的引用是否来自**未安装**的模组（→ 永远匹配不到，可正确跳过）。
     *
     * <p>平台命名空间、无命名空间的写法一律返回 false（不把"我们没有"算成"模组不在"）。</p>
     */
    public static boolean isUnavailable(String namespacedId) {
        String namespace = namespaceOf(namespacedId);
        if (namespace.isEmpty() || isPlatformNamespace(namespace)) {
            return false;
        }
        return !isLoaded(namespace);
    }

    /**
     * 某个类在当前运行时是否存在 —— **能力探测**，用来代替版本号校验。
     *
     * <p>可选模组的 API 会随版本增删，但"这个类/方法在不在"永远是最可靠的判据；版本号比较
     * 既容易误判（同一个 mod 的不同 fork 版本号不可比），也会把"其实能用"的版本挡在外面。
     * 探测到缺失时由调用方回退或跳过，遇到真实问题再处理 —— 不硬性限制版本。</p>
     */
    public static boolean isClassPresent(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        try {
            Class.forName(className, false, ModAvailability.class.getClassLoader());
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 单测钩子：替换"是否已加载"的判定。 */
    public static void setLoadProbe(Predicate<String> probe) {
        loadProbe = probe == null ? ModAvailability::loaderIsModLoaded : probe;
    }

    /** 单测钩子：恢复真实判定。 */
    public static void clearLoadProbe() {
        loadProbe = ModAvailability::loaderIsModLoaded;
    }
}
