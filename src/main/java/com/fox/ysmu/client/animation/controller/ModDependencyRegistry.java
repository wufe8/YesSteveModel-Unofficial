package com.fox.ysmu.client.animation.controller;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fox.ysmu.Config;
import com.fox.ysmu.ysmu;

/**
 * 可选模组依赖注册中心。管理所有已知的 {@link ModDependency}，
 * 提供统一的检测和查询方法。
 *
 * <p>设计为集中式注册，避免在控制器解析和运行时散落硬编码的
 * 条件检测。每新增一个可选模组的兼容，只需在此注册一个
 * {@link ModDependency} 实例即可。</p>
 */
public final class ModDependencyRegistry {

    private static final List<ModDependency> DEPENDENCIES = new ArrayList<>();

    private ModDependencyRegistry() {}

    /** 注册一个可选模组依赖。通常在 {@code ClientProxy} 初始化时调用。 */
    public static void register(ModDependency dep) {
        if (dep == null) return;
        // 避免重复注册
        for (ModDependency existing : DEPENDENCIES) {
            if (existing.getModId().equals(dep.getModId())) return;
        }
        DEPENDENCIES.add(dep);
        ysmu.LOG.info("[YSMU-DEP] Registered mod dependency: {} (loaded={})",
            dep.getModId(), dep.isLoaded());
    }

    /** 返回所有已注册的依赖（不可修改视图）。 */
    public static List<ModDependency> getAll() {
        return Collections.unmodifiableList(DEPENDENCIES);
    }

    /**
     * 扫描给定文本，返回所有匹配的已注册依赖的 modId 集合。
     * 用于在控制器解析阶段检测依赖。
     */
    public static Set<String> detect(String text) {
        if (text == null || text.isEmpty()) return Collections.emptySet();
        Set<String> result = new LinkedHashSet<>();
        for (ModDependency dep : DEPENDENCIES) {
            if (dep.matches(text)) {
                result.add(dep.getModId());
            }
        }
        return result;
    }

    /**
     * 扫描多个文本段落，返回所有匹配的 modId 集合。
     */
    public static Set<String> detectAll(Collection<String> texts) {
        if (texts == null || texts.isEmpty()) return Collections.emptySet();
        Set<String> result = new LinkedHashSet<>();
        for (String text : texts) {
            result.addAll(detect(text));
        }
        return result;
    }

    /**
     * 检查给定的 modId 集合中，是否有任意一个依赖对应模组未加载。
     * 用于运行时决定是否跳过控制器。
     */
    public static boolean hasUnmetDependencies(Set<String> modIds) {
        if (modIds == null || modIds.isEmpty()) return false;
        for (ModDependency dep : DEPENDENCIES) {
            if (modIds.contains(dep.getModId()) && !dep.isLoaded()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回未加载的依赖 modId 集合。用于日志输出。
     */
    public static Set<String> getUnmetModIds(Set<String> modIds) {
        if (modIds == null || modIds.isEmpty()) return Collections.emptySet();
        Set<String> result = new LinkedHashSet<>();
        for (ModDependency dep : DEPENDENCIES) {
            if (modIds.contains(dep.getModId()) && !dep.isLoaded()) {
                result.add(dep.getModId());
            }
        }
        return result;
    }
}
