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

    /**
     * 常驻变量（{@code v.roaming.*}）的动画侧写回：把动画 / 时间轴 / 关键帧 Molang 写下的值
     * 记为"该模型下已被设置"，否则下一帧 {@link MolangPhysicsRuntime#begin} 注入 ysm.json 里的
     * 默认值时会把它冲掉。
     *
     * <p>wiki 语义依据（{@code /wiki/molang/var/}）：{@code variable.roaming.} 是"常驻变量"，
     * 与实体变量一样**赋值后一直保持**，只有再次赋值才改变；默认值只用于"从未赋过值"的 null
     * 初值，绝不是每帧复位。实测案例：某模型轮盘"变身"的时间轴写
     * {@code v.roaming.a=1-v.roaming.b; v.roaming.b=v.roaming.a;}，而 {@code v.roaming.b}
     * 同时是模型自定义配置里的复选框变量（默认 0）。默认值每帧回写 → 时间轴写下的 1 在下一帧
     * 被冲成 0 → 第二次"变身"算出的目标状态与第一次相同 → 变身只能生效一次，之后无法切回。</p>
     *
     * <p>写入的仍是 {@link #PENDING_ROAMING}（全局平坦表，与轮盘 GUI 同一个存储），因此轮盘
     * 配置界面读到的就是动画刚写下的状态；跨模型隔离由 {@code EXPLICIT_ROAMING_BY_MODEL}
     * 提供（该标记只对这个模型生效）。该存储没有玩家维度：多人下某玩家动画写下的常驻变量
     * 会对同模型的其他人可见 —— 与轮盘 GUI 写入的现状一致，属既有设计的限制。</p>
     *
     * @param varName 变量名，可带 {@code v.} / {@code variable.} 前缀
     */
    public static void noteRoamingWrite(ResourceLocation modelId, String varName, double value) {
        if (varName == null) {
            return;
        }
        String name = varName;
        if (name.startsWith("variable.")) {
            name = name.substring("variable.".length());
        }
        if (name.startsWith("v.")) {
            name = name.substring(2);
        }
        if (!name.startsWith("roaming.")) {
            // 只有常驻变量会被默认值每帧回写；其它 v.* 没有这条写回，不必记录。
            return;
        }
        PENDING_ROAMING.put(name, value);
        markRoamingExplicit(modelId, name);
        invalidateFrameRoamingCache();
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

    /** 把 roaming 变量注入目标 map：原 case + 小写 +（roaming. 前缀剥离后的）裸名 + 裸名小写，
     *  使控制器条件（RuntimeState，keyPrefix=""）与关键帧 Molang（ScopeState，keyPrefix="v."）
     *  都能按多种写法命中同一变量。两处注入逻辑唯一实现，避免重复漂移。 */
    public static void injectRoamingVar(java.util.Map<String, Double> target, String keyPrefix,
        String varName, double value) {
        target.put(keyPrefix + varName, value);
        String lc = varName.toLowerCase(java.util.Locale.ROOT);
        if (!lc.equals(varName)) {
            target.put(keyPrefix + lc, value);
        }
        if (varName.startsWith("roaming.")) {
            String plain = varName.substring("roaming.".length());
            target.put(keyPrefix + plain, value);
            String lcPlain = plain.toLowerCase(java.util.Locale.ROOT);
            if (!lcPlain.equals(plain)) {
                target.put(keyPrefix + lcPlain, value);
            }
        }
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

    /**
     * 重置指定模型的用户漫游变量（/ysm reset 用）。
     * 只清除"用户通过 GUI/轮盘显式设置"的值：EXPLICIT_ROAMING_BY_MODEL 中属于
     * 该模型的标记，以及 PENDING_ROAMING 中被这些标记覆盖的条目。
     * 保留模型定义（MODEL_ROAMING_VARS / MODEL_ROAMING_DEFAULTS），因此 reset 后
     * 变量回落到模型 ysm.json 定义的默认值，而不是消失。
     */
    public static void resetUserRoamingVars(ResourceLocation modelId) {
        if (modelId == null) {
            return;
        }
        java.util.Set<String> explicit = EXPLICIT_ROAMING_BY_MODEL.remove(modelId);
        if (explicit != null) {
            for (String varName : explicit) {
                PENDING_ROAMING.remove(varName);
            }
            // 全局标记也可能含该模型的变量（无模型上下文写入的兜底路径）
            EXPLICIT_ROAMING.removeAll(explicit);
        }
        // 全局标记里非模型专属的条目（如 lock_wheel / wheel_anim）也一并重置，
        // 这些是用户设置的全局状态，reset 时应回默认。
        EXPLICIT_ROAMING.remove("lock_wheel");
        EXPLICIT_ROAMING.remove("wheel_anim");
        PENDING_ROAMING.remove("lock_wheel");
        PENDING_ROAMING.remove("wheel_anim");
        invalidateFrameRoamingCache();
    }

    /**
     * 重置所有用户的漫游变量（/ysm reset @a 用）。清空全部用户显式设置，
     * 保留模型定义（MODEL_ROAMING_VARS / MODEL_ROAMING_DEFAULTS）。
     */
    public static void resetAllUserRoamingVars() {
        PENDING_ROAMING.clear();
        EXPLICIT_ROAMING.clear();
        EXPLICIT_ROAMING_BY_MODEL.clear();
        invalidateFrameRoamingCache();
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
        if (!modelRoaming.isEmpty()) {
            for (Map.Entry<String, Double> entry : modelRoaming.entrySet()) {
                injectRoamingVar(runtimeState.variables, "", entry.getKey(), entry.getValue());
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
        // (many models have no player.main) — it plays idle/walk/run/sneak and must
        // keep Root so sneaking_Control's crouch lowering [0,-7.625,0] and the
        // walk body bob are not silently stripped.
        String ctrlName = event.getController().getName();
        boolean excludeRoot = ctrlName != null
            && !MAIN_CONTROLLER.equals(ctrlName)
            && !OPENYSM_PRE_MAIN_CONTROLLER.equals(ctrlName)
            && !ctrlName.startsWith("parallel_")
            && !ctrlName.startsWith("pre_parallel_");
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
            ysmu.LOG.info("[YSMU-CTRL-ANIM] {} state='{}' animations={} mergedBones={}",
                ctrlName, state.name, animationNames,
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
        // It takes isReEntry explicitly: a re-entry (and, inside, a model/state or
        // final-name change) must reset the cursors even when the animation name list
        // is unchanged.
        configureTimeline(runtimeState, state, animationId, animationNames, contributors,
            mergedLength, mergedAnim, finalName, isReEntry);
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
        boolean reEntry) {
        if (mergedAnim == null) {
            clearTimeline(runtimeState, animationId, state.name, finalName);
            return;
        }
        boolean modelChanged = !animationId.equals(runtimeState.lastTimelineAnimationId);
        boolean nameChanged = finalName == null ? runtimeState.lastTimelineFinalName != null
            : !finalName.equals(runtimeState.lastTimelineFinalName);
        boolean stateChanged = !state.name.equals(runtimeState.lastTimelineState);
        boolean restart = reEntry || modelChanged || nameChanged || stateChanged
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
    private static void mergeBones(List<software.bernie.geckolib3.core.keyframe.BoneAnimation> target,
        List<software.bernie.geckolib3.core.keyframe.BoneAnimation> source,
        java.util.Set<String> ownedBones) {
        for (software.bernie.geckolib3.core.keyframe.BoneAnimation incoming : source) {
            int hit = -1;
            for (int i = 0; i < target.size(); i++) {
                if (target.get(i).boneName.equals(incoming.boneName)) {
                    hit = i;
                    break;
                }
            }
            if (hit < 0) {
                // Not merged into anything yet, so the shared object is safe to reference.
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

    /** 具名并行槽位的备用池控制器名：{@code (player.)?<族>_extra_<序号>_controller}。 */
    private static final java.util.regex.Pattern PARALLEL_EXTRA_CONTROLLER =
        java.util.regex.Pattern.compile("^(?:player\\.)?(pre_parallel|parallel)_extra_(\\d+)_controller$");

    private static List<ControllerMatch> resolveControllers(ControllerSet set, ResourceLocation animationId,
        String geckoControllerName) {
        List<ControllerMatch> matches = new ArrayList<>();
        // 具名并行槽位的备用池必须先分流：它的名字也带 pre_parallel_/parallel_ 前缀，
        // 落到下面的数字槽位解析会得到一个 -1 然后什么都不匹配。
        NamedParallelRoute namedRoute = routeNamedParallel(animationId, geckoControllerName);
        if (namedRoute != null) {
            if (namedRoute.controllerKey != null) {
                addMatch(matches, set, namedRoute.controllerKey);
            }
            return matches;
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
        if (geckoControllerName.endsWith("_controller")) {
            addMatch(matches, set, geckoControllerName.substring(0, geckoControllerName.length() - 11));
        }
        // 模糊匹配：player.post_main → player.post_main_<anything>
        // 用于车辆动画等带后缀的槽位控制器
        if (geckoControllerName.endsWith("_main") || geckoControllerName.endsWith("_hold")
            || geckoControllerName.endsWith("_swing") || geckoControllerName.endsWith("_use")) {
            String prefix = geckoControllerName + "_";
            for (String key : set.controllers.keySet()) {
                if (key.startsWith(prefix)) {
                    addMatch(matches, set, key);
                }
            }
        }
        return matches;
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
        double enteredTick;
        boolean lastSwingActive;
        /** Regular HashMap is safe: all RuntimeState access is on the client render thread. */
        final Map<String, Double> variables = new java.util.HashMap<>();
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

    private static final class ControllerMatch {
        private final Controller controller;

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
