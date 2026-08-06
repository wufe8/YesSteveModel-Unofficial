package com.fox.ysmu.client.animation.controller;

import static com.fox.ysmu.util.ControllerUtils.CAP_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.HOLD_MAINHAND_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.HOLD_OFFHAND_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.MAIN_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.OPENYSM_PRE_MAIN_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.SWING_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.USE_CONTROLLER;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.ysmu;
import com.fox.ysmu.client.animation.MovementSpeedMatcher;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.AnimationEntry;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Controller;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.ControllerSet;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.State;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Transition;
import com.fox.ysmu.client.animation.molang.MolangInstructionExecutor;
import com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime;
import com.fox.ysmu.client.entity.CustomPlayerEntity;

import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

public final class OpenYsmPlayerControllerRuntime {

    private static final Map<StateKey, RuntimeState> STATES = new ConcurrentHashMap<>();
    /** Simple per-tag rate limiter for debug logs: tag → last log time (ms). */
    private static final java.util.Map<String, Long> DEBUG_LOG_LAST_TIME = new ConcurrentHashMap<>();

    /** Returns true if the given debug tag should log now (at most once per 1000ms). */
    private static boolean allowDebugLog(String tag) {
        long now = System.currentTimeMillis();
        Long last = DEBUG_LOG_LAST_TIME.get(tag);
        if (last != null && now - last < 1000) {
            return false;
        }
        DEBUG_LOG_LAST_TIME.put(tag, now);
        return true;
    }

    /** Roaming variables set from outside the render loop (e.g. GUI config panel).
     *  Key is the variable name WITHOUT the "v." prefix (e.g. "roaming.ef").
     *  NOTE: This is a global flat map shared across all models.  To prevent
     *  cross-model contamination, use {@link #getRoamingVarsForModel(ResourceLocation)}
     *  instead of iterating this map directly. */
    public static final Map<String, Double> PENDING_ROAMING = new ConcurrentHashMap<>();
    /** Tracks which PENDING_ROAMING keys were explicitly set by user interaction
     *  (not just default-initialized). Used by the ?? operator to distinguish
     *  "user set to 0" from "never set (defaults to 0)".
     *  NOTE: 全局集合仅用于"无模型上下文"写入的兜底（见 markRoamingExplicit）。
     *  正常路径应使用 {@link #markRoamingExplicit}/{@link #isRoamingExplicit}
     *  按模型维度读写，避免模型 A 的显式设置泄漏到模型 B。 */
    public static final java.util.Set<String> EXPLICIT_ROAMING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 显式设置的 roaming 变量按模型隔离：modelId → set of varName（不含 v. 前缀）。
     * 用户通过轮盘 GUI 或在控制器 onEntry/onExit 表达式中设置 v.roaming.* 时，
     * 只标记该变量属于当前操作的模型，避免同名但含义不同的自定义变量跨模型串值。
     */
    private static final Map<ResourceLocation, java.util.Set<String>> EXPLICIT_ROAMING_BY_MODEL =
        new ConcurrentHashMap<>();

