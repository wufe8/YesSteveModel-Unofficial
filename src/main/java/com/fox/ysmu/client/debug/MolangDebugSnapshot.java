package com.fox.ysmu.client.debug;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import com.fox.ysmu.client.animation.controller.OpenYsmControllerExpressionEvaluator;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;
import com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * 捕获并格式化 Molang 变量快照，用于调试命令和后续的 debug overlay。
 * 所有方法仅应在客户端调用。
 */
public final class MolangDebugSnapshot {

    private MolangDebugSnapshot() {}

    /** 聊天前缀 */
    public static final String CHAT_PREFIX = "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r";

    /**
     * 搜索别名展开：把开头的 "q."（不区分大小写）当作 "query." 处理，
     * 供 debug overlay 过滤框和 /ysm query 命令共用。
     * 例："q.any_animation_finished" → "query.any_animation_finished"。
     */
    public static String expandSearchAlias(String text) {
        if (text == null || text.length() < 2) return text;
        if ((text.charAt(0) == 'q' || text.charAt(0) == 'Q') && text.charAt(1) == '.') {
            return "query." + text.substring(2);
        }
        return text;
    }

    /**
     * 读取单个变量的当前值。
     *
     * @param name 变量名，如 {@code "ysm.person_view"}、{@code "query.health"}、{@code "v.roaming.bq_eye"}
     * @return 变量值，如果未知则返回 {@code NaN}
     */
    public static double queryVariable(String name) {
        // 语法糖：q. → query.，/ysm query 可直接输 q.xxx
        name = expandSearchAlias(name);
        // ctrl.* 必须最先检查 —— MolangParser.VARIABLES 中可能有
        // computeIfAbsent 残留的默认值 0.0 条目，会覆盖动态评估结果。
        if (name.startsWith("ctrl.")) {
            EntityPlayer p = Minecraft.getMinecraft().thePlayer;
            if (p == null) return Double.NaN;
            return OpenYsmControllerExpressionEvaluator
                .evaluateCtrlState(name.substring("ctrl.".length()), p);
        }
        // 动画完成查询是控制器条件里动态计算的，MolangParser.VARIABLES 里的
        // 静态注册值恒为 0，这里返回控制器求值缓存的最新真实值。
        if ("query.any_animation_finished".equals(name)) {
            return OpenYsmControllerExpressionEvaluator.getLastAnyAnimationFinished();
        }
        if ("query.all_animations_finished".equals(name)) {
            return OpenYsmControllerExpressionEvaluator.getLastAllAnimationsFinished();
        }
        // 1. 静态注册变量 (query.* / ysm.* / math.*)
        software.bernie.geckolib3.core.molang.LazyVariable var =
            MolangParser.VARIABLES.get(name);
        if (var != null) {
            return var.get();
        }
        // 2. v.* 变量 — 先查最近一帧 ScopeState 快照（嵌套赋值/动画 timeline
        //    写入的真实值，如 v.wet），再查 PENDING_ROAMING（GUI/轮盘设置的
        //    漫游值），最后回退 MolangParser.VARIABLES（顶层赋值写入的）。
        if (name.startsWith("v.")) {
            Double frameVal = MolangPhysicsRuntime.getLastFrameVariable(name);
            if (frameVal != null) return frameVal;
            String key = name.substring(2);
            Double roamingVal = OpenYsmPlayerControllerRuntime.PENDING_ROAMING.get(key);
            if (roamingVal != null) return roamingVal;
            // 也查一下 MolangParser.VARIABLES（InstrutionExecutor 写入的）
            software.bernie.geckolib3.core.molang.LazyVariable gVar =
                MolangParser.VARIABLES.get(name);
            if (gVar != null) return gVar.get();
            return Double.NaN;
        }
        return Double.NaN;
    }

