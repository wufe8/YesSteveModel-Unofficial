package com.fox.ysmu.client.animation.controller;

import cpw.mods.fml.common.Loader;

/**
 * 描述一个可选模组依赖及其在控制器中的检测模式。
 * 每个依赖关联一个 Forge mod ID 和一组字符串模式，
 * 用于在控制器条件/动画条件/动画关键帧中检测对该模组的引用。
 *
 * <p>当运行时检测到某控制器的条件/动画引用了某个模组的变量，
 * 但该模组未加载时，控制器会被跳过以避免无效动画。</p>
 */
public final class ModDependency {

    private final String modId;
    private final String[] detectionPatterns;

    /**
     * @param modId            Forge mod ID（如 {@code "tacz"}），用于 {@link Loader#isModLoaded}
     * @param detectionPatterns 用于在条件/动画文本中检测的字符串模式
     *                          （如 {@code "ctrl.tac_"} 匹配 ctrl.tac_hold_gun 等变量）
     */
    public ModDependency(String modId, String... detectionPatterns) {
        this.modId = modId;
        this.detectionPatterns = detectionPatterns;
    }

    /** 该模组是否已加载。 */
    public boolean isLoaded() {
        return Loader.isModLoaded(modId);
    }

    /** 检查给定文本是否包含此依赖的任意检测模式。 */
    public boolean matches(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String pattern : detectionPatterns) {
            if (text.contains(pattern)) return true;
        }
        return false;
    }

    /** 返回 modId，用于日志和集合操作。 */
    public String getModId() {
        return modId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModDependency)) return false;
        return modId.equals(((ModDependency) o).modId);
    }

    @Override
    public int hashCode() {
        return modId.hashCode();
    }
}
