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

    /** 匹配 ctrl.<state> 后跟可选的参数列表，用于定位控制块起始位置 */
    private static final Pattern CTRL_STATE_PATTERN =
        Pattern.compile("ctrl\\.(\\w+)(?:\\([^)]*\\))?");

    /** 匹配 ctrl.set_animation('<name>')（也接受第二个参数，如 ctrl.loop，见 wiki 自定义函数页） */
    private static final Pattern SET_ANIM_PATTERN =
        Pattern.compile("ctrl\\.set_animation\\s*\\(\\s*['\"]([^'\"]+)['\"](?:\\s*,[^)]*)?\\s*\\)");

    /** 匹配条件守卫后的 set_animation: 如 v.show_car ? { ctrl.set_animation('开车_待命'); } */
    private static final Pattern CONDITIONAL_SET_ANIM_PATTERN =
        Pattern.compile("([^;{]+)\\s*\\?\\s*\\{[^}]*ctrl\\.set_animation\\s*\\(\\s*['\"]([^'\"]+)['\"](?:\\s*,[^)]*)?\\s*\\)[^}]*\\}");

    /** 依次匹配每个 ctrl.set_animation('name')，用于提取它前后的其他 ctrl.* 调用。 */
    private static final Pattern ANY_SET_ANIM_PATTERN =
        Pattern.compile("ctrl\\.set_animation\\s*\\(\\s*['\"]([^'\"]+)['\"]");

    /** 匹配 ctrl.set_beginning_transition_length(<秒>) */
    private static final Pattern SET_TRANSITION_PATTERN =
        Pattern.compile("ctrl\\.set_beginning_transition_length\\s*\\(\\s*([0-9]*\\.?[0-9]+)\\s*\\)");

    /** 匹配 ctrl.indicate_reload（无参数，允许空括号） */
    private static final Pattern INDICATE_RELOAD_PATTERN =
        Pattern.compile("ctrl\\.indicate_reload\\s*(?:\\(\\s*\\))?");

    /** 查找下一个 ctrl.<state>(...) 后最近的 ? { 块，返回 {blockStart, blockEnd, stateEnd, qmarkPos} 或 null */
    private static int[] findNextCtrlBlock(String script, int searchFrom) {
        while (true) {
            Matcher stateMatcher = CTRL_STATE_PATTERN.matcher(script);
            if (!stateMatcher.find(searchFrom)) return null;
            int stateEnd = stateMatcher.end();
            // 在 stateEnd 到下一个 ';'（语句结束符）或下一个 '}'（块结束符）之间找 '?'
            int minBound = Math.min(
                indexOfSkipStrings(script, ';', stateEnd),
                indexOfSkipStrings(script, '}', stateEnd));
            if (minBound < 0) minBound = script.length();
            int qmark = script.indexOf('?', stateEnd);
            if (qmark < 0 || qmark >= minBound) {
                searchFrom = stateEnd;
                continue;
            }
            // 跳过 '?' 后的空格找到 '{'
            int blockOpen = -1;
            for (int i = qmark + 1; i < script.length(); i++) {
                char c = script.charAt(i);
                if (c == '{') { blockOpen = i; break; }
                if (!Character.isWhitespace(c)) break;
            }
            if (blockOpen < 0) {
                searchFrom = qmark + 1;
                continue;
            }
            // 大括号深度匹配
            int depth = 1;
            for (int i = blockOpen + 1; i < script.length(); i++) {
                char c = script.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return new int[]{blockOpen, i, stateEnd, qmark};
                }
            }
            return null;
        }
    }

    /** 检查 ctrl.<state> 到 ? 之间是否只有空白（即纯条件，无 && || 或括号包裹） */
    private static boolean isSimpleCtrlCondition(String script, int stateEnd, int qmarkPos) {
        for (int i = stateEnd; i < qmarkPos; i++) {
            char c = script.charAt(i);
            if (!Character.isWhitespace(c)) return false;
        }
        return true;
    }

    /** 从 blockOpen 位置向前回溯，提取最近的 ctrl.<state> 中的 state 名；找不到/为空返回 null。 */
    private static String extractCtrlStateName(String script, int blockOpen) {
        String prefix = script.substring(0, blockOpen);
        int lastCtrl = prefix.lastIndexOf("ctrl.");
        if (lastCtrl < 0) {
            return null;
        }
        String afterCtrl = prefix.substring(lastCtrl + 5);
        StringBuilder stateName = new StringBuilder();
        for (int i = 0; i < afterCtrl.length(); i++) {
            char c = afterCtrl.charAt(i);
            if (c == '(' || c == '?' || Character.isWhitespace(c)) break;
            stateName.append(c);
        }
        String state = stateName.toString().trim();
        return state.isEmpty() ? null : state;
    }

    /** 在字符串中查找字符 ch，但跳过被单引号或双引号包裹的区域 */
    private static int indexOfSkipStrings(String s, char ch, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ch) return i;
            if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                while (i < s.length() && s.charAt(i) != quote) i++;
            }
        }
        return -1;
    }

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
        int searchFrom = 0;
        while (true) {
            int[] block = findNextCtrlBlock(script, searchFrom);
            if (block == null) break;
            int blockOpen = block[0];
            int blockEnd = block[1];
            int stateEnd = block[2];
            int qmarkPos = block[3];
            searchFrom = blockEnd + 1;
            // 只提取纯 ctrl.<state> ? { 条件（state 名到 ? 之间只有空白）
            if (!isSimpleCtrlCondition(script, stateEnd, qmarkPos)) continue;
            String state = extractCtrlStateName(script, blockOpen);
            if (state == null || result.containsKey(state)) continue;
            // 在块内容中找到第一个 ctrl.set_animation('name')
            String blockContent = script.substring(blockOpen + 1, blockEnd);
            Matcher animMatcher = SET_ANIM_PATTERN.matcher(blockContent);
            if (animMatcher.find()) {
                String animName = animMatcher.group(1);
                if (StringUtils.isNoneBlank(animName)) {
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
        int searchFrom = 0;
        while (true) {
            int[] block = findNextCtrlBlock(script, searchFrom);
            if (block == null) break;
            int blockOpen = block[0];
            int blockEnd = block[1];
            searchFrom = blockEnd + 1;
            String state = extractCtrlStateName(script, blockOpen);
            if (state == null) continue;
            // 块内容
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

    /**
     * {@code ctrl.set_animation('x')} 附近的其他 ctrl.* 调用（wiki 自定义函数页的动画控制部分）：
     * <ul>
     *   <li>{@code ctrl.set_beginning_transition_length(秒)} —— 切到该动画时的过渡时长，
     *       不写就用控制器默认值；</li>
     *   <li>{@code ctrl.indicate_reload} —— 即使目标动画与当前相同也要重新加载。</li>
     * </ul>
     * 两者都按**动画名**记录：同一个动画名在多个状态里给了不同数值时以最后一次为准
     * （脚本里同一动画重复出现且数值不同的情况在实践中不存在）。
     */
    public static final class AnimationHints {

        /** 动画名 → 过渡时长（tick）。 */
        public final Map<String, Double> transitionTicks = new LinkedHashMap<>();
        /** 声明了 indicate_reload 的动画名。 */
        public final java.util.Set<String> reloadAnimations = new java.util.LinkedHashSet<>();

        public void mergeFrom(AnimationHints other) {
            if (other == null) return;
            transitionTicks.putAll(other.transitionTicks);
            reloadAnimations.addAll(other.reloadAnimations);
        }
    }

    /**
     * 提取 {@link AnimationHints}。
     * <p>每个 {@code set_animation} 只与它**自己前面**（到上一个 {@code set_animation} 为止）
     * 的 ctrl.* 调用配对，这样同一个块里有多段动画时不会串味。
     */
    public static AnimationHints parseAnimationHints(byte[] data) {
        AnimationHints hints = new AnimationHints();
        if (data == null || data.length == 0) {
            return hints;
        }
        String script = new String(data, StandardCharsets.UTF_8);
        Matcher matcher = ANY_SET_ANIM_PATTERN.matcher(script);
        int previousEnd = 0;
        while (matcher.find()) {
            String animName = matcher.group(1);
            if (StringUtils.isBlank(animName)) {
                previousEnd = matcher.end();
                continue;
            }
            String segment = script.substring(Math.min(previousEnd, matcher.start()), matcher.start());
            Matcher transition = SET_TRANSITION_PATTERN.matcher(segment);
            Double seconds = null;
            while (transition.find()) {
                seconds = Double.valueOf(transition.group(1));
            }
            if (seconds != null) {
                hints.transitionTicks.put(animName, seconds * 20.0d);
            }
            if (INDICATE_RELOAD_PATTERN.matcher(segment).find()) {
                hints.reloadAnimations.add(animName);
            }
            previousEnd = matcher.end();
        }
        return hints;
    }
}