    /**
     * 批量读取一组变量。
     */
    public static Map<String, Double> queryVariables(List<String> names) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String name : names) {
            double value = queryVariable(name);
            if (!Double.isNaN(value)) {
                result.put(name, value);
            }
        }
        return result;
    }

    /**
     * 查询匹配通配符模式的所有变量。
     * 支持: {@code "ysm.*"}, {@code "query.is_*"}, {@code "v.roaming.*"}
     */
    public static Map<String, Double> queryWildcard(String pattern) {
        pattern = expandSearchAlias(pattern);
        Map<String, Double> result = new LinkedHashMap<>();
        if (!pattern.endsWith("*")) {
            double v = queryVariable(pattern);
            if (!Double.isNaN(v)) result.put(pattern, v);
            return result;
        }
        String prefix = pattern.substring(0, pattern.length() - 1);
        // 扫描 MolangParser.VARIABLES 中的所有注册变量
        for (Map.Entry<String, software.bernie.geckolib3.core.molang.LazyVariable> entry
            : MolangParser.VARIABLES.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey(), entry.getValue().get());
            }
        }
        // v.roaming.* 通配符 — 扫描 PENDING_ROAMING
        if ("v.roaming.*".equals(pattern) || "v.*".equals(pattern)) {
            String roamingPrefix = "v.roaming.*".equals(pattern) ? "v.roaming." : "v.";
            for (Map.Entry<String, Double> entry
                : OpenYsmPlayerControllerRuntime.PENDING_ROAMING.entrySet()) {
                result.put(roamingPrefix + entry.getKey(), entry.getValue());
            }
            // 补充最近一帧 ScopeState 快照：动画嵌套赋值（如 v.wet）写入的值
            // 不在 PENDING_ROAMING，也不在 MolangParser.VARIABLES，需从这里读。
            for (Map.Entry<String, Double> entry
                : MolangPhysicsRuntime.getLastFrameVariables().entrySet()) {
                if ("v.*".equals(pattern) || entry.getKey().startsWith("v.roaming.")) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        }
        // query.* 通配符 — 补充动画完成查询的真实动态值
        if (prefix.startsWith("query.")) {
            addAnimationFinishedEntries(result);
        }
        return result;
    }

    /**
     * 把动画完成查询的真实动态值写入 result：覆盖全局 query.any/all_animation_finished
     * （MolangParser.VARIABLES 里的静态注册值恒为 0），并补充各控制器单独的结果
     * （query.<q>@<geckoControllerName>）。
     */
    private static void addAnimationFinishedEntries(Map<String, Double> result) {
        result.put("query.any_animation_finished",
            OpenYsmControllerExpressionEvaluator.getLastAnyAnimationFinished());
        result.put("query.all_animations_finished",
            OpenYsmControllerExpressionEvaluator.getLastAllAnimationsFinished());
        for (Map.Entry<String, Double> e : OpenYsmControllerExpressionEvaluator.getLastAnimationFinished().entrySet()) {
            int sep = e.getKey().indexOf('|');
            if (sep > 0) {
                result.put("query." + e.getKey().substring(sep + 1) + "@" + e.getKey().substring(0, sep),
                    e.getValue());
            }
        }
    }

    // ---- 聊天输出 ----

    /**
     * 获取所有变量的完整快照（用于 overlay 全量显示）。
     * 包括 query.* / ysm.* / math.* / ctrl.* / v.roaming.*
     */
    public static Map<String, Double> getAllVariables() {
        Map<String, Double> result = new LinkedHashMap<>();
        // MolangParser.VARIABLES 中的静态注册变量
        for (Map.Entry<String, LazyVariable> entry : MolangParser.VARIABLES.entrySet()) {
            result.put(entry.getKey(), entry.getValue().get());
        }
        // v.roaming.* 漫游变量
        for (Map.Entry<String, Double> entry : OpenYsmPlayerControllerRuntime.PENDING_ROAMING.entrySet()) {
            result.put("v." + entry.getKey(), entry.getValue());
        }
        // 最近一帧 ScopeState 快照 — 覆盖 VARIABLES 中的默认值，反映动画嵌套赋值
        // （如 v.wet）写入的真实帧值，并补充非 roaming 的 v.*（如 v.anim_ctrl）。
        for (Map.Entry<String, Double> entry : MolangPhysicsRuntime.getLastFrameVariables().entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }
        // ctrl.* 动态状态 — 使用 evaluator 的实时评估
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        if (p != null) {
            String[] ctrlStates = {"idle", "death", "sleep", "swim", "climb", "climbing",
                "ladder_up", "ladder_stillness", "ladder_down",
                "ride", "ride_pig", "boat", "sit",
                "elytra_fly", "fly", "swim_stand",
                "attacked", "jump", "sneak", "sneaking", "run", "walk"};
            for (String s : ctrlStates) {
                result.put("ctrl." + s,
                    OpenYsmControllerExpressionEvaluator.evaluateCtrlState(s, p));
            }
        }
        // 覆盖静态注册的动画完成查询，显示控制器条件求值出的真实动态值，
        // 并补充各控制器单独的结果（query.<q>@<geckoControllerName>）。
        addAnimationFinishedEntries(result);
        return result;
    }

    /**
     * 在聊天框输出一组变量值。
     */
    public static void printToChat(Map<String, Double> values) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return;
        if (values.isEmpty()) {
            player.addChatMessage(new ChatComponentText(
                CHAT_PREFIX + " §eNo variables matched."));
            return;
        }
        player.addChatMessage(new ChatComponentText(
            CHAT_PREFIX + " §a" + values.size() + " variable(s):"));
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            player.addChatMessage(new ChatComponentText(
                "  §e" + entry.getKey() + "§r = " + formatValue(entry.getValue())));
        }
    }

    /**
     * 解析变量名，支持精确匹配、通配符(*)前缀匹配、以及模糊子串匹配。
     *
     * @param pattern 变量名模式
     * @return 匹配的变量名 → 值的映射
     */
    public static Map<String, Double> resolveVariables(String pattern) {
        // 语法糖：q. → query.，/ysm query 可直接输 q.xxx
        pattern = expandSearchAlias(pattern);
        // 1. 精确匹配
        double exact = queryVariable(pattern);
        if (!Double.isNaN(exact)) {
            Map<String, Double> result = new LinkedHashMap<>();
            result.put(pattern, exact);
            return result;
        }
        // 2. 通配符 * 前缀匹配
        if (pattern.contains("*")) {
            String prefix = pattern.substring(0, pattern.lastIndexOf('*'));
            Map<String, Double> result = new LinkedHashMap<>();
            for (Map.Entry<String, software.bernie.geckolib3.core.molang.LazyVariable> entry
                : MolangParser.VARIABLES.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    result.put(entry.getKey(), entry.getValue().get());
                }
            }
            // v.roaming.* 通配符
            if ((prefix.equals("v.") || prefix.equals("v.roaming.")) && result.isEmpty()) {
                String rp = prefix.equals("v.roaming.") ? "v.roaming." : "v.";
                for (Map.Entry<String, Double> entry
                    : OpenYsmPlayerControllerRuntime.PENDING_ROAMING.entrySet()) {
                    result.put(rp + entry.getKey(), entry.getValue());
                }
            }
            // ctrl.* 通配符 — 使用动态评估
            if (prefix.equals("ctrl.")) {
                addDynamicCtrlStates(result);
            }
            // query.* 通配符 — 补充动画完成查询的真实动态值
            if (prefix.startsWith("query.")) {
                addAnimationFinishedEntries(result);
            }
            if (!result.isEmpty()) return result;
        }
        // 3. 模糊子串匹配（不包含通配符但也不是精确匹配）
        Map<String, Double> result = new LinkedHashMap<>();
        String lower = pattern.toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<String, software.bernie.geckolib3.core.molang.LazyVariable> entry
            : MolangParser.VARIABLES.entrySet()) {
            if (entry.getKey().toLowerCase(java.util.Locale.ROOT).contains(lower)) {
                result.put(entry.getKey(), entry.getValue().get());
            }
        }
        // v. 模糊匹配 — 扫描 PENDING_ROAMING
        if (lower.startsWith("v.") || lower.startsWith("v.roaming.")) {
            for (Map.Entry<String, Double> entry
                : OpenYsmPlayerControllerRuntime.PENDING_ROAMING.entrySet()) {
                if (entry.getKey().toLowerCase(java.util.Locale.ROOT).contains(
                    lower.startsWith("v.roaming.") ? lower.substring(10) : lower.substring(2))) {
                    result.put("v." + entry.getKey(), entry.getValue());
                }
            }
        }
        // ctrl.* 模糊匹配 — 使用动态评估
        if (lower.startsWith("ctrl.")) {
            addDynamicCtrlStates(result);
        }
        // 模糊匹配动画完成查询 — 用真实动态值覆盖静态 0
        if (lower.contains("animation_finished")) {
            addAnimationFinishedEntries(result);
        }
        return result;
    }

    /** 把动态 ctrl.* 状态评估结果加入 result */
    private static void addDynamicCtrlStates(Map<String, Double> result) {
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        if (p == null) return;
        String[] ctrlStates = {"idle", "death", "sleep", "swim", "climb", "climbing",
            "ladder_up", "ladder_stillness", "ladder_down",
            "ride", "ride_pig", "boat", "sit",
            "elytra_fly", "fly", "swim_stand",
            "attacked", "jump", "sneak", "sneaking", "run", "walk"};
        for (String s : ctrlStates) {
            result.put("ctrl." + s,
                com.fox.ysmu.client.animation.controller.OpenYsmControllerExpressionEvaluator
                    .evaluateCtrlState(s, p));
        }
    }

    /**
     * 求值一个 Molang 表达式/函数并把结果显示在聊天框。
     * 供 {@code /ysm debug eval <expression>} 使用：输入任意函数或表达式
     * （如 {@code math.floor(3.7)}、{@code math.clamp(5, 0, 2) + 1}），
     * 输出其返回值。解析/求值在客户端进行（MolangParser 为客户端单例）。
     */
    public static void evalToChat(String expression) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return;
        if (expression == null || expression.trim().isEmpty()) {
            player.addChatMessage(new ChatComponentText(
                CHAT_PREFIX + " §cUsage: /ysm debug eval <expression>"));
            return;
        }
        try {
            software.bernie.geckolib3.core.molang.MolangParser parser =
                software.bernie.geckolib3.resource.GeckoLibCache.getInstance().parser;
            software.bernie.geckolib3.core.molang.expressions.MolangExpression expr =
                parser.parseExpression(expression);
            double result = expr.get();
            player.addChatMessage(new ChatComponentText(
                CHAT_PREFIX + " §a" + sanitize(expression) + "§r = " + formatValue(result)));
        } catch (Exception e) {
            player.addChatMessage(new ChatComponentText(
                CHAT_PREFIX + " §cError evaluating: §f" + sanitize(expression)
                    + "§c — " + sanitize(String.valueOf(e.getMessage()))));
        }
    }

    /**
     * 去掉字符串里的 MC 格式码字符（0xA7），防止回显时被渲染成颜色/格式码。
     * eval 的表达式是用户输入，含 {@code §} 时应原样显示而非变成聊天格式码。
     */
    private static String sanitize(String text) {
        if (text == null) return "";
        return text.replace('\u00a7', '&');
    }

    /**
     * 在聊天框输出变量值，支持通配符和模糊匹配。
     */
    public static void printSingleToChat(String name) {
        Map<String, Double> resolved = resolveVariables(name);
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return;
        if (resolved.isEmpty()) {
            player.addChatMessage(new ChatComponentText(
                CHAT_PREFIX + " §e" + name + "§r = §7<unknown>"));
        } else if (resolved.size() == 1 && resolved.containsKey(name)) {
            // 精确匹配单个变量
            double value = resolved.get(name);
            player.addChatMessage(new ChatComponentText(
                CHAT_PREFIX + " §e" + name + "§r = " + formatValue(value)));
        } else {
            // 多个匹配结果
            player.addChatMessage(new ChatComponentText(
                CHAT_PREFIX + " §a" + resolved.size() + " variable(s) matching §e" + name + "§r:"));
            for (Map.Entry<String, Double> entry : resolved.entrySet()) {
                player.addChatMessage(new ChatComponentText(
                    "  §e" + entry.getKey() + "§r = " + formatValue(entry.getValue())));
            }
        }
    }

    // ---- 内部辅助 ----

    private static String formatValue(double value) {
        if (Double.isNaN(value)) return "§7NaN";
        if (Double.isInfinite(value)) return "§c" + (value > 0 ? "+∞" : "-∞");
        if (Math.abs(value) < 0.000001) return "§70.0§r (false)";
        if (Math.abs(value - 1.0) < 0.000001) return "§a1.0§r (true)";
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return "§b" + (long) value + ".0";
        }
        return String.format("§b%.6f", value);
    }


}