    /**
     * 标记某模型的 roaming 变量为"用户显式设置"。modelId 为 null 时退化为
     * 全局标记（写入方拿不到模型上下文的兜底，对所有模型生效）。
     */
    public static void markRoamingExplicit(ResourceLocation modelId, String varName) {
        if (modelId != null) {
            EXPLICIT_ROAMING_BY_MODEL.computeIfAbsent(modelId,
                k -> java.util.Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(varName);
        } else {
            EXPLICIT_ROAMING.add(varName);
        }
    }

    /** 判断某变量是否在指定模型上被显式设置。全局标记（无模型上下文写入）对所有模型生效。 */
    public static boolean isRoamingExplicit(ResourceLocation modelId, String varName) {
        if (EXPLICIT_ROAMING.contains(varName)) {
            return true;
        }
        if (modelId != null) {
            java.util.Set<String> set = EXPLICIT_ROAMING_BY_MODEL.get(modelId);
            return set != null && set.contains(varName);
        }
        return false;
    }

    /**
     * Tracks which roaming variable names each model has registered via its
     * extraAnimationButtons config forms.  Used to filter PENDING_ROAMING
     * entries so that model A's roaming variables don't leak into model B's
     * ScopeState or controller RuntimeState.
     * Key = model ResourceLocation (mainId), value = set of variable names
     * WITHOUT the "v." prefix (e.g. "qh", "roaming.ef").
     */
    private static final Map<ResourceLocation, java.util.Set<String>> MODEL_ROAMING_VARS = new ConcurrentHashMap<>();

    /**
     * Per-model initial default values for roaming variables, set during
     * {@code registerExtraWheel()} from each model's ysm.json config forms.
     * These are used as the base in {@link #computeRoamingVarsForModel},
     * overlaid with any explicitly user-set values from {@link #PENDING_ROAMING}.
     * Key = model ResourceLocation (mainId), value = varName → default double.
     */
    private static final Map<ResourceLocation, Map<String, Double>> MODEL_ROAMING_DEFAULTS = new ConcurrentHashMap<>();

    /**
     * Frame-scoped cache for getRoamingVarsForModel — valid only within begin()/end().
     * Keyed by modelId to prevent cross-model contamination when multiple models
     * render in the same frame (e.g. GUI preview + player entity have different
     * MODEL_ROAMING_VARS sizes and the cache would return the wrong model's data).
     */
    private static final java.util.Map<ResourceLocation, Map<String, Double>> frameRoamingCache =
        new java.util.HashMap<>();

    /**
     * Registers a roaming variable name as belonging to the given model.
     * Called during model registration (registerExtraWheel).
     */
    public static void registerModelRoamingVar(ResourceLocation modelId, String varName) {
        MODEL_ROAMING_VARS.computeIfAbsent(modelId, k -> java.util.Collections.newSetFromMap(new ConcurrentHashMap<>()))
            .add(varName);
    }

    /**
     * Stores a per-model default value for a roaming variable, set from the
     * model's ysm.json config forms.  Used as the base value in
     * {@link #computeRoamingVarsForModel} when the variable has not been
     * explicitly set by the user via the wheel GUI.
     */
    public static void setModelRoamingDefault(ResourceLocation modelId, String varName, double value) {
        MODEL_ROAMING_DEFAULTS.computeIfAbsent(modelId, k -> new ConcurrentHashMap<>())
            .put(varName, value);
    }

    /**
     * Returns the subset of PENDING_ROAMING entries that belong to the given model.
     * Also includes global entries (lock_wheel, wheel_anim) that are not model-specific.
     * Results are cached for the duration of the current render frame (begin()/end()).
     */
    public static Map<String, Double> getRoamingVarsForModel(ResourceLocation modelId) {
        // Frame-scoped cache: all ~33 callers per frame share the same result.
        Map<String, Double> cached = frameRoamingCache.get(modelId);
        if (cached != null) {
            return cached;
        }
        Map<String, Double> result = computeRoamingVarsForModel(modelId);
        frameRoamingCache.put(modelId, result);
        return result;
    }

    /** Invalidates the frame-scoped roaming variable cache (called at frame end). */
    public static void invalidateFrameRoamingCache() {
        frameRoamingCache.clear();
    }

    /** Computes the roaming variable map for the given model — no caching. */
    private static Map<String, Double> computeRoamingVarsForModel(ResourceLocation modelId) {
        if (modelId == null) {
            return java.util.Collections.emptyMap();
        }
        java.util.Set<String> knownVars = MODEL_ROAMING_VARS.get(modelId);
        Map<String, Double> result = new java.util.HashMap<>();

        // 1. Start with per-model defaults from ysm.json config forms.
        //    This ensures each model gets its own initial values regardless
        //    of model load order (fixes cross-model default contamination).
        Map<String, Double> defaults = MODEL_ROAMING_DEFAULTS.getOrDefault(modelId,
            java.util.Collections.emptyMap());
        result.putAll(defaults);

        // 2. Overlay with explicitly user-set values from PENDING_ROAMING.
        //    Only vars explicitly marked for THIS model are overlaid — initial
        //    defaults set by registerExtraWheel() are NOT in EXPLICIT_ROAMING,
        //    so they stay per-model.  Using isRoamingExplicit(modelId, ...)
        //    keeps model A's user-set values from leaking into model B even
        //    when both models happen to use the same variable name.
        for (Map.Entry<String, Double> entry : PENDING_ROAMING.entrySet()) {
            String key = entry.getKey();
            boolean inExplicit = isRoamingExplicit(modelId, key);
            // Global vars that are not model-specific
            boolean isGlobal = "lock_wheel".equals(key) || "wheel_anim".equals(key);
            if (inExplicit || isGlobal) {
                result.put(key, entry.getValue());
            }
        }

        // 3. For models with no registered vars, also include any PENDING_ROAMING
        //    entries that are explicitly set but not known to any registered model
        //    (e.g. runtime-registered vars from radio button expressions).
        //    isKnownToAnyModel() guard: only truly unregistered vars leak through
        //    here — a var that belongs to model A (registered via its forms) must
        //    NOT be injected into a model B that has no registered vars.
        if (knownVars == null || knownVars.isEmpty()) {
            for (Map.Entry<String, Double> entry : PENDING_ROAMING.entrySet()) {
                String key = entry.getKey();
                if (isRoamingExplicit(modelId, key) && !isKnownToAnyModel(key)) {
                    result.put(key, entry.getValue());
                }
            }
        }

        return result;
    }

    /** 判断变量名是否已注册到任何模型（MODEL_ROAMING_VARS）。 */
    private static boolean isKnownToAnyModel(String varName) {
        for (java.util.Set<String> vars : MODEL_ROAMING_VARS.values()) {
            if (vars.contains(varName)) {
                return true;
            }
        }
        return false;
    }

    /** Clears the per-model roaming variable tracking (called during cache reset). */
    public static void clearModelRoamingVars() {
        MODEL_ROAMING_VARS.clear();
        MODEL_ROAMING_DEFAULTS.clear();
        EXPLICIT_ROAMING_BY_MODEL.clear();
    }

    private OpenYsmPlayerControllerRuntime() {}

    public static PlayState tryApply(AnimationEvent<CustomPlayerEntity> event) {
        if (event == null || event.getController() == null || event.getAnimatable() == null) {
            return null;
        }
        CustomPlayerEntity animatable = event.getAnimatable();
        EntityPlayer player = animatable.getPlayer();
        ResourceLocation animationId = animatable.getAnimation();
        ControllerSet set = OpenYsmAnimationControllerRegistry.get(animationId);
        if (set == null) {
            return null;
        }

        String geckoControllerName = event.getController().getName();
        for (ControllerMatch match : resolveControllers(set, geckoControllerName)) {
            PlayState result = tryApplyController(event, player, animationId, geckoControllerName, match);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * Checks if the given GeckoLib controller name would match any OpenYSM controller
     * in the model's controller set. Returns true only if a match exists, meaning
     * the model has a dedicated controller for this slot (even if its state machine
     * hasn't produced an animation yet).
     */
    static void clear() {
        STATES.clear();
    }

    /**
     * 清零预览（player==null）上下文中指定模型的条件动画变量（swing/hold 类），
     * 使条件驱动的动画立即停止。只影响 GUI 预览状态，不影响实际玩家模型。
     * 由预览页面的 Stop 按钮调用。
     */
    public static void resetPreviewConditionalVariables(ResourceLocation modelId) {
        if (modelId == null) return;
        for (Map.Entry<StateKey, RuntimeState> e : STATES.entrySet()) {
            StateKey key = e.getKey();
            if (key.playerId != null || !modelId.equals(key.animationId)) {
                continue;
            }
            RuntimeState rs = e.getValue();
            rs.variables.remove("swing_sword");
            rs.variables.remove("swing");
            rs.variables.remove("swing_end");
            rs.variables.remove("attack");
            rs.variables.remove("attacking");
            rs.variables.remove("hold_mainhand");
            rs.variables.remove("hold_offhand");
        }
        MolangPhysicsRuntime.clearPreviewVariables(modelId);
    }

    /**
     * 重置预览（player==null）上下文中指定模型的完整状态机与条件变量。
     * 关闭/打开预览页面时调用，防止 swing/hold 等条件动画在页面切换后残留
     * （如 v.swing_sword=1 导致 alt+Y 预览页里音效每帧重触发）。
     * 不会影响实际玩家模型的状态。
     */
    public static void resetPreviewState(ResourceLocation modelId) {
        if (modelId == null) return;
        java.util.Iterator<Map.Entry<StateKey, RuntimeState>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<StateKey, RuntimeState> e = it.next();
            StateKey key = e.getKey();
            if (key.playerId == null && modelId.equals(key.animationId)) {
                it.remove();
            }
        }
        MolangPhysicsRuntime.clearPreviewVariables(modelId);
    }

    /** 判断是否为挥动脉冲（v.swing_sword）的消费者控制器。
     *  只有这些控制器才能消费预览中的 swing 脉冲并随后清零。 */
    private static boolean isSwingPulseConsumer(String geckoControllerName, Controller controller) {
        if (controller != null && controller.name != null) {
            String name = controller.name;
            if ("swing".equals(name) || "player.swing".equals(name)
                || "post_swing".equals(name) || "player.post_swing".equals(name)
                || "pre_swing".equals(name) || "player.pre_swing".equals(name)) {
                return true;
            }
        }
        return geckoControllerName != null && geckoControllerName.contains("swing");
    }

    /**
     * Returns true if the model at the given animation ID has any OpenYSM controllers registered.
     */
    public static boolean hasAnyController(ResourceLocation animationId) {
        return animationId != null && OpenYsmAnimationControllerRegistry.get(animationId) != null;
    }

    /** Returns true if the parallel controller at the given index has transitioned away from its initial state. */
    public static boolean isParallelActive(ResourceLocation animationId, int parallelIndex) {
        if (animationId == null) return false;
        for (StateKey key : STATES.keySet()) {
            if (!animationId.equals(key.animationId)) continue;
            String name = key.openYsmControllerName;
            if ((name.equals("player.parallel_" + parallelIndex) || name.equals("parallel_" + parallelIndex))
                && key.geckoControllerName.equals("parallel_" + parallelIndex + "_controller")) {
                RuntimeState rs = STATES.get(key);
                if (rs != null && rs.hasLeftInitial) return true;
            }
        }
        return false;
    }

    private static PlayState tryApplyController(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        ResourceLocation animationId, String geckoControllerName, ControllerMatch match) {
        RuntimeState runtimeState = runtimeState(player, animationId, geckoControllerName, match.controller.name);
        // 跳过依赖未加载模组的并行控制器（如 player.parallel_3）。
        // 没有 TacZ 时并行控制器的 transition 条件永远无法满足，
        // 导致卡在 default 状态循环播放带有音效关键帧的动画。
        //
        // 主控制器（post_main/main/base/move）不跳过——它们的非模组
        // 相关状态（如 idle/walk/run/jump）可以正常运行，模组相关条件
        // 自然返回 false。
        if (!match.controller.modDependencies.isEmpty()
            && ModDependencyRegistry.hasUnmetDependencies(match.controller.modDependencies)) {
            // 仅跳过并行控制器，不跳过主身体控制器
            String ctrlName = geckoControllerName;
            boolean isParallel = ctrlName != null
                && (ctrlName.startsWith("parallel_") || ctrlName.startsWith("pre_parallel_"));
            if (isParallel) {
                runtimeState.currentState = "";
                runtimeState.lastSelectedAnimationState = "";
                runtimeState.lastSelectedAnimation = "";
                com.fox.ysmu.client.audio.YSMSoundManager.stopController(geckoControllerName);
                event.getController().currentAnimationBuilder = new AnimationBuilder();
                return null;
            }
        }
        // Inject roaming variables scoped to the current model only.
        // Using getRoamingVarsForModel() instead of directly iterating
        // PENDING_ROAMING prevents cross-model variable contamination.
        // Inject both original case and lowercase for compatibility.
        Map<String, Double> modelRoaming = getRoamingVarsForModel(animationId);
        if (!modelRoaming.isEmpty()) {
            for (Map.Entry<String, Double> entry : modelRoaming.entrySet()) {
                runtimeState.variables.put(entry.getKey(), entry.getValue());
                String lcKey = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!lcKey.equals(entry.getKey())) {
                    runtimeState.variables.put(lcKey, entry.getValue());
                }
                // Also inject with the "roaming." prefix stripped so that
                // controller conditions like "v.bq_eye<=0" can find the value
                // via localVariableValue("bq_eye"), which looks up the bare
                // name (without "roaming.") in runtimeState.variables.
                // Otherwise the condition always reads 0 (default) because
                // the value is stored as "roaming.bq_eye" instead of "bq_eye",
                // and the timeline instruction that computes v.bq_eye from
                // v.roaming.bq_eye runs AFTER the condition evaluation.
                if (entry.getKey().startsWith("roaming.")) {
                    String plainKey = entry.getKey().substring("roaming.".length());
                    runtimeState.variables.put(plainKey, entry.getValue());
                    String lcPlainKey = plainKey.toLowerCase(java.util.Locale.ROOT);
                    if (!lcPlainKey.equals(plainKey)) {
                        runtimeState.variables.put(lcPlainKey, entry.getValue());
                    }
                }
            }
        }
        // Debug: log roaming variables relevant to pants/coat switching
        Double dbgHa = runtimeState.variables.get("ha");
        Double dbgHb = runtimeState.variables.get("hb");
        Double dbgVal = runtimeState.variables.get("value_kuzi");
        if (Config.DEBUG_CONTROLLER && (dbgHa != null || dbgHb != null || dbgVal != null)) {
            com.fox.ysmu.ysmu.LOG.debug("[YSMU-CTRL] {} roaming: ha={} hb={} value_kuzi={} (all: {})",
                geckoControllerName, dbgHa, dbgHb, dbgVal, runtimeState.variables);
        }
        OpenYsmControllerExpressionEvaluator.Context context = new OpenYsmControllerExpressionEvaluator.Context(
            event, player, runtimeState);
        prepareFrameVariables(geckoControllerName, player, runtimeState, context);
        State state = ensureState(event, match.controller, runtimeState, context);
        if (state == null) {
            com.fox.ysmu.client.audio.YSMSoundManager.stopController(geckoControllerName);
            event.getController().currentAnimationBuilder = new AnimationBuilder();
            return null;
        }
        // Debug: log transition evaluation details for all controllers (rate-limited to 1s)
        if (Config.DEBUG_CONTROLLER && allowDebugLog("CTRL-EVAL-" + geckoControllerName)) {
            StringBuilder sb = new StringBuilder();
            sb.append("[YSMU-CTRL-EVAL] ").append(geckoControllerName)
              .append(" from='").append(state.name).append("'");
            if (state.transitions != null) {
                for (Transition t : state.transitions) {
                    State target = match.controller.states.get(t.targetState);
                    boolean condMet = target != null && OpenYsmControllerExpressionEvaluator.evaluateBoolean(t.condition, context);
                    String condStr = t.condition != null ? t.condition.substring(0, Math.min(t.condition.length(), 80)) : "null";
                    sb.append(" | ").append(t.targetState).append("=").append(condMet).append("[").append(condStr).append("]");
                }
            }
            // 附加实际输入/移动值：方便定位潜行状态机（乐魂 Sneak/Sneaking 依赖
            // ctrl.sneak/ctrl.sneaking + ysm.input_vertical）。
            if (player != null) {
                sb.append(" | input_vertical=").append(String.format(java.util.Locale.ROOT, "%.3f", player.moveForward))
                  .append(" ground_speed=").append(String.format(java.util.Locale.ROOT, "%.3f",
                      Math.sqrt(Math.pow(player.posX - player.prevPosX, 2) + Math.pow(player.posZ - player.prevPosZ, 2)) * 20.0d));
            }
            ysmu.LOG.info(sb.toString());
        }
        int preTransCount = 0;
        // Wiki 2.6.3 空状态连续跳转的循环检测：
        // 记录本次跳转链访问过的状态，若某状态想跳回已访问状态（A->B->A 循环），
        // 则停在构成循环前的状态，不再跳转回去。避免 Check<->Start_Sneak 等
        // 空状态之间的帧内振荡导致潜行动画永远无法播放。
        java.util.Set<String> visitedStates = new java.util.HashSet<>();
        visitedStates.add(state.name);
        for (int i = 0; i < 4; i++) {
            String prevStateName = state.name;
            State nextState = applyTransition(event, match.controller, state, runtimeState, context, visitedStates);
            if (nextState == state) {
                break;
            }
            visitedStates.add(nextState.name);
            // Log state transitions (rate-limited to 1s per controller)
            if (Config.DEBUG_CONTROLLER && allowDebugLog("CTRL-TRANS-" + geckoControllerName)) {
                ysmu.LOG.info("[YSMU-CTRL-TRANS] iter={}: {} -> {} [{}]",
                    i, prevStateName, nextState.name, geckoControllerName);
            }
            preTransCount++;
            state = nextState;
        }

        // If the current state has no animations, try to force transition to any
        // state that has them. Only transition when the condition is met so we
        // don't force the wrong state (e.g. play/holster based on weapon held).
        if (state.animations.isEmpty() && !match.controller.getStatesWithAnimations().isEmpty()) {
            State forcedTarget = null;
            for (Transition transition : state.transitions) {
                State target = match.controller.states.get(transition.targetState);
                if (target != null && !target.animations.isEmpty()
                    && OpenYsmControllerExpressionEvaluator.evaluateBoolean(transition.condition, context)) {
                    forcedTarget = target;
                    break;
                }
            }
            if (forcedTarget != null) {
                OpenYsmControllerExpressionEvaluator.executeStatements(state.onExit, context);
                runtimeState.currentState = forcedTarget.name;
                runtimeState.hasLeftInitial = true;
                runtimeState.enteredTick = event.getAnimationTick();
                runtimeState.lastSelectedAnimationState = "";
                runtimeState.lastSelectedAnimation = "";
                OpenYsmControllerExpressionEvaluator.executeStatements(forcedTarget.onEntry, context);
                playStateSounds(forcedTarget, event.getAnimatable().getPlayer());
                state = forcedTarget;
            }
        }

        // GUI 预览（player==null）上下文：模拟游戏内 v.swing_sword 的"一次性脉冲"。
        // 游戏内该变量仅在挥动首帧为 1（prepareFrameVariables 随后清 0）；
        // 预览中 swing:sword 的 timeline 每循环都重新置 1，若不消费后立即清零，
        // post_swing 状态机会每帧在 attack1→2→3→1 间跳转，每次 setAnimation
        // 都清空已执行关键帧并让动画回到 tick 0，使 tick 0.0 的三叉戟音效关键帧
        // 每帧重触发（对应日志里 onSoundKeyframe 刷屏）。消费完本次脉冲后清零，
        // 等效于游戏内的一次挥动：攻击动画正常播放、音效每次挥动只触发一次。
        //
        // 注意：必须只在"挥动脉冲的消费者"（swing 类控制器）里清零，不能在所有
        // 预览控制器里清。控制器按注册顺序处理（LinkedHashMap），player.post_main
        // 先于 player.post_swing 执行；若 post_main 提前清掉共享 scope 里的
        // v.swing_sword，后处理的 post_swing 就永远看不到脉冲，挥剑动画完全不播。
        if (player == null && isSwingPulseConsumer(geckoControllerName, match.controller)) {
            runtimeState.variables.remove("swing_sword");
            runtimeState.variables.remove("swing");
            runtimeState.variables.remove("swing_end");
            MolangPhysicsRuntime.clearVariable("v.swing_sword");
            MolangPhysicsRuntime.clearVariable("v.swing");
            MolangPhysicsRuntime.clearVariable("v.swing_end");
        }

        // Collect all active animations from the state. In OpenYSM, a state's
        // "animations" list plays all entries simultaneously; selectAnimation
        // only returns the first match for historical code that expects one.
        List<String> activeAnimations = collectActiveAnimations(state, animationId, context);
        if (activeAnimations.isEmpty()) {
            // 限流到每秒一次：无 TacZ 时 post_main/post_swing 等主控制器
            // 卡在空状态（空闲/default）会每帧走到这里，DEBUG 下刷屏淹没
            // 其他调试日志。
            if (Config.DEBUG_CONTROLLER && allowDebugLog("NO-ACTIVE-ANIM-" + geckoControllerName)) {
                ysmu.LOG.info("[YSMU-CTRL] {}: no active animations, state='{}'",
                    geckoControllerName, runtimeState.currentState);
            }
            // Clear animation tracking so the next frame's transition to a valid
            // state (e.g. 起跳/潜行) will force setAnimation instead of skipping
            // via sameAnim with a stale animation name.
            runtimeState.lastSelectedAnimationState = "";
            runtimeState.lastSelectedAnimation = "";
            // Do NOT reset currentState to initial state. The controller stays in
            // its current state (e.g. 空闲) so that transitions are re-evaluated
            // next frame. Without this, the controller resets to 入场动画 and
            // q.all_animations_finished may return false (stale animation from a
            // previous state still stuck in getCurrentAnimation()), permanently
            // preventing the transition loop from ever evaluating 空闲's transitions.
            if ("ysm-builtin".equals(runtimeState.currentState)) {
            } else {
                com.fox.ysmu.client.audio.YSMSoundManager.stopController(geckoControllerName);
                event.getController().currentAnimationBuilder = new AnimationBuilder();
            }
            return null;
        }
        // Filter to animations that actually exist
        List<String> existing = new ArrayList<>();
        for (String name : activeAnimations) {
            if (animationExists(animationId, name)) {
                existing.add(name);
            }
        }
        if (existing.isEmpty()) {
            if (Config.DEBUG_CONTROLLER) {
                ysmu.LOG.info("[YSMU-CTRL] {}: animations exist but none found in file, state='{}'",
                    geckoControllerName, runtimeState.currentState);
            }
            com.fox.ysmu.client.audio.YSMSoundManager.stopController(geckoControllerName);
            event.getController().currentAnimationBuilder = new AnimationBuilder();
            return null;
        }
        if (SWING_CONTROLLER.equals(geckoControllerName) && existing.contains("attack_empty") && existing.size() == 1) {
            com.fox.ysmu.client.audio.YSMSoundManager.stopController(geckoControllerName);
            event.getController().currentAnimationBuilder = new AnimationBuilder();
            return null;
        }
        if (state.blendTransitionTicks >= 0f) {
            AnimationController<?> ctrl = event.getController();
            ctrl.transitionLengthTicks = state.blendTransitionTicks;
        }
        // If the animation changed (different from last selected), stop this
        // controller's sound so it doesn't linger from the previous animation.
        String prevAnim = StringUtils.isBlank(runtimeState.lastSelectedAnimation)
            ? null : runtimeState.lastSelectedAnimation;
        // 防滑步（stride matching）：按真实水平速度缩放移动类动画的播放倍速。
        // 只作用于主身体控制器（main_controller / player.pre_main），
        // player==null（GUI 预览实体）时跳过，避免覆盖预览页的暂停/冻结倍速。
        applyPlaybackSpeed(event, player, geckoControllerName, existing);
        applyAnimations(event, runtimeState, state, existing, animationId);
        if (prevAnim == null || !prevAnim.equals(existing.get(0))) {
            com.fox.ysmu.client.audio.YSMSoundManager.stopController(geckoControllerName);
        }
        return PlayState.CONTINUE;
    }

    /** 播放倍速（stride × anim_speed）：OpenYSM 控制器路径。
     *  防滑步仅对主身体控制器（main_controller / player.pre_main）生效，
     *  anim_speed 对所有控制器生效。player==null（GUI 预览实体）时跳过，
     *  避免覆盖预览页的暂停/冻结倍速。计算逻辑见 MovementSpeedMatcher.applyPlaybackSpeed。 */
    private static void applyPlaybackSpeed(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        String geckoControllerName, List<String> existing) {
        // GUI 预览（player==null）：不干预 animationSpeed（由预览页控制暂停/冻结）
        if (player == null || existing == null || existing.isEmpty()) {
            return;
        }
        ResourceLocation animId = event.getAnimatable().getAnimation();
        AnimationFile file = animId != null ? GeckoLibCache.getInstance().getAnimations().get(animId) : null;
        boolean isBody = MAIN_CONTROLLER.equals(geckoControllerName) || OPENYSM_PRE_MAIN_CONTROLLER.equals(geckoControllerName);
        MovementSpeedMatcher.applyPlaybackSpeed(event.getController(), player, existing.get(0), isBody, file);
    }

    private static RuntimeState runtimeState(EntityPlayer player, ResourceLocation animationId,
        String geckoControllerName, String openYsmControllerName) {
        // 预览实体（player==null）用 null 作为键，与 MolangPhysicsRuntime 的
        // ScopeKey 保持一致。不要用"全 0 UUID"哨兵：若某离线账号恰好使用
        // 00000000-0000-0000-0000-000000000000，会把真实玩家状态与预览状态混在一起。
        UUID playerId = player != null ? player.getUniqueID() : null;
        StateKey key = new StateKey(playerId, animationId, geckoControllerName, openYsmControllerName);
        RuntimeState state = STATES.get(key);
        if (state == null) {
            state = new RuntimeState();
            STATES.put(key, state);
        }
        return state;
    }

    private static State ensureState(AnimationEvent<CustomPlayerEntity> event, Controller controller,
        RuntimeState runtimeState, OpenYsmControllerExpressionEvaluator.Context context) {
        State state = controller.states.get(runtimeState.currentState);
        if (state != null) {
            return state;
        }
        State initial = controller.getInitialState();
        if (initial == null) {
            return null;
        }
        runtimeState.currentState = initial.name;
        runtimeState.enteredTick = event.getAnimationTick();
        runtimeState.lastSelectedAnimationState = "";
        runtimeState.lastSelectedAnimation = "";
        OpenYsmControllerExpressionEvaluator.executeStatements(initial.onEntry, context);
        playStateSounds(initial, event.getAnimatable().getPlayer());
        return initial;
    }

    private static State applyTransition(AnimationEvent<CustomPlayerEntity> event, Controller controller, State state,
        RuntimeState runtimeState, OpenYsmControllerExpressionEvaluator.Context context,
        java.util.Set<String> visitedStates) {
        for (Transition transition : state.transitions) {
            State target = controller.states.get(transition.targetState);
            if (target == null) {
                continue;
            }
            boolean conditionMet = OpenYsmControllerExpressionEvaluator.evaluateBoolean(transition.condition, context);
            if (Config.DEBUG_CONTROLLER) {
                com.fox.ysmu.ysmu.LOG.debug("[YSMU-CTRL]   trans: {} --[{}]--> {} = {}",
                    state.name,
                    transition.condition != null ? transition.condition.substring(0, Math.min(transition.condition.length(), 120)) : "null",
                    target.name, conditionMet);
            }
            if (!conditionMet) {
                continue;
            }
            // Wiki 2.6.3 空状态连续跳转的循环检测：
            // 若第一个满足条件的跳转目标已在本次跳转链中访问过（构成循环），
            // 则停止在当前状态，不执行该跳转（也不执行 onExit/onEntry）。
            if (visitedStates != null && visitedStates.contains(target.name)) {
                return state;
            }
            // Delay start→sky so sneaking_start is visible for at least 5
            // ticks before transitioning to the stationary crouch pose.
            // TODO: 2025-06: 暂时注释掉硬编码的 sky/start 延迟，测试 Molang 表达式是否已能正确处理
            //if ("sky".equals(target.name) && "start".equals(state.name)
            //    && event.getAnimationTick() - runtimeState.enteredTick < 5.0) {
            //    continue;
            //}
            OpenYsmControllerExpressionEvaluator.executeStatements(state.onExit, context);
            runtimeState.currentState = target.name;
            runtimeState.hasLeftInitial = true;
            runtimeState.enteredTick = event.getAnimationTick();
            runtimeState.lastSelectedAnimationState = "";
            runtimeState.lastSelectedAnimation = "";
            OpenYsmControllerExpressionEvaluator.executeStatements(target.onEntry, context);
            playStateSounds(target, event.getAnimatable().getPlayer());
            return target;
        }
        return state;
    }

    /** Plays sound effects defined on an OpenYSM controller state. */
    private static void playStateSounds(State state, EntityPlayer player) {
        if (state.soundEffects == null || state.soundEffects.isEmpty()) return;
        if (player == null) return;
        for (String soundName : state.soundEffects) {
            if (soundName != null && !soundName.isEmpty()) {
                com.fox.ysmu.client.audio.YSMSoundManager.playSound(player, soundName, 1.0f, 1.0f);
            }
        }
    }

    private static boolean animationEntryActive(AnimationEntry entry,
        OpenYsmControllerExpressionEvaluator.Context context) {
        return StringUtils.isBlank(entry.condition)
            || OpenYsmControllerExpressionEvaluator.evaluateBoolean(entry.condition, context);
    }

    private static boolean animationExists(ResourceLocation animationId, String animationName) {
        AnimationFile file = GeckoLibCache.getInstance().getAnimations().get(animationId);
        // 用 getAnimation() 判断存在性（而非直接查 map），使内置 "empty" 兜底也能命中：
        // 否则 空闲 等引用 "empty" 的状态会被过滤为无动画，回到 all_animations_finished
        // 误判的旧 bug 路径。
        if (file == null || file.getAnimation(animationName) == null) {
            OpenYsmAnimationControllerRegistry.warnOnce(
                "missing-animation:" + animationId + ":" + animationName,
                "OpenYSM controller selected missing animation " + animationName + " for " + animationId);
            return false;
        }
        return true;
    }

    /**
     * Collects all animation entries from the state that should be active
     * (no condition or condition is met). In OpenYSM, a state's animation
     * list plays all matching entries simultaneously.
     */
    private static List<String> collectActiveAnimations(State state, ResourceLocation animationId,
        OpenYsmControllerExpressionEvaluator.Context context) {
        List<String> result = new ArrayList<>();
        for (AnimationEntry entry : state.animations) {
            if (animationEntryActive(entry, context) && animationExists(animationId, entry.animationName)) {
                result.add(entry.animationName);
            }
        }
        return result;
    }

    /**
     * Applies multiple animations simultaneously by merging bone animations
     * from all parallel animations into the primary animation.
     */
    private static void applyAnimations(AnimationEvent<CustomPlayerEntity> event, RuntimeState runtimeState,
        State state, List<String> animationNames, ResourceLocation animationId) {
        String primaryName = animationNames.get(0);
        // Determine whether this controller should animate the Root bone.
        // Post-* overlay controllers (post_hold, post_swing, etc.) should NOT
        // override Root, which controls full-body position/rotation and should
        // only come from the main controller's animation.
        // Parallel controllers ARE allowed to animate Root since they are
        // designed for blended parallel animation (e.g. attack combos that
        // need full-body movement).
        // player.pre_main is the PRIMARY body controller in modern YSM models
        // (smx/乐魂 have no player.main) — it plays idle/walk/run/sneak and must
        // keep Root so sneaking_Control's crouch lowering [0,-7.625,0] and the
        // walk body bob are not silently stripped.
        String ctrlName = event.getController().getName();
        boolean excludeRoot = ctrlName != null
            && !MAIN_CONTROLLER.equals(ctrlName)
            && !OPENYSM_PRE_MAIN_CONTROLLER.equals(ctrlName)
            && !ctrlName.startsWith("parallel_")
            && !ctrlName.startsWith("pre_parallel_");
        // Build merged bone animations. For single-animation states or when
        // Root filtering is needed, we create a merged copy stored in the
        // GeckoLib cache so the controller loads our modified version.
        List<software.bernie.geckolib3.core.keyframe.BoneAnimation> mergedBones = null;
        software.bernie.geckolib3.file.AnimationFile animFile =
            software.bernie.geckolib3.resource.GeckoLibCache.getInstance().getAnimations().get(animationId);
        software.bernie.geckolib3.core.builder.Animation primaryAnim = null;
        if (animFile != null) {
            primaryAnim = animFile.getAnimation(primaryName);
        }
        if (primaryAnim == null) {
            primaryAnim = lookupAnimation(primaryName);
        }
        if (primaryAnim != null && primaryAnim.boneAnimations != null) {
            mergedBones = new ArrayList<>(primaryAnim.boneAnimations);
        }
        // Merge additional animations' bones on top (for multi-animation states)
        for (int i = 1; i < animationNames.size(); i++) {
            software.bernie.geckolib3.core.builder.Animation a = null;
            if (animFile != null) {
                a = animFile.getAnimation(animationNames.get(i));
            }
            if (a == null) {
                a = lookupAnimation(animationNames.get(i));
            }
            if (a != null && a.boneAnimations != null) {
                if (mergedBones == null) {
                    mergedBones = new ArrayList<>(a.boneAnimations);
                } else {
                    mergeBones(mergedBones, a.boneAnimations);
                }
            }
        }
        // Collect all custom instruction keyframes (timeline) from all animations.
        // The primary animation's timeline is included; additional animations'
        // timelines are merged so that Molang variable assignments in their
        // timelines (e.g. v.bq_eye = v.roaming.bq_eye) are not lost.
        java.util.List<software.bernie.geckolib3.core.keyframe.EventKeyFrame<String>> mergedTimeline = new java.util.ArrayList<>();
        if (primaryAnim != null && primaryAnim.customInstructionKeyframes != null) {
            mergedTimeline.addAll(primaryAnim.customInstructionKeyframes);
        }
        for (int i = 1; i < animationNames.size(); i++) {
            software.bernie.geckolib3.core.builder.Animation a = null;
            if (animFile != null) {
                a = animFile.getAnimation(animationNames.get(i));
            }
            if (a == null) {
                a = lookupAnimation(animationNames.get(i));
            }
            if (a != null && a.customInstructionKeyframes != null) {
                // Merge non-duplicate keyframes (by trigger time) from each source.
                // When two keyframes share the same tick, CONCATENATE their instruction
                // strings instead of dropping the second one — otherwise important
                // timeline instructions (e.g. v.bq_eye in pre_parallel7) can be lost
                // when another animation (e.g. pre_parallel3) already registered a
                // keyframe at the same tick.
                for (software.bernie.geckolib3.core.keyframe.EventKeyFrame<String> kf : a.customInstructionKeyframes) {
                    boolean dup = false;
                    for (int j = 0; j < mergedTimeline.size(); j++) {
                        software.bernie.geckolib3.core.keyframe.EventKeyFrame<String> existing = mergedTimeline.get(j);
                        if (Math.abs(existing.getStartTick() - kf.getStartTick()) < 0.001d) {
                            // Merge: concatenate existing and new instructions with ";;"
                            String merged = existing.getEventData() + ";;" + kf.getEventData();
                            mergedTimeline.set(j, new software.bernie.geckolib3.core.keyframe.EventKeyFrame<>(
                                existing.getStartTick(), merged));
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        mergedTimeline.add(kf);
                    }
                }
            }
        }
        // Determine the final animation name: if we have merged bones and either
        // need Root filtering or have multiple animations, use a cached merged copy.
        boolean needsMergedCopy = mergedBones != null
            && (excludeRoot || animationNames.size() > 1);
        String finalName;
        ILoopType finalLoop;
        if (Config.DEBUG_CONTROLLER && ctrlName != null
            && (ctrlName.startsWith("pre_parallel_") || ctrlName.startsWith("parallel_"))
            && allowDebugLog("CTRL-ANIM-" + ctrlName)) {
            ysmu.LOG.info("[YSMU-CTRL-ANIM] {} state='{}' animations={} mergedBones={}",
                ctrlName, state.name, animationNames,
                mergedBones == null ? -1 : mergedBones.size());
        }
        if (needsMergedCopy) {
            // Remove Root bone for overlay controllers
            if (excludeRoot) {
                mergedBones.removeIf(ba -> "Root".equals(ba.boneName));
            }
            String mergedName = "__ysm_merged__" + primaryName;
            software.bernie.geckolib3.core.builder.Animation mergedAnim = null;
            software.bernie.geckolib3.file.AnimationFile cachedFile =
                software.bernie.geckolib3.resource.GeckoLibCache.getInstance().getAnimations().get(animationId);
            if (cachedFile != null) {
                mergedAnim = cachedFile.getAnimation(mergedName);
            }
            if (mergedAnim == null) {
                mergedAnim = new software.bernie.geckolib3.core.builder.Animation();
                mergedAnim.animationName = mergedName;
                if (cachedFile != null) {
                    cachedFile.putAnimation(mergedName, mergedAnim);
                }
            }
            mergedAnim.boneAnimations = mergedBones;
            mergedAnim.customInstructionKeyframes = mergedTimeline;
            // Preserve sound keyframes from the primary animation
            if (primaryAnim != null && primaryAnim.soundKeyFrames != null
                && !primaryAnim.soundKeyFrames.isEmpty()) {
                mergedAnim.soundKeyFrames = new java.util.ArrayList<>(primaryAnim.soundKeyFrames);
            } else {
                mergedAnim.soundKeyFrames = new java.util.ArrayList<>();
            }
            if (primaryAnim != null) {
                mergedAnim.animationLength = primaryAnim.animationLength;
                mergedAnim.animTimeUpdate = primaryAnim.animTimeUpdate;
                mergedAnim.animSpeed = primaryAnim.animSpeed;
                // When the animation file has no explicit loop field (null),
                // default to HOLD_ON_LAST_FRAME so the last frame is held
                // instead of snapping bones back to bind pose.
                ILoopType loop = primaryAnim.loop != null
                    ? primaryAnim.loop
                    : ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME;
                mergedAnim.loop = loop;
                finalLoop = loop;
            } else {
                mergedAnim.animationLength = null;
                mergedAnim.loop = ILoopType.EDefaultLoopTypes.LOOP;
                finalLoop = ILoopType.EDefaultLoopTypes.LOOP;
            }
            finalName = mergedName;
        } else {
            finalName = primaryName;
            // When the animation file has no explicit loop field (null),
            // default to HOLD_ON_LAST_FRAME so the last frame is held
            // instead of snapping bones back to bind pose.
            finalLoop = primaryAnim != null
                ? (primaryAnim.loop != null ? primaryAnim.loop : ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME)
                : ILoopType.EDefaultLoopTypes.LOOP;
        }
        // Only call setAnimation ONCE with the final name, so GeckoLib does NOT
        // reset shouldResetTick every frame (which would freeze the animation at tick 0).
        finalName = finalName != null ? finalName : primaryName;
        // Same-animation detection: skip setAnimation when the same state and
        // animation are already playing.  BUT if the model changed (animationId
        // differs), force setAnimation because RuntimeState persists across
        // model switches and would incorrectly match the old animation name.
        boolean sameModel = animationId.equals(runtimeState.lastAnimationId);
        boolean sameState = sameModel && state.name.equals(runtimeState.lastSelectedAnimationState);
        boolean sameAnim = sameState && StringUtils.isNotBlank(runtimeState.lastSelectedAnimation)
            && runtimeState.lastSelectedAnimation.equals(primaryName);
        runtimeState.lastAnimationId = animationId;
        runtimeState.lastAnimation = primaryName;
        runtimeState.lastSelectedAnimationState = state.name;
        runtimeState.lastSelectedAnimation = primaryName;
        // Detect model re-entry: if this RuntimeState was parked for more
        // than a few frames (model switched away and back), treat sameAnim
        // as false so setAnimation reloads the merged bone keyframes.
        boolean isReEntry = runtimeState.lastActiveFrame > 0
            && FRAME_COUNTER - runtimeState.lastActiveFrame > 10;
        runtimeState.lastActiveFrame = FRAME_COUNTER;
        if (sameAnim && isReEntry) {
            // Force setAnimation on re-entry: clear stale tracking so
            // sameAnim falls through to the setAnimation path below.
            runtimeState.lastAnimationId = null;
            runtimeState.lastAnimation = "";
            runtimeState.lastSelectedAnimationState = "";
            runtimeState.lastSelectedAnimation = "";
            runtimeState.lastActiveAnimations.clear();
            runtimeState.enteredTick = event.getAnimationTick();
        }
        if (sameAnim) {
            // Same state + same animation → skip setAnimation to preserve
            // keyframe tracking (sound/particle keyframes already executed
            // won't re-fire).  However, timeline custom instructions must
            // still re-execute every frame for pre_parallel/parallel controllers
            // so that roaming variable changes from the expression wheel take
            // effect immediately.  GeckoLib's native keyframe event tracking
            // only fires each instruction once.
            // We ONLY re-execute for pre_parallel/parallel controllers because
            // other controllers' timeline instructions set swing-related
            // variables (v.qh, v.random, etc.) that must NOT be re-triggered
            // every frame.
            // Additionally, check if conditional animation entries have changed
            // since last frame. These depend on roaming variable values evaluated
            // in collectActiveAnimations(),
            // and when they change, setAnimation must run to apply the new
            // merged bone keyframes even though the primary animation name
            // (e.g. pre_parallel0) hasn't changed.
            boolean animsChanged = !animationNames.equals(runtimeState.lastActiveAnimations);
            runtimeState.lastActiveAnimations = new java.util.ArrayList<>(animationNames);
            if (animsChanged) {
                // Conditional animation entries changed → must call
                // setAnimation to apply new merged bone keyframes.
                // Fall through to the setAnimation logic below.
            } else if (ctrlName != null
                && (ctrlName.startsWith("pre_parallel_") || ctrlName.startsWith("parallel_"))) {
                if (!mergedTimeline.isEmpty()) {
                    // First pass: check if any instruction references roaming
                    // variables.  If none do, we can skip the per-frame
                    // execute() entirely — GeckoLib's processKeyFrameEvents
                    // already fires each instruction once per animation loop,
                    // which is sufficient for non-roaming variables.
                    boolean hasRoamingRef = false;
                    for (software.bernie.geckolib3.core.keyframe.EventKeyFrame<String> kf : mergedTimeline) {
                        String data = kf.getEventData();
                        if (data != null && data.contains("roaming.")) {
                            hasRoamingRef = true;
                            break;
                        }
                    }
                    if (hasRoamingRef) {
                        for (software.bernie.geckolib3.core.keyframe.EventKeyFrame<String> kf : mergedTimeline) {
                            String data = kf.getEventData();
                            if (data != null) {
                                MolangInstructionExecutor.execute(data);
                            }
                        }
                    }
                }
                return;
            } else {
                return;
            }
        }
        // When the state hasn't changed but only the animation variant changed
        // (e.g. attack1's animation switches from sword_attack_01 to
        // sword_attack_run1 because the player started running), preserve the
        // current tick position so the animation doesn't restart from tick 0.
        // Full restarts (state transitions, e.g. default→attack1) use
        // setAnimation to reset tick to 0 as expected.
        AnimationBuilder builder = new AnimationBuilder().addAnimation(finalName, finalLoop);
        if (Config.DEBUG_CONTROLLER && allowDebugLog("CTRL-PLAY-" + ctrlName)) {
            ysmu.LOG.info("[YSMU-CTRL-PLAY] {} state='{}' playing='{}' animations={} sameState={}",
                ctrlName, state.name, finalName, animationNames, sameState);
        }
        if (sameState) {
            // Preserve playback position: the animation continues from where it
            // left off, just with updated bone keyframes for the new variant.
            event.getController().setAnimationPreservingTick(builder,
                event.getAnimationTick(),
                Math.max(0.0d, event.getAnimationTick() - runtimeState.enteredTick));
        } else {
            // State transition: restart animation from tick 0.
            event.getController().markNeedsReload();
            event.getController().setAnimation(builder);
        }
    }

    private static void mergeBones(List<software.bernie.geckolib3.core.keyframe.BoneAnimation> target,
        List<software.bernie.geckolib3.core.keyframe.BoneAnimation> source) {
        for (software.bernie.geckolib3.core.keyframe.BoneAnimation ba : source) {
            boolean exists = false;
            for (software.bernie.geckolib3.core.keyframe.BoneAnimation existing : target) {
                if (existing.boneName.equals(ba.boneName)) {
                    existing.rotationKeyFrames = ba.rotationKeyFrames;
                    existing.positionKeyFrames = ba.positionKeyFrames;
                    existing.scaleKeyFrames = ba.scaleKeyFrames;
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                target.add(ba);
            }
        }
    }

    private static software.bernie.geckolib3.core.builder.Animation lookupAnimation(String name) {
        for (software.bernie.geckolib3.file.AnimationFile file :
            software.bernie.geckolib3.resource.GeckoLibCache.getInstance().getAnimations().values()) {
            software.bernie.geckolib3.core.builder.Animation anim = file.getAnimation(name);
            if (anim != null) return anim;
        }
        return null;
    }

    private static void prepareFrameVariables(String geckoControllerName, EntityPlayer player, RuntimeState state,
        OpenYsmControllerExpressionEvaluator.Context context) {
        // Run on EVERY controller so that parallel_2 (and others without a
        // post_swing OpenYSM controller) can detect swings independently.
        // Each controller uses its OWN lastSwingActive so that
        // on_exit variable clearance is never overwritten.
        // In 1.7.10 swingProgressInt DECREASES (max→0), unlike Bedrock where it
        // increases.  swingReset comparing progress values would fire every frame
        // during a swing, causing v.swing=1 to be set repeatedly and the controller
        // state machine to bounce between sub-states.  Use a simple boolean flag
        // to detect the first frame of each new swing instead.
        //
        // syncToRuntimeState copies v.* variables from the per-(player, model)
        // ScopeState into the controller's RuntimeState.  This is the authoritative
        // source: values set by timeline custom instructions reach ScopeState via
        // ScopedMolangVariable.set() (which succeeds because tickAnimation() runs
        // WITHIN the MolangPhysicsRuntime.begin()/end() frame).
        //
        // We deliberately do NOT sync from the global MolangParser.VARIABLES map
        // here, because it accumulates stale v.* values from ALL models — reading
        // from it would cause cross-model variable contamination (e.g. model A's
        // timeline sets v.jump=1, leaking into model B's controller state).
        //
        // IMPORTANT: syncToRuntimeState MUST run BEFORE the swing detection below.
        // The on_entry statements of swing states (e.g. v.swing=0, v.swing_end=1)
        // write into MolangPhysicsRuntime via setVariable(). These values PERSIST
        // in ScopeState across frames. If syncToRuntimeState ran AFTER the swing
        // detection, it would overwrite the freshly-set v.swing=1 / v.swing_end=0
        // with the stale values (v.swing=0, v.swing_end=1) from the previous
        // swing's on_entry, permanently trapping the controller in the default
        // state.
        com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime.syncToRuntimeState(state.variables);
        // Log values from syncToRuntimeState for post_swing (rate-limited to 1s)
        if (Config.DEBUG_CONTROLLER && geckoControllerName.contains("post_swing")) {
            double postSyncAttack = state.variables.getOrDefault("attack", -999.0);
            double postSyncSwingEnd = state.variables.getOrDefault("swing_end", -999.0);
            if ((postSyncAttack != -999.0 || postSyncSwingEnd != -999.0) && allowDebugLog("PS-SYNC")) {
                ysmu.LOG.info("[YSMU-PS-SYNC] after syncToRuntimeState: attack={} swing_end={}",
                    postSyncAttack, postSyncSwingEnd);
            }
            // Log v.qh variables that drive the 默认挥剑 animation combo (rate-limited to 1s)
            if (allowDebugLog("PS-QH")) {
                double qh = state.variables.getOrDefault("qh", Double.NaN);
                double qh2 = state.variables.getOrDefault("qh2", Double.NaN);
                double jump = state.variables.getOrDefault("jump", Double.NaN);
                double vrandom = state.variables.getOrDefault("random", Double.NaN);
                ysmu.LOG.info("[YSMU-PS-QH] qh={} qh2={} jump={} random={}",
                    qh, qh2, jump, vrandom);
            }
        }

        if (player != null) {
            // 1.7.10: 剑/盾右键格挡时抑制 swing 变量（不让 OpenYSM 控制器播放挥动动画）
            boolean isBlocking = com.fox.ysmu.compat.BlockingCompat.isBlocking(player);

            if (isBlocking) {
                state.lastSwingActive = player.isSwingInProgress;
                state.variables.put("swing", 0.0d);
                state.variables.put("swing_sword", 0.0d);
            } else {
                boolean swingJustStarted = player.isSwingInProgress && !state.lastSwingActive;
                boolean newSwing = swingJustStarted;
                if (Config.DEBUG_CONTROLLER && newSwing) {
                    ysmu.LOG.info("[YSMU-CTRL] {}: newSwing detected, swing={} lastSwingActive={}",
                        geckoControllerName, player.isSwingInProgress, state.lastSwingActive);
                }
                if (newSwing) {
                    state.variables.put("swing", 1.0d);
                    state.variables.put("swing_end", 0.0d);
                    boolean swordSwing = OpenYsmControllerExpressionEvaluator.evaluateBoolean(
                        "ctrl.swing('mainhand', ':sword')||ctrl.swing('offhand', ':sword')",
                        context);
                    if (swordSwing) {
                        state.variables.put("swing_sword", 1.0d);
                        if (Config.DEBUG_CONTROLLER) {
                            ysmu.LOG.info("[YSMU-CTRL] {}: sword swing detected, set swing_sword=1", geckoControllerName);
                        }
                    }
                    state.variables.put(
                        "jump",
                        OpenYsmControllerExpressionEvaluator.evaluateBoolean(
                            "q.is_jumping&&(q.vertical_speed<0)",
                            context) ? 1.0d : 0.0d);
                } else {
                    state.variables.put("swing_sword", 0.0d);
                    state.variables.put("swing", 0.0d);
                }
                state.lastSwingActive = player.isSwingInProgress;
            }
        }
    }

    private static List<ControllerMatch> resolveControllers(ControllerSet set, String geckoControllerName) {
        List<ControllerMatch> matches = new ArrayList<>();
        int preferredIndex = getParallelIndex(geckoControllerName);
        if (preferredIndex >= 0) {
            if (geckoControllerName.startsWith("pre_parallel_")) {
                addMatch(matches, set, "player.pre_parallel_" + preferredIndex, preferredIndex);
                addMatch(matches, set, "pre_parallel_" + preferredIndex, preferredIndex);
            } else {
                addMatch(matches, set, "player.parallel_" + preferredIndex, preferredIndex);
                addMatch(matches, set, "parallel_" + preferredIndex, preferredIndex);
            }
        } else if (MAIN_CONTROLLER.equals(geckoControllerName)) {
            addMatch(matches, set, "player.main", -1);
            addMatch(matches, set, "player.base", -1);
            addMatch(matches, set, "player.move", -1);
            addMatch(matches, set, "main", -1);
        } else if (HOLD_MAINHAND_CONTROLLER.equals(geckoControllerName)) {
            addMatch(matches, set, "player.hold_mainhand", -1);
            addMatch(matches, set, "hold_mainhand", -1);
        } else if (HOLD_OFFHAND_CONTROLLER.equals(geckoControllerName)) {
            addMatch(matches, set, "player.hold_offhand", -1);
            addMatch(matches, set, "hold_offhand", -1);
        } else if (SWING_CONTROLLER.equals(geckoControllerName)) {
            addMatch(matches, set, "player.swing", -1);
            addMatch(matches, set, "swing", -1);
        } else if (USE_CONTROLLER.equals(geckoControllerName)) {
            addMatch(matches, set, "player.use", -1);
            addMatch(matches, set, "use", -1);
        } else if (CAP_CONTROLLER.equals(geckoControllerName)) {
            addMatch(matches, set, "player.cap", -1);
            addMatch(matches, set, "cap", -1);
        }
        addMatch(matches, set, geckoControllerName, preferredIndex);
        if (geckoControllerName.startsWith("player.")) {
            addMatch(matches, set, geckoControllerName.substring("player.".length()), preferredIndex);
        }
        if (geckoControllerName.endsWith("_controller")) {
            addMatch(matches, set, geckoControllerName.substring(0, geckoControllerName.length() - 11), preferredIndex);
        }
        // 模糊匹配：player.post_main → player.post_main_<anything>
        // 用于车辆动画等带后缀的槽位控制器
        if (geckoControllerName.endsWith("_main") || geckoControllerName.endsWith("_hold")
            || geckoControllerName.endsWith("_swing") || geckoControllerName.endsWith("_use")) {
            String prefix = geckoControllerName + "_";
            for (String key : set.controllers.keySet()) {
                if (key.startsWith(prefix)) {
                    addMatch(matches, set, key, preferredIndex);
                }
            }
        }
        return matches;
    }

    private static void addMatch(List<ControllerMatch> matches, ControllerSet set, String controllerName,
        int preferredAnimationIndex) {
        Controller controller = set.controllers.get(controllerName);
        if (controller == null) {
            return;
        }
        for (ControllerMatch match : matches) {
            if (match.controller == controller) {
                return;
            }
        }
        matches.add(new ControllerMatch(controller, preferredAnimationIndex));
    }

    private static int getParallelIndex(String geckoControllerName) {
        if (geckoControllerName == null || !geckoControllerName.endsWith("_controller")) {
            return -1;
        }
        String name = geckoControllerName.substring(0, geckoControllerName.length() - 11);
        String prefix = null;
        if (name.startsWith("pre_parallel_")) {
            prefix = "pre_parallel_";
        } else if (name.startsWith("parallel_")) {
            prefix = "parallel_";
        }
        if (prefix == null) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Monotonically increasing frame counter used to detect RuntimeState
     *  re-entry after a controller was inactive (e.g. model switched away
     *  and back).  Incremented at the start of each render frame in
     *  MolangPhysicsRuntime.begin(). */
    private static int FRAME_COUNTER = 0;

    /** Called by MolangPhysicsRuntime.begin() to advance the frame counter. */
    public static void advanceFrameCounter() {
        FRAME_COUNTER++;
    }

    static final class RuntimeState {
        String currentState = "";
        /** Whether the controller has ever transitioned away from its initial state. */
        boolean hasLeftInitial = false;
        String lastAnimation = "";
        String lastSelectedAnimationState = "";
        String lastSelectedAnimation = "";
        /** The animationId (model) that lastSelectedAnimation belongs to.  Used to
         * detect model switches where the same state+animation name would otherwise
         * match via sameAnim but the underlying GeckoLib controller is playing a
         * different model's animation (RuntimeState persists across model switches). */
        ResourceLocation lastAnimationId = null;
        /** Tracks the full list of animation names played in the last frame.
         *  Used by sameAnim detection to detect changes in conditional animation
         *  entries that depend on roaming variables.
         *  When this list changes, setAnimation must run to apply the new
         *  merged bone keyframes even though the primary animation name is the same. */
        java.util.List<String> lastActiveAnimations = new java.util.ArrayList<>();
        /** The frame counter value when this RuntimeState was last actively
         *  processing a CONTINUE predicate (i.e. tryApplyController reached
         *  the setAnimation decision).  Used to detect model-switch re-entry:
         *  if sameAnim is true but lastActiveFrame is far behind FRAME_COUNTER,
         *  the controller was parked and the player model has just been
         *  re-selected, so we must force setAnimation even though the
         *  animation name hasn't changed. */
        int lastActiveFrame = 0;
        double enteredTick;
        boolean lastSwingActive;
        /** Regular HashMap is safe: all RuntimeState access is on the client render thread. */
        final Map<String, Double> variables = new java.util.HashMap<>();
    }

    private static final class ControllerMatch {
        private final Controller controller;
        private final int preferredAnimationIndex;

        private ControllerMatch(Controller controller, int preferredAnimationIndex) {
            this.controller = controller;
            this.preferredAnimationIndex = preferredAnimationIndex;
        }
    }

    private static final class StateKey {
        private final UUID playerId;
        private final ResourceLocation animationId;
        private final String geckoControllerName;
        private final String openYsmControllerName;

        private StateKey(UUID playerId, ResourceLocation animationId, String geckoControllerName,
            String openYsmControllerName) {
            this.playerId = playerId;
            this.animationId = animationId;
            this.geckoControllerName = geckoControllerName;
            this.openYsmControllerName = openYsmControllerName;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof StateKey)) {
                return false;
            }
            StateKey other = (StateKey) obj;
            return (playerId == null ? other.playerId == null : playerId.equals(other.playerId))
                && animationId.equals(other.animationId)
                && geckoControllerName.equals(other.geckoControllerName)
                && openYsmControllerName.equals(other.openYsmControllerName);
        }

        @Override
        public int hashCode() {
            int result = playerId == null ? 0 : playerId.hashCode();
            result = 31 * result + animationId.hashCode();
            result = 31 * result + geckoControllerName.hashCode();
            result = 31 * result + openYsmControllerName.hashCode();
            return result;
        }
    }
}
