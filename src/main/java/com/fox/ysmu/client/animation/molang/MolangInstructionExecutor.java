package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangException;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.resource.GeckoLibCache;

public final class MolangInstructionExecutor {

    private static final Set<String> WARNED_INSTRUCTIONS = Collections
        .newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** Cache for parsed Molang expressions — avoids re-parsing same string every frame. */
    private static final ConcurrentHashMap<String, IValue> EXPRESSION_CACHE = new ConcurrentHashMap<>();
    /**
     * Instruction-level cache: maps the full instruction string (e.g.
     * "v.bq_eye = v.roaming.bq_eye;;v.qh = 0") to a pre-parsed array of
     * operations.  Subsequent executions of the same instruction string
     * skip splitStatements(), parseCached(), and findAssignmentOperator()
     * entirely — only the parsed IValue.get() calls remain.
     */
    private static final ConcurrentHashMap<String, ParsedInstruction[]> INSTRUCTION_CACHE = new ConcurrentHashMap<>();

    /** A single pre-parsed operation within a multi-statement instruction. */
    private static final class ParsedInstruction {
        final boolean isAssignment;
        final String target;   // non-null for assignments starting with "v."
        final IValue value;    // the parsed expression to evaluate

        ParsedInstruction(boolean isAssignment, String target, IValue value) {
            this.isAssignment = isAssignment;
            this.target = target;
            this.value = value;
        }
    }

    private MolangInstructionExecutor() {}

    /** 限流：只对 v.wet / v.fireP / particle / enchantment 相关指令打印一次解析结果。 */
    private static final java.util.Set<String> LOGGED_INSTRUCTION = java.util.Collections
        .newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /** 已经打过"该控制器的时间轴被执行过"日志的控制器名。 */
    private static final java.util.Set<String> LOGGED_TIMELINE = java.util.Collections
        .newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /**
     * 诊断（{@code DebugController}，每个控制器一条）：某个控制器的**时间轴被触发了**。
     * <p>
     * 这是"动画在播"和"时间轴里的 Molang 真的执行了"之间的分界线。没有它的时候，只能靠
     * 时间轴写出来的变量反推；本轮排查就因为没有这条线而多绕了一圈。
     */
    public static void noteTimelineExecution(String controllerName, String instructions) {
        if (!com.fox.ysmu.Config.DEBUG_CONTROLLER || controllerName == null
            || !LOGGED_TIMELINE.add(controllerName)) {
            return;
        }
        int chars = instructions == null ? 0 : instructions.length();
        ysmu.LOG.info("[YSMU-TL] controller '{}' fired a timeline instruction ({} chars)", controllerName, chars);
    }

    /** 模型缓存刷新时清掉一次性日志去重表。 */
    public static void clearTimelineLog() {
        LOGGED_TIMELINE.clear();
    }

    public static void execute(String instructions) {
        if (StringUtils.isBlank(instructions)) {
            return;
        }

        // 诊断：确认 parallel3 / swing:sword 的 timeline 指令是否真的被收到（只打一次）
        if (com.fox.ysmu.Config.DEBUG_CONTROLLER) {
            String lower = instructions.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("v.wet") || lower.contains("v.firep")
                || lower.contains("particle") || lower.contains("enchantment_level")
                || lower.contains("relative_block_name")) {
                if (LOGGED_INSTRUCTION.add(instructions)) {
                    ysmu.LOG.info("[YSMU-TL-RECV] instruction received ({} chars): {}",
                        instructions.length(),
                        instructions.length() > 400 ? instructions.substring(0, 400) + "..." : instructions);
                }
            }
        }

        // ── Instruction-level cache hit: skip split + parse entirely ──
        ParsedInstruction[] cached = INSTRUCTION_CACHE.get(instructions);
        if (cached != null) {
            executeCached(cached);
            return;
        }

