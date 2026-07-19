package com.fox.ysmu.client.animation.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.AnimationEntry;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Controller;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.ControllerSet;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.State;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Transition;

import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * Simplified animation controller runtime for projectile sub-entities.
 * <p>
 * Evaluates OpenYSM controller state machines (defined in ysm.json's
 * files.projectiles entries) without coupling to EntityPlayer or
 * AnimationEvent.  Reads ysm.* Molang variables from the shared
 * {@link MolangParser#VARIABLES} map, which the caller populates
 * each frame before calling {@link #getActiveAnimations}.
 * </p>
 * <p>
 * State is maintained per entity (keyed by entity ID + animation ID +
 * controller name) in an internal concurrent map, so different projectiles
 * (arrows, tridents, etc.) have independent state machines.
 * </p>
 */
public final class ProjectileControllerRuntime {

    /** Per-controller runtime state. */
    private static final class RuntimeState {
        String currentState = "";
        double enteredTick;
    }

    /** Per-(entity, animation, controller) state key. */
    private static final class StateKey {
        final int entityId;
        final ResourceLocation animId;
        final String controllerName;

        StateKey(int entityId, ResourceLocation animId, String controllerName) {
            this.entityId = entityId;
            this.animId = animId;
            this.controllerName = controllerName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof StateKey)) return false;
            StateKey that = (StateKey) o;
            return entityId == that.entityId
                && animId.equals(that.animId)
                && controllerName.equals(that.controllerName);
        }

        @Override
        public int hashCode() {
            int result = entityId;
            result = 31 * result + animId.hashCode();
            result = 31 * result + controllerName.hashCode();
            return result;
        }
    }

    private static final Map<StateKey, RuntimeState> STATES = new ConcurrentHashMap<>();

    private ProjectileControllerRuntime() {}

    /**
     * Evaluates all controllers registered for the given projectile animation
     * ID and returns the list of animation names that should be active.
     *
     * @param entityId  the projectile entity's ID (for state isolation)
     * @param animId    the projectile's animation ResourceLocation
     *                  (e.g. {@code ysmu:mingf/projectile_#arrow})
     * @param ageInTicks current animation time in ticks
     * @return active animation names; empty if no controllers or no active animations
     */
    public static List<String> getActiveAnimations(int entityId, ResourceLocation animId, double ageInTicks) {
        ControllerSet set = OpenYsmAnimationControllerRegistry.get(animId);
        if (set == null || set.controllers.isEmpty()) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-PROJ-CTRL] getActiveAnimations: no controllers for {}, entityId={}",
                animId, entityId);
            return Collections.emptyList();
        }

        com.fox.ysmu.ysmu.LOG.info("[YSMU-PROJ-CTRL] getActiveAnimations: entityId={}, animId={}, controllerCount={}, names={}",
            entityId, animId, set.controllers.size(), set.controllers.keySet());

        // Collect all animation names referenced by any controller state
        java.util.Set<String> managedAnims = new java.util.HashSet<>();
        for (Controller c : set.controllers.values()) {
            for (State s : c.states.values()) {
                for (AnimationEntry ae : s.animations) {
                    managedAnims.add(ae.animationName);
                }
            }
        }

        // Evaluate controller state machines for active animations
        List<String> result = new ArrayList<>();
        for (Controller controller : set.controllers.values()) {
            List<String> controllerAnims = evaluateController(entityId, animId, controller, ageInTicks);
            com.fox.ysmu.ysmu.LOG.info("[YSMU-PROJ-CTRL]   controller '{}': state='{}', anims={}",
                controller.name,
                getCurrentStateName(entityId, animId, controller.name),
                controllerAnims);
            result.addAll(controllerAnims);
        }

        // Include unmanaged animations (e.g. parallel0-7) that are not referenced
        // by any controller state. These are pass-through animations that handle
        // bone visibility and should always play alongside controller-managed ones.
        software.bernie.geckolib3.file.AnimationFile animFile =
            software.bernie.geckolib3.resource.GeckoLibCache.getInstance().getAnimations().get(animId);
        if (animFile != null && animFile.animations != null) {
            for (String animName : animFile.animations.keySet()) {
                if (!managedAnims.contains(animName) && !result.contains(animName)) {
                    result.add(animName);
                }
            }
        }

        return result;
    }

    private static String getCurrentStateName(int entityId, ResourceLocation animId, String controllerName) {
        StateKey key = new StateKey(entityId, animId, controllerName);
        RuntimeState rs = STATES.get(key);
        return rs != null ? rs.currentState : "(no state)";
    }

    /**
     * Evaluate a single controller's state machine and return active animation names.
     */
    private static List<String> evaluateController(int entityId, ResourceLocation animId,
        Controller controller, double ageInTicks) {
        RuntimeState state = getOrCreateState(entityId, animId, controller.name);

        // Ensure current state is valid
        State current = controller.states.get(state.currentState);
        if (current == null) {
            state.currentState = "";
            state.enteredTick = ageInTicks;
        }

        // Resolve to initial state if needed
        if (StringUtils.isBlank(state.currentState)) {
            State initial = controller.getInitialState();
            if (initial == null) {
                return Collections.emptyList();
            }
            state.currentState = initial.name;
            state.enteredTick = ageInTicks;
            current = initial;
        }

        // Evaluate transitions (allow chaining up to 4 steps per frame)
        for (int i = 0; i < 4; i++) {
            State next = applyTransition(controller, current, state, ageInTicks);
            if (next == current) break;
            current = next;
        }

        // Collect active animation names from the current state
        List<String> anims = new ArrayList<>();
        if (current != null) {
            for (AnimationEntry entry : current.animations) {
                // Projectile animations typically don't use conditions,
                // but support them for completeness.
                if (StringUtils.isBlank(entry.condition)
                    || evaluateExpression(entry.condition)) {
                    // Only include animations that actually exist
                    if (animationExists(animId, entry.animationName)) {
                        anims.add(entry.animationName);
                    }
                }
            }
        }
        return anims;
    }

    /**
     * Evaluate transitions from the current state. Returns the first
     * state whose transition condition is met, or the current state
     * if no transition fires.
     */
    private static State applyTransition(Controller controller, State current,
        RuntimeState state, double ageInTicks) {
        for (Transition transition : current.transitions) {
            State target = controller.states.get(transition.targetState);
            if (target == null) continue;

            boolean conditionMet = evaluateExpression(transition.condition);
            if (!conditionMet) continue;

            // Transition!
            state.currentState = target.name;
            state.enteredTick = ageInTicks;
            return target;
        }
        return current;
    }

    /**
     * Evaluate a Molang expression in the projectile context.
     * Supports: ysm.* variables (read from MolangParser.VARIABLES),
     * !, &&, ||, ==, !=, comparisons, math.* functions, parentheses,
     * and numeric literals.
     */
    private static boolean evaluateExpression(String expression) {
        if (StringUtils.isBlank(expression)) {
            return true;
        }
        double result = eval(expression);
        boolean boolResult = Math.abs(result) > 0.000001d;
        com.fox.ysmu.ysmu.LOG.info("[YSMU-PROJ-CTRL] evaluateExpression: '{}' = {} ({})",
            expression, result, boolResult);
        return boolResult;
    }

    /**
     * Evaluate any Molang expression string and return the numeric result.
     */
    private static double eval(String expr) {
        if (StringUtils.isBlank(expr)) {
            return 1.0d; // empty = true
        }
        int[] idx = {0};
        Double result = parseOr(expr, idx);
        return result != null ? result : 0.0d;
    }

    // ── Recursive descent parser ──────────────────────────────────────────

    private static Double parseOr(String expr, int[] idx) {
        Double left = parseAnd(expr, idx);
        if (left == null) return null;
        while (true) {
            skipSpace(expr, idx);
            if (match(expr, idx, "||")) {
                Double right = parseAnd(expr, idx);
                if (right == null) return null;
                left = (truthy(left) || truthy(right)) ? 1.0d : 0.0d;
            } else {
                return left;
            }
        }
    }

    private static Double parseAnd(String expr, int[] idx) {
        Double left = parseEquality(expr, idx);
        if (left == null) return null;
        while (true) {
            skipSpace(expr, idx);
            if (match(expr, idx, "&&")) {
                Double right = parseEquality(expr, idx);
                if (right == null) return null;
                left = (truthy(left) && truthy(right)) ? 1.0d : 0.0d;
            } else {
                return left;
            }
        }
    }

    private static Double parseEquality(String expr, int[] idx) {
        Double left = parseComparison(expr, idx);
        if (left == null) return null;
        while (true) {
            skipSpace(expr, idx);
            if (match(expr, idx, "==")) {
                Double right = parseComparison(expr, idx);
                if (right == null) return null;
                left = nearlyEqual(left, right) ? 1.0d : 0.0d;
            } else if (match(expr, idx, "!=")) {
                Double right = parseComparison(expr, idx);
                if (right == null) return null;
                left = nearlyEqual(left, right) ? 0.0d : 1.0d;
            } else {
                return left;
            }
        }
    }

    private static Double parseComparison(String expr, int[] idx) {
        Double left = parseAdditive(expr, idx);
        if (left == null) return null;
        while (true) {
            skipSpace(expr, idx);
            if (match(expr, idx, ">=")) {
                Double right = parseAdditive(expr, idx);
                if (right == null) return null;
                left = left >= right ? 1.0d : 0.0d;
            } else if (match(expr, idx, "<=")) {
                Double right = parseAdditive(expr, idx);
                if (right == null) return null;
                left = left <= right ? 1.0d : 0.0d;
            } else if (match(expr, idx, ">")) {
                Double right = parseAdditive(expr, idx);
                if (right == null) return null;
                left = left > right ? 1.0d : 0.0d;
            } else if (match(expr, idx, "<")) {
                Double right = parseAdditive(expr, idx);
                if (right == null) return null;
                left = left < right ? 1.0d : 0.0d;
            } else {
                return left;
            }
        }
    }

    private static Double parseAdditive(String expr, int[] idx) {
        Double left = parseMultiplicative(expr, idx);
        if (left == null) return null;
        while (true) {
            skipSpace(expr, idx);
            if (match(expr, idx, "+")) {
                Double right = parseMultiplicative(expr, idx);
                if (right == null) return null;
                left = left + right;
            } else if (match(expr, idx, "-")) {
                Double right = parseMultiplicative(expr, idx);
                if (right == null) return null;
                left = left - right;
            } else {
                return left;
            }
        }
    }

    private static Double parseMultiplicative(String expr, int[] idx) {
        Double left = parseUnary(expr, idx);
        if (left == null) return null;
        while (true) {
            skipSpace(expr, idx);
            if (match(expr, idx, "*")) {
                Double right = parseUnary(expr, idx);
                if (right == null) return null;
                left = left * right;
            } else if (match(expr, idx, "/")) {
                Double right = parseUnary(expr, idx);
                if (right == null) return null;
                left = right == 0.0d ? 0.0d : left / right;
            } else if (match(expr, idx, "%")) {
                Double right = parseUnary(expr, idx);
                if (right == null) return null;
                left = right == 0.0d ? 0.0d : left % right;
            } else {
                return left;
            }
        }
    }

    private static Double parseUnary(String expr, int[] idx) {
        skipSpace(expr, idx);
        if (match(expr, idx, "!")) {
            Double operand = parseUnary(expr, idx);
            return operand != null ? (truthy(operand) ? 0.0d : 1.0d) : null;
        }
        if (match(expr, idx, "-")) {
            Double operand = parseUnary(expr, idx);
            return operand != null ? -operand : null;
        }
        return parsePrimary(expr, idx);
    }

    private static Double parsePrimary(String expr, int[] idx) {
        skipSpace(expr, idx);
        if (idx[0] >= expr.length()) return 0.0d;

        char c = expr.charAt(idx[0]);

        // Parenthesized expression
        if (c == '(') {
            idx[0]++;
            Double inner = parseOr(expr, idx);
            match(expr, idx, ")");
            return inner;
        }

        // Quoted string (treated as false)
        if (c == '\'' || c == '"') {
            skipQuotedString(expr, idx);
            return 0.0d;
        }

        // Numeric literal
        if (Character.isDigit(c) || (c == '.' && idx[0] + 1 < expr.length()
            && Character.isDigit(expr.charAt(idx[0] + 1)))) {
            return parseNumber(expr, idx);
        }

        // Identifier / variable / function call
        int start = idx[0];
        String identifier = parseIdentifier(expr, idx);
        if (identifier.isEmpty()) {
            idx[0]++;
            return 0.0d;
        }

        skipSpace(expr, idx);

        // Boolean literals
        if ("true".equals(identifier)) return 1.0d;
        if ("false".equals(identifier)) return 0.0d;

        // Function call
        if (match(expr, idx, "(")) {
            List<Double> args = new ArrayList<>();
            while (true) {
                skipSpace(expr, idx);
                if (match(expr, idx, ")")) break;
                if (idx[0] >= expr.length()) break;
                args.add(eval(readRawArg(expr, idx)));
                skipSpace(expr, idx);
                if (!match(expr, idx, ",")) {
                    match(expr, idx, ")");
                    break;
                }
            }
            return evaluateFunction(identifier, args);
        }

        // Variable reference (ysm.xxx)
        return resolveVariable(identifier);
    }

    /**
     * Resolve a variable reference in the projectile context.
     * Supports ysm.* variables from MolangParser.VARIABLES.
     */
    private static double resolveVariable(String name) {
        if (name.startsWith("ysm.")) {
            String varName = name.substring(4); // "ysm.in_ground" → "in_ground"
            Map<String, software.bernie.geckolib3.core.molang.LazyVariable> vars = MolangParser.VARIABLES;
            software.bernie.geckolib3.core.molang.LazyVariable v = vars.get(varName);
            if (v != null) return v.get();
            // Try with "ysm." prefix in the variable map
            v = vars.get(name);
            if (v != null) return v.get();
            return 0.0d;
        }
        if (name.startsWith("v.")) {
            // v.* variables are not used in projectile controllers currently
            return 0.0d;
        }
        // Try as a direct MolangParser variable
        Map<String, software.bernie.geckolib3.core.molang.LazyVariable> vars = MolangParser.VARIABLES;
        software.bernie.geckolib3.core.molang.LazyVariable v = vars.get(name);
        if (v != null) return v.get();
        return 0.0d;
    }

    /**
     * Evaluate a function call.
     */
    private static double evaluateFunction(String name, List<Double> args) {
        int n = args.size();
        if ("math.abs".equals(name) && n >= 1) return Math.abs(args.get(0));
        if ("math.sqrt".equals(name) && n >= 1) return args.get(0) < 0 ? 0.0d : Math.sqrt(args.get(0));
        if ("math.floor".equals(name) && n >= 1) return Math.floor(args.get(0));
        if ("math.ceil".equals(name) && n >= 1) return Math.ceil(args.get(0));
        if ("math.round".equals(name) && n >= 1) return Math.round(args.get(0));
        if ("math.sin".equals(name) && n >= 1) return Math.sin(Math.toRadians(args.get(0)));
        if ("math.cos".equals(name) && n >= 1) return Math.cos(Math.toRadians(args.get(0)));
        if ("math.exp".equals(name) && n >= 1) return Math.exp(args.get(0));
        if ("math.ln".equals(name) && n >= 1) return args.get(0) <= 0 ? 0.0d : Math.log(args.get(0));
        if (("math.hermite_blend".equals(name) || "math.hermite".equals(name)) && n >= 1) {
            double v = args.get(0);
            return v * v * (3 - 2 * v);
        }
        if ("math.pow".equals(name) && n >= 2) return Math.pow(args.get(0), args.get(1));
        if ("math.max".equals(name) && n >= 2) return Math.max(args.get(0), args.get(1));
        if ("math.min".equals(name) && n >= 2) return Math.min(args.get(0), args.get(1));
        if ("math.mod".equals(name) && n >= 2) {
            double b = args.get(1);
            if (b == 0.0d) return 0.0d;
            double r = args.get(0) % b;
            return r < 0 ? r + Math.abs(b) : r;
        }
        if ("math.atan2".equals(name) && n >= 2) return Math.toDegrees(Math.atan2(args.get(0), args.get(1)));
        if ("math.atan".equals(name) && n >= 1) return Math.toDegrees(Math.atan(args.get(0)));
        if ("math.clamp".equals(name) && n >= 3) {
            return Math.max(args.get(1), Math.min(args.get(2), args.get(0)));
        }
        if ("math.lerp".equals(name) && n >= 3) {
            return args.get(0) + (args.get(1) - args.get(0)) * args.get(2);
        }
        if ("math.random".equals(name) && n >= 2) {
            double low = args.get(0), high = args.get(1);
            return low >= high ? low : low + Math.random() * (high - low);
        }
        if ("math.min_angle".equals(name) && n >= 2) {
            double diff = (args.get(1) - args.get(0)) % 360;
            if (diff > 180) diff -= 360;
            if (diff <= -180) diff += 360;
            return diff;
        }
        if ("math.lerprotate".equals(name) && n >= 3) {
            double a = args.get(0), b = args.get(1), c = args.get(2);
            double diff = (b - a) % 360;
            if (diff > 180) diff -= 360;
            if (diff <= -180) diff += 360;
            double r = a + diff * c;
            if (r >= 360) r -= 360;
            if (r < 0) r += 360;
            return r;
        }
        if ((("math.die_roll".equals(name) || "math.roll".equals(name)) && n >= 3)
            || (("math.die_roll_integer".equals(name) || "math.rolli".equals(name)) && n >= 3)) {
            boolean isInt = "math.die_roll_integer".equals(name) || "math.rolli".equals(name);
            int count = Math.min((int) (double) args.get(0), 100);
            double low = args.get(1), high = args.get(2);
            double sum = 0;
            for (int i = 0; i < count; i++) {
                if (isInt) {
                    int min = (int) low, max = (int) high;
                    if (min > max) { int t = min; min = max; max = t; }
                    sum += min + (int) (Math.random() * (max - min + 1));
                } else {
                    sum += low + Math.random() * (high - low);
                }
            }
            return sum;
        }
        if ("math.pi".equals(name) && n == 0) return Math.PI;
        if ("math.e".equals(name) && n == 0) return Math.E;
        // Unsupported function → false
        return 0.0d;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static Double parseNumber(String expr, int[] idx) {
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
        return Double.parseDouble(expr.substring(start, idx[0]));
    }

    private static String parseIdentifier(String expr, int[] idx) {
        int start = idx[0];
        while (idx[0] < expr.length()) {
            char c = expr.charAt(idx[0]);
            if (Character.isLetter(c) || c == '_' || c == '.') {
                idx[0]++;
            } else {
                break;
            }
        }
        return expr.substring(start, idx[0]);
    }

    private static void skipQuotedString(String expr, int[] idx) {
        if (idx[0] >= expr.length()) return;
        char quote = expr.charAt(idx[0]);
        idx[0]++;
        while (idx[0] < expr.length() && expr.charAt(idx[0]) != quote) {
            idx[0]++;
        }
        if (idx[0] < expr.length()) idx[0]++;
    }

    /**
     * Read a raw argument to a function call, respecting parentheses
     * and quoted strings. Does NOT compile the expression — just returns
     * the raw text so the caller can eval() it.
     */
    private static String readRawArg(String expr, int[] idx) {
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

    private static void skipSpace(String expr, int[] idx) {
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

    private static boolean truthy(double value) {
        return Math.abs(value) > 0.000001d;
    }

    private static boolean nearlyEqual(double left, double right) {
        return Math.abs(left - right) < 0.000001d;
    }

    private static boolean animationExists(ResourceLocation animId, String animationName) {
        software.bernie.geckolib3.file.AnimationFile file =
            GeckoLibCache.getInstance().getAnimations().get(animId);
        return file != null && file.animations.containsKey(animationName);
    }

    private static RuntimeState getOrCreateState(int entityId, ResourceLocation animId, String controllerName) {
        StateKey key = new StateKey(entityId, animId, controllerName);
        return STATES.computeIfAbsent(key, k -> new RuntimeState());
    }

    /** Called when model caches are cleared (e.g. /ysm reload). */
    public static void clear() {
        STATES.clear();
    }
}
