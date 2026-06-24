package com.fox.ysmu.client.animation.molang;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

/**
 * 解析高版本 YSM 模型的 .molang 函数文件，提取 ctrl.<state> → 动画名 的映射。
 * <p>
 * .molang 函数文件作为主动画控制器使用，结构如下：
 * <pre>
 * ctrl.idle ? {
 *     ctrl.set_animation('正常_待命');
 *     return ctrl.state_continue;
 * };
 * ctrl.walk ? {
 *     !v.show_car ? { ctrl.set_animation('正常_行走'); };
 *     v.show_car  ? { ctrl.set_animation('开车_行走'); };
 * };
 * </pre>
 * 解析器从这些块中提取出 "idle" → "正常_待命" 等映射，
 * 并识别有条件分支的替代动画（如 v.show_car 时的开车动画）。
 */
public final class MolangFunctionParser {

    private MolangFunctionParser() {}

    /** 匹配 ctrl.<state> ? { ... }; 块 */
    private static final Pattern CTRL_BLOCK_PATTERN =
        Pattern.compile("ctrl\\.(\\w+)\\s*\\?\\s*\\{[^}]*ctrl\\.set_animation\\s*\\(\\s*'([^']+)'\\s*\\)[^}]*\\};");

    /** 匹配 ctrl.set_animation('<name>') 调用 */
    private static final Pattern SET_ANIM_PATTERN =
        Pattern.compile("ctrl\\.set_animation\\s*\\(\\s*'([^']+)'\\s*\\)");

    /** 匹配条件守卫后的 set_animation: 如 v.show_car ? { ctrl.set_animation('开车_待命'); } */
    private static final Pattern CONDITIONAL_SET_ANIM_PATTERN =
        Pattern.compile("([^;{]+)\\s*\\?\\s*\\{[^}]*ctrl\\.set_animation\\s*\\(\\s*'([^']+)'\\s*\\)[^}]*\\}");

    /**
     * 从 .molang 函数文件的原始字节中解析出 ctrl.<state> → 动画名 的映射。
     * <p>
     * 只提取每个 ctrl.<state> 块中的第一个 ctrl.set_animation() 调用作为默认映射。
     *
     * @param data .molang 文件原始字节
     * @return state → animationName 的映射，不会为 null
     */
    public static Map<String, String> parseStateToAnimationMap(byte[] data) {
        Map<String, String> result = new LinkedHashMap<>();
        if (data == null || data.length == 0) {
            return result;
        }
        String script = new String(data, StandardCharsets.UTF_8);
        Matcher matcher = CTRL_BLOCK_PATTERN.matcher(script);
        while (matcher.find()) {
            String state = matcher.group(1);
            String animName = matcher.group(2);
            if (StringUtils.isNoneBlank(state) && StringUtils.isNoneBlank(animName)) {
                if (!result.containsKey(state)) {
                    result.put(state, animName);
                }
            }
        }
        return result;
    }

    /**
     * 从 .molang 函数文件的原始字节中解析条件动画映射。
     * <p>
     * 对于每个 ctrl.<state> 块，提取所有有条件守卫的 ctrl.set_animation() 调用。
     * 例如 v.show_car ? { ctrl.set_animation('开车_待命'); } 会生成
     * ("idle", "v.show_car") → "开车_待命" 的映射。
     *
     * @param data .molang 文件原始字节
     * @return state → (condition, animationName) 列表，不会为 null
     */
    public static Map<String, List<Pair<String, String>>> parseConditionalAnimations(byte[] data) {
        Map<String, List<Pair<String, String>>> result = new LinkedHashMap<>();
        if (data == null || data.length == 0) {
            return result;
        }
        String script = new String(data, StandardCharsets.UTF_8);
        // 找到每个 ctrl.<state> ? 并定位其 { ... } 块的起止
        Matcher ctrlMatcher = Pattern.compile("ctrl\\.(\\w+)\\s*\\?").matcher(script);
        while (ctrlMatcher.find()) {
            String state = ctrlMatcher.group(1);
            // 从 ctrl.<state> ? 之后找到第一个 '{'（即该块的开括号）
            int searchStart = ctrlMatcher.end();
            int blockOpen = -1;
            for (int i = searchStart; i < script.length(); i++) {
                if (script.charAt(i) == '{') { blockOpen = i; break; }
            }
            if (blockOpen < 0) continue;
            // 从此 '{' 开始匹配对应的 '}'
            int braceDepth = 1;
            int blockEnd = -1;
            for (int i = blockOpen + 1; i < script.length(); i++) {
                char c = script.charAt(i);
                if (c == '{') braceDepth++;
                else if (c == '}') { braceDepth--; if (braceDepth == 0) { blockEnd = i; break; } }
            }
            if (blockEnd < 0) continue;
            // 块内容 = 开括号和闭括号之间的部分
            String blockContent = script.substring(blockOpen + 1, blockEnd);
            // 在这个块中找所有条件守卫的 set_animation
            Matcher condMatcher = CONDITIONAL_SET_ANIM_PATTERN.matcher(blockContent);
            while (condMatcher.find()) {
                String condition = condMatcher.group(1).trim();
                String animName = condMatcher.group(2);
                if (StringUtils.isNoneBlank(condition) && StringUtils.isNoneBlank(animName)) {
                    result.computeIfAbsent(state, k -> new ArrayList<>())
                        .add(Pair.of(condition, animName));
                }
            }
        }
        return result;
    }

    /**
     * 检查二进制数据是否为 .molang 函数脚本。
     */
    public static boolean isMolangScript(byte[] data) {
        if (data == null || data.length == 0) return false;
        String content = new String(data, StandardCharsets.UTF_8);
        return content.contains("ctrl.") && content.contains("set_animation");
    }
}