        // ── Cache miss: parse the instruction string and cache the result ──
        MolangParser parser = GeckoLibCache.getInstance().parser;
        Iterable<String> statements;
        try {
            statements = executableStatements(instructions);
        } catch (MolangException e) {
            warnOnce(instructions, e);
            return;
        }
        java.util.ArrayList<ParsedInstruction> ops = new java.util.ArrayList<>();
        for (String statement : statements) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // Check if this is an assignment (e.g. "v.roaming.h=0" or "v.hold=5")
            int eqIdx = findAssignmentOperator(trimmed);
            if (eqIdx > 0) {
                String target = trimmed.substring(0, eqIdx).trim();
                String valueExpr = trimmed.substring(eqIdx + 1).trim();
                if (target.startsWith("v.")) {
                    try {
                        IValue val = parseCached(parser, valueExpr);
                        if (val != null) {
                            ops.add(new ParsedInstruction(true, target, val));
                        }
                    } catch (Exception e) {
                        warnOnce(trimmed, e);
                    }
                }
                continue;
            }
            // Not an assignment — evaluate as a normal expression
            try {
                IValue result = parseCached(parser, trimmed);
                if (result != null) {
                    ops.add(new ParsedInstruction(false, null, result));
                }
            } catch (Exception e) {
                warnOnce(trimmed, e);
            }
        }
        if (!ops.isEmpty()) {
            cached = ops.toArray(new ParsedInstruction[0]);
            INSTRUCTION_CACHE.put(instructions, cached);
            executeCached(cached);
        }
    }

    /**
     * 把一条 timeline 指令串切成**可执行语句**：先剥 C 风格注释，再按 {@code ;} 切开，丢掉空白。
     * <p>
     * 剥注释必须在切句**之前**：模型常把 {@code // 说明;} 写在语句后面，注释里的 {@code ;}
     * 若先参与切分，会把注释碎片变成"语句"（解析失败、白打警告），甚至切坏后面的代码。
     * <p>
     * 而"先剥注释"能成立的前提是**指令串保留了行结构**：行注释是"吃到行尾"，
     * {@code JsonAnimationUtils.instructionString()} 把 timeline 数组用 {@code ";
"} 拼接，
     * 正是为此（只用 {@code ";"} 拼时第一个 {@code //} 会把整条 timeline 吃掉）。
     */
    static java.util.List<String> executableStatements(String instructions) throws MolangException {
        java.util.List<String> out = new java.util.ArrayList<>();
        String stripped = MolangFunctionParser.stripComments(instructions);
        for (String statement : MolangParser.splitStatements(stripped)) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** Execute a pre-parsed instruction array — no split/parse overhead. */
    private static void executeCached(ParsedInstruction[] ops) {
        for (ParsedInstruction pi : ops) {
            if (pi.isAssignment) {
                double d = pi.value.get();
                // 大小写归一化：parseExpression 会对整个表达式 lowerCase，
                // 模型骨骼/嵌套表达式读取的 key 都是小写（如 v.firep）。
                // 顶层赋值 target 来自原始 timeline 指令（可能大写，如 v.fireP），
                // 必须转小写写入，否则读取侧永远读不到（v.fireP 恒 0 bug）。
                String target = pi.target.toLowerCase(java.util.Locale.ROOT);
                // Write through MolangParser.VARIABLES so ScopedMolangVariable
                // (if it exists) sees the change.
                MolangParser.VARIABLES.computeIfAbsent(target,
                    k -> new software.bernie.geckolib3.core.molang.LazyVariable(k, 0)).set(d);
                // 记录该全局变量的写入来源模型（debug overlay 显示 @模型来源，
                // 定位跨模型残留——其他模型 timeline 写的 v.* 会残留到新模型）。
                com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime.noteGlobalVarOwner(target);
                // Also write directly to MolangPhysicsRuntime so that
                // syncToRuntimeState() can see the value even when no
                // ScopedMolangVariable was previously registered for this key
                // (e.g. v.bq_eye set by pre_parallel7's timeline).
                com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime.setVariable(target, d);
                // Log v.qh timeline variable assignments for debugging
                if (com.fox.ysmu.Config.DEBUG_CONTROLLER) {
                    String t = target;
                    if ("v.qh".equals(t) || "v.qh2".equals(t)
                        || "v.jump".equals(t) || "v.random".equals(t)
                        || "v.wet".equals(t) || "v.firep".equals(t)) {
                        ysmu.LOG.info("[YSMU-TL-SET] {} = {}", t, d);
                    }
                }
            } else {
                pi.value.get(); // evaluate for side effects
            }
        }
    }

    /**
     * Locates the first {@code =} character that acts as a TOP-LEVEL assignment
     * operator (ignoring {@code ==}, {@code !=}, {@code <=}, {@code >=},
     * {@code ?=}/{@code ?:}, and any {@code =} nested inside parentheses,
     * brackets, or ternary branches).
     *
     * <p>OpenYSM timeline instructions commonly use nested assignments like
     * {@code (cond) ? (v.x = 30) : 0} — those {@code =} live inside parens and
     * must NOT be treated as a top-level assignment (which would mis-parse the
     * whole statement and skip it).  Only a bare {@code v.xxx = ...} at
     * parentheses depth 0 is an assignment.</p>
     */
    private static int findAssignmentOperator(String text) {
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < text.length()) {
                    i++;
                } else if (c == quote) {
                    inString = false;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                inString = true;
                quote = c;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }
            if (depth != 0) {
                continue;
            }
            if (c == '=') {
                // Skip two-character operators: ==, !=, <=, >=
                if (i > 0) {
                    char prev = text.charAt(i - 1);
                    if (prev == '=' || prev == '!' || prev == '<' || prev == '>') {
                        continue;
                    }
                }
                // Skip if this is part of ?= or ?: operator
                if (i > 0 && text.charAt(i - 1) == '?') {
                    continue;
                }
                if (i + 1 < text.length() && (text.charAt(i + 1) == '=')) {
                    continue;
                }
                return i;
            }
        }
        return -1;
    }

    public static void clearWarnings() {
        WARNED_INSTRUCTIONS.clear();
    }

    /** Cache lookup: returns cached parsed expression, or parses on first access. */
    private static IValue parseCached(MolangParser parser, String expr) {
        IValue cached = EXPRESSION_CACHE.get(expr);
        if (cached != null) return cached;
        try {
            IValue parsed = parser.parseExpression(expr);
            if (parsed != null) {
                EXPRESSION_CACHE.put(expr, parsed);
            }
            return parsed;
        } catch (MolangException e) {
            return null;
        }
    }

    /** Clear parsed expression cache — call when models are reloaded. */
    public static void clearCache() {
        EXPRESSION_CACHE.clear();
        INSTRUCTION_CACHE.clear();
    }

    private static void warnOnce(String instruction, Exception e) {
        if (WARNED_INSTRUCTIONS.add(instruction)) {
            ysmu.LOG
                .warn("Failed to execute OpenYSM timeline Molang instruction '{}': {}", instruction, e.getMessage());
        }
    }
}
