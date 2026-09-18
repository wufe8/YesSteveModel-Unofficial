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

    /** 弹射物的实体状态，决定 {@code air} / {@code ground} / {@code fire} / {@code water} 选哪几个。 */
    public static final class ProjectileState {

        /** 默认状态：在空中飞行、没有落地/着火/入水。 */
        public static final ProjectileState AIRBORNE = new ProjectileState(true, false, false, false);

        final boolean inAir;
        final boolean inGround;
        final boolean onFire;
        final boolean inWater;

        public ProjectileState(boolean inAir, boolean inGround, boolean onFire, boolean inWater) {
            this.inAir = inAir;
            this.inGround = inGround;
            this.onFire = onFire;
            this.inWater = inWater;
        }

        /**
         * 由弹射物实体推导状态。四个状态**可以同时为真**：落地/入水/着火并不互斥，优先级由
         * {@link #getActiveAnimations} 的应用顺序决定。{@code inAir} 只要求"没落地"，因为
         * 入水/着火的动画是叠在飞行动画之上覆盖的（wiki: air 优先度低，被其他动画覆盖）。
         */
        public static ProjectileState of(boolean inGround, boolean inWater, boolean onFire) {
            return new ProjectileState(!inGround, inGround, onFire, inWater);
        }
    }

    private ProjectileControllerRuntime() {}

    /**
     * 一个"当前应播放的弹射物动画"，以及它相对**所属控制器状态进入时刻**的已播放 tick。
     *
     * <p>状态动画必须从进入该状态的那一帧开始计时：{@code post_main} / {@code post_ground}
     * 这类 {@code hold_on_last_frame} 动画只在开头几 tick 里把骨骼放大/爆开，用实体总年龄去采样
     * 会直接停在最后一帧上（实测落地动画永远停在"缩放 0"）。{@code air}/{@code ground}/
     * {@code parallel*} 这些非控制器动画仍然从实体出生起计时（循环动画，偏移恒为 0）。</p>
     */
    public static final class ActiveAnimation {

        public final String name;
        /** 相对状态进入时刻已经过的 tick；非控制器动画恒为 0。 */
        public final double tickOffset;

        ActiveAnimation(String name, double tickOffset) {
            this.name = name;
            this.tickOffset = tickOffset;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Evaluates all controllers registered for the given projectile animation
     * ID and returns the list of animation names that should be active.
     *
     * @param entityId  the projectile entity's ID (for state isolation)
     * @param animId    the projectile's animation ResourceLocation
     *                  (e.g. {@code ysmu:<model>/projectile_#arrow})
     * @param ageInTicks current animation time in ticks
     * @return active animation names; empty if no controllers or no active animations
     */
    public static List<String> getActiveAnimations(int entityId, ResourceLocation animId, double ageInTicks) {
        return getActiveAnimations(entityId, animId, ageInTicks, ProjectileState.AIRBORNE);
    }

    /**
     * Evaluates all controllers registered for the given projectile animation
     * ID and returns the list of animation names that should be active.
     *
     * @param entityId  the projectile entity's ID (for state isolation)
     * @param animId    the projectile's animation ResourceLocation
     *                  (e.g. {@code ysmu:<model>/projectile_#arrow})
     * @param ageInTicks current animation time in ticks
     * @param state     the entity state that selects the {@code air}/{@code ground}/
     *                  {@code fire}/{@code water} state animations
     * @return active animation names; empty if no controllers or no active animations
     */
    public static List<String> getActiveAnimations(int entityId, ResourceLocation animId, double ageInTicks,
        ProjectileState state) {
        List<ActiveAnimation> entries = getActiveAnimationEntries(entityId, animId, ageInTicks, state);
        List<String> names = new ArrayList<>(entries.size());
        for (ActiveAnimation entry : entries) {
            names.add(entry.name);
        }
        return names;
    }

    /**
     * 同 {@link #getActiveAnimations(int, ResourceLocation, double, ProjectileState)}，但额外带回
     * 每条动画**相对它所属控制器状态进入时刻**的已播放 tick（渲染器要用它采样关键帧，见
     * {@link ActiveAnimation}）。返回顺序与 {@code getActiveAnimations} 完全一致，一次求值只走
     * 一遍状态机。
     */
    public static List<ActiveAnimation> getActiveAnimationEntries(int entityId, ResourceLocation animId,
        double ageInTicks, ProjectileState state) {
        ControllerSet set = OpenYsmAnimationControllerRegistry.get(animId);
        if (set == null || set.controllers.isEmpty()) {
            if (com.fox.ysmu.Config.DEBUG_CONTROLLER) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-PROJ-CTRL] getActiveAnimations: no controllers for {}, entityId={}",
                    animId, entityId);
            }
            return selectImplicitAnimations(animId, state, new ArrayList<>(), Collections.emptySet());
        }

        if (com.fox.ysmu.Config.DEBUG_CONTROLLER) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-PROJ-CTRL] getActiveAnimations: entityId={}, animId={}, controllerCount={}, names={}",
                entityId, animId, set.controllers.size(), set.controllers.keySet());
        }

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
        List<ActiveAnimation> result = new ArrayList<>();
        for (Controller controller : set.controllers.values()) {
            List<ActiveAnimation> controllerAnims = evaluateController(entityId, animId, controller, ageInTicks);
            if (com.fox.ysmu.Config.DEBUG_CONTROLLER) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-PROJ-CTRL]   controller '{}': state='{}', anims={}",
                    controller.name,
                    getCurrentStateName(entityId, animId, controller.name),
                    controllerAnims);
            }
            result.addAll(controllerAnims);
        }

        return selectImplicitAnimations(animId, state, result, managedAnims);
    }

    /**
     * Appends the animations the projectile runtime picks by itself (outside the
     * controller state machines) and returns the final, ordered active list.
     *
     * <p>YSM-wiki: 动画制作/弹射物动画（其前身 箭矢动画）把弹射物的可自定义动画定为
     * {@code water} / {@code fire} / {@code ground} / {@code air} 四个**状态**动画加
     * {@code parallel0..7} 并行动画。状态动画按实体状态播放：{@code air}/{@code ground}
     * 优先度低（会被其他动画覆盖），{@code fire}/{@code water} 优先度高（只被并行动画覆盖）；
     * 并行动画恒定播放且优先级最高。渲染器按返回列表的顺序逐个把关键帧写成骨骼的**绝对**变换，
     * 所以"后出现 = 优先度高"就是列表顺序。</p>
     *
     * <p>这里必须按状态过滤。早先的实现是"把控制器没引用到的动画全部当直通动画播放"，
     * 于是 {@code air}/{@code fire}/{@code ground}/{@code water} 四个状态动画同时生效 ——
     * 模型为不同弹射物状态准备的子模型（弓、弩、爆开、落地插地…）会全部叠在同一个实体上显示，
     * 而没有任何控制器的模型（只有状态动画 + 并行动画）受这个错误影响最严重。</p>
     */
    private static List<ActiveAnimation> selectImplicitAnimations(ResourceLocation animId, ProjectileState state,
        List<ActiveAnimation> result, java.util.Set<String> managedAnims) {
        software.bernie.geckolib3.file.AnimationFile animFile =
            software.bernie.geckolib3.resource.GeckoLibCache.getInstance().getAnimations().get(animId);
        if (animFile == null || animFile.animations == null) {
            return result;
        }

        // 低优先度：飞行 → 落地；高优先度：着火 → 入水。后加入的在同骨骼上覆盖先加入的。
        addIfPresent(result, animFile, state.inAir, "air");
        addIfPresent(result, animFile, state.inGround, "ground");
        addIfPresent(result, animFile, state.onFire, "fire");
        addIfPresent(result, animFile, state.inWater, "water");

        // 并行动画：恒定播放，数字越大越靠后（优先级越高）。
        for (int i = 0; i < MAX_PARALLEL_SLOT; i++) {
            addIfPresent(result, animFile, true, "parallel" + i);
        }

        // 其余既没被控制器引用、也不是状态/并行动画的动画不再无条件播放：它们不在弹射物动画
        // 清单里，旧实现的"一律播放"正是状态动画叠加的来源。开 DEBUG_CONTROLLER 时列出来，
        // 便于发现确实依赖这种非标准写法的模型。
        if (com.fox.ysmu.Config.DEBUG_CONTROLLER) {
            for (String name : animFile.animations.keySet()) {
                if (containsName(result, name) || managedAnims.contains(name) || isKnownImplicitAnimation(name)) {
                    continue;
                }
                com.fox.ysmu.ysmu.LOG.info(
                    "[YSMU-PROJ-CTRL]   ignored non-standard unmanaged animation '{}' for {}", name, animId);
            }
        }
        return result;
    }

    private static boolean containsName(List<ActiveAnimation> entries, String name) {
        for (ActiveAnimation entry : entries) {
            if (entry.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** {@code parallel0..7} 的槽位数（wiki: 弹射物并行动画固定 8 个槽位）。 */
    private static final int MAX_PARALLEL_SLOT = 8;

    private static boolean isKnownImplicitAnimation(String name) {
        if (name == null) {
            return false;
        }
        if ("air".equals(name) || "ground".equals(name) || "fire".equals(name) || "water".equals(name)) {
            return true;
        }
        if (!name.startsWith("parallel")) {
            return false;
        }
        String digits = name.substring("parallel".length());
        return digits.length() == 1 && digits.charAt(0) >= '0' && digits.charAt(0) < '0' + MAX_PARALLEL_SLOT;
    }

    private static void addIfPresent(List<ActiveAnimation> result, software.bernie.geckolib3.file.AnimationFile animFile,
        boolean condition, String animationName) {
        if (!condition || containsName(result, animationName)) {
            return;
        }
        if (animFile.animations.containsKey(animationName)) {
            // 非控制器动画：从实体出生起计时（循环动画），偏移为 0。
            result.add(new ActiveAnimation(animationName, 0.0d));
        }
    }

    private static String getCurrentStateName(int entityId, ResourceLocation animId, String controllerName) {
        StateKey key = new StateKey(entityId, animId, controllerName);
        RuntimeState rs = STATES.get(key);
        return rs != null ? rs.currentState : "(no state)";
    }

    /**
     * Evaluate a single controller's state machine and return its active animations,
     * each carrying the tick offset of the state it belongs to.
     */
    private static List<ActiveAnimation> evaluateController(int entityId, ResourceLocation animId,
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

        // 状态动画从**进入当前状态**那一刻开始计时：YSM 的控制器每进入一个状态就从头播该状态的
        // 动画（hold_on_last_frame 停住）。用实体总年龄采样会让 0.2 秒长的落地动画从第一帧起就
        // 停在最后一帧上，整段爆开效果被跳过。
        double tickOffset = Math.max(0.0d, ageInTicks - state.enteredTick);

        // Collect active animation names from the current state
        List<ActiveAnimation> anims = new ArrayList<>();
        if (current != null) {
            for (AnimationEntry entry : current.animations) {
                // Projectile animations typically don't use conditions,
                // but support them for completeness.
                if (StringUtils.isBlank(entry.condition)
                    || evaluateExpression(entry.condition)) {
                    // Only include animations that actually exist
                    if (animationExists(animId, entry.animationName)) {
                        anims.add(new ActiveAnimation(entry.animationName, tickOffset));
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
        if (com.fox.ysmu.Config.DEBUG_CONTROLLER) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-PROJ-CTRL] evaluateExpression: '{}' = {} ({})",
                expression, result, boolResult);
        }
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
            if (Character.isLetter(c) || Character.isDigit(c) || c == '_' || c == '.') {
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

    /**
     * Removes all controller state entries for the given entity ID.
     * Call when the projectile entity is removed from the world to
     * prevent stale entries accumulating in {@link #STATES}.
     */
    public static void cleanupEntity(int entityId) {
        STATES.entrySet().removeIf(e -> e.getKey().entityId == entityId);
    }

    /**
     * Removes all controller state entries for the given animation ID.
     * Call when model caches are cleared for a specific model.
     */
    public static void cleanupAnimation(ResourceLocation animId) {
        STATES.entrySet().removeIf(e -> e.getKey().animId.equals(animId));
    }
}
