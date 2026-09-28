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
import java.util.regex.Matcher;

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

    /**
     * Global upper bound on timeline instructions dispatched per entity render
     * frame (reset in {@link #advanceFrameCounter()}). Prevents one entity with
     * pathological short-period timelines from starving every other entity; the
     * per-controller scheduler additionally caps its own per-advance dispatching.
     */
    private static final int MAX_TIMELINE_DISPATCHES_PER_FRAME = 8192;
    private static int timelineDispatchBudget = MAX_TIMELINE_DISPATCHES_PER_FRAME;

    /**
     * Upper bound on the number of source keyframes the per-frame roaming refresh
     * scans on one controller. The shared dispatch budget already bounds how many
     * instructions may <em>execute</em>; this additionally bounds the scan itself so
     * a model with an enormous instruction list cannot make the refresh loop over it
     * once per frame.
     */
    private static final int MAX_ROAMING_REFRESH_SCAN_PER_FRAME = 2048;

    /**
     * Temporary escape hatch: when true, restore the pre-scheduler behaviour of
     * replaying <em>every</em> roaming-referencing instruction each frame (including
     * random/increment/particle side effects). Default false: only provably
     * idempotent assignments refresh every frame (see
     * {@code MolangInstructionExecutor.isIdempotentRoamingAssignment}). Promote to a
     * config option only after real-wheel acceptance testing.
     */
    static boolean LEGACY_ROAMING_REPLAY = false;

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

    // Compatibility facade. State and lifecycle rules live together in RoamingVariables.
    public static final Map<String, Double> PENDING_ROAMING = RoamingVariables.PENDING_ROAMING;
    public static final java.util.Set<String> EXPLICIT_ROAMING = RoamingVariables.EXPLICIT_ROAMING;

    public static void markRoamingExplicit(ResourceLocation modelId, String name) {
        RoamingVariables.markRoamingExplicit(modelId, name);
    }

    public static void noteRoamingWrite(ResourceLocation modelId, String name, double value) {
        RoamingVariables.noteRoamingWrite(modelId, name, value);
    }

    public static boolean isRoamingNameForModel(ResourceLocation modelId, String name) {
        return RoamingVariables.isRoamingNameForModel(modelId, name);
    }

    public static boolean isRoamingExplicit(ResourceLocation modelId, String name) {
        return RoamingVariables.isRoamingExplicit(modelId, name);
    }

    static boolean isModelOwnedVar(ResourceLocation modelId, String name) {
        return RoamingVariables.isModelOwnedVar(modelId, name);
    }

    public static void injectRoamingVar(Map<String, Double> target, String prefix,
        String name, double value, ResourceLocation modelId) {
        RoamingVariables.injectRoamingVar(target, prefix, name, value, modelId);
    }

    public static void injectRoamingVar(Map<String, Double> target, java.util.Set<String> dirty,
        String prefix, String name, double value, ResourceLocation modelId) {
        RoamingVariables.injectRoamingVar(target, dirty, prefix, name, value, modelId);
    }

    public static void registerModelRoamingVar(ResourceLocation modelId, String name) {
        RoamingVariables.registerModelRoamingVar(modelId, name);
    }

    public static void setModelRoamingDefault(ResourceLocation modelId, String name, double value) {
        RoamingVariables.setModelRoamingDefault(modelId, name, value);
    }

    public static Map<String, Double> getRoamingVarsForModel(ResourceLocation modelId) {
        return RoamingVariables.getRoamingVarsForModel(modelId);
    }

    public static void invalidateFrameRoamingCache() {
        RoamingVariables.invalidateFrameRoamingCache();
    }

    public static void clearModelRoamingVars() {
        RoamingVariables.clearModelRoamingVars();
    }

    public static void resetUserRoamingVars(ResourceLocation modelId) {
        RoamingVariables.resetUserRoamingVars(modelId);
    }

    public static void resetAllUserRoamingVars() {
        RoamingVariables.resetAllUserRoamingVars();
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
        for (ControllerMatch match : resolveControllers(set, animationId, geckoControllerName)) {
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
    public static void clear() {
        STATES.clear();
        resetTimelineDispatchBudget();
    }

    /** Drop animation objects on resource eviction; keep authored state and model variables. */
    public static void releaseModelAnimations(ResourceLocation animationId) {
        for (Map.Entry<StateKey, RuntimeState> entry : STATES.entrySet()) {
            if (!animationId.equals(entry.getKey().animationId)) continue;
            RuntimeState state = entry.getValue();
            clearTimeline(state, animationId, state.currentState, "");
            state.lastAnimationId = null;
            state.lastSelectedAnimationState = "";
            state.lastSelectedAnimation = "";
            state.lastActiveAnimations.clear();
        }
    }

    /** 清理指定玩家的全部 RuntimeState（玩家登出时调用）。
     *  玩家断线/离开世界后，按 (playerId, model, controller) 组合累积的
     *  状态不再被使用，但会一直驻留到下次 reload 的 clear()——多玩家长时
     *  运行会缓慢增长，这里按玩家精确清除。 */
    public static void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        java.util.Iterator<Map.Entry<StateKey, RuntimeState>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<StateKey, RuntimeState> e = it.next();
            if (playerId.equals(e.getKey().playerId)) {
                it.remove();
            }
        }
    }

    /**
     * 清掉某玩家某模型下**一个** OpenYSM 控制器的运行时状态（{@code ctrl.reset}）。
     *
     * <p>wiki: molang/script「动画控制」—— {@code ctrl.reset} 要求"立刻重置动画控制器至初始
     * 状态"：不清这个状态机快照的话，控制器下一帧会带着 reset 之前的 state/时间戳继续走，
     * 看起来就像 reset 没生效。</p>
     *
     * @param controllerName OpenYSM 控制器名（如 {@code player.main}）；null 表示清该模型下全部
     */
    public static void clearControllerState(UUID playerId, ResourceLocation modelId, String controllerName) {
        if (playerId == null || modelId == null) {
            return;
        }
        java.util.Iterator<Map.Entry<StateKey, RuntimeState>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<StateKey, RuntimeState> entry = it.next();
            StateKey key = entry.getKey();
            if (!playerId.equals(key.playerId) || !modelId.equals(key.animationId)) {
                continue;
            }
            if (controllerName == null || matchesControllerName(controllerName, key.openYsmControllerName)
                || matchesControllerName(controllerName, key.geckoControllerName)) {
                it.remove();
            }
        }
    }

    /**
     * {@code ctrl.reset} / {@code state_stop} 的控制器名匹配：调用方可能写 wiki 槽位名
     * （{@code parallel_6}）、OpenYSM 名（{@code player.parallel_6}）或 legacy GeckoLib 名
     * （{@code parallel_6_controller}），三者指的是同一个槽位。
     * <p>
     * 统一去掉 {@code player.} 前缀与 {@code _controller} 后缀再比较。**修的是 reset 路由**：
     * 具名并行槽位的运行时键是 {@code player.parallel_6}（或短名），而 reset 只从脚本拿到槽位名
     * {@code parallel_6} —— 按原样比较匹配不到，状态机不会回到初始状态，看起来就像 reset 没生效。
     */
    static boolean matchesControllerName(String requested, String candidate) {
        if (requested == null || candidate == null) {
            return false;
        }
        return normalizeControllerName(requested).equals(normalizeControllerName(candidate));
    }

    /** 控制器名归一化：去掉 {@code player.} 前缀与 {@code _controller} 后缀。 */
    private static String normalizeControllerName(String name) {
        String result = name;
        if (result.startsWith("player.")) {
            result = result.substring("player.".length());
        }
        if (result.endsWith("_controller")) {
            result = result.substring(0, result.length() - "_controller".length());
        }
        return result;
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

    /**
     * 这份模型漫游变量是否需要注入 {@code state}？需要时顺手记下"已注入"并返回 true。
     *
     * <p>同一个渲染 pass 内，所有控制器拿到的是 {@code frameRoamingCache} 里的**同一个 map 实例**；
     * 而 {@code RuntimeState.variables} 跨帧存活。所以"同一个实例已经注入过"就说明这个状态里的值
     * 已经是最新的，不必每控制器每 tick 再灌一遍（注入本身要按名派生若干别名键 + 若干次 map 操作，
     * 是 predicate 路径上的固定开销）。换 pass 时 {@code getRoamingVarsForModel} 会给新实例，
     * 轮盘改值同样如此 —— 那时重新注入，所以这里不会把值"冻住"。
     *
     * <p>空 map 直接返回 false（没有任何变量要注入）。
     */
    static boolean markRoamingInjectedIfNeeded(Map<String, Double> modelRoaming, RuntimeState state) {
        if (modelRoaming.isEmpty() || modelRoaming == state.lastInjectedRoaming) {
            return false;
        }
        state.lastInjectedRoaming = modelRoaming;
        return true;
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
        // injectRoamingVar covers original case + lowercase + the "roaming."
        // prefix-stripped bare name (so controller conditions like
        // "v.bq_eye<=0" can find the value via localVariableValue("bq_eye")
        // even though the stored key is "roaming.bq_eye").
        Map<String, Double> modelRoaming = getRoamingVarsForModel(animationId);
        if (markRoamingInjectedIfNeeded(modelRoaming, runtimeState)) {
            for (Map.Entry<String, Double> entry : modelRoaming.entrySet()) {
                injectRoamingVar(runtimeState.variables, "", entry.getKey(), entry.getValue(), animationId);
            }
        }
        // Debug: log roaming variables relevant to pants/coat switching.
        // 三次 get 必须先判开关：这里是每控制器每 tick 的路径，关掉调试时不该白查三张表。
        if (Config.DEBUG_CONTROLLER) {
            Double dbgHa = runtimeState.variables.get("ha");
            Double dbgHb = runtimeState.variables.get("hb");
            Double dbgVal = runtimeState.variables.get("value_kuzi");
            if (dbgHa != null || dbgHb != null || dbgVal != null) {
                com.fox.ysmu.ysmu.LOG.debug("[YSMU-CTRL] {} roaming: ha={} hb={} value_kuzi={} (all: {})",
                    geckoControllerName, dbgHa, dbgHb, dbgVal, runtimeState.variables);
            }
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
            // 附加实际输入/移动值：方便定位潜行状态机（有模型的 Sneak/Sneaking 依赖
            // ctrl.sneak/ctrl.sneaking + ysm.input_vertical）。
            if (player != null) {
                sb.append(" | input_vertical=").append(String.format(java.util.Locale.ROOT, "%.3f", player.moveForward))
                  .append(" ground_speed=").append(String.format(java.util.Locale.ROOT, "%.3f",
                      Math.sqrt(Math.pow(player.posX - player.prevPosX, 2) + Math.pow(player.posZ - player.prevPosZ, 2)) * 20.0d));
            }
            ysmu.LOG.info(sb.toString());
        }
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
        // ...unless this is only a conditional variant switch inside the same
        // state (an attack state moving between its standing and walking entry),
        // which keeps the playback position via setAnimationPreservingTick. That
        // is still the same logical attack, so the one-shot swing sound it started
        // must not be stopped half way; stopping it and then letting the new
        // variant's tick-0 keyframe fire is what made the swing sound repeat when
        // the player started or stopped moving mid-swing.
        boolean sameControllerState = animationId.equals(runtimeState.lastAnimationId)
            && state.name.equals(runtimeState.lastSelectedAnimationState);
        // 防滑步（stride matching）：按真实水平速度缩放移动类动画的播放倍速。
        // 只作用于主身体控制器（main_controller / player.pre_main），
        // player==null（GUI 预览实体）时跳过，避免覆盖预览页的暂停/冻结倍速。
        applyPlaybackSpeed(event, player, geckoControllerName, existing);
        applyAnimations(event, runtimeState, state, existing, animationId);
        if (!sameControllerState && (prevAnim == null || !prevAnim.equals(existing.get(0)))) {
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
            state = new RuntimeState(geckoControllerName);
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
            // "还没加载回来"不是"模型缺这条动画"：懒加载模式下重动画文件要等
            // AssetManager.anim(mainId).get() 的后台解密+解析写完 GeckoLibCache 才存在，
            // 这期间这里对所有名字都返回 false（模型渲染成绑定姿势），但报出来的却像模型
            // 缺陷。两者对调用方都是一样的 STOP，只有日志要分开：
            //   - isPending() → 加载中/待加载，静默等下一帧；
            //   - 否则 → 文件确实在、名字确实不在，报一次（每 模型×名字）。
            if (!com.fox.ysmu.client.asset.AssetManager.anim(animationId)
                .isPending()) {
                OpenYsmAnimationControllerRegistry.warnOnce(
                    "missing-animation:" + animationId + ":" + animationName,
                    "OpenYSM controller selected missing animation " + animationName + " for " + animationId);
            }
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
        // (many models have no player.main) — it plays idle/walk/run/sneak and must
        // keep Root so sneaking_Control's crouch lowering [0,-7.625,0] and the
        // walk body bob are not silently stripped.
        String ctrlName = event.getController().getName();
        boolean excludeRoot;
        if (isSlotExtraController(ctrlName)) {
            // 池承载的槽位后缀控制器与它的**基槽位控制器**同样处理 Root：只有玩家主动画
            // （main_controller）与 player.pre_main 保留 Root。pre_main 的后缀控制器必须保留 ——
            // 那类动画（形态切换/起飞降落）整段都是全身 Root 位移，剔掉 Root 就等于没动。
            OpenYsmAnimationControllerRegistry.SlotExtra slotExtra = routeSlotExtra(animationId, ctrlName);
            excludeRoot = slotExtra == null || !"pre_main".equals(slotExtra.family);
        } else {
            excludeRoot = ctrlName != null
                && !MAIN_CONTROLLER.equals(ctrlName)
                && !OPENYSM_PRE_MAIN_CONTROLLER.equals(ctrlName)
                && !ctrlName.startsWith("parallel_")
                && !ctrlName.startsWith("pre_parallel_");
        }
        // Resolve every contributing animation once. The list feeds both the bone
        // merge and the bounded timeline scheduler (see buildTimelineContributors) —
        // it is never rebuilt per frame beyond this single pass.
        software.bernie.geckolib3.file.AnimationFile animFile =
            software.bernie.geckolib3.resource.GeckoLibCache.getInstance().getAnimations().get(animationId);
        int contributorCount = animationNames.size();
        List<software.bernie.geckolib3.core.builder.Animation> contributors = new ArrayList<>(contributorCount);
        for (int i = 0; i < contributorCount; i++) {
            software.bernie.geckolib3.core.builder.Animation a = null;
            if (animFile != null) {
                a = animFile.getAnimation(animationNames.get(i));
            }
            if (a == null) {
                a = lookupAnimation(animationNames.get(i));
            }
            contributors.add(a);
        }
        software.bernie.geckolib3.core.builder.Animation primaryAnim = contributors.get(0);
        // Build merged bone animations. For single-animation states or when
        // Root filtering is needed, we create a merged copy stored in the
        // GeckoLib cache so the controller loads our modified version.
        List<software.bernie.geckolib3.core.keyframe.BoneAnimation> mergedBones = null;
        if (primaryAnim != null && primaryAnim.boneAnimations != null) {
            mergedBones = new ArrayList<>(primaryAnim.boneAnimations);
        }
        // Merge additional animations' bones on top (for multi-animation states).
        // `ownedBones` tracks the bone names whose BoneAnimation we have already
        // copied away from the shared GeckoLib cache, so a later merge may write
        // into our copy but never into a cache-owned object.
        java.util.Set<String> ownedBones = new java.util.HashSet<>();
        for (int i = 1; i < contributorCount; i++) {
            software.bernie.geckolib3.core.builder.Animation a = contributors.get(i);
            if (a != null && a.boneAnimations != null) {
                if (mergedBones == null) {
                    mergedBones = new ArrayList<>(a.boneAnimations);
                } else {
                    mergeBones(mergedBones, a.boneAnimations, ownedBones);
                }
            }
        }
        // Playback period of the flattened animation. In OpenYSM every animation of
        // a state is an independent timeline that loops on its own length; YSMU
        // flattens them into one, so the copy has to span the LONGEST contributor.
        // Copying the primary's length instead lets the shortest contributor become
        // the clock: a player.pre_parallel_0 that starts with hair_physics
        // (0.0202 s, a per-frame physics driver) pinned the whole state to tick ~0
        // and froze pre_parallel6's 4 s lightning flash. Each short contributor now
        // keeps its own period in the bounded scheduler instead of repeating its
        // events by an offset loop.
        double mergedLength = 0.0d;
        for (int i = 0; i < contributorCount; i++) {
            mergedLength = Math.max(mergedLength, playbackLengthTicks(contributors.get(i)));
        }
        // Determine the final animation name: if we have merged bones and either
        // need Root filtering or have multiple animations, use a cached merged copy.
        boolean needsMergedCopy = mergedBones != null
            && (excludeRoot || contributorCount > 1);
        String finalName;
        ILoopType finalLoop;
        if (Config.DEBUG_CONTROLLER && ctrlName != null
            && (ctrlName.startsWith("pre_parallel_") || ctrlName.startsWith("parallel_"))
            && allowDebugLog("CTRL-ANIM-" + ctrlName)) {
            // 带模型 id：这条日志不带来源时，多模型（世界渲染 + GUI 预览）同时输出会无法
            // 归因到具体模型，排查"某个模型状态不对"时看不出是谁。
            ysmu.LOG.info("[YSMU-CTRL-ANIM] {} model={} state='{}' animations={} mergedBones={}",
                ctrlName, animationId, state.name, animationNames,
                mergedBones == null ? -1 : mergedBones.size());
        }
        software.bernie.geckolib3.core.builder.Animation mergedAnim = null;
        if (needsMergedCopy) {
            // Remove Root bone for overlay controllers
            if (excludeRoot) {
                mergedBones.removeIf(ba -> "Root".equals(ba.boneName));
            }
            String mergedName = "__ysm_merged__" + primaryName;
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
            // The bounded scheduler owns merged-timeline dispatch. Emptying this list
            // removes GeckoLib's identity-based customInstructionKeyframes channel:
            // no double dispatch, and no fresh EventKeyFrame instances every frame to
            // defeat executedKeyFrames tracking.
            mergedAnim.customInstructionKeyframes = java.util.Collections.emptyList();
            // Preserve sound keyframes from the primary animation. Native particle
            // keyframes keep their original path (they were never copied onto the
            // merged copy) — this change is scoped to the custom-instruction timeline.
            if (primaryAnim != null && primaryAnim.soundKeyFrames != null
                && !primaryAnim.soundKeyFrames.isEmpty()) {
                mergedAnim.soundKeyFrames = new java.util.ArrayList<>(primaryAnim.soundKeyFrames);
            } else {
                mergedAnim.soundKeyFrames = new java.util.ArrayList<>();
            }
            if (primaryAnim != null) {
                mergedAnim.animationLength = mergedLength > 0.0d ? mergedLength : primaryAnim.animationLength;
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
        // Detect model re-entry: if this RuntimeState was parked for more than a
        // few frames (model switched away and back), treat sameAnim as false so
        // setAnimation reloads the merged bone keyframes, and restart the timeline
        // cursors.
        boolean isReEntry = isReEntry(runtimeState.lastActiveFrame);
        runtimeState.lastActiveFrame = FRAME_COUNTER;
        // Same-animation detection: skip setAnimation when the same state and
        // animation are already playing.  BUT if the model changed (animationId
        // differs), force setAnimation because RuntimeState persists across
        // model switches and would incorrectly match the old animation name.
        boolean sameModel = animationId.equals(runtimeState.lastAnimationId);
        boolean sameState = sameModel && state.name.equals(runtimeState.lastSelectedAnimationState);
        boolean sameAnim = sameState && StringUtils.isNotBlank(runtimeState.lastSelectedAnimation)
            && runtimeState.lastSelectedAnimation.equals(primaryName);
        if (sameAnim && isReEntry) {
            // YSMU: a re-entry is NOT a same-animation frame. This must flip the flag
            // (the old code only cleared the tracking fields, but sameAnim had already
            // been computed and stayed true for the skipSetAnimation branch below).
            sameAnim = false;
            runtimeState.lastAnimationId = null;
            runtimeState.lastAnimation = "";
            runtimeState.lastSelectedAnimationState = "";
            runtimeState.lastSelectedAnimation = "";
            runtimeState.lastActiveAnimations.clear();
            runtimeState.enteredTick = event.getAnimationTick();
        }
        runtimeState.lastAnimationId = animationId;
        runtimeState.lastAnimation = primaryName;
        runtimeState.lastSelectedAnimationState = state.name;
        runtimeState.lastSelectedAnimation = primaryName;
        // Configure the bounded timeline scheduler before any early return below.
        // It takes the restart signal explicitly: a re-entry (and, inside, a model/state
        // or final-name change) must reset the cursors even when the animation name list
        // is unchanged.
        // 还必须带上"这一步会 setAnimation 归零"（`!sameState`）：状态名相同不代表没换状态。
        // 空状态连续跳转允许一帧内 A ->（空状态）-> A 的自环（实测某模型每次挥剑都走
        // `挥剑_default -> default -> 挥剑_default`），此时名字没变、骨头动画确实从 tick 0
        // 重播了，但只按名字判断的话时间轴游标会走"重锚"路径（不回放已跳过的区间），
        // 于是动画 t=0 的那些 timeline 指令（模型用它做逐次挥剑的变体计数 v.qh、随机
        // v.random）**只执行第一次**：站起来看到的挥剑变体永远不变，而模型在 YSM 里是
        // 一次挥剑换一个变体。
        configureTimeline(runtimeState, state, animationId, animationNames, contributors,
            mergedLength, mergedAnim, finalName, isReEntry || !sameState);
        boolean skipSetAnimation = false;
        if (sameAnim) {
            // Same state + same animation → skip setAnimation to preserve keyframe
            // tracking (sound/particle keyframes already executed won't re-fire).
            // Conditional animation entries can still change between frames because
            // they depend on roaming variables; when they do, setAnimation must run
            // to apply the new merged bone keyframes even though the primary
            // animation name (e.g. pre_parallel0) has not changed.
            boolean animsChanged = !animationNames.equals(runtimeState.lastActiveAnimations);
            runtimeState.lastActiveAnimations = new java.util.ArrayList<>(animationNames);
            if (!animsChanged) {
                skipSetAnimation = true;
            }
        }
        if (skipSetAnimation) {
            // Timeline refresh runs once per frame here for pre_parallel/parallel
            // controllers so roaming variable changes from the expression wheel take
            // effect immediately. Only provably idempotent assignments are re-run —
            // random/increment/particle side effects are not replayed. Other
            // controllers' timeline instructions set swing-related variables (v.qh,
            // v.random, …) that must NOT be re-triggered every frame.
            if (ctrlName != null
                && (ctrlName.startsWith("pre_parallel_") || ctrlName.startsWith("parallel_"))) {
                refreshRoamingAssignments(contributors, ctrlName);
            }
        } else {
            // When the state hasn't changed but only the animation variant changed
            // (e.g. attack1's animation switches from sword_attack_01 to
            // sword_attack_run1 because the player started running), preserve the
            // current tick position so the animation doesn't restart from tick 0.
            // Full restarts (state transitions, e.g. default→attack1) use
            // setAnimation to reset tick to 0 as expected.
            AnimationBuilder builder = new AnimationBuilder().addAnimation(finalName, finalLoop);
            if (Config.DEBUG_CONTROLLER && allowDebugLog("CTRL-PLAY-" + ctrlName)) {
                ysmu.LOG.info("[YSMU-CTRL-PLAY] {} model={} state='{}' playing='{}' animations={} sameState={}",
                    ctrlName, animationId, state.name, finalName, animationNames, sameState);
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
        // Install the narrow per-controller playback listener. The controller calls
        // it from inside processCurrentAnimation() — i.e. during this frame's
        // controller.process(), after the time update and before bone evaluation —
        // so the scheduler dispatches on the real final playback tick instead of the
        // predicate-time tick (which anim_time_update and the loop/HOLD handling
        // have not applied yet at applyAnimations() time).
        installTimelineListener(event.getController(), runtimeState, mergedAnim);
    }

    /**
     * Configures (or reuses) this RuntimeState's bounded timeline scheduler.
     * <p>
     * Only the merged path owns dispatch: its GeckoLib copy has
     * {@code customInstructionKeyframes} emptied, so the scheduler is the single
     * channel. A single-animation controller keeps GeckoLib's stable-identity path
     * and does not schedule.
     * <p>
     * The reuse fast path is only taken when the contributor set <em>and</em> every
     * condition that forces a reset agree: a state change or a re-entry with the
     * same animation list must restart the cursors, otherwise switching
     * {@code attack → attack} (or leaving and returning to a model) would resume the
     * previous state's phase and never fire the state's entry events.
     */
    private static void configureTimeline(RuntimeState runtimeState, State state, ResourceLocation animationId,
        List<String> animationNames, List<software.bernie.geckolib3.core.builder.Animation> contributors,
        double mergedLength, software.bernie.geckolib3.core.builder.Animation mergedAnim, String finalName,
        boolean forceRestart) {
        if (mergedAnim == null) {
            clearTimeline(runtimeState, animationId, state.name, finalName);
            return;
        }
        boolean modelChanged = !animationId.equals(runtimeState.lastTimelineAnimationId);
        boolean nameChanged = finalName == null ? runtimeState.lastTimelineFinalName != null
            : !finalName.equals(runtimeState.lastTimelineFinalName);
        boolean stateChanged = !state.name.equals(runtimeState.lastTimelineState);
        // `forceRestart` covers what the name comparison cannot see: a self-loop through
        // an empty state (A -> 空 -> A, the pack's per-swing default attack) restarts the
        // animation from tick 0 while the state name stays the same, and the model's
        // tick-0 timeline instructions must run again for each of those entries.
        boolean restart = forceRestart || modelChanged || nameChanged || stateChanged
            || runtimeState.timelineScheduler == null || !runtimeState.timelineScheduler.isStarted();
        String key = animationId + "|" + finalName + "|" + animationNames;
        if (key.equals(runtimeState.timelineProgramKey) && runtimeState.timelineScheduler != null && !restart) {
            // Steady state: the contributor set is unchanged, so the cursors simply
            // keep advancing — no per-frame program rebuild, no allocation churn.
            runtimeState.timelineOwnsDispatch = true;
            runtimeState.lastTimelineAnimationId = animationId;
            runtimeState.lastTimelineState = state.name;
            runtimeState.lastTimelineFinalName = finalName;
            return;
        }
        TimelineProgram program = buildTimelineProgram(animationNames, contributors, mergedLength);
        if (program.contributors.isEmpty()) {
            clearTimeline(runtimeState, animationId, state.name, finalName);
            return;
        }
        runtimeState.timelineOwnsDispatch = true;
        if (runtimeState.timelineScheduler == null) {
            runtimeState.timelineScheduler = new TimelineEventScheduler();
        }
        // A genuine restart resets every cursor; an incremental conditional-entry
        // change keeps the cursors of contributors whose content is unchanged.
        runtimeState.timelineScheduler.configure(program.contributors, restart);
        runtimeState.timelineProgramKey = key;
        reportTimelineTruncation(runtimeState.timelineScheduler, program, animationId, animationNames);
        runtimeState.lastTimelineAnimationId = animationId;
        runtimeState.lastTimelineState = state.name;
        runtimeState.lastTimelineFinalName = finalName;
    }

    private static void clearTimeline(RuntimeState runtimeState, ResourceLocation animationId, String stateName,
        String finalName) {
        runtimeState.timelineOwnsDispatch = false;
        runtimeState.timelineScheduler = null;
        runtimeState.timelineProgramKey = "";
        runtimeState.timelineMergedAnim = null;
        runtimeState.lastTimelineAnimationId = animationId;
        runtimeState.lastTimelineState = stateName;
        runtimeState.lastTimelineFinalName = finalName;
    }

    /**
     * Registers this RuntimeState as the controller's timeline playback listener.
     * <p>
     * At most one listener exists per controller, so a model switch or a controller
     * that falls back to the GeckoLib-owned single-animation path cannot leave a
     * stale listener dispatching another model's instructions. When the timeline is
     * not active the listener is retired to {@code NONE} rather than left pointing at
     * a dropped RuntimeState.
     */
    private static void installTimelineListener(AnimationController<?> controller, RuntimeState runtimeState,
        software.bernie.geckolib3.core.builder.Animation mergedAnim) {
        if (controller == null) {
            return;
        }
        if (mergedAnim == null || !runtimeState.timelineOwnsDispatch || runtimeState.timelineScheduler == null) {
            if (controller.getTimelinePlaybackListener() != AnimationController.ITimelinePlaybackListener.NONE) {
                controller.setTimelinePlaybackListener(AnimationController.ITimelinePlaybackListener.NONE);
            }
            runtimeState.timelineMergedAnim = null;
            return;
        }
        runtimeState.timelineMergedAnim = mergedAnim;
        if (controller.getTimelinePlaybackListener() != runtimeState.timelineListener) {
            controller.setTimelinePlaybackListener(runtimeState.timelineListener);
        }
    }

    /**
     * Per-frame playback callback. Called by
     * {@link AnimationController#processCurrentAnimation} after the time update and
     * before bone evaluation, so {@code tick} is the final playback position of this
     * frame. {@code delta} is the exact forward distance since the previous frame and
     * is {@code 0} when the position was (re)anchored, which the scheduler treats as
     * a rebase instead of a replay.
     */
    private static void handleTimelinePlayback(RuntimeState runtimeState, double tick, double delta) {
        if (!runtimeState.timelineOwnsDispatch) {
            return;
        }
        TimelineEventScheduler scheduler = runtimeState.timelineScheduler;
        if (scheduler == null) {
            return;
        }
        scheduler.advanceFrame(tick, delta, runtimeState.timelineSink);
    }

    /**
     * Per-frame roaming refresh for pre_parallel/parallel controllers. By default
     * only provably idempotent {@code v.x = <roaming expression>} assignments are
     * re-run; random/increment/particle side effects are left to the scheduler's
     * once-per-loop dispatch. {@link #LEGACY_ROAMING_REPLAY} restores the old
     * replay-everything behaviour as a temporary escape hatch.
     * <p>
     * <b>Deliberate conservatism.</b> This is a behaviour change, not just a
     * performance cap: an instruction that is <em>not</em> classified idempotent no
     * longer runs every frame, so a model that relied on a non-idempotent expression
     * (a per-frame increment, a random draw, a particle spawn) now sees it once per
     * loop instead. The old "contains {@code roaming.}" test was too broad — it
     * replayed those side effects every frame. The classifier accepts only
     * {@code v.<name> = <expression>} statements that read {@code roaming.} and
     * contain no function call, so anything it cannot prove is left to the scheduler.
     * <p>
     * Bounds: the scan is capped at {@link #MAX_ROAMING_REFRESH_SCAN_PER_FRAME}
     * keyframes and every execution consumes the shared per-frame
     * {@link #MAX_TIMELINE_DISPATCHES_PER_FRAME} budget, so this path can never run
     * more instructions than the scheduler's own dispatch path and cannot starve
     * other entities for more than one frame's worth of budget.
     */
    private static void refreshRoamingAssignments(
        List<software.bernie.geckolib3.core.builder.Animation> contributors, String ctrlName) {
        if (contributors == null) {
            return;
        }
        int scanned = 0;
        for (software.bernie.geckolib3.core.builder.Animation animation : contributors) {
            if (animation == null || animation.customInstructionKeyframes == null) {
                continue;
            }
            for (software.bernie.geckolib3.core.keyframe.EventKeyFrame<String> keyFrame
                : animation.customInstructionKeyframes) {
                // Bound the scan itself, not just the executions: the budget below
                // already limits how many instructions run, this limits how much the
                // model can make the refresh read every frame.
                if (++scanned > MAX_ROAMING_REFRESH_SCAN_PER_FRAME) {
                    return;
                }
                if (keyFrame == null) {
                    continue;
                }
                String data = keyFrame.getEventData();
                if (data == null) {
                    continue;
                }
                if (LEGACY_ROAMING_REPLAY) {
                    if (!data.toLowerCase(java.util.Locale.ROOT)
                        .contains("roaming.")) {
                        continue;
                    }
                } else if (!MolangInstructionExecutor.isIdempotentRoamingAssignment(data)) {
                    continue;
                }
                if (timelineDispatchBudget <= 0) {
                    return;
                }
                timelineDispatchBudget--;
                MolangInstructionExecutor.noteTimelineExecution(ctrlName, data);
                MolangInstructionExecutor.execute(data);
            }
        }
    }

    /**
     * The scheduler program plus the counts the builder itself dropped.
     * <p>
     * The builder caps <em>before</em> allocating (it must not materialise a copy
     * larger than the scheduler would keep), so a model over the caps is already
     * reduced by the time {@link TimelineEventScheduler#configure} sees it — the
     * scheduler's own {@code getTruncated*} counters would then read 0 and the
     * {@code [YSMU-TL-CAP]} diagnostic would never fire. These fields keep the drop
     * visible.
     */
    static final class TimelineProgram {

        final List<TimelineEventScheduler.Contributor> contributors;
        final int truncatedContributors;
        final int truncatedEvents;

        TimelineProgram(List<TimelineEventScheduler.Contributor> contributors, int truncatedContributors,
            int truncatedEvents) {
            this.contributors = contributors;
            this.truncatedContributors = truncatedContributors;
            this.truncatedEvents = truncatedEvents;
        }
    }

    /**
     * Builds the scheduler program from the contributing animations' timelines.
     * <p>
     * The caps are applied <em>before</em> allocation, not after: the source
     * keyframe list is scanned at most {@code MAX_EVENTS_PER_CONTRIBUTOR} times and
     * the events array is sized to the bounded count, so a model that declares an
     * enormous instruction list never materialises a full-size copy first. The
     * scheduler repeats the same caps, which keeps the two layers consistent; what
     * the builder itself dropped is reported through {@link TimelineProgram}.
     * <p>
     * <b>Contributors keep their own loop flag.</b> A {@code HOLD_ON_LAST_FRAME} or
     * {@code PLAY_ONCE} merged program stops the controller from advancing at its
     * end, so every contributor's {@code delta} becomes 0 and none of them reach
     * another event anyway. Forcing them all non-looping would additionally truncate
     * a short looping contributor that is legitimately mid-cycle while the holding
     * parent animation is still short of its last frame.
     */
    static TimelineProgram buildTimelineProgram(List<String> animationNames,
        List<software.bernie.geckolib3.core.builder.Animation> contributors, double mergedLength) {
        List<TimelineEventScheduler.Contributor> out = new ArrayList<>();
        if (contributors == null) {
            return new TimelineProgram(out, 0, 0);
        }
        int remainingEvents = TimelineEventScheduler.MAX_TOTAL_EVENTS;
        int truncatedContributors = 0;
        long truncatedEvents = 0;
        for (int i = 0; i < contributors.size(); i++) {
            software.bernie.geckolib3.core.builder.Animation animation = contributors.get(i);
            if (animation == null || animation.customInstructionKeyframes == null
                || animation.customInstructionKeyframes.isEmpty()) {
                continue;
            }
            int sourceCount = animation.customInstructionKeyframes.size();
            if (out.size() >= TimelineEventScheduler.MAX_CONTRIBUTORS || remainingEvents <= 0) {
                // Over a cap: the contributor is dropped whole and the scheduler will
                // never see it, so the drop has to be counted here.
                truncatedContributors++;
                truncatedEvents += sourceCount;
                continue;
            }
            double period = playbackLengthTicks(animation);
            if (period <= 0.0d && mergedLength > 0.0d) {
                // No computable period (e.g. an anim_time_update contributor): keep it
                // on the merged period rather than letting its events fire once and
                // never again.
                period = mergedLength;
            }
            boolean loops = animation.loop == ILoopType.EDefaultLoopTypes.LOOP;
            // Cap before allocating: never copy more than the scheduler would keep.
            int capacity = Math.min(remainingEvents,
                Math.min(sourceCount, TimelineEventScheduler.MAX_EVENTS_PER_CONTRIBUTOR));
            // Truncation is sourceCount minus what we were allowed to keep; null
            // keyframes below shrink the stored events but are not truncation.
            truncatedEvents += sourceCount - capacity;
            List<TimelineEventScheduler.Event> events = new ArrayList<>(capacity);
            for (int k = 0; k < capacity; k++) {
                software.bernie.geckolib3.core.keyframe.EventKeyFrame<String> keyFrame =
                    animation.customInstructionKeyframes.get(k);
                if (keyFrame == null) {
                    continue;
                }
                Double tick = keyFrame.getStartTick();
                events.add(new TimelineEventScheduler.Event(tick == null ? 0.0d : tick, keyFrame.getEventData()));
            }
            String name = i < animationNames.size() ? animationNames.get(i) : animation.animationName;
            remainingEvents -= events.size();
            out.add(TimelineEventScheduler.contributor(name, period, loops, events));
        }
        return new TimelineProgram(out, truncatedContributors, (int) Math.min(Integer.MAX_VALUE, truncatedEvents));
    }

    /** Rate-limited report when a model exceeds the scheduler's (or the builder's) safety caps. */
    private static void reportTimelineTruncation(TimelineEventScheduler scheduler, TimelineProgram program,
        ResourceLocation animationId, List<String> animationNames) {
        int droppedContributors = scheduler.getTruncatedContributors() + program.truncatedContributors;
        int droppedEvents = scheduler.getTruncatedEvents() + program.truncatedEvents;
        if (droppedContributors == 0 && droppedEvents == 0) {
            return;
        }
        if (Config.DEBUG_CONTROLLER && allowDebugLog("TL-CAP-" + animationId)) {
            ysmu.LOG.warn(
                "[YSMU-TL-CAP] {} animations={}: dropped {} contributor(s) and {} event(s) over the scheduler caps",
                animationId, animationNames, droppedContributors, droppedEvents);
        }
    }

    /** Playback period of one animation in ticks, or {@code <= 0} when it has no
     *  time-based content at all (every channel is a constant Molang value) or
     *  when its clock is driven by {@code anim_time_update}.
     *  <p>{@code calculateLength} returns {@link Double#MAX_VALUE} as a sentinel
     *  for "no keyframe timing", which must not be treated as a real period. */
    /**
     * 动画的播放周期（tick）。包级可见：弹射物时间轴执行器
     * （{@code ProjectileTimelineRuntime}）用同一套周期判定，避免两条路径对
     * "这个动画多久循环一次"得出不同结论。
     */
    static double playbackLengthTicks(software.bernie.geckolib3.core.builder.Animation animation) {
        if (animation == null) {
            return 0.0d;
        }
        if (animation.animTimeUpdate != null && !animation.animTimeUpdate.isEmpty()) {
            return 0.0d;
        }
        Double declared = animation.animationLength;
        if (declared != null && declared > 0.0d && declared < Double.MAX_VALUE) {
            return declared;
        }
        double longest = 0.0d;
        if (animation.boneAnimations != null) {
            for (software.bernie.geckolib3.core.keyframe.BoneAnimation bone : animation.boneAnimations) {
                longest = Math.max(longest, channelLengthTicks(bone.rotationKeyFrames));
                longest = Math.max(longest, channelLengthTicks(bone.positionKeyFrames));
                longest = Math.max(longest, channelLengthTicks(bone.scaleKeyFrames));
            }
        }
        return longest;
    }

    /** Total duration of a keyframe channel in ticks (the axes share lengths). */
    private static double channelLengthTicks(
        software.bernie.geckolib3.core.keyframe.VectorKeyFrameList<software.bernie.geckolib3.core.keyframe.KeyFrame<com.eliotlash.mclib.math.IValue>> channel) {
        if (channel == null || channel.xKeyFrames == null || channel.xKeyFrames.isEmpty()) {
            return 0.0d;
        }
        double total = 0.0d;
        for (software.bernie.geckolib3.core.keyframe.KeyFrame<com.eliotlash.mclib.math.IValue> frame : channel.xKeyFrames) {
            total += frame.getLengthPrimitive();
        }
        return total;
    }

    /** Merges {@code source} bone animations into {@code target}, channel by channel.
     *  <p>Two invariants are load-bearing here, and both used to be violated:
     *  <ol>
     *   <li><b>Never mutate cache-owned objects.</b> The {@code BoneAnimation} /
     *       {@code VectorKeyFrameList} instances handed out by the animation cache are
     *       shared by every controller and every later frame. The previous version
     *       wrote straight into them, so one merge permanently erased a sibling
     *       animation's channel for the rest of the session.</li>
     *   <li><b>Only overwrite a channel the source actually animates.</b> Overwriting
     *       all three channels let a {@code position}-only sibling erase another
     *       animation's {@code scale} channel. That is exactly what happened to
     *       幻影剑 bones: {@code pre_parallel2} carries the hide-scale
     *       ({@code !v.roaming.yinglou} / {@code !v.roaming.yingbai}) while
     *       {@code pre_parallel6} only repositions the same bones — merging
     *       {@code pre_parallel6} last wiped the scale, so 隐藏后排/前排幻影剑
     *       toggles changed nothing but their own checkbox.</li>
     *  </ol>
     */
    /** {@code mergeBones} 的骨骼名索引表按**嵌套深度**复用；用 ThreadLocal 是因为深度栈
     *  不能跨线程共享（客户端线程与脚本/网络线程都可能走到控制器）。为什么不用单例：
     *  {@code applyAnimations} 可能被嵌套触发（控制器 predicate 里再次 tryApply），单例会被
     *  内层覆盖；按深度分槽后每层拿到自己的表，用完 clear 归还 —— 和 AnimationProcessor 的
     *  tick 槽位同一套思路。 */
    private static final ThreadLocal<MergeIndexScratch> MERGE_INDEX = ThreadLocal
        .withInitial(MergeIndexScratch::new);

    private static final class MergeIndexScratch {

        private final java.util.List<java.util.Map<String, Integer>> slots = new java.util.ArrayList<>();
        private int depth;

        java.util.Map<String, Integer> acquire() {
            if (depth > 64) {
                // 异常路径导致深度漂移时的自愈：丢掉这一轮的槽位，从 0 重新开始。
                depth = 0;
            }
            if (depth == slots.size()) {
                slots.add(new java.util.HashMap<>());
            }
            java.util.Map<String, Integer> map = slots.get(depth++);
            map.clear();
            return map;
        }

        void release() {
            if (depth > 0) {
                depth--;
            }
        }
    }

    private static void mergeBones(List<software.bernie.geckolib3.core.keyframe.BoneAnimation> target,
        List<software.bernie.geckolib3.core.keyframe.BoneAnimation> source,
        java.util.Set<String> ownedBones) {
        // Index `target` by bone name once per call. The previous linear scan made
        // this O(bones²) every call, and the call runs once per frame for every
        // multi-animation state; a client profile showed this method as the largest
        // self-time on the YSMU side (1.0 s of a 24.8 s client thread), essentially
        // all of it String.equals inside that scan.
        java.util.Map<String, Integer> index = MERGE_INDEX.get().acquire();
        for (int i = 0; i < target.size(); i++) {
            // A null bone name never matched in the linear scan either
            // (String.equals(null) is false), so keep it out of the index.
            String name = target.get(i).boneName;
            if (name != null) {
                index.put(name, i);
            }
        }
        for (software.bernie.geckolib3.core.keyframe.BoneAnimation incoming : source) {
            Integer hit = incoming.boneName == null ? null : index.get(incoming.boneName);
            if (hit == null) {
                // Not merged into anything yet, so the shared object is safe to reference.
                if (incoming.boneName != null) {
                    index.put(incoming.boneName, target.size());
                }
                target.add(incoming);
                continue;
            }
            software.bernie.geckolib3.core.keyframe.BoneAnimation merged = target.get(hit);
            if (ownedBones.add(incoming.boneName)) {
                // First time we write to this bone — take a private shallow copy.
                // Channel lists are only ever re-pointed (never mutated in place),
                // so a shallow copy is enough to protect the cache.
                software.bernie.geckolib3.core.keyframe.BoneAnimation copy =
                    new software.bernie.geckolib3.core.keyframe.BoneAnimation();
                copy.boneName = merged.boneName;
                copy.rotationKeyFrames = merged.rotationKeyFrames;
                copy.positionKeyFrames = merged.positionKeyFrames;
                copy.scaleKeyFrames = merged.scaleKeyFrames;
                merged = copy;
                target.set(hit, copy);
            }
            if (hasKeyFrames(incoming.rotationKeyFrames)) {
                merged.rotationKeyFrames = incoming.rotationKeyFrames;
            }
            if (hasKeyFrames(incoming.positionKeyFrames)) {
                merged.positionKeyFrames = incoming.positionKeyFrames;
            }
            if (hasKeyFrames(incoming.scaleKeyFrames)
                && !(isIdentityScale(incoming.scaleKeyFrames) && hasKeyFrames(merged.scaleKeyFrames))) {
                // Scale is the one channel Bedrock/YSM blend multiplicatively, so a
                // constant 1 is the neutral element ("I don't care about visibility")
                // rather than an override. Letting it win would erase an explicit
                // hide/scale toggle contributed by a sibling animation — e.g.
                // a follow_animation ending with scale=1 erased pre_parallel2's
                // `v.roaming.soul` and broke the part it was hiding.
                merged.scaleKeyFrames = incoming.scaleKeyFrames;
            }
        }
        MERGE_INDEX.get().release();
    }

    /** True when the channel carries at least one keyframe, i.e. the animation
     *  actually drives that channel. A missing/empty channel must be treated as
     *  "this animation says nothing about it", not as "reset it". */
    private static boolean hasKeyFrames(
        software.bernie.geckolib3.core.keyframe.VectorKeyFrameList<software.bernie.geckolib3.core.keyframe.KeyFrame<com.eliotlash.mclib.math.IValue>> channel) {
        return channel != null && channel.xKeyFrames != null && !channel.xKeyFrames.isEmpty();
    }

    /** True when the scale channel is the identity transform (constant 1 on every
     *  axis, start and end of every keyframe). Under multiplicative scale blending
     *  such a channel is a no-op and must not overwrite another animation's scale. */
    private static boolean isIdentityScale(
        software.bernie.geckolib3.core.keyframe.VectorKeyFrameList<software.bernie.geckolib3.core.keyframe.KeyFrame<com.eliotlash.mclib.math.IValue>> channel) {
        if (channel == null || channel.xKeyFrames.isEmpty() || channel.yKeyFrames.isEmpty()
            || channel.zKeyFrames.isEmpty()) {
            return false;
        }
        return allValuesAreOne(channel.xKeyFrames) && allValuesAreOne(channel.yKeyFrames)
            && allValuesAreOne(channel.zKeyFrames);
    }

    private static boolean allValuesAreOne(
        List<software.bernie.geckolib3.core.keyframe.KeyFrame<com.eliotlash.mclib.math.IValue>> frames) {
        for (software.bernie.geckolib3.core.keyframe.KeyFrame<com.eliotlash.mclib.math.IValue> f : frames) {
            // Expressions are re-evaluated here; if one is Molang-driven this
            // returns false and we fall back to plain "later animation wins".
            if (Math.abs(f.getStartValueDouble() - 1.0d) > 1e-6d) {
                return false;
            }
            if (Math.abs(f.getEndValueDouble() - 1.0d) > 1e-6d) {
                return false;
            }
        }
        return true;
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
                state.lastSwingProgressInt = player.swingProgressInt;
                state.variables.put("swing", 0.0d);
                state.variables.put("swing_sword", 0.0d);
            } else {
                // 1.7.10 没有 1.9+ 的攻击冷却：挥到一半再点击会重新触发一次挥剑，而
                // isSwingInProgress 一直是 true，只有 swingProgressInt 被重置为 -1 ——
                // 只看布尔上升沿会漏掉"打断重挥"（模型把 v.swing 当一次性触发消费，
                // 收不到新触发就只能等动画播完）。判定收在 SwingEdge，两处路径共用。
                boolean newSwing = com.fox.ysmu.util.SwingEdge.isNewSwing(
                    player.isSwingInProgress, player.swingProgressInt,
                    state.lastSwingProgressInt, state.lastSwingActive);
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
                state.lastSwingProgressInt = player.swingProgressInt;
            }
        }
    }

    /** 具名并行槽位的备用池控制器名：{@code (player.)?<族>_extra_<序号>_controller}。 */
    private static final java.util.regex.Pattern PARALLEL_EXTRA_CONTROLLER =
        java.util.regex.Pattern.compile("^(?:player\\.)?(pre_parallel|parallel)_extra_(\\d+)_controller$");

    /** 槽位后缀控制器的共享备用池控制器名：{@code openysm_slot_extra_<序号>_controller}。 */
    private static final java.util.regex.Pattern SLOT_EXTRA_CONTROLLER =
        java.util.regex.Pattern.compile("^"
            + java.util.regex.Pattern.quote(com.fox.ysmu.util.ControllerUtils.SLOT_EXTRA_CONTROLLER_PREFIX)
            + "(\\d+)_controller$");

    /** 名字是不是槽位后缀备用池控制器。谓词路径每帧都问，所以先用廉价的 startsWith 挡掉绝大多数。 */
    public static boolean isSlotExtraController(String geckoControllerName) {
        return geckoControllerName != null
            && geckoControllerName.startsWith(com.fox.ysmu.util.ControllerUtils.SLOT_EXTRA_CONTROLLER_PREFIX)
            && SLOT_EXTRA_CONTROLLER.matcher(geckoControllerName).matches();
    }

    /**
     * 槽位后缀池控制器的路由：第 i 个池控制器承载当前模型第 i 个
     * {@code player.<slot>_<后缀>}（见
     * {@link OpenYsmAnimationControllerRegistry#slotExtraControllers}）。
     *
     * <p>池控制器名里没有模型信息，所以路由必须按当前模型算——与具名并行槽位同样的理由：
     * {@code registerControllers} 对每个 animatable 只跑一次，而模型可以随时切换。</p>
     *
     * @return 路由到的条目；不是池控制器、或该下标在当前模型里没有对应后缀控制器时返回 {@code null}
     */
    public static OpenYsmAnimationControllerRegistry.SlotExtra routeSlotExtra(ResourceLocation animationId,
        String geckoControllerName) {
        if (animationId == null || geckoControllerName == null) {
            return null;
        }
        java.util.regex.Matcher matcher = SLOT_EXTRA_CONTROLLER.matcher(geckoControllerName);
        if (!matcher.matches()) {
            return null;
        }
        int index = Integer.parseInt(matcher.group(1));
        java.util.List<OpenYsmAnimationControllerRegistry.SlotExtra> entries =
            OpenYsmAnimationControllerRegistry.slotExtraControllers(animationId);
        return index < entries.size() ? entries.get(index) : null;
    }

    /**
     * 池控制器本帧应该交给动画控制脚本的**槽位名**（{@code <slot>_<后缀>}）。
     * <p>
     * {@code AnimationManager.controlSlotName()} 对池名故意返回 null（模型里没有这个槽位名，
     * 真正的名字只有这里知道），所以那里必须补上这次路由 —— 与具名并行槽位
     * （{@link #namedParallelControlSlot}）完全同一个套路。
     *
     * @return 槽位名；不是池控制器、或没路由到条目时返回 {@code null}
     */
    public static String slotExtraControlSlot(ResourceLocation animationId, String geckoControllerName) {
        OpenYsmAnimationControllerRegistry.SlotExtra route = routeSlotExtra(animationId, geckoControllerName);
        return route == null ? null : route.controlSlot;
    }

    /** 包内可见（单测直接断言"名字 → 匹配列表"）：{@link ControllerSet#routeCache} 缓存它。 */
    static List<ControllerMatch> resolveControllers(ControllerSet set, ResourceLocation animationId,
        String geckoControllerName) {
        // 名字 → 匹配列表的解析只依赖这个模型的控制器表，缓存到 ControllerSet 上
        // （set 发布后不再被改动，见 ControllerSet#routeCache 的说明）。
        List<ControllerMatch> cached = set.routeCache.get(geckoControllerName);
        if (cached != null) {
            return cached;
        }
        List<ControllerMatch> matches = new ArrayList<>();
        // 具名并行槽位的备用池必须先分流：它的名字也带 pre_parallel_/parallel_ 前缀，
        // 落到下面的数字槽位解析会得到一个 -1 然后什么都不匹配。
        NamedParallelRoute namedRoute = routeNamedParallel(animationId, geckoControllerName);
        if (namedRoute != null) {
            if (namedRoute.controllerKey != null) {
                addMatch(matches, set, namedRoute.controllerKey);
            }
            List<ControllerMatch> stored = matches.isEmpty() ? java.util.Collections.emptyList() : matches;
            set.routeCache.put(geckoControllerName, stored);
            return stored;
        }
        // 槽位后缀控制器的共享备用池：名字里没有模型信息，路由按当前模型算
        // （wiki「动画控制器」2.6.3：同一组内的控制器按名称字母序排序后依次加载；
        // 官方对 player.pre_main_* 这类带后缀的名字也各发一个独立控制器）。
        if (isSlotExtraController(geckoControllerName)) {
            OpenYsmAnimationControllerRegistry.SlotExtra slotExtra =
                routeSlotExtra(animationId, geckoControllerName);
            if (slotExtra != null) {
                addMatch(matches, set, slotExtra.controllerKey);
            }
            List<ControllerMatch> stored = matches.isEmpty() ? java.util.Collections.emptyList() : matches;
            set.routeCache.put(geckoControllerName, stored);
            return stored;
        }
        int preferredIndex = getParallelIndex(geckoControllerName);
        if (preferredIndex >= 0) {
            if (geckoControllerName.startsWith("pre_parallel_")) {
                addMatch(matches, set, "player.pre_parallel_" + preferredIndex);
                addMatch(matches, set, "pre_parallel_" + preferredIndex);
            } else {
                addMatch(matches, set, "player.parallel_" + preferredIndex);
                addMatch(matches, set, "parallel_" + preferredIndex);
            }
        } else if (MAIN_CONTROLLER.equals(geckoControllerName)) {
            addMatch(matches, set, "player.main");
            addMatch(matches, set, "player.base");
            addMatch(matches, set, "player.move");
            addMatch(matches, set, "main");
        } else if (HOLD_MAINHAND_CONTROLLER.equals(geckoControllerName)) {
            addMatch(matches, set, "player.hold_mainhand");
            addMatch(matches, set, "hold_mainhand");
        } else if (HOLD_OFFHAND_CONTROLLER.equals(geckoControllerName)) {
            addMatch(matches, set, "player.hold_offhand");
            addMatch(matches, set, "hold_offhand");
        } else if (SWING_CONTROLLER.equals(geckoControllerName)) {
            addMatch(matches, set, "player.swing");
            addMatch(matches, set, "swing");
        } else if (USE_CONTROLLER.equals(geckoControllerName)) {
            addMatch(matches, set, "player.use");
            addMatch(matches, set, "use");
        } else if (CAP_CONTROLLER.equals(geckoControllerName)) {
            addMatch(matches, set, "player.cap");
            addMatch(matches, set, "cap");
        }
        addMatch(matches, set, geckoControllerName);
        if (geckoControllerName.startsWith("player.")) {
            addMatch(matches, set, geckoControllerName.substring("player.".length()));
        }
        // 缓存的是"解析结果"，调用方只遍历、不改。空结果也缓存（它是常态：
        // 大多数控制器名在当前模型里没有对应定义）。
        if (geckoControllerName.endsWith("_controller")) {
            addMatch(matches, set, geckoControllerName.substring(0, geckoControllerName.length() - 11));
        }
        // (缓存写入统一放在最后，见方法末尾)
        // 模糊匹配：player.post_main → player.post_main_<anything>
        // 用于车辆动画等带后缀的槽位控制器。
        // 已经被槽位后缀备用池承载的那些**不在这里**再加一遍：池控制器会让它们各自作为独立
        // 控制器运行（与官方一致），而基槽位的这条模糊规则是"取第一个非空匹配"，两者同时命中
        // 就是同一份动画在两个控制器里各播一遍 —— 骨骼结果一样，但 timeline/音效/粒子关键帧
        // 会触发两次。只有池放不下（配置调小、或模型的槽位后缀比池大）时才退回这条老路径。
        if (geckoControllerName.endsWith("_main") || geckoControllerName.endsWith("_hold")
            || geckoControllerName.endsWith("_swing") || geckoControllerName.endsWith("_use")) {
            String prefix = geckoControllerName + "_";
            java.util.List<OpenYsmAnimationControllerRegistry.SlotExtra> poolExtras =
                OpenYsmAnimationControllerRegistry.slotExtraControllers(animationId);
            for (String key : set.controllers.keySet()) {
                if (key.startsWith(prefix) && !isClaimedBySlotExtraPool(poolExtras, key)) {
                    addMatch(matches, set, key);
                }
            }
        }
        List<ControllerMatch> stored = matches.isEmpty() ? java.util.Collections.emptyList() : matches;
        set.routeCache.put(geckoControllerName, stored);
        return stored;
    }

    /**
     * 这个后缀控制器是否已经由槽位后缀备用池承载（即池下标 &lt; {@code Config.SLOT_EXTRA_CONTROLLERS}）。
     * <p>
     * 池承载的必须从基槽位的模糊匹配里排除，否则同一份动画会在基槽位控制器和池控制器里各播一遍
     * （骨骼结果相同，但每份 {@code RuntimeState} 各自推进 timeline，音效/粒子/自定义指令会触发两次）。
     * 池放不下的（配置调小、或模型声明的后缀比池大）返回 false，由基槽位的"第一个非空匹配"兜底。
     */
    private static boolean isClaimedBySlotExtraPool(
        java.util.List<OpenYsmAnimationControllerRegistry.SlotExtra> poolExtras, String controllerKey) {
        if (controllerKey == null || poolExtras.isEmpty()) {
            return false;
        }
        int carried = Math.min(Config.SLOT_EXTRA_CONTROLLERS, poolExtras.size());
        for (int i = 0; i < carried; i++) {
            if (controllerKey.equals(poolExtras.get(i).controllerKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 具名并行槽位的备用池：{@code (player.)?<族>_extra_<i>_controller} 的第 i 个池控制器
     * 承载该族第 i 个具名槽位（见
     * {@link OpenYsmAnimationControllerRegistry#namedParallelSlots(ResourceLocation, String)}）。
     * <p>
     * 这个映射是**按当前模型**算的：换模型后同一个池下标可能承载另一个槽位，所以不能缓存
     * "下标 → 控制器名"到池控制器上（槽位表本身按模型缓存，见注册表）。
     *
     * @return 路由结果；{@code null} 表示这个名字不是池控制器
     */
    static NamedParallelRoute routeNamedParallel(ResourceLocation animationId, String geckoControllerName) {
        String[] pool = parseNamedParallelPoolController(geckoControllerName);
        if (pool == null) {
            return null;
        }
        String family = pool[0];
        int index = Integer.parseInt(pool[1]);
        java.util.List<String> slots = OpenYsmAnimationControllerRegistry.namedParallelSlots(animationId, family);
        int poolSize = Config.NAMED_PARALLEL_EXTRA_SLOTS;
        if (slots.size() > poolSize) {
            // 模型声明的具名槽位比池子大：多出来的那些没有池控制器承载，永远不会播放。
            // 只警告一次、不静默截断（提高配置项 NamedParallelExtraSlots 即可解决）。
            OpenYsmAnimationControllerRegistry.warnOnce(
                "parallel-extra-overflow:" + animationId + ":" + family,
                animationId + " declares " + slots.size() + " named " + family + " slots but only "
                    + poolSize + " extra pool controllers exist; raise Config NamedParallelExtraSlots"
                    + " (slots=" + slots + ")");
        }
        String slot = index < slots.size() ? slots.get(index) : null;
        String controlSlot = slot == null ? null : family + "_" + slot;
        String controllerKey = slot == null ? null
            : OpenYsmAnimationControllerRegistry.resolveParallelSlotKey(animationId, family, slot);
        return new NamedParallelRoute(family, index, controlSlot, controllerKey, slots.size(), poolSize);
    }

    /**
     * 池控制器本帧应该交给动画控制脚本的**槽位名**（如 {@code parallel_6} / {@code pre_parallel_表情}）。
     * <p>
     * {@code controlSlotName()} 对池名故意返回 null（模型给的槽位名只有这里知道），
     * 所以 {@code AnimationManager} 在具名并行槽位上用这个方法补齐脚本路由；
     * 该槽位只有脚本、没有 JSON 控制器时也能命中脚本。
     *
     * @return 槽位名；不是池控制器、或该模型没有这么多具名槽位时返回 {@code null}
     */
    public static String namedParallelControlSlot(ResourceLocation animationId, String geckoControllerName) {
        NamedParallelRoute route = routeNamedParallel(animationId, geckoControllerName);
        return route == null ? null : route.controlSlot;
    }

    /** 一个具名并行池控制器的路由结果。 */
    static final class NamedParallelRoute {

        final String family;
        final int index;
        /** 该池下标承载的具名槽位对应的控制脚本槽位名（{@code <族>_<后缀>}），没分到为 null。 */
        final String controlSlot;
        /** 该槽位解析出的 ControllerSet 键名（{@code player.<族>_<后缀>} 优先），
         *  只有脚本、没有 JSON 控制器时为 null。 */
        final String controllerKey;
        /** 模型声明的具名槽位数。 */
        final int declaredSlots;
        /** 已注册的池控制器数量（{@code Config.NAMED_PARALLEL_EXTRA_SLOTS}）。 */
        final int poolSize;

        NamedParallelRoute(String family, int index, String controlSlot, String controllerKey,
            int declaredSlots, int poolSize) {
            this.family = family;
            this.index = index;
            this.controlSlot = controlSlot;
            this.controllerKey = controllerKey;
            this.declaredSlots = declaredSlots;
            this.poolSize = poolSize;
        }

        boolean overflow() {
            return declaredSlots > poolSize;
        }
    }

    /**
     * 从 GeckoLib 控制器名里解析出备用池槽位 {族名, 序号字符串}；不是池控制器返回 null。
     * <p>
     * 池名也带 {@code pre_parallel_} / {@code parallel_} 前缀，所以必须先认出它，
     * 否则会掉进 {@link #getParallelIndex} 的数字槽位解析（对 {@code extra_0} 抛
     * NumberFormatException 后返回 -1，什么都不匹配）。
     */
    static String[] parseNamedParallelPoolController(String geckoControllerName) {
        if (geckoControllerName == null) {
            return null;
        }
        Matcher matcher = PARALLEL_EXTRA_CONTROLLER.matcher(geckoControllerName);
        if (!matcher.matches()) {
            return null;
        }
        return new String[] { matcher.group(1), matcher.group(2) };
    }

    private static void addMatch(List<ControllerMatch> matches, ControllerSet set, String controllerName) {
        Controller controller = set.controllers.get(controllerName);
        if (controller == null) {
            return;
        }
        for (ControllerMatch match : matches) {
            if (match.controller == controller) {
                return;
            }
        }
        matches.add(new ControllerMatch(controller));
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

    /** Monotonically increasing **render frame** counter used to detect RuntimeState
     *  re-entry after a controller was inactive (e.g. model switched away and back).
     *  Advanced once per rendered frame by {@link #advanceRenderFrame()} (hooked to
     *  {@code TickEvent.RenderTickEvent}), never per model pass — see
     *  {@link #beginModelPass()} for why that distinction is load-bearing. */
    private static int FRAME_COUNTER = 0;

    /** 停放超过这么多**渲染帧**才算"模型被换走又换回来"。 */
    private static final int RE_ENTRY_FRAMES = 10;

    /** "模型被换走又换回来"判定：该控制器上一次处理距今超过 {@link #RE_ENTRY_FRAMES} 帧。 */
    static boolean isReEntry(int lastActiveFrame) {
        return lastActiveFrame > 0 && FRAME_COUNTER - lastActiveFrame > RE_ENTRY_FRAMES;
    }

    /** 测试用：当前帧计数。 */
    static int frameCounter() {
        return FRAME_COUNTER;
    }

    /**
     * 判定为"再入"前允许停放的最大渲染帧数（即 {@link #RE_ENTRY_FRAMES}）。
     *
     * <p>给"某个 pass 的刷新间隔由限频策略决定，但它同时又是该模型唯一的动画 pass"的场景用
     * （见 {@code HudPreviewCache}：第一人称下 HUD 纸娃娃是自身模型的唯一 pass）。那个间隔必须
     * 留在本窗口内，否则限频会被 {@link #isReEntry} 误判成"模型被换走又换回来"，
     * {@code setAnimation} 被重新装上、动画被钉回 tick 0。边界由
     * {@code ControllerReEntryFrameTest} 锁定：差 10 帧不算再入，第 11 帧才算。</p>
     */
    public static int reEntryFrameWindow() {
        return RE_ENTRY_FRAMES;
    }

    /**
     * 任何"按自己的节奏刷新、但刷新就是该模型唯一一次动画推进"的 pass，两次之间允许停放的最大
     * 渲染帧数（= 再入窗口留 2 帧余量）。
     *
     * <p>限频策略必须留在这个窗口内，否则 {@link #isReEntry} 会把限频误判成"模型被换走又换回来"，
     * 于是并行控制器（眼睛/耳朵/尾巴/表情/物理时间轴）每次刷新都被重新装上、被钉回 tick 0 ——
     * 表现是眨眼卡在过程中、尾巴僵住。目前有两处用：{@code HudPreviewCache}（第一人称的 HUD
     * 纸娃娃）与 {@code PreviewRefreshPolicy}（模型选择页那一页缩略图）。</p>
     */
    public static int safePassWindowFrames() {
        return RE_ENTRY_FRAMES - 2;
    }

    /**
     * 每个**渲染帧**推进一次帧计数（由 {@code ClientEventHandler.onRenderTick} 调用）。
     *
     * <p>必须与"模型 pass"分开：模型选择页一帧要渲染十几到二十几个模型，如果按 pass 推进，
     * 同一个控制器两次处理之间会差十几二十个"帧"，{@link #isReEntry} 就每帧误判成再入，
     * 于是并行控制器（眼睛/耳朵/尾巴/表情/物理状态时间轴）每帧重启一次——表现为预览页
     * 抖动 + 眼睛逐帧眨动，而关掉预览页（每帧只有 2~3 个 pass）就正常。</p>
     */
    public static void advanceRenderFrame() {
        FRAME_COUNTER++;
    }

    /**
     * 模型渲染 pass 入口（每个模型每帧一次，由 {@code MolangPhysicsRuntime.begin()} 调用）：
     * 只重置本 pass 的时间轴派发预算。**不要**在这里推进帧计数（见 {@link #advanceRenderFrame()}）。
     */
    public static void beginModelPass() {
        timelineDispatchBudget = MAX_TIMELINE_DISPATCHES_PER_FRAME;
    }

    /** Clears the timeline dispatch budget immediately; used by tests and clear(). */
    static void resetTimelineDispatchBudget() {
        timelineDispatchBudget = MAX_TIMELINE_DISPATCHES_PER_FRAME;
    }

    static final class RuntimeState {
        /** GeckoLib controller name; used only by the rate-limited timeline sink. */
        final String controllerName;
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
        /** 上一次采样到的 swingProgressInt（1.7.10 打断重挥判定用）。 */
        int lastSwingProgressInt = 0;
        double enteredTick;
        boolean lastSwingActive;
        /** Regular HashMap is safe: all RuntimeState access is on the client render thread. */
        final Map<String, Double> variables = new java.util.HashMap<>();
        /** 上一次注入本状态的那份"模型漫游变量"map（按实例比较，见 tryApplyController）。
         *  null = 还没注入过。 */
        Map<String, Double> lastInjectedRoaming;
        /** Bounded timeline scheduler; non-null only while the merged path owns
         *  dispatch. Dropped together with the RuntimeState on reset/reload. */
        TimelineEventScheduler timelineScheduler;
        boolean timelineOwnsDispatch;
        /** Signature (model | finalName | animation list) of the configured program. */
        String timelineProgramKey = "";
        ResourceLocation lastTimelineAnimationId = null;
        String lastTimelineState = "";
        String lastTimelineFinalName = null;
        /** The merged animation instance this state currently schedules, so the
         *  playback listener can ignore a callback for any other animation. */
        software.bernie.geckolib3.core.builder.Animation timelineMergedAnim;
        /** The single playback listener installed on the gecko controller. It carries
         *  no state of its own: the merged-animation identity check above is what
         *  makes a retired RuntimeState inert, so a model switch cannot leave this
         *  state's scheduler advancing on another model's controller. */
        final AnimationController.ITimelinePlaybackListener timelineListener =
            new AnimationController.ITimelinePlaybackListener() {

                @Override
                public void onTimelinePlayback(software.bernie.geckolib3.core.builder.Animation animation, double tick,
                    double delta, boolean wrapped) {
                    if (animation == null || animation != timelineMergedAnim) {
                        return;
                    }
                    handleTimelinePlayback(RuntimeState.this, tick, delta);
                }
            };
        /** Budget-aware dispatch sink for the scheduler (captures the controller name). */
        final TimelineEventScheduler.Sink timelineSink = new TimelineEventScheduler.Sink() {

            @Override
            public void dispatch(String instructions) {
                if (timelineDispatchBudget <= 0) {
                    return;
                }
                timelineDispatchBudget--;
                MolangInstructionExecutor.noteTimelineExecution(controllerName, instructions);
                MolangInstructionExecutor.execute(instructions);
            }
        };

        RuntimeState(String controllerName) {
            this.controllerName = controllerName;
        }
    }

    /** 包内可见：{@link OpenYsmControllerDefinitions.ControllerSet#routeCache} 缓存它，单测读 {@code controller.name}。 */
    static final class ControllerMatch {
        final Controller controller;

        private ControllerMatch(Controller controller) {
            this.controller = controller;
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
