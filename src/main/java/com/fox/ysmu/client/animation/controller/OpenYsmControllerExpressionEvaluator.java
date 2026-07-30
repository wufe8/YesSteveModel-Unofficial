package com.fox.ysmu.client.animation.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.client.animation.RemotePlayerMotionStates;
import com.fox.ysmu.client.animation.condition.InnerClassify;
import com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime;
import com.fox.ysmu.compat.BackhandCompat;
import com.fox.ysmu.compat.BlockingCompat;
import com.fox.ysmu.compat.EtFuturumCompat;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;

public final class OpenYsmControllerExpressionEvaluator {

    private static final double TRUE = 1.0d;
    private static final double FALSE = 0.0d;

    /** Cache of compiled expressions — avoids re-parsing the same string every frame. */
    private static final java.util.concurrent.ConcurrentHashMap<String, CompiledExpr> COMPILED_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    @FunctionalInterface
    interface CompiledExpr {
        double eval(Context ctx);
    }

    /** Holds a compiled argument for function calls: either a string constant or a compiled expression. */
    static final class CompiledArg {
        final boolean isString;
        final String stringValue;
        final CompiledExpr exprValue;
        private CompiledArg(String s) { isString = true; stringValue = s; exprValue = null; }
        private CompiledArg(CompiledExpr e) { isString = false; stringValue = null; exprValue = e; }
        static CompiledArg ofString(String s) { return new CompiledArg(s); }
        static CompiledArg ofExpr(CompiledExpr e) { return new CompiledArg(e); }
        Argument toArgument(Context ctx) {
            return isString ? Argument.string(stringValue) : Argument.number(exprValue.eval(ctx));
        }
    }

    private static CompiledExpr compile(String expression) {
        return COMPILED_CACHE.computeIfAbsent(expression, OpenYsmControllerExpressionEvaluator::doCompile);
    }

    /** Compile an expression string into a reusable CompiledExpr tree. */
    private static CompiledExpr doCompile(String expr) {
        if (StringUtils.isBlank(expr)) {
            return ctx -> TRUE;
        }
        // Use a simple index-based parser that builds a CompiledExpr tree
        int[] idx = {0};
        CompiledExpr result = parseOrCompiled(expr, idx);
        return result != null ? result : ctx -> FALSE;
    }

    private static CompiledExpr parseOrCompiled(String expr, int[] idx) {
        CompiledExpr left = parseAndCompiled(expr, idx);
        if (left == null) return null;
        while (true) {
            skipWhitespace(expr, idx);
            if (match(expr, idx, "||")) {
                CompiledExpr right = parseAndCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> truthy(l.eval(ctx)) || truthy(r.eval(ctx)) ? TRUE : FALSE;
            } else {
                return left;
            }
        }
    }

    private static CompiledExpr parseAndCompiled(String expr, int[] idx) {
        CompiledExpr left = parseEqualityCompiled(expr, idx);
        if (left == null) return null;
        while (true) {
            skipWhitespace(expr, idx);
            if (match(expr, idx, "&&")) {
                CompiledExpr right = parseEqualityCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> truthy(l.eval(ctx)) && truthy(r.eval(ctx)) ? TRUE : FALSE;
            } else {
                return left;
            }
        }
    }

    private static CompiledExpr parseEqualityCompiled(String expr, int[] idx) {
        CompiledExpr left = parseComparisonCompiled(expr, idx);
        if (left == null) return null;
        while (true) {
            skipWhitespace(expr, idx);
            if (match(expr, idx, "==")) {
                CompiledExpr right = parseComparisonCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> {
                    double lv = l.eval(ctx);
                    double rv = r.eval(ctx);
                    // NaN is never equal to anything, not even itself.
                    if (Double.isNaN(lv) || Double.isNaN(rv)) return FALSE;
                    return nearlyEqual(lv, rv) ? TRUE : FALSE;
                };
            } else if (match(expr, idx, "!=")) {
                CompiledExpr right = parseComparisonCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> !nearlyEqual(l.eval(ctx), r.eval(ctx)) ? TRUE : FALSE;
            } else {
                return left;
            }
        }
    }

    private static CompiledExpr parseComparisonCompiled(String expr, int[] idx) {
        CompiledExpr left = parseAdditiveCompiled(expr, idx);
        if (left == null) return null;
        while (true) {
            skipWhitespace(expr, idx);
            if (match(expr, idx, ">=")) {
                CompiledExpr right = parseAdditiveCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> l.eval(ctx) >= r.eval(ctx) ? TRUE : FALSE;
            } else if (match(expr, idx, "<=")) {
                CompiledExpr right = parseAdditiveCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> l.eval(ctx) <= r.eval(ctx) ? TRUE : FALSE;
            } else if (match(expr, idx, ">")) {
                CompiledExpr right = parseAdditiveCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> l.eval(ctx) > r.eval(ctx) ? TRUE : FALSE;
            } else if (match(expr, idx, "<")) {
                CompiledExpr right = parseAdditiveCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> l.eval(ctx) < r.eval(ctx) ? TRUE : FALSE;
            } else {
                return left;
            }
        }
    }

    private static CompiledExpr parseAdditiveCompiled(String expr, int[] idx) {
        CompiledExpr left = parseMultiplicativeCompiled(expr, idx);
        if (left == null) return null;
        while (true) {
            skipWhitespace(expr, idx);
            if (match(expr, idx, "+")) {
                CompiledExpr right = parseMultiplicativeCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> l.eval(ctx) + r.eval(ctx);
            } else if (match(expr, idx, "-")) {
                CompiledExpr right = parseMultiplicativeCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> l.eval(ctx) - r.eval(ctx);
            } else {
                return left;
            }
        }
    }

