package com.fox.ysmu.model.resource;

import org.apache.commons.lang3.StringUtils;

public final class YsmControllerResources {

    public static final String ANIMATION_MAP_PREFIX = "__ysm_controller__";
    /** 前缀标记：.molang 函数文件在 animations map 中的键前缀 */
    public static final String MOLANG_MAP_PREFIX = "__ysm_molang__";

    private YsmControllerResources() {}

    public static boolean isControllerResource(String name) {
        return StringUtils.startsWith(name, ANIMATION_MAP_PREFIX);
    }

    /** 检测是否为 .molang 函数条目 */
    public static boolean isMolangResource(String name) {
        return StringUtils.startsWith(name, MOLANG_MAP_PREFIX);
    }

    /** 从 molang 条目的键中提取原始 .molang 文件名 */
    public static String molangName(String mapKey) {
        if (mapKey == null || !mapKey.startsWith(MOLANG_MAP_PREFIX)) {
            return mapKey;
        }
        return mapKey.substring(MOLANG_MAP_PREFIX.length());
    }

    public static String resourceName(String sourceName, int index) {
        String safeName = StringUtils.defaultIfBlank(sourceName, "controller_" + index)
            .replace('\\', '_')
            .replace('/', '_');
        return ANIMATION_MAP_PREFIX + safeName;
    }

    /** 生成 .molang 文件在 animations map 中的键名 */
    public static String molangResourceName(String molangFileName) {
        String safeName = StringUtils.defaultIfBlank(molangFileName, "unknown")
            .replace('\\', '_')
            .replace('/', '_');
        return MOLANG_MAP_PREFIX + safeName;
    }
}
