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
 * <p>
 * 三条容易漏掉的规则（见 wiki「自定义函数」页）：
 * <ul>
 *   <li><b>注释</b>：脚本支持 C 风格注释，而且注释里常带 {@code ? } ;} 之类的字符。
 *       不先剥掉注释，条件里就会混进 {@code // 某说明}，表达式求值必然失败。</li>
 *   <li><b>复合条件守卫</b>：{@code (ctrl.walk && ysm.input_vertical < 0.1) ? { ... }} 既不是
 *       纯 {@code ctrl.<state>} 条件（进不了状态映射），块内通常也没有嵌套三元（条件提取也抓不到）。
 *       它应该成为该状态的**条件替代动画**，且比状态本身更窄、要先判。</li>
 *   <li><b>{@code ctrl.state_bypass}</b>："当前控制逻辑无操作，交回内置控制逻辑"。写在某个
 *       {@code ctrl.<state>} 块**自己那一层**时，表示这个状态不由脚本接管 → 不出默认映射
 *       （块内条件分支里的替代动画仍然有效）。脚本**结尾**的 bypass 是整段脚本的兜底，
 *       含义就是"没提到的状态用内置逻辑"，而这一点已经由"没有映射条目"表达了，不需要额外处理。</li>
 * </ul>
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

    /** 状态级"交回内置逻辑"标记（wiki：ctrl.state_bypass）。只认名字，允许 return 后的空格写法差异。 */
    private static final String STATE_BYPASS = "state_bypass";

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
     * 剥掉 C 风格注释（{@code //} 行注释与块注释），字符串常量原样保留。
     * <p>
     * 必须先做这一步：脚本里的注释写了什么都有可能（问号、分号、大括号、中文说明），
     * 而条件提取是纯文本匹配，注释混进条件后表达式求值一律失败 —— 表现为
     * "脚本里明明写了这个分支，动画却永远走默认那条"。
     */
    static String stripComments(String script) {
        StringBuilder out = new StringBuilder(script.length());
        int i = 0;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (c == '\'' || c == '"') {
                int end = skipQuoted(script, i);
                out.append(script, i, Math.min(end + 1, script.length()));
                i = end + 1;
                continue;
            }
            if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '/') {
                i += 2;
                while (i < script.length() && script.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? script.length() : end + 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 返回 s 中 quoteIndex 处引号的**闭合**引号下标；没有闭合则返回最后一个字符下标。 */
    private static int skipQuoted(String s, int quoteIndex) {
        char quote = s.charAt(quoteIndex);
        for (int i = quoteIndex + 1; i < s.length(); i++) {
            if (s.charAt(i) == quote) return i;
        }
        return s.length() - 1;
    }

    /**
     * 块内容里**自己那一层**（不在任何嵌套 {@code {}} 内）有没有 {@code ctrl.state_bypass}。
     * 嵌套在条件分支里的 bypass 只是一个分支，不能作废该状态在整个脚本里的覆盖。
     */
    private static boolean hasUnconditionalStateBypass(String blockContent) {
        int depth = 0;
        for (int i = 0; i < blockContent.length(); i++) {
            char c = blockContent.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(blockContent, i);
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (depth == 0 && blockContent.startsWith(STATE_BYPASS, i)) {
                return true;
            }
        }
        return false;
    }

    /** 条件表达式的起点：从 ? 往前找最近的语句边界（{@code ; } { }}）或脚本开头。 */
    private static int statementStart(String script, int qmarkPos) {
        for (int i = qmarkPos - 1; i >= 0; i--) {
            char c = script.charAt(i);
            if (c == ';' || c == '}' || c == '{') {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * 从 .molang 函数文件的原始字节中解析出 ctrl.<state> → 动画名 的映射。
     * <p>
     * 只提取每个 ctrl.<state> 块中的第一个 ctrl.set_animation() 调用作为默认映射；
     * 块自己那一层 {@code return ctrl.state_bypass} 的状态不出映射（交回内置逻辑，
     * 见类注释）。
     *
     * @param data .molang 文件原始字节
     * @return state → animationName 的映射，不会为 null
     */
    public static Map<String, String> parseStateToAnimationMap(byte[] data) {
        Map<String, String> result = new LinkedHashMap<>();
        if (data == null || data.length == 0) {
            return result;
        }
        String script = stripComments(new String(data, StandardCharsets.UTF_8));
        int searchFrom = 0;
        while (true) {
            int[] block = findNextCtrlBlock(script, searchFrom);
            if (block == null) break;
            int blockOpen = block[0];
            int blockEnd = block[1];
            int stateEnd = block[2];
            int qmarkPos = block[3];
            searchFrom = blockEnd + 1;
            // 只提取纯 ctrl.<state> ? { 条件（state 名到 ? 之间只有空白）；
            // 复合条件守卫走 parseConditionalAnimations 的替代动画路径。
            if (!isSimpleCtrlCondition(script, stateEnd, qmarkPos)) continue;
            String state = extractCtrlStateName(script, blockOpen);
            if (state == null || result.containsKey(state)) continue;
            String blockContent = script.substring(blockOpen + 1, blockEnd);
            // 脚本在这个状态上明确说了"我不管" —— 不要拿块里第一条 set_animation 冒充默认动画。
            if (hasUnconditionalStateBypass(blockContent)) continue;
            // 在块内容中找到第一个 ctrl.set_animation('name')
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
     * 对每个 ctrl.<state> 块，提取所有有条件守卫的 ctrl.set_animation() 调用。
     * 例如 v.show_car ? { ctrl.set_animation('开车_待命'); } 会生成
     * ("idle", "v.show_car") → "开车_待命" 的映射。
     * <p>
     * 两类守卫都会收集，并且**块自己的守卫排在前面**（它比状态本身更窄，脚本也是先判它）：
     * <ol>
     *   <li>块自己那一层的守卫，但只在它不是纯 {@code ctrl.<state>} 时收集，例如
     *       {@code (ctrl.walk && ysm.input_vertical < 0.1) ? { ctrl.set_animation('walkBack'); }}；</li>
     *   <li>块内部嵌套的条件分支，例如 {@code v.show_car ? { ctrl.set_animation('开车_行走'); }}。</li>
     * </ol>
     *
     * @param data .molang 文件原始字节
     * @return state → (condition, animationName) 列表，不会为 null
     */
    public static Map<String, List<Pair<String, String>>> parseConditionalAnimations(byte[] data) {
        Map<String, List<Pair<String, String>>> result = new LinkedHashMap<>();
        if (data == null || data.length == 0) {
            return result;
        }
        String script = stripComments(new String(data, StandardCharsets.UTF_8));
        int searchFrom = 0;
        while (true) {
            int[] block = findNextCtrlBlock(script, searchFrom);
            if (block == null) break;
            int blockOpen = block[0];
            int blockEnd = block[1];
            int stateEnd = block[2];
            int qmarkPos = block[3];
            searchFrom = blockEnd + 1;
            String state = extractCtrlStateName(script, blockOpen);
            if (state == null) continue;
            // 块内容
            String blockContent = script.substring(blockOpen + 1, blockEnd);
            // 1) 先收集**内层条件**的 set_animation：`cond ? { ctrl.set_animation('X'); ... }`。
            List<Pair<String, String>> inner = new ArrayList<>();
            Matcher condMatcher = CONDITIONAL_SET_ANIM_PATTERN.matcher(blockContent);
            while (condMatcher.find()) {
                String condition = condMatcher.group(1).trim();
                String animName = condMatcher.group(2);
                if (StringUtils.isNoneBlank(condition) && StringUtils.isNoneBlank(animName)) {
                    inner.add(Pair.of(condition, animName));
                }
            }
            // 2) 只有当块里**没有**内层条件动画时，才把"外层复合守卫 + 块里第一个 set_animation"
            // 当成一条替代动画。
            // 否则外层守卫会顶替掉内层的真判定：例如
            //   ctrl.run || ctrl.walk ? { (v.east==false && facing==5) ? { set_animation('defWall') … } … }
            // 曾被登记成 walk -> [("ctrl.run||ctrl.walk", "defWall")]，而该守卫在**走路时无条件成立** ——
            // 四个方向都没有墙的空旷地形（内层条件全假）也会播 defWall，相对方块/朝向判定被整个绕过。
            // 内层条件才是作者真正的意图（外层守卫只是"别在没移动时求值"），所以有内层条目时只留内层。
            if (inner.isEmpty() && !isSimpleCtrlCondition(script, stateEnd, qmarkPos)) {
                String guard = script.substring(statementStart(script, qmarkPos), qmarkPos).trim();
                Matcher guardAnim = SET_ANIM_PATTERN.matcher(blockContent);
                if (StringUtils.isNoneBlank(guard) && guardAnim.find()) {
                    String animName = guardAnim.group(1);
                    if (StringUtils.isNoneBlank(animName)) {
                        result.computeIfAbsent(state, k -> new ArrayList<>())
                            .add(Pair.of(guard, animName));
                    }
                }
            }
            for (Pair<String, String> entry : inner) {
                result.computeIfAbsent(state, k -> new ArrayList<>())
                    .add(entry);
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
        String script = stripComments(new String(data, StandardCharsets.UTF_8));
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