    private static CompiledExpr parseMultiplicativeCompiled(String expr, int[] idx) {
        CompiledExpr left = parseUnaryCompiled(expr, idx);
        if (left == null) return null;
        while (true) {
            skipWhitespace(expr, idx);
            if (match(expr, idx, "*")) {
                CompiledExpr right = parseUnaryCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> l.eval(ctx) * r.eval(ctx);
            } else if (match(expr, idx, "/")) {
                CompiledExpr right = parseUnaryCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> { double d = r.eval(ctx); return d == 0.0d ? 0.0d : l.eval(ctx) / d; };
            } else if (match(expr, idx, "%")) {
                CompiledExpr right = parseUnaryCompiled(expr, idx);
                CompiledExpr l = left, r = right;
                left = ctx -> { double d = r.eval(ctx); return d == 0.0d ? 0.0d : l.eval(ctx) % d; };
            } else {
                return left;
            }
        }
    }

    private static CompiledExpr parseUnaryCompiled(String expr, int[] idx) {
        skipWhitespace(expr, idx);
        if (match(expr, idx, "!")) {
            CompiledExpr operand = parseUnaryCompiled(expr, idx);
            return ctx -> truthy(operand.eval(ctx)) ? FALSE : TRUE;
        }
        if (match(expr, idx, "-")) {
            CompiledExpr operand = parseUnaryCompiled(expr, idx);
            return ctx -> -operand.eval(ctx);
        }
        return parsePrimaryCompiled(expr, idx);
    }

    private static CompiledExpr parsePrimaryCompiled(String expr, int[] idx) {
        skipWhitespace(expr, idx);
        if (idx[0] >= expr.length()) {
            return ctx -> FALSE;
        }
        char c = expr.charAt(idx[0]);
        if (c == '(') {
            idx[0]++;
            CompiledExpr inner = parseOrCompiled(expr, idx);
            match(expr, idx, ")");
            return inner;
        }
        if (c == '\'' || c == '"') {
            String s = readQuotedString(expr, idx);
            // 空字符串返回 FALSE (0.0)，使 ctrl.x=='' 能正确表示"变量为空"。
            // 非空字符串返回 NaN，防止 ctrl.tac_gun_type=='rifle' 在无 TacZ 时
            // 误判为 0.0==0.0（所有 TacZ 条件动画同时播放）。
            if (s.isEmpty()) {
                return ctx -> FALSE;
            }
            return ctx -> Double.NaN;
        }
        if (Character.isDigit(c) || (c == '.' && idx[0] + 1 < expr.length()
            && Character.isDigit(expr.charAt(idx[0] + 1)))) {
            return parseNumberCompiled(expr, idx);
        }
        int start = idx[0];
        String identifier = parseIdentifier(expr, idx);
        if (identifier.isEmpty()) {
            idx[0]++;
            return ctx -> FALSE;
        }
        skipWhitespace(expr, idx);
        if (match(expr, idx, "(")) {
            // Function call — compile arguments
            List<CompiledArg> compiledArgs = new ArrayList<>();
            while (true) {
                skipWhitespace(expr, idx);
                if (match(expr, idx, ")")) break;
                if (idx[0] >= expr.length()) break;
                char ac = expr.charAt(idx[0]);
                if (ac == '\'' || ac == '"') {
                    String s = readQuotedString(expr, idx);
                    compiledArgs.add(CompiledArg.ofString(s));
                } else {
                    String raw = readRawArgument(expr, idx);
                    compiledArgs.add(CompiledArg.ofExpr(compile(raw)));
                }
                skipWhitespace(expr, idx);
                if (!match(expr, idx, ",")) {
                    match(expr, idx, ")");
                    break;
                }
            }
            String funcName = identifier;
            return ctx -> {
                List<Argument> evaluated = new ArrayList<>();
                for (CompiledArg a : compiledArgs) {
                    evaluated.add(a.toArgument(ctx));
                }
                return ctx.functionValue(funcName, evaluated);
            };
        }
        // Variable reference
        String varName = identifier;
        return ctx -> ctx.variableValue(varName);
    }

    private static CompiledExpr parseNumberCompiled(String expr, int[] idx) {
        int start = idx[0];
        boolean dotSeen = false;
        while (idx[0] < expr.length()) {
            char c = expr.charAt(idx[0]);
            if (Character.isDigit(c)) {
                idx[0]++;
            } else if (c == '.' && !dotSeen) {
                dotSeen = true;
                idx[0]++;
            } else {
                break;
            }
        }
        double value = Double.parseDouble(expr.substring(start, idx[0]));
        return ctx -> value;
    }

    // Reuse the original evaluator's static helper methods by delegating

    private static void skipWhitespace(String expr, int[] idx) {
        while (idx[0] < expr.length() && expr.charAt(idx[0]) <= ' ') {
            idx[0]++;
        }
    }

    private static boolean match(String expr, int[] idx, String expected) {
        if (expr.regionMatches(idx[0], expected, 0, expected.length())) {
            idx[0] += expected.length();
            return true;
        }
        return false;
    }

    private static String parseIdentifier(String expr, int[] idx) {
        int start = idx[0];
        while (idx[0] < expr.length()) {
            char c = expr.charAt(idx[0]);
            if (Character.isLetter(c) || Character.isDigit(c) || c == '_' || c == '.') {
                idx[0]++;
            } else {
                break;
            }
        }
        return expr.substring(start, idx[0]);
    }

    private static String readQuotedString(String expr, int[] idx) {
        if (idx[0] >= expr.length()) return "";
        char quote = expr.charAt(idx[0]);
        idx[0]++;
        int start = idx[0];
        while (idx[0] < expr.length() && expr.charAt(idx[0]) != quote) {
            idx[0]++;
        }
        String result = expr.substring(start, idx[0]);
        if (idx[0] < expr.length()) idx[0]++; // skip closing quote
        return result;
    }

    private static String readRawArgument(String expr, int[] idx) {
        int start = idx[0];
        int depth = 0;
        boolean quoted = false;
        char quote = 0;
        while (idx[0] < expr.length()) {
            char c = expr.charAt(idx[0]);
            if (quoted) {
                if (c == quote) quoted = false;
            } else if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) break;
                depth--;
            } else if ((c == ',' || c == ';') && depth == 0) {
                break;
            }
            idx[0]++;
        }
        return expr.substring(start, idx[0]);
    }

    // ---- Compiled expression evaluation (cached, avoids re-parsing) ----

    static boolean evaluateBoolean(String expression, Context context) {
        if (StringUtils.isBlank(expression)) {
            return true;
        }
        return truthy(compile(expression).eval(context));
    }

    static double evaluateNumber(String expression, Context context) {
        try {
            return compile(expression).eval(context);
        } catch (RuntimeException e) {
            OpenYsmAnimationControllerRegistry.warnOnce(
                "expr:" + expression,
                "Failed to evaluate OpenYSM controller expression: " + expression + " (" + e.getMessage() + ")");
            return FALSE;
        }
    }

    static void executeStatements(List<String> statements, Context context) {
        for (String statement : statements) {
            executeStatement(statement, context);
        }
    }

    private static void executeStatement(String statements, Context context) {
        if (StringUtils.isBlank(statements)) {
            return;
        }
        String[] split = statements.split(";");
        for (String statement : split) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int equals = findAssignmentOperator(trimmed);
            if (equals <= 0) {
                evaluateNumber(trimmed, context);
                continue;
            }
            String target = normalizeVariableName(trimmed.substring(0, equals).trim());
            String valueExpression = trimmed.substring(equals + 1).trim();
            if (target.startsWith("v.")) {
                String varName = target.substring(2);
                double value = evaluateNumber(valueExpression, context);
                context.state.variables.put(varName, value);
                // 同步到 MolangPhysicsRuntime，使动画关键帧中的 Molang 表达式
                // (通过 ScopedMolangVariable → MolangPhysicsRuntime.getVariable())
                // 能够读取到控制器 onEntry/onExit 设置的 v.* 变量值
                MolangPhysicsRuntime.setVariable(target, value);
                // 同步 roaming 变量回 PENDING_ROAMING，防止下一帧
                // tryApplyController() 的注入循环用陈旧值覆盖刚设的值
                if (varName.startsWith("roaming.")) {
                    OpenYsmPlayerControllerRuntime.PENDING_ROAMING.put(varName, value);
                    OpenYsmPlayerControllerRuntime.EXPLICIT_ROAMING.add(varName);
                }
            }
        }
    }

    private static boolean truthy(double value) {
        return Math.abs(value) > 0.000001d;
    }

    private static boolean nearlyEqual(double left, double right) {
        return Math.abs(left - right) < 0.000001d;
    }

    private static String normalizeVariableName(String variable) {
        if (variable.startsWith("variable.")) {
            return "v." + variable.substring("variable.".length());
        }
        return variable;
    }

    private static int findAssignmentOperator(String statement) {
        for (int i = 0; i < statement.length(); i++) {
            if (statement.charAt(i) != '=') {
                continue;
            }
            char before = i > 0 ? statement.charAt(i - 1) : 0;
            char after = i + 1 < statement.length() ? statement.charAt(i + 1) : 0;
            if (before != '=' && before != '!' && before != '<' && before != '>' && after != '=') {
                return i;
            }
        }
        return -1;
    }
    /**
     * Static helper for debug queries — evaluates a ctrl.* state using only
     * the player reference, without needing AnimationEvent or RuntimeState.
     * LimbSwing-dependent states (swim/sneak/run/walk) are approximated.
     */
    public static double evaluateCtrlState(String name, EntityPlayer player) {
        if (player == null) return FALSE;
        if ("death".equals(name)) return player.isDead ? TRUE : FALSE;
        if ("sleep".equals(name)) return player.isPlayerSleeping() ? TRUE : FALSE;
        if ("swim".equals(name)) return player.isInWater() ? TRUE : FALSE;
        if ("climb".equals(name) || "climbing".equals(name)) return player.isOnLadder() ? TRUE : FALSE;
        if ("ladder_up".equals(name)) return (player.isOnLadder() && evalMotionY(player, 0.1) > 0) ? TRUE : FALSE;
        if ("ladder_stillness".equals(name)) return (player.isOnLadder() && evalMotionY(player, 0.1) == 0) ? TRUE : FALSE;
        if ("ladder_down".equals(name)) return (player.isOnLadder() && evalMotionY(player, 0.1) < 0) ? TRUE : FALSE;
        if ("ride_pig".equals(name)) return player.ridingEntity instanceof net.minecraft.entity.passive.EntityPig ? TRUE : FALSE;
        if ("boat".equals(name)) return player.ridingEntity instanceof net.minecraft.entity.item.EntityBoat ? TRUE : FALSE;
        if ("ride".equals(name) || "sit".equals(name)) return player.isRiding() ? TRUE : FALSE;
        if ("elytra_fly".equals(name)) return com.fox.ysmu.compat.EtFuturumCompat.isElytraFlying(player) ? TRUE : FALSE;
        if ("fly".equals(name)) return evalIsFlying(player) ? TRUE : FALSE;
        if ("swim_stand".equals(name)) return player.isInWater() ? TRUE : FALSE;
        if ("attacked".equals(name)) return player.hurtTime > 0 ? TRUE : FALSE;
        if ("jump".equals(name)) {
            if (evalIsFlying(player) || player.isRiding() || evalIsOnGround(player) || player.isInWater()) return FALSE;
            return evalMotionY(player, 0.0) != 0 ? TRUE : FALSE;
        }
        if ("sneak".equals(name) || "sneaking".equals(name)) {
            return (evalIsOnGround(player) && player.isSneaking()) ? TRUE : FALSE;
        }
        if ("run".equals(name)) return (evalIsOnGround(player) && player.isSprinting()) ? TRUE : FALSE;
        if ("walk".equals(name)) {
            boolean moving = Math.abs(player.motionX) > 0.001 || Math.abs(player.motionZ) > 0.001;
            return (evalIsOnGround(player) && !player.isSprinting() && moving) ? TRUE : FALSE;
        }
        if ("idle".equals(name)) {
            if (player.isDead || player.isPlayerSleeping() || player.isInWater()
                || player.isOnLadder() || player.isRiding() || player.hurtTime > 0
                || !evalIsOnGround(player) || player.isSprinting() || player.isSneaking()) return FALSE;
            return TRUE;
        }
        return Double.NaN;
    }

    private static boolean evalIsFlying(EntityPlayer player) {
        if (com.fox.ysmu.compat.EtFuturumCompat.isElytraFlying(player)) return true;
        if (player == net.minecraft.client.Minecraft.getMinecraft().thePlayer) {
            return player.capabilities.isFlying;
        }
        return com.fox.ysmu.client.animation.RemotePlayerMotionStates.isFlying(player);
    }

    private static boolean evalIsOnGround(EntityPlayer player) {
        if (player == net.minecraft.client.Minecraft.getMinecraft().thePlayer) {
            return player.onGround;
        }
        return com.fox.ysmu.client.animation.RemotePlayerMotionStates.isOnGround(player);
    }

    private static int evalMotionY(EntityPlayer player, double threshold) {
        double motionY = player == net.minecraft.client.Minecraft.getMinecraft().thePlayer
            ? player.motionY : (player.posY - player.prevPosY) * 2.0d;
        if (motionY > threshold) return 1;
        if (motionY < -threshold) return -1;
        return 0;
    }

    static final class Context {
        private final AnimationEvent<?> event;
        private final EntityPlayer player;
        private final OpenYsmPlayerControllerRuntime.RuntimeState state;

        Context(AnimationEvent<?> event, EntityPlayer player, OpenYsmPlayerControllerRuntime.RuntimeState state) {
            this.event = event;
            this.player = player;
            this.state = state;
        }

        double variableValue(String name) {
            String normalized = normalizeVariableName(name);
            if ("true".equals(normalized)) {
                return TRUE;
            }
            if ("false".equals(normalized)) {
                return FALSE;
            }
            if (normalized.startsWith("q.")) {
                normalized = "query." + normalized.substring(2);
            }
            if (normalized.startsWith("v.")) {
                return localVariableValue(normalized.substring(2));
            }
            if (normalized.startsWith("query.")) {
                return queryValue(normalized.substring("query.".length()));
            }
            if (normalized.startsWith("ctrl.")) {
                return ctrlValue(normalized.substring("ctrl.".length()));
            }
            if (normalized.startsWith("ysm.")) {
                return ysmValue(normalized.substring("ysm.".length()));
            }
            if ("math.pi".equals(normalized)) {
                return Math.PI;
            }
            if ("math.e".equals(normalized)) {
                return Math.E;
            }
            OpenYsmAnimationControllerRegistry.warnOnce(
                "var:" + normalized,
                "Unsupported OpenYSM controller variable: " + normalized);
            return FALSE;
        }

        double functionValue(String name, List<Argument> arguments) {
            if (player == null) return FALSE;
            if ("ctrl.hold".equals(name)) {
                return handMatch(arguments, false, false);
            }
            if ("ctrl.use".equals(name)) {
                return handMatch(arguments, true, false);
            }
            if ("ctrl.swing".equals(name)) {
                return handMatch(arguments, false, true);
            }
            if ("ctrl.ride".equals(name)) {
                return player.isRiding() ? TRUE : FALSE;
            }
            // --- math 单参数函数 ---
            if (("math.floor".equals(name) || "math.round".equals(name) || "math.ceil".equals(name)
                || "math.trunc".equals(name) || "math.abs".equals(name) || "math.sqrt".equals(name)
                || "math.exp".equals(name) || "math.ln".equals(name) || "math.hermite_blend".equals(name)
                || "math.hermite".equals(name)
                || "math.sin".equals(name) || "math.cos".equals(name) || "math.atan".equals(name))
                && arguments.size() >= 1) {
                double v = arguments.get(0).asNumber();
                if ("math.floor".equals(name)) return Math.floor(v);
                if ("math.round".equals(name)) return Math.round(v);
                if ("math.ceil".equals(name)) return Math.ceil(v);
                if ("math.trunc".equals(name)) return v < 0 ? Math.ceil(v) : Math.floor(v);
                if ("math.abs".equals(name)) return Math.abs(v);
                if ("math.sqrt".equals(name)) return v < 0 ? 0.0d : Math.sqrt(v);
                if ("math.exp".equals(name)) return Math.exp(v);
                if ("math.ln".equals(name)) return v <= 0 ? 0.0d : Math.log(v);
                if ("math.hermite_blend".equals(name) || "math.hermite".equals(name)) return v * v * (3 - 2 * v);
                if ("math.sin".equals(name)) return Math.sin(Math.toRadians(v));
                if ("math.cos".equals(name)) return Math.cos(Math.toRadians(v));
                if ("math.atan".equals(name)) return Math.toDegrees(Math.atan(v));
            }
            // --- math 双参数函数 ---
            if (("math.mod".equals(name) || "math.max".equals(name) || "math.min".equals(name)
                || "math.pow".equals(name) || "math.atan2".equals(name) || "math.min_angle".equals(name))
                && arguments.size() >= 2) {
                double a = arguments.get(0).asNumber();
                double b = arguments.get(1).asNumber();
                if ("math.mod".equals(name)) {
                    if (b == 0.0d) return FALSE;
                    double r = a % b;
                    if (r < 0) r += Math.abs(b);
                    return r;
                }
                if ("math.max".equals(name)) return Math.max(a, b);
                if ("math.min".equals(name)) return Math.min(a, b);
                if ("math.pow".equals(name)) return Math.pow(a, b);
                if ("math.atan2".equals(name)) return Math.toDegrees(Math.atan2(a, b));
                if ("math.min_angle".equals(name)) {
                    double diff = (b - a) % 360;
                    if (diff > 180) diff -= 360;
                    if (diff <= -180) diff += 360;
                    return diff;
                }
            }
            // --- math 三参数函数 ---
            if (("math.clamp".equals(name) || "math.lerp".equals(name) || "math.lerprotate".equals(name)
                || "math.die_roll".equals(name) || "math.die_roll_integer".equals(name)
                || "math.roll".equals(name) || "math.rolli".equals(name))
                && arguments.size() >= 3) {
                double a = arguments.get(0).asNumber();
                double b = arguments.get(1).asNumber();
                double c = arguments.get(2).asNumber();
                if ("math.clamp".equals(name)) return Math.max(b, Math.min(c, a));
                if ("math.lerp".equals(name)) return a + (b - a) * c;
                if ("math.lerprotate".equals(name)) {
                    double diff = (b - a) % 360;
                    if (diff > 180) diff -= 360;
                    if (diff <= -180) diff += 360;
                    double r = a + diff * c;
                    if (r >= 360) r -= 360;
                    if (r < 0) r += 360;
                    return r;
                }
                if ("math.die_roll".equals(name) || "math.roll".equals(name)) {
                    double sum = 0;
                    for (int i = 0; i < (int) a && i < 100; i++) sum += b + Math.random() * (c - b);
                    return sum;
                }
                if ("math.die_roll_integer".equals(name) || "math.rolli".equals(name)) {
                    int sum = 0;
                    int min = (int) b, max = (int) c;
                    if (min > max) { int t = min; min = max; max = t; }
                    for (int i = 0; i < (int) a && i < 100; i++) sum += min + (int)(Math.random() * (max - min + 1));
                    return sum;
                }
            }
            // --- math 无参数常量 ---
            if ("math.pi".equals(name) && arguments.isEmpty()) return Math.PI;
            if ("math.e".equals(name) && arguments.isEmpty()) return Math.E;
            // --- math 单参数 + 别名 ---
            if ("math.randomi".equals(name) && arguments.size() >= 1) {
                return (int)(Math.random() * ((int) arguments.get(0).asNumber()));
            }
            // --- math 双参数 + 别名 ---
            if ("math.random_integer".equals(name) && arguments.size() >= 2) {
                int min = (int) arguments.get(0).asNumber();
                int max = (int) arguments.get(1).asNumber();
                if (min > max) return min;
                return min + (int)(Math.random() * (max - min + 1));
            }
            if ("math.random".equals(name) && arguments.size() >= 2) {
                double low = arguments.get(0).asNumber();
                double high = arguments.get(1).asNumber();
                if (low >= high) return low;
                return low + Math.random() * (high - low);
            }
            if ("query.position".equals(name) && arguments.size() >= 1) {
                return queryPositionValue((int) arguments.get(0).asNumber());
            }
            // --- ysm.* 函数 ---
            if ("ysm.keyboard".equals(name) && arguments.size() >= 1) {
                int keycode = (int) arguments.get(0).asNumber();
                try {
                    return org.lwjgl.input.Keyboard.isKeyDown(keycode) ? TRUE : FALSE;
                } catch (Exception e) {
                    return FALSE;
                }
            }
            OpenYsmAnimationControllerRegistry.warnOnce(
                "func:" + name,
                "Unsupported OpenYSM controller function: " + name);
            return FALSE;
        }

        private double localVariableValue(String name) {
            if ("jump".equals(name)) {
                return isJumping() ? TRUE : FALSE;
            }
            Double value = state.variables.get(name);
            return value == null ? FALSE : value;
        }

        private double queryValue(String name) {
            if (player == null) return FALSE;
            if ("anim_time".equals(name)) {
                return Math.max(0.0d, event.getAnimationTick() - state.enteredTick) / 20.0d;
            }
            if ("life_time".equals(name)) {
                return event.getAnimationTick() / 20.0d;
            }
            if ("all_animations_finished".equals(name) || "any_animation_finished".equals(name)) {
                return allAnimationsFinished() ? TRUE : FALSE;
            }
            if ("ground_speed".equals(name)) {
                return horizontalSpeed();
            }
            if ("vertical_speed".equals(name)) {
                return (player.posY - player.prevPosY) * 20.0d;
            }
            if ("modified_distance_moved".equals(name)) {
                return player.distanceWalkedModified;
            }
            if ("walk_distance".equals(name)) {
                return player.distanceWalkedOnStepModified;
            }
            if ("is_on_ground".equals(name)) {
                return isOnGround() ? TRUE : FALSE;
            }
            if ("head_x_rotation".equals(name)) {
                return player.rotationPitch;
            }
            if ("head_y_rotation".equals(name)) {
                return player.rotationYaw;
            }
            if ("cardinal_facing_2d".equals(name)) {
                int facing = net.minecraft.util.MathHelper.floor_double(
                    (player.rotationYaw * 4.0F / 360.0F) + 0.5D) & 3;
                double[] YSM_CARDINAL = {3.0, 4.0, 2.0, 5.0};
                return YSM_CARDINAL[facing];
            }
            if ("distance_from_camera".equals(name)) {
                Minecraft mc = Minecraft.getMinecraft();
                return mc.renderViewEntity == null ? 0.0d
                    : mc.renderViewEntity.getDistanceToEntity(player);
            }
            if ("equipment_count".equals(name)) {
                int count = 0;
                for (net.minecraft.item.ItemStack s : player.inventory.armorInventory) {
                    if (s != null) count++;
                }
                return count;
            }
            if ("eye_target_x_rotation".equals(name)) {
                return player.rotationPitch;
            }
            if ("eye_target_y_rotation".equals(name)) {
                return player.rotationYaw;
            }
            if ("has_cape".equals(name)) {
                return FALSE;
            }
            if ("has_rider".equals(name)) {
                return player.riddenByEntity != null ? TRUE : FALSE;
            }
            if ("actor_count".equals(name)) {
                Minecraft mc = Minecraft.getMinecraft();
                return mc.theWorld == null ? 0.0d : mc.theWorld.loadedEntityList.size();
            }
            if ("is_spectator".equals(name)) {
                return FALSE; // 1.7.10 has no spectator mode
            }
            if ("player_level".equals(name)) {
                return player.experienceLevel;
            }
            if ("moon_phase".equals(name)) {
                Minecraft mc = Minecraft.getMinecraft();
                return mc.theWorld == null ? 0.0d : mc.theWorld.getMoonPhase();
            }
            if ("yaw_speed".equals(name)) {
                return 0.0d; // yawSpeed needs QueryValues not available in controller context
            }
            if ("item_in_use_duration".equals(name)) {
                net.minecraft.item.ItemStack useItem = player.getItemInUse();
                if (useItem == null) return 0.0d;
                return (useItem.getMaxItemUseDuration() - player.getItemInUseCount()) / 20.0d;
            }
            if ("item_max_use_duration".equals(name)) {
                net.minecraft.item.ItemStack useItem = player.getItemInUse();
                if (useItem == null) return 0.0d;
                return useItem.getMaxItemUseDuration() / 20.0d;
            }
            if ("item_remaining_use_duration".equals(name)) {
                return player.getItemInUseCount() / 20.0d;
            }
            if ("is_sneaking".equals(name)) {
                return player.isSneaking() ? TRUE : FALSE;
            }
            if ("is_sprinting".equals(name)) {
                return player.isSprinting() ? TRUE : FALSE;
            }
            if ("is_swimming".equals(name) || "is_in_water".equals(name)) {
                return player.isInWater() ? TRUE : FALSE;
            }
            if ("is_in_water_or_rain".equals(name)) {
                return player.isWet() ? TRUE : FALSE;
            }
            if ("is_using_item".equals(name)) {
                return player.isUsingItem() ? TRUE : FALSE;
            }
            if ("is_jumping".equals(name)) {
                return isJumping() ? TRUE : FALSE;
            }
            if ("is_riding".equals(name)) {
                return player.isRiding() ? TRUE : FALSE;
            }
            if ("is_sleeping".equals(name)) {
                return player.isPlayerSleeping() ? TRUE : FALSE;
            }
            if ("is_on_fire".equals(name)) {
                return player.isBurning() ? TRUE : FALSE;
            }
            if ("is_playing_dead".equals(name)) {
                return player.isDead ? TRUE : FALSE;
            }
            if ("is_eating".equals(name)) {
                return player.getItemInUse() != null && player.getItemInUse().getItemUseAction() == EnumAction.eat
                    ? TRUE
                    : FALSE;
            }
            if ("is_blocking".equals(name)) {
                return BlockingCompat.isBlocking(player) ? TRUE : FALSE;
            }
            if ("health".equals(name)) {
                return player.getHealth();
            }
            if ("max_health".equals(name)) {
                return player.getMaxHealth();
            }
            if ("hurt_time".equals(name)) {
                return player.hurtTime;
            }
            if ("time_of_day".equals(name)) {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc.theWorld == null) return 0.0d;
                long dayTime = mc.theWorld.getWorldTime() % 24000L;
                // Normalize to [0,1): midnight=0, sunrise=0.25, noon=0.5, sunset=0.75
                return dayTime / 24000.0d;
            }
            if ("time_stamp".equals(name)) {
                Minecraft mc = Minecraft.getMinecraft();
                return mc.theWorld == null ? 0.0d : mc.theWorld.getWorldTime();
            }
            OpenYsmAnimationControllerRegistry.warnOnce(
                "query:" + name,
                "Unsupported OpenYSM controller query: query." + name);
            return FALSE;
        }

        private double ctrlValue(String name) {
            // playing_extra_animation is NOT a controller state predicate like
            // walk/idle/death — it's a standalone check that should NOT be
            // included in hasNonIdleControllerState() (otherwise ctrl.idle
            // would return false whenever the wheel is open).
            if ("playing_extra_animation".equals(name)) {
                return isPlayingExtraAnimation() ? TRUE : FALSE;
            }
            return isControllerState(name) ? TRUE : FALSE;
        }

        private double ysmValue(String name) {
            if (player == null) return FALSE;
            if ("is_fishing".equals(name)) {
                return player.fishEntity != null ? TRUE : FALSE;
            }
            if ("swinging".equals(name)) {
                return player.isSwingInProgress ? TRUE : FALSE;
            }
            if ("swing_time".equals(name)) {
                return player.swingProgressInt;
            }
            if ("swinging_arm".equals(name)) {
                return BackhandCompat.swingingArm(player) ? 0.0d : 1.0d;
            }
            if ("is_passenger".equals(name)) {
                return player.isRiding() ? TRUE : FALSE;
            }
            if ("is_sleep".equals(name)) {
                return player.isPlayerSleeping() ? TRUE : FALSE;
            }
            if ("is_sneak".equals(name)) {
                return isOnGround() && player.isSneaking() ? TRUE : FALSE;
            }
            if ("on_ladder".equals(name)) {
                return player.isOnLadder() ? TRUE : FALSE;
            }
            if ("is_riptide".equals(name)) {
                return FALSE;
            }
            if ("has_mainhand".equals(name)) {
                return player.getHeldItem() != null ? TRUE : FALSE;
            }
            if ("has_offhand".equals(name)) {
                return BackhandCompat.getOffhandItem(player) != null ? TRUE : FALSE;
            }
            if ("mainhand_charged_crossbow".equals(name)) {
                return com.fox.ysmu.compat.TinkersCrossbowCompat.isCrossbowLoaded(player.getHeldItem()) ? TRUE : FALSE;
            }
            if ("offhand_charged_crossbow".equals(name)) {
                return com.fox.ysmu.compat.TinkersCrossbowCompat.isCrossbowLoaded(
                    com.fox.ysmu.compat.BackhandCompat.getOffhandItem(player)) ? TRUE : FALSE;
            }
            if ("armor_value".equals(name)) {
                return player.getTotalArmorValue();
            }
            if ("hurt_time".equals(name)) {
                return player.hurtTime;
            }
            if ("food_level".equals(name)) {
                return player.getFoodStats().getFoodLevel();
            }
            if ("ground_speed2".equals(name)) {
                return horizontalSpeed();
            }
            if ("fps".equals(name)) {
                try {
                    java.lang.reflect.Field f = Minecraft.class.getDeclaredField("debugFPS");
                    f.setAccessible(true);
                    return f.getInt(Minecraft.getMinecraft());
                } catch (Exception e) {
                    return 60;
                }
            }
            if ("input_vertical".equals(name)) {
                return player.moveForward;
            }
            if ("input_horizontal".equals(name)) {
                return player.moveStrafing;
            }
            if ("xxa".equals(name)) {
                return player.moveStrafing;
            }
            if ("yya".equals(name)) {
                return 0.0d; // 1.7.10 has no vertical input equivalent
            }
            if ("zza".equals(name)) {
                return player.moveForward;
            }
            if ("has_helmet".equals(name)) {
                return player.inventory.armorItemInSlot(3) != null ? TRUE : FALSE;
            }
            if ("attack_time".equals(name)) {
                return player.isSwingInProgress ? 1.0d : 0.0d;
            }
            if ("arrow_count".equals(name)) {
                return player.getArrowCountInEntity();
            }
            if ("time_delta".equals(name)) {
                return com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime.getTimeDelta();
            }
            if ("person_view".equals(name)) {
                return Minecraft.getMinecraft().gameSettings.thirdPersonView;
            }
            if ("rendering_in_inventory".equals(name)) {
                return com.fox.ysmu.util.RenderUtil.RENDERING_IN_INVENTORY ? TRUE : FALSE;
            }
            if ("rendering_in_paperdoll".equals(name)) {
                return com.fox.ysmu.util.RenderUtil.RENDERING_IN_PAPERDOLL ? TRUE : FALSE;
            }
            if ("is_open_air".equals(name)) {
                return player.worldObj.canBlockSeeTheSky(
                    net.minecraft.util.MathHelper.floor_double(player.posX),
                    net.minecraft.util.MathHelper.floor_double(player.posY + 1.0D),
                    net.minecraft.util.MathHelper.floor_double(player.posZ)) ? TRUE : FALSE;
            }
            if ("weather".equals(name)) {
                if (player.worldObj.isThundering()) return 2.0;
                if (player.worldObj.isRaining()) return 1.0;
                return 0.0;
            }
            if ("dimension_name".equals(name)) {
                return player.dimension; // 1.7.10 uses integer dimension IDs
            }
            if ("block_light".equals(name)) {
                return player.worldObj.getBlockLightValue(
                    net.minecraft.util.MathHelper.floor_double(player.posX),
                    net.minecraft.util.MathHelper.floor_double(player.posY),
                    net.minecraft.util.MathHelper.floor_double(player.posZ));
            }
            if ("sky_light".equals(name)) {
                return player.worldObj.getSavedLightValue(
                    net.minecraft.world.EnumSkyBlock.Sky,
                    net.minecraft.util.MathHelper.floor_double(player.posX),
                    net.minecraft.util.MathHelper.floor_double(player.posY),
                    net.minecraft.util.MathHelper.floor_double(player.posZ));
            }
            OpenYsmAnimationControllerRegistry.warnOnce(
                "ysm:" + name,
                "Unsupported OpenYSM controller ysm variable: ysm." + name);
            return FALSE;
        }

        /** Returns true when the cap controller is playing an extra animation
         *  (wheel or EEP).  Used by ctrl.playing_extra_animation. */
        private boolean isPlayingExtraAnimation() {
            if (player == null) return false;
            // Check EEP (persistent animation from wheel GUI or /ysm play)
            com.fox.ysmu.eep.ExtendedModelInfo eep = com.fox.ysmu.eep.ExtendedModelInfo.get(player);
            if (eep != null && eep.isPlayAnimation()) {
                return true;
            }
            // Check wheel lock animation
            if (OpenYsmPlayerControllerRuntime.PENDING_ROAMING.getOrDefault("lock_wheel", 0.0) > 0
                && OpenYsmPlayerControllerRuntime.PENDING_ROAMING.getOrDefault("wheel_anim", 0.0) > 0) {
                return true;
            }
            return false;
        }

        /** Returns the entity's position component (0=x, 1=y, 2=z), using
         *  partial tick interpolation.  Implements query.position(index). */
        private double queryPositionValue(int index) {
            if (index < 0 || index > 2 || player == null) return 0.0d;
            float partialTicks = event.getPartialTick();
            switch (index) {
                case 0:
                    return player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
                case 1:
                    return player.prevPosY + (player.posY - player.prevPosY) * partialTicks;
                case 2:
                    return player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;
                default:
                    return 0.0d;
            }
        }

        private boolean isControllerState(String name) {
            if ("idle".equals(name)) {
                return !hasNonIdleControllerState();
            }
            return isControllerStateDirect(name);
        }

        private boolean hasNonIdleControllerState() {
            return isControllerStateDirect("death")
                || isControllerStateDirect("sleep")
                || isControllerStateDirect("swim")
                || isControllerStateDirect("climb")
                || isControllerStateDirect("climbing")
                || isControllerStateDirect("ladder_up")
                || isControllerStateDirect("ladder_stillness")
                || isControllerStateDirect("ladder_down")
                || isControllerStateDirect("fly")
                || isControllerStateDirect("elytra_fly")
                || isControllerStateDirect("swim_stand")
                || isControllerStateDirect("attacked")
                || isControllerStateDirect("jump")
                || isControllerStateDirect("sneak")
                || isControllerStateDirect("sneaking")
                || isControllerStateDirect("run")
                || isControllerStateDirect("walk");
        }

        private boolean isControllerStateDirect(String name) {
            if (player == null) return false;
            if ("death".equals(name)) {
                return player.isDead;
            }
            if ("sleep".equals(name)) {
                return player.isPlayerSleeping();
            }
            if ("swim".equals(name)) {
                return player.isInWater() && Math.abs(event.getLimbSwingAmount()) > 0.05f;
            }
            if ("climb".equals(name) || "climbing".equals(name)) {
                return player.isOnLadder();
            }
            if ("ladder_up".equals(name)) {
                return player.isOnLadder() && motionYState(0.1d) > 0;
            }
            if ("ladder_stillness".equals(name)) {
                return player.isOnLadder() && motionYState(0.1d) == 0;
            }
            if ("ladder_down".equals(name)) {
                return player.isOnLadder() && motionYState(0.1d) < 0;
            }
            if ("ride_pig".equals(name)) {
                return player.ridingEntity instanceof EntityPig;
            }
            if ("boat".equals(name)) {
                return player.ridingEntity instanceof EntityBoat;
            }
            if ("ride".equals(name) || "sit".equals(name)) {
                return player.isRiding();
            }
            if ("elytra_fly".equals(name)) {
                return EtFuturumCompat.isElytraFlying(player);
            }
            if ("fly".equals(name)) {
                return isFlying();
            }
            if ("swim_stand".equals(name)) {
                return player.isInWater();
            }
            if ("attacked".equals(name)) {
                return player.hurtTime > 0;
            }
            if ("jump".equals(name)) {
                return isJumping();
            }
            if ("sneak".equals(name)) {
                return isOnGround() && player.isSneaking() && Math.abs(event.getLimbSwingAmount()) > 0.05f;
            }
            if ("sneaking".equals(name)) {
                return isOnGround() && player.isSneaking() && !(Math.abs(event.getLimbSwingAmount()) > 0.05f);
            }
            if ("run".equals(name)) {
                return isOnGround() && player.isSprinting();
            }
            if ("walk".equals(name)) {
                return isOnGround() && event.getLimbSwingAmount() > 0.05f;
            }
            return false;
        }

        private double handMatch(List<Argument> arguments, boolean requireUse, boolean requireSwing) {
            String hand = arguments.size() > 0 ? arguments.get(0).asString() : "mainhand";
            String matcher = arguments.size() > 1 ? arguments.get(1).asString() : "";
            boolean mainHand = !"offhand".equals(hand);
            if (!mainHand && !BackhandCompat.isBackhandLoaded()) {
                return FALSE;
            }
            if (requireUse && (!player.isUsingItem() || BackhandCompat.getUsedItemHand(player) != mainHand)) {
                return FALSE;
            }
            if (requireSwing && (!player.isSwingInProgress || BackhandCompat.swingingArm(player) != mainHand)) {
                return FALSE;
            }
            ItemStack stack = BackhandCompat.getItemInHand(player, mainHand);
            return itemMatches(stack, matcher) ? TRUE : FALSE;
        }

        private boolean itemMatches(ItemStack stack, String matcher) {
            if (StringUtils.isBlank(matcher)) {
                return stack != null;
            }
            if ("empty".equals(matcher)) {
                return stack == null;
            }
            if (stack == null || stack.getItem() == null) {
                return false;
            }
            String id = itemId(stack);
            if (matcher.startsWith("$")) {
                return id.equals(matcher.substring(1).toLowerCase(Locale.ROOT));
            }
            if (matcher.startsWith("#")) {
                return false;
            }
            String category = matcher.startsWith(":") ? matcher.substring(1) : matcher;
            return itemCategoryMatches(stack, id, category.toLowerCase(Locale.ROOT));
        }

        private String itemId(ItemStack stack) {
            Object rawName = Item.itemRegistry.getNameForObject(stack.getItem());
            return rawName == null ? "" : rawName.toString().toLowerCase(Locale.ROOT);
        }

        private boolean itemCategoryMatches(ItemStack stack, String id, String category) {
            String itemType = InnerClassify.getItemType(stack);
            if (category.equals(itemType)) {
                return true;
            }
            if ("trident".equals(category) && "spear".equals(itemType)) {
                return true;
            }
            if ("spear".equals(category) || "trident".equals(category)) {
                return id.contains("spear") || id.contains("trident");
            }
            if (isKnownItemCategory(category)) {
                return false;
            }
            return id.contains(category);
        }

        private boolean isKnownItemCategory(String category) {
            return "sword".equals(category)
                || "axe".equals(category)
                || "pickaxe".equals(category)
                || "shovel".equals(category)
                || "hoe".equals(category)
                || "bow".equals(category)
                || "crossbow".equals(category)
                || "shield".equals(category)
                || "spear".equals(category)
                || "trident".equals(category)
                || "fishing_rod".equals(category)
                || "throwable_potion".equals(category);
        }

        private boolean allAnimationsFinished() {
            Animation current = event.getController() == null ? null : event.getController().getCurrentAnimation();
            if (current == null || current.animationLength == null || current.animationLength <= 0.0d) {
                // No animation to play — trivially all finished.
                return true;
            }
            // If the controller's animation builder is empty (cleared by the empty-state
            // handler returning null last frame), the current animation is stale/leftover
            // from a previous state and should be treated as finished.
            if (event.getController().currentAnimationBuilder == null
                || event.getController().currentAnimationBuilder.getRawAnimationList().isEmpty()) {
                return true;
            }
            // event.getAnimationTick() 和 current.animationLength 都是 tick 数（20 TPS），
            // 两者单位一致，直接比较。注意 anim_time 查询会除以 20 转成秒给 Molang 用，
            // 但这里不应该除。
            return event.getAnimationTick() - state.enteredTick >= current.animationLength;
        }

        private boolean isOnGround() {
            if (player == null) return false;
            if (player == Minecraft.getMinecraft().thePlayer) {
                return player.onGround;
            }
            return RemotePlayerMotionStates.isOnGround(player);
        }

        private boolean isFlying() {
            if (player == null) return false;
            // 检查鞘翅飞行（Et-Futurum 或其他鞘翅mod）
            if (EtFuturumCompat.isElytraFlying(player)) {
                return true;
            }
            if (player == Minecraft.getMinecraft().thePlayer) {
                return player.capabilities.isFlying;
            }
            return RemotePlayerMotionStates.isFlying(player);
        }

        private boolean isJumping() {
            if (player == null) return false;
            return !isFlying() && !player.isRiding() && !isOnGround() && !player.isInWater()
                && motionYState(0.0d) != 0;
        }

        private double horizontalSpeed() {
            if (player == null) return 0.0d;
            double x = player.posX - player.prevPosX;
            double z = player.posZ - player.prevPosZ;
            return MathHelper.sqrt_double(x * x + z * z) * 20.0d;
        }

        private int motionYState(double threshold) {
            double motionY = player == Minecraft.getMinecraft().thePlayer ? player.motionY
                : (player.posY - player.prevPosY) * 2.0d;
            if (motionY > threshold) {
                return 1;
            }
            if (motionY < -threshold) {
                return -1;
            }
            return 0;
        }
    }

    static final class Argument {
        private final String stringValue;
        private final double numberValue;
        private final boolean string;

        private Argument(String stringValue, double numberValue, boolean string) {
            this.stringValue = stringValue;
            this.numberValue = numberValue;
            this.string = string;
        }

        static Argument string(String value) {
            return new Argument(value, 0.0d, true);
        }

        static Argument number(double value) {
            return new Argument("", value, false);
        }

        String asString() {
            return string ? stringValue : Double.toString(numberValue);
        }

        double asNumber() {
            return string ? parseStringAsNumber(stringValue) : numberValue;
        }

        private static double parseStringAsNumber(String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return 0.0d;
            }
        }
    }
}
