package com.fox.ysmu.client.animation;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.condition.*;
import com.fox.ysmu.client.animation.controller.OpenYsmAnimationControllerRegistry;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.compat.BackhandCompat;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.util.ControllerUtils;
import com.fox.ysmu.ysmu;

import com.google.common.collect.Lists;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

public final class AnimationManager {

    private static AnimationManager MANAGER;
    /** True when the main controller body animation is handled by the legacy system
        (no player.main OpenYSM controller match). */
    public static volatile boolean legacyBodyActive = false;
    /** Current wheel animation name set by the wheel GUI, null when none active.
        Used for client-side playback without waiting for EEP server sync. */
    public static volatile String currentWheelAnim = null;
    /** Incremented each time the wheel animation is (re)set, so predicateCap can
        detect user interaction and force keyframe reset on the cap controller. */
    private static volatile int wheelAnimVersion = 0;
    private static int lastWheelAnimVersion = 0;
    /**
     * 从模型的 .molang 函数文件（如 @player_ctrl_pre_main.molang）中提取的
     * ctrl.<state> → 动画名 映射。key=模型 ResourceLocation, value=state→animName。
     * 当模型提供了 .molang 主动画控制器时，传统谓词系统优先使用这里的映射名，
     * 而不是直接使用标准英文名（如 walk→正常_行走）。
     */
    public static final Map<ResourceLocation, Map<String, String>> MOLANG_STATE_MAP = new ConcurrentHashMap<>();
    /**
     * 有条件分支的动画映射，如 v.show_car 时的开车动画。
     * key=模型 ResourceLocation, value=state→[(condition, animName), ...]。
     * 在 getMolangMappedAnimation() 中检查这些条件，若满足则使用替代动画。
     */
    public static final Map<ResourceLocation, Map<String, List<org.apache.commons.lang3.tuple.Pair<String, String>>>>
        MOLANG_CONDITIONAL_MAP = new ConcurrentHashMap<>();
    /**
     * .molang 动画控制脚本里的 {@code ctrl.set_beginning_transition_length(秒)}：
     * key=模型, value=动画名→过渡 tick 数。脚本没写就保持控制器的默认过渡。
     */
    public static final Map<ResourceLocation, Map<String, Double>> MOLANG_TRANSITION_MAP = new ConcurrentHashMap<>();
    /**
     * .molang 动画控制脚本里声明了 {@code ctrl.indicate_reload} 的动画名：
     * 即使目标动画与当前相同也要重新加载（YSMU 侧 = {@code markNeedsReload()}）。
     */
    public static final Map<ResourceLocation, java.util.Set<String>> MOLANG_RELOAD_MAP = new ConcurrentHashMap<>();
    private final Int2ObjectOpenHashMap<LinkedList<AnimationState>> data = new Int2ObjectOpenHashMap<>();
    private final Map<UUID, Integer> swingProgressByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> useDurationByPlayer = new ConcurrentHashMap<>();
    /** Tracks the last held item hash to detect item changes for animation reload. */
    private final Map<UUID, Integer> lastMainhandItemHash = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastOffhandItemHash = new ConcurrentHashMap<>();
    /** Tracks previous riding state to detect dismount transitions. */
    private final Map<UUID, Boolean> wasRiding = new ConcurrentHashMap<>();
    /** Non-null entry means player is in dismount; value is the animation being played. */
    private final Map<UUID, String> dismountAnim = new ConcurrentHashMap<>();
    /**
     * Battlegear2 格挡尾迹 tick 计数器。
     * Battlegear2 的 shield flag (battlegear2$isShielding) 只持续 1-2 tick，
     * 导致 use_controller 动画在播到格挡姿态前就被中断。
     * 此计数器在格挡结束后让 use_controller 多保持一段时间（默认 10 tick），
     * 让动画有时间播过初始帧进入盾牌格挡姿态。
     */
    private final Map<UUID, Integer> blockingTailTicks = new ConcurrentHashMap<>();
    /** Remaining ticks to suppress other controllers during dismount. */
    private final Map<UUID, Integer> dismountTimer = new ConcurrentHashMap<>();
    /** Tracks last logged animation name per animId, to avoid spamming [YSMU-ANIM] log every frame. */
    private final Map<ResourceLocation, String> lastLoggedAnim = new ConcurrentHashMap<>();
    // --- 硬编码的攻击组合动画已被注释掉 (2025-06-26) ---
    // 原 ATTACK_COMBO = {"attack_1", "attack_2", "attack_3"}
    // 原 ATTACK_COMBO_IDLE = {"attack_idle_1", "attack_idle_2", "attack_idle_3"}
    // 这些硬编码的动画名大多数模型并不存在，会导致:
    // - 攻击变为空动画 (bind pose / A-pose)
    // - cap 控制器卡死在非存在动画上，后续 cap 动画异常
    // - 肢体在某些动作后完全无动画
    // private final Map<UUID, Integer> swingCombo = new ConcurrentHashMap<>();
    // private final Map<UUID, Double> swingComboStartTick = new ConcurrentHashMap<>();
    // private final Map<UUID, Boolean> comboIsIdle = new ConcurrentHashMap<>();
    // private static final String[] ATTACK_COMBO = {"attack_1", "attack_2", "attack_3"};
    // private static final String[] ATTACK_COMBO_IDLE = {"attack_idle_1", "attack_idle_2", "attack_idle_3"};

    public void resetPlayerState(UUID playerId) {
        swingProgressByPlayer.remove(playerId);
        useDurationByPlayer.remove(playerId);
        lastMainhandItemHash.remove(playerId);
        lastOffhandItemHash.remove(playerId);
        dismountAnim.remove(playerId);
        dismountTimer.remove(playerId);
        wasRiding.remove(playerId);
    }

    public static AnimationManager getInstance() {
        if (MANAGER == null) {
            MANAGER = new AnimationManager();
        }
        return MANAGER;
    }

    public static void setCurrentWheelAnimName(String name) {
        currentWheelAnim = name;
        wheelAnimVersion++; // signal predicateCap to reset keyframes
    }

    public static String getCurrentWheelAnimName() {
        return currentWheelAnim;
    }

    /** Sentinel key used in {@link #lastLoggedAnim} when the animId is null. */
    private static final ResourceLocation NULL_ANIM_KEY = new ResourceLocation("ysmu", "__null_anim__");

    /** Only log [YSMU-ANIM] when the animation (or animId) actually changes, to avoid per-frame spam. */
    private void logAnimChange(ResourceLocation animId, String message) {
        if (!Config.DEBUG_ANIMATION) return;
        ResourceLocation key = animId != null ? animId : NULL_ANIM_KEY;
        String prev = lastLoggedAnim.get(key);
        if (prev == null || !prev.equals(message)) {
            lastLoggedAnim.put(key, message);
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ANIM] {}", message);
        }
    }

    /**
     * 检查动画是否有实际的骨骼关键帧数据。
     * 某些高版本 YSM 模型会在 main.animation.json 中包含空桩动画
     * （只有 "loop": true，没有 "bones" 数据），这些动画播放时不会产生
     * 任何骨骼变换，导致模型显示为绑定姿势/A-pose。
     */
    private static boolean isAnimationNonEmpty(Animation anim) {
        return anim != null && anim.boneAnimations != null && !anim.boneAnimations.isEmpty();
    }

    /**
     * 当模型提供了 .molang 函数文件（如 @player_ctrl_main.molang）时，
     * 从中提取 ctrl.<state> → 动画名 映射。传统谓词系统应优先使用映射名。
     * 同时会检查有条件分支的替代动画（如 v.show_car → 开车动画）。
     *
     * @param animId      模型的动画 ResourceLocation
     * @param stateName   标准谓词状态名（如 "walk"、"idle"）
     * @param event       当前求值事件；条件替代动画要靠它（玩家状态/模型上下文）才能求值
     * @return 映射的动画名（如 "正常_行走"），若无可返回 null
     */
    @Nullable
    private static String getMolangMappedAnimation(ResourceLocation animId, String stateName,
        AnimationEvent<CustomPlayerEntity> event) {
        if (animId == null) return null;
        // 1) 检查有条件分支的替代动画（脚本顺序 = 优先级）
        Map<String, List<org.apache.commons.lang3.tuple.Pair<String, String>>> condMap =
            MOLANG_CONDITIONAL_MAP.get(animId);
        if (condMap != null) {
            String alternative = pickConditionalAnimation(
                condMap.get(stateName), condition -> evaluateSimpleCondition(condition, event));
            if (alternative != null) {
                // 条件分支只有求值真的成功才可能命中，所以这条一次性日志顺带证明了
                // "复杂条件求值"这条链路在实机里是通的（每个 模型×状态 只打一条）。
                if (Config.DEBUG_CONTROLLER && LOGGED_MOLANG_HINTS.add(animId + "|cond|" + stateName)) {
                    com.fox.ysmu.ysmu.LOG.info("[YSMU-MOLANG] {} state '{}' -> '{}' (conditional branch)",
                        animId, stateName, alternative);
                }
                return alternative;
            }
        }
        // 2) 检查默认映射
        Map<String, String> mapping = MOLANG_STATE_MAP.get(animId);
        if (mapping == null) return null;
        return mapping.get(stateName);
    }

    /**
     * 条件替代动画的选取规则（纯函数，便于单测）：按脚本顺序取**第一个**条件成立的；
     * 一条都不成立返回 null，由调用方回落到默认映射 / 内置谓词。
     */
    static String pickConditionalAnimation(List<org.apache.commons.lang3.tuple.Pair<String, String>> alternatives,
        java.util.function.Predicate<String> conditionEval) {
        if (alternatives == null) {
            return null;
        }
        for (org.apache.commons.lang3.tuple.Pair<String, String> alternative : alternatives) {
            if (alternative.getKey() == null || alternative.getValue() == null) {
                continue;
            }
            if (conditionEval.test(alternative.getKey())) {
                return alternative.getValue();
            }
        }
        return null;
    }

    /**
     * 求值 .molang 函数文件中的条件表达式。
     * <p>
     * 纯 {@code v.<名字>} / {@code !v.<名字>} 走下面的快速路径（只查 PENDING_ROAMING，
     * 保持历史行为）；其余交给控制器求值器
     * （{@link com.fox.ysmu.client.animation.controller.OpenYsmControllerExpressionEvaluator}），
     * 因此 {@code !v.show_car&&!(ysm.food_level<=6)} 这类复合条件、以及
     * {@code (ctrl.walk && ysm.input_vertical < 0.1)} 这类复合守卫都能真正求值。
     * <p>
     * 求值失败/没有上下文仍然是 false —— 宁可不用替代动画，也不要凭空命中一条分支。
     */
    private static boolean evaluateSimpleCondition(String condition, AnimationEvent<CustomPlayerEntity> event) {
        if (StringUtils.isBlank(condition)) return true;
        String trimmed = condition.trim();
        if (isPureVariableCondition(trimmed)) {
            // 取反: !v.xxx
            if (trimmed.startsWith("!")) {
                return getMolangVariable(trimmed.substring(1).trim()) == 0;
            }
            // 正向: v.xxx
            return getMolangVariable(trimmed) != 0;
        }
        EntityPlayer player = event != null && event.getAnimatable() != null
            ? event.getAnimatable().getPlayer() : null;
        return com.fox.ysmu.client.animation.controller.OpenYsmControllerExpressionEvaluator
            .evaluateCondition(trimmed, player, event);
    }

    /** 只有 {@code v.<名字>} 或 {@code !v.<名字>}（没有运算符、没有括号、没有函数调用）。 */
    private static boolean isPureVariableCondition(String trimmed) {
        String body = trimmed.startsWith("!") ? trimmed.substring(1).trim() : trimmed;
        if (!body.startsWith("v.")) {
            return false;
        }
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '!';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** 控制脚本动态覆盖动画名的日志（每个 模型×槽位 只打一条，避免每帧刷屏）。 */
    private static final java.util.Set<String> LOGGED_CONTROL_SCRIPT = java.util.concurrent.ConcurrentHashMap
        .newKeySet();

    /**
     * GeckoLib 控制器名 → wiki 的槽位名（{@code @player_ctrl_<槽位>.molang} 里那个槽位）。
     *
     * <p>两种命名都要认：OpenYSM 槽位形如 {@code player.pre_main} / {@code player.parallel_6}
     * （wiki 的控制器名把 {@code .} 换成 {@code _ctrl_}），legacy 控制器形如
     * {@code main_controller} / {@code use_controller} / {@code parallel_6_controller}。
     * 认不出（例如具名并行备用池 {@code pre_parallel_extra_0_controller}）返回 null ——
     * 它的槽位名要问运行时路由结果，这里不猜；{@link #controlSlotFor} 会用
     * {@link OpenYsmPlayerControllerRuntime#namedParallelControlSlot} 把这块补上。</p>
     */
    @Nullable
    static String controlSlotName(@Nullable String geckoControllerName) {
        if (geckoControllerName == null || geckoControllerName.isEmpty()) {
            return null;
        }
        // 具名并行备用池（pre_parallel_extra_N_controller / parallel_extra_N_controller）是 YSMU
        // 的实现细节，模型里没有对应槽位名：要控制的是运行时路由到的**具名**槽位
        // （如 parallel_6），那个名字只有 OpenYsmPlayerControllerRuntime 知道，这里不猜。
        if (geckoControllerName.startsWith("pre_parallel_extra_")
            || geckoControllerName.startsWith("parallel_extra_")) {
            return null;
        }
        if (geckoControllerName.startsWith("player.")) {
            return geckoControllerName.substring("player.".length());
        }
        if (geckoControllerName.endsWith("_controller")) {
            return geckoControllerName.substring(0, geckoControllerName.length() - "_controller".length());
        }
        return null;
    }

    /**
     * 这一帧要交给动画控制脚本的槽位名：普通槽位由 {@link #controlSlotName} 从控制器名推导；
     * 具名并行备用池（{@code *_extra_N_controller}）的槽位名取决于**当前模型**的槽位表，
     * 只有 {@link OpenYsmPlayerControllerRuntime#namedParallelControlSlot} 知道，在这里补齐。
     * <p>
     * 不补的话，模型给具名并行槽位写的 {@code @player_ctrl_<槽位>.molang} 永远拿不到控制权：
     * 池控制器的谓词每帧先查脚本，查到的槽位名是 null，脚本阶段直接跳过。
     */
    @Nullable
    static String controlSlotFor(AnimationEvent<CustomPlayerEntity> event) {
        if (event == null || event.getController() == null) {
            return null;
        }
        String name = event.getController().getName();
        String slot = controlSlotName(name);
        if (slot != null) {
            return slot;
        }
        // 池名：解析它只为给动画控制脚本用。没开脚本、或当前模型根本没有脚本时，
        // applyControlScript 也会直接返回 null，所以省掉这次按模型的路由（绝大多数模型走这里）。
        if (!Config.MOLANG_CONTROL_SCRIPTS) {
            return null;
        }
        CustomPlayerEntity animatable = event.getAnimatable();
        ResourceLocation animId = animatable == null ? null : animatable.getAnimation();
        if (animId == null
            || !com.fox.ysmu.client.animation.molang.MolangScriptRegistry.hasScripts(animId)) {
            return null;
        }
        return OpenYsmPlayerControllerRuntime.namedParallelControlSlot(animId, name);
    }

    /**
     * 某个槽位的动画控制脚本本帧的决定 → {@code PlayState}；没有脚本/交回内置逻辑返回 null。
     *
     * <p>有决定时在这里就把动画播出去（走 {@link #playAnimation}，所以过渡时长、播放倍速、
     * 脚本里的循环类型都会生效），调用方直接返回该 {@code PlayState}。</p>
     */
    @Nullable
    static PlayState applyControlScript(AnimationEvent<CustomPlayerEntity> event, @Nullable String slot) {
        if (!Config.MOLANG_CONTROL_SCRIPTS || event == null || slot == null || event.getAnimatable() == null) {
            return null;
        }
        CustomPlayerEntity animatable = event.getAnimatable();
        ResourceLocation animId = animatable.getAnimation();
        if (animId == null
            || !com.fox.ysmu.client.animation.molang.MolangScriptRegistry.hasScripts(animId)) {
            // 绝大多数模型没有任何 .molang 脚本：一次 map 查询就退出，不做后面的动画表查询。
            return null;
        }
        AnimationFile animFile = GeckoLibCache.getInstance()
            .getAnimations()
            .get(animId);
        com.fox.ysmu.client.animation.molang.AnimationControlResult result =
            com.fox.ysmu.client.animation.molang.AnimationControlScripts.evaluate(animId, slot, () -> {
                EntityPlayer player = animatable.getPlayer();
                return player == null ? null : new com.fox.ysmu.client.animation.controller.OpenYsmScriptScope(
                    player, event, animId, java.util.Collections.emptyList());
            });
        if (result == null) {
            return null;
        }
        EntityPlayer player = animatable.getPlayer();
        if (player == null) {
            return null;
        }
        com.fox.ysmu.client.animation.molang.AnimationControlResult.LoopType scriptLoop = result.loopType();
        ControlScriptDecision decision = decideControlScript(result, animFile == null ? null : animFile.animations,
            null, animId, slot);
        if (decision == null) {
            return null;
        }
        if (decision.stop) {
            applyControlScriptStop(animId, slot, event, decision.reset);
            return PlayState.STOP;
        }
        if (decision.animationName == null) {
            return null;
        }
        ILoopType loopType = loopTypeOf(scriptLoop);
        return loopType == null ? playAnimation(event, decision.animationName)
            : playAnimation(event, decision.animationName, loopType);
    }

    /** 脚本给出的循环类型 → GeckoLib 的 loop 类型；没写（null）返回 null（用动画自带的）。 */
    @Nullable
    private static ILoopType loopTypeOf(@Nullable com.fox.ysmu.client.animation.molang.AnimationControlResult.LoopType type) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case LOOP:
                return ILoopType.EDefaultLoopTypes.LOOP;
            case PLAY_ONCE:
                return ILoopType.EDefaultLoopTypes.PLAY_ONCE;
            case HOLD_ON_LAST_FRAME:
                return ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME;
            default:
                return null;
        }
    }

    /** 控制脚本对本帧的决定：要播哪个动画、要不要中止当前动画、要不要重置控制器状态。 */
    static final class ControlScriptDecision {

        @Nullable
        final String animationName;
        /** {@code state_stop} / {@code ctrl.reset}：中止当前动画（{@code PlayState.STOP}）。 */
        final boolean stop;
        /** {@code ctrl.reset}：同时清掉该槽位的控制器运行时状态（回到初始状态）。 */
        final boolean reset;

        ControlScriptDecision(@Nullable String animationName, boolean stop, boolean reset) {
            this.animationName = animationName;
            this.stop = stop;
            this.reset = reset;
        }
    }

    /**
     * 控制脚本结果 → 本帧决定（纯逻辑，便于单测）；副作用只在
     * {@link #applyControlScriptStop} 里做。
     *
     * <p>规则：只在**明确的 {@code state_continue} + 具体动画名 + 该动画确实存在于模型里**时
     * 覆盖调用方的目标；{@code state_bypass}/{@code NONE}/脚本报错/动画不存在一律返回 null，
     * 让调用方沿用内置逻辑与静态映射 —— 开启这个功能不会把本来能动的模型弄坏。</p>
     */
    @Nullable
    static ControlScriptDecision decideControlScript(
        com.fox.ysmu.client.animation.molang.AnimationControlResult result,
        java.util.Map<String, ?> animations, @Nullable String fallbackName, ResourceLocation animId, String slot) {
        if (result == null) {
            return null;
        }
        com.fox.ysmu.client.animation.molang.AnimationControlResult.Action action = result.action();
        String name = result.animationName();
        if (animations != null && name != null && !animations.containsKey(name)) {
            // 脚本算出了一个模型里不存在的动画：不覆盖（否则看起来像"动画丢了"）。
            if (Config.DEBUG_CONTROLLER && LOGGED_CONTROL_SCRIPT.add(animId + "|" + slot + "|missing")) {
                com.fox.ysmu.ysmu.LOG.info(
                    "[YSMU-CTRLSCRIPT] {} slot '{}' set_animation('{}') but that animation is not in the file; keeping '{}'",
                    animId, slot, name, fallbackName);
            }
            return null;
        }
        if (action == com.fox.ysmu.client.animation.molang.AnimationControlResult.Action.PAUSE) {
            // GeckoLib 没有"暂停播放但不暂停时间轴"的原语：tick 由 AnimationProcessor 统一推进，
            // 骨骼关键帧与 timeline/音效/粒子事件都吃同一个 tick。先按"不动内置逻辑"处理。
            if (Config.DEBUG_CONTROLLER && LOGGED_CONTROL_SCRIPT.add(animId + "|" + slot + "|pause")) {
                com.fox.ysmu.ysmu.LOG.info(
                    "[YSMU-CTRLSCRIPT] {} slot '{}' requested state_pause — GeckoLib 无暂停原语，保持内置逻辑",
                    animId, slot);
            }
            return null;
        }
        if (result.reset()) {
            // wiki：ctrl.reset = 立刻重置控制器至初始状态、粗暴中止当前动画，并含 indicate_reload。
            if (Config.DEBUG_CONTROLLER && LOGGED_CONTROL_SCRIPT.add(animId + "|" + slot + "|reset")) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-CTRLSCRIPT] {} slot '{}' ctrl.reset -> 中止当前动画并重置控制器状态",
                    animId, slot);
            }
            return new ControlScriptDecision(null, true, true);
        }
        if (action == com.fox.ysmu.client.animation.molang.AnimationControlResult.Action.STOP) {
            // wiki 说的是"平滑地停止"，但 GeckoLib 的 STOP 会把骨骼队列直接清掉、由
            // AnimationProcessor 的 reset 分支还原初值，而那个分支当前是**瞬时**的
            // （resetTickLength 默认 1 且无人设置；rotation/position 的 mostRecentReset*Tick
            // 被写成 0，见那里的 TODO）。所以这里只能做到"中止"，"平滑"要改 vendored 才能做。
            if (Config.DEBUG_CONTROLLER && LOGGED_CONTROL_SCRIPT.add(animId + "|" + slot + "|stop")) {
                com.fox.ysmu.ysmu.LOG.info(
                    "[YSMU-CTRLSCRIPT] {} slot '{}' state_stop -> 中止当前动画（平滑淡出未实现）",
                    animId, slot);
            }
            return new ControlScriptDecision(null, true, false);
        }
        if (action != com.fox.ysmu.client.animation.molang.AnimationControlResult.Action.CONTINUE || name == null) {
            // bypass / NONE：脚本明确要求交回内置逻辑。
            return null;
        }
        if (!name.equals(fallbackName) && Config.DEBUG_CONTROLLER
            && LOGGED_CONTROL_SCRIPT.add(animId + "|" + slot + "|override|" + name)) {
            if (fallbackName == null) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-CTRLSCRIPT] {} slot '{}' -> '{}'", animId, slot, name);
            } else {
                com.fox.ysmu.ysmu.LOG.info(
                    "[YSMU-CTRLSCRIPT] {} slot '{}' -> '{}' (script overrides static mapping '{}')", animId, slot,
                    name, fallbackName);
            }
        }
        return new ControlScriptDecision(name, false, false);
    }

    /**
     * 应用 {@code ctrl.reset} / {@code state_stop}：中止当前动画，并（reset 时）清掉该槽位的
     * 控制器运行时状态，让内置逻辑从初始状态重新评估。
     */
    private static void applyControlScriptStop(ResourceLocation animId, String slot,
        AnimationEvent<CustomPlayerEntity> event, boolean reset) {
        if (event != null && event.getController() != null) {
            // wiki：ctrl.reset 包含 indicate_reload 的作用；state_stop 也一并重载，
            // 否则下次播同一个动画会"接着上次的进度"而不像重新开始。
            event.getController()
                .markNeedsReload();
        }
        if (!reset || animId == null || event == null) {
            return;
        }
        EntityPlayer player = event.getAnimatable() == null ? null : event.getAnimatable()
            .getPlayer();
        if (player != null) {
            // 这里只给得出 wiki 槽位名（如 parallel_6）；clearControllerState 内部会把
            // player. 前缀 / _controller 后缀归一化后再匹配，因此具名并行备用池承载的
            // 槽位（运行时键是 player.parallel_6 或短名）也能被 reset 到。
            com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime.clearControllerState(
                player.getUniqueID(), animId, "player." + slot);
        }
    }

    /** 从 PENDING_ROAMING 读取 Molang 变量的当前值 */
    private static double getMolangVariable(String varName) {
        if (StringUtils.isBlank(varName)) return 0;
        String roamingName = varName.startsWith("v.") ? varName.substring(2) : varName;
        Double val = OpenYsmPlayerControllerRuntime.PENDING_ROAMING.get(roamingName);
        // 也检查带 v. 前缀的
        if (val == null) {
            val = OpenYsmPlayerControllerRuntime.PENDING_ROAMING.get("v." + roamingName);
        }
        return val != null ? val : 0;
    }

    /** 播放倍速（stride × anim_speed）：legacy 控制器路径。
     *  在公共 playAnimation 入口调用，因此所有 legacy 控制器都会应用 anim_speed；
     *  防滑步仅对 main_controller 生效。GUI 预览（player==null）不干预，
     *  避免覆盖预览页的暂停/冻结倍速。计算逻辑见 MovementSpeedMatcher.applyPlaybackSpeed。 */
    private static void applyPlaybackSpeed(AnimationEvent<?> event, String animationName) {
        if (event == null || event.getController() == null) {
            return;
        }
        CustomPlayerEntity animatable = event.getAnimatable() instanceof CustomPlayerEntity
            ? (CustomPlayerEntity) event.getAnimatable() : null;
        EntityPlayer player = animatable != null ? animatable.getPlayer() : null;
        if (player == null) {
            return;
        }
        ResourceLocation animId = animatable.getAnimation();
        AnimationFile file = animId != null ? GeckoLibCache.getInstance().getAnimations().get(animId) : null;
        MovementSpeedMatcher.applyPlaybackSpeed(event.getController(), player, animationName,
            ControllerUtils.MAIN_CONTROLLER.equals(event.getController().getName()), file);
    }

    @NotNull
    private static <P extends IAnimatable> PlayState playLoopAnimation(AnimationEvent<P> event, String animationName) {
        return playAnimation(event, animationName, ILoopType.EDefaultLoopTypes.LOOP);
    }

    /** 播放 GUI 预览动画（focus/hover/hover_fadeout），使用 LOOP
     *  模型作者设计为每 1000 秒循环一次触发音效，保持原设计行为。 */
    @NotNull
    private static <P extends IAnimatable> PlayState playGuiPreviewAnimation(AnimationEvent<P> event, String animationName) {
        return playAnimation(event, animationName, ILoopType.EDefaultLoopTypes.LOOP);
    }

    @NotNull
    private static <P extends IAnimatable> PlayState playAnimation(AnimationEvent<P> event, String animationName,
        ILoopType loopType) {
        // 播放倍速（stride × anim_speed）：所有 legacy 控制器共用入口统一生效；
        // 预览（player==null）在 applyPlaybackSpeed 内跳过，不覆盖预览冻结。
        applyPlaybackSpeed(event, animationName);
        applyMolangPlaybackHints(event, animationName);
        if (animationName != null && (animationName.equals("gui") || animationName.startsWith("extra"))) {
            EntityPlayer p = event.getAnimatable() instanceof CustomPlayerEntity
                ? ((CustomPlayerEntity) event.getAnimatable()).getPlayer() : null;
            if (p != null && Config.DEBUG_ANIMATION) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-ANIM] in-game playing '{}' on controller '{}'",
                    animationName, event.getController().getName());
            }
        }
        event.getController()
            .setAnimation(new AnimationBuilder().addAnimation(animationName, loopType));
        return PlayState.CONTINUE;
    }

    @NotNull
    private static <P extends IAnimatable> PlayState playAnimation(AnimationEvent<P> event, String animationName) {
        applyPlaybackSpeed(event, animationName);
        applyMolangPlaybackHints(event, animationName);
        event.getController()
            .setAnimation(new AnimationBuilder().addAnimation(animationName));
        return PlayState.CONTINUE;
    }

    /**
     * 应用 .molang 动画控制脚本里 {@code ctrl.set_animation(...)} 前后的提示：
     * <ul>
     *   <li>{@code ctrl.set_beginning_transition_length(秒)} —— 覆盖这次切换的过渡时长；</li>
     *   <li>{@code ctrl.indicate_reload} —— 目标动画与当前相同时也要重新加载。</li>
     * </ul>
     * 映射只对声明了 .molang 控制脚本的模型存在，其他模型两次 map 查询即返回。
     */
    private static <P extends IAnimatable> void applyMolangPlaybackHints(AnimationEvent<P> event,
        String animationName) {
        if (event == null || event.getController() == null || animationName == null) return;
        if (!(event.getAnimatable() instanceof CustomPlayerEntity animatable)) return;
        ResourceLocation animId = animatable.getAnimation();
        if (animId == null) return;
        Map<String, Double> transitions = MOLANG_TRANSITION_MAP.get(animId);
        if (transitions != null) {
            double defaultTicks = ControllerUtils.MAIN_CONTROLLER.equals(event.getController().getName())
                ? Config.ANIMATION_TRANSITION_TICKS : 0.0d;
            Double ticks = transitions.get(animationName);
            Double resolved = resolveMolangTransition(transitions, animationName, defaultTicks);
            if (resolved != null) {
                event.getController().transitionLengthTicks = resolved;
            }
            // 过渡时长只靠肉眼很难确认，DebugController 下为每个 (模型, 动画) 打一条一次性日志，
            // 至少能证明脚本提取与注入这一整条链路是通的。
            if (Config.DEBUG_CONTROLLER && ticks != null
                && LOGGED_MOLANG_HINTS.add(animId + "|" + animationName)) {
                com.fox.ysmu.ysmu.LOG.info(
                    "[YSMU-MOLANG] {} transition {} ticks (from .molang, default {}) for '{}'", animId, ticks,
                    defaultTicks, animationName);
            }
        }
        java.util.Set<String> reloads = MOLANG_RELOAD_MAP.get(animId);
        if (reloads != null && reloads.contains(animationName)) {
            event.getController()
                .markNeedsReload();
            if (Config.DEBUG_CONTROLLER && LOGGED_MOLANG_HINTS.add(animId + "|reload|" + animationName)) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-MOLANG] {} indicate_reload for '{}'", animId, animationName);
            }
        }
    }

    /**
     * .molang 过渡时长的取值规则（纯函数，便于测试）：
     * 模型没有该过渡映射 → {@code null}（不要动控制器默认值）；该动画在脚本里写了值 → 用它；
     * 脚本没写这段动画 → 回控制器默认值，避免上一段动画的自定义值泄漏过来。
     */
    static Double resolveMolangTransition(Map<String, Double> transitions, String animationName, double defaultTicks) {
        if (transitions == null) {
            return null;
        }
        Double scripted = transitions.get(animationName);
        return scripted != null ? scripted : defaultTicks;
    }

    /** 一次性日志去重（DEBUG_CONTROLLER 下的 .molang 提示），换模型/重载时随缓存一起清。 */
    private static final java.util.Set<String> LOGGED_MOLANG_HINTS =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /** 清空 .molang 提示日志的去重表（模型缓存全量清理时调用）。 */
    public static void clearMolangHintLog() {
        LOGGED_MOLANG_HINTS.clear();
    }

    /**
     * 播放盾牌/剑格挡动画：将 use_mainhand/use_offhand 动画长度设为 2.0s，
     * 配合 HOLD_ON_LAST_FRAME 让动画自然停在格挡姿态。
     */
    private static PlayState playBlockingAnimation(AnimationEvent<CustomPlayerEntity> event,
        String fallbackAnim) {
        ResourceLocation animId = getAnimationId(event);
        if (animId != null) {
            software.bernie.geckolib3.file.AnimationFile f = software.bernie.geckolib3.resource.GeckoLibCache.getInstance()
                .getAnimations().get(animId);
            if (f != null) {
                software.bernie.geckolib3.core.builder.Animation a = f.getAnimation(fallbackAnim);
                if (a != null) {
                    a.animationLength = 2.0;
                }
            }
        }
        return playAnimation(event, fallbackAnim, ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME);
    }

    private static boolean animationExistsInFile(ResourceLocation animId, String animationName) {
        if (animId == null || animationName == null) {
            return false;
        }
        AnimationFile file = GeckoLibCache.getInstance().getAnimations().get(animId);
        return file != null && file.animations.containsKey(animationName);
    }

    private static ResourceLocation getAnimationId(AnimationEvent<CustomPlayerEntity> event) {
        return event.getAnimatable()
            .getAnimation();
    }

    /** {@code pre_parallel_N_controller} / {@code parallel_N_controller} 都算并行槽位。 */
    private static boolean isParallelSlotName(String geckoControllerName) {
        return geckoControllerName != null && (geckoControllerName.startsWith("pre_parallel_")
            || geckoControllerName.startsWith("parallel_"));
    }

    public void register(AnimationState state) {
        if (data.containsKey(state.getPriority())) {
            data.get(state.getPriority())
                .add(state);
        } else {
            LinkedList<AnimationState> states = Lists.newLinkedList();
            states.add(state);
            data.put(state.getPriority(), states);
        }
    }

    public PlayState predicateParallel(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        if (Minecraft.getMinecraft()
            .isGamePaused()) {
            return PlayState.STOP;
        }
        CustomPlayerEntity animatable = event.getAnimatable();
        ResourceLocation animId = animatable != null ? animatable.getAnimation() : null;
        String geckoName = event.getController().getName();
        // 动画控制脚本（@player_ctrl_<槽位>.molang）优先：它明确接管这一帧才跳过内置逻辑。
        PlayState scriptState = applyControlScript(event, controlSlotName(geckoName));
        if (scriptState != null) {
            return scriptState;
        }
        if (animId != null && OpenYsmPlayerControllerRuntime.hasAnyController(animId)) {
            // 下马期间抑制 parallel 控制器，让 dismount 动画不受覆盖
            if (geckoName != null && geckoName.startsWith("parallel_")) {
                EntityPlayer player = animatable != null ? animatable.getPlayer() : null;
                if (player != null) {
                    if (dismountAnim.containsKey(player.getUniqueID())) {
                        return PlayState.STOP;
                    }
                }
            }
            PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
            if (controllerState != null) {
                // 梯子上跳过 parallel 控制器结果，防止 climbing_start 等动画的
                // Root rotation [90,0,0] 覆盖主控制器的梯子姿态导致模型平躺过渡
                if (geckoName != null && geckoName.startsWith("parallel_")) {
                    EntityPlayer player = animatable != null ? animatable.getPlayer() : null;
                    if (player != null && player.isOnLadder()) {
                        return PlayState.STOP;
                    }
                }
                return controllerState;
            }
            // No matching OpenYSM controller. Only fall back to the raw legacy
            // animation when the model declares NO parallel controller at all.
            // A model that ships parallel controllers drives those animations
            // itself, frequently merging several raw pre_parallelN animations
            // into one state (a player.pre_parallel_0 plays
            // pre_parallel1..7). Replaying the raw animation on this per-slot
            // controller duplicates it on a second controller whose clock never
            // advances, and because this controller is processed after the
            // merged one it overwrites it — that pinned the merged state's
            // one-shot effect bones to their tick-0 pose whatever the merged
            // animation produced.
            // This is safe because OpenYsmAnimationControllerRegistry gives every
            // pre_parallelN/parallelN animation the model defines its own implicit
            // controller (mirroring OpenYSM's ParallelProcessor), so a model that
            // owns pre_parallelN — e.g. one that only declares player.parallel_0..7
            // and drives v.roaming.* visibility from pre_parallel3/6/7 — still plays
            // those animations through the OpenYSM runtime above.
            // Skip if the animation doesn't exist in the model's file to avoid
            // GeckoLib's System.out.printf spam ("Could not load animation: ...").
            // 槽位名的日志两种情况都要覆盖：pre_parallel_N 也是并行槽位，只认 "parallel_" 前缀时
            // pre_parallel_* 的回退/抑制完全没有日志（排查"并行/时间轴不生效"时就看不到任何线索）。
            boolean parallelSlot = isParallelSlotName(geckoName);
            if (!com.fox.ysmu.client.animation.controller.OpenYsmAnimationControllerRegistry
                .hasParallelController(animId) && animationExistsInFile(animId, animationName)) {
                if (Config.DEBUG_CONTROLLER && parallelSlot) {
                    ysmu.LOG.info("[YSMU-PAR] {} fallback -> playLoopAnimation('{}')", geckoName, animationName);
                }
                return playLoopAnimation(event, animationName);
            }
            if (Config.DEBUG_CONTROLLER && parallelSlot) {
                // 这个分支还包含"动画文件里还没有这条动画"（懒加载/惰性重载途中）——
                // 以前它是完全静默的 STOP，正是本轮排查卡住的地方。
                ysmu.LOG.info("[YSMU-PAR] {} suppressed (no such animation in the loaded file: '{}')", geckoName,
                    animationName);
            }
            return PlayState.STOP;
        }
        if (animationExistsInFile(animId, animationName)) {
            return playLoopAnimation(event, animationName);
        }
        return PlayState.STOP;
    }

    public PlayState predicateOpenYsmSlot(AnimationEvent<CustomPlayerEntity> event) {
        if (Minecraft.getMinecraft()
            .isGamePaused()) {
            return PlayState.STOP;
        }
        // 下马期间抑制所有 OpenYSM 槽位控制器
        CustomPlayerEntity animatable = event.getAnimatable();
        if (animatable != null) {
            EntityPlayer player = animatable.getPlayer();
            if (player != null) {
                if (dismountAnim.containsKey(player.getUniqueID())) {
                    return PlayState.STOP;
                }
            }
        }
        // 动画控制脚本优先（pre_main / post_main / pre_hold / pre_swing / pre_use …）。
        PlayState scriptState = applyControlScript(event, controlSlotFor(event));
        if (scriptState != null) {
            return scriptState;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        return controllerState == null ? PlayState.STOP : controllerState;
    }

    /** Tracks whether predicateCap was playing an animation last frame.
     *  When it transitions from CONTINUE to STOP, we clean up cap sounds. */
    private static boolean capWasPlaying = false;

    public PlayState predicateCap(AnimationEvent<CustomPlayerEntity> event) {
        CustomPlayerEntity animatable = event.getAnimatable();
        EntityPlayer player = animatable.getPlayer();
        if (player == null) {
            // GUI preview render path (e.g. model selection GUI, player icon).
            // The preview entity has no real player, and its controller shares
            // the same name as the main player's cap_controller.  Do NOT call
            // stopController here — that would yank the main player's sound
            // mapping every frame.
            if (!animatable.areGuiAnimationsEnabled()) {
                return PlayState.STOP;
            }
            if (animatable.hasPreviewAnimation()) {
                // 控制器从 STOP → PLAY 时强制重载，使动画从 tick 0 重新开始，
                // 这样音效关键帧每次选中模型时都会重新触发，但不会循环重复。
                if (event.getController().getAnimationState() == software.bernie.geckolib3.core.AnimationState.Stopped) {
                    event.getController().markNeedsReload();
                }
                // GUI 预览动画（focus/hover）使用 HOLD_ON_LAST_FRAME，
                // 动画播放到最后一帧后保持，音效关键帧只触发一次。
                return playGuiPreviewAnimation(event, animatable.getPreviewAnimation());
            }
            return PlayState.STOP;
        }
        if (dismountAnim.containsKey(player.getUniqueID())) {
            if (capWasPlaying) {
                capWasPlaying = false;
                com.fox.ysmu.client.audio.YSMSoundManager.stopController(event.getController().getName());
            }
            return PlayState.STOP;
        }
        // Force keyframe reset on the cap controller whenever the user clicks
        // the wheel (version increments in setCurrentWheelAnimName).  This must
        // run before both the wheel-lock and EEP paths so that sound keyframes
        // fire again on replay regardless of which code path handles playback.
        if (lastWheelAnimVersion != wheelAnimVersion) {
            event.getController().currentAnimationBuilder = new AnimationBuilder();
            lastWheelAnimVersion = wheelAnimVersion;
        }

        // extra轮盘动画重载 — 客户端本地 wheel 动画名（仅在 lock 开启时生效）
        if (OpenYsmPlayerControllerRuntime.PENDING_ROAMING.getOrDefault("lock_wheel", 0.0) > 0
            && OpenYsmPlayerControllerRuntime.PENDING_ROAMING.getOrDefault("wheel_anim", 0.0) > 0) {
            String wheelAnimName = getCurrentWheelAnimName();
            if (wheelAnimName != null) {
                capWasPlaying = true;
                return playAnimation(event, wheelAnimName);
            }
        }
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep != null && eep.isPlayAnimation()) {
            String anim = eep.getAnimation();
            // When a PLAY_ONCE animation finishes naturally (controller
            // transitions to Stopped), clean up to prevent infinite restart.
            // EEP animations are expected to play once and only once — the
            // timeline events (e.g. toggling model states) should fire only
            // on that single playthrough.
            if (capWasPlaying && event.getController().getAnimationState()
                == software.bernie.geckolib3.core.AnimationState.Stopped) {
                eep.stopAnimation();
                capWasPlaying = false;
                com.fox.ysmu.client.audio.YSMSoundManager.stopController(event.getController().getName());
                return PlayState.STOP;
            }
            // Without the wheel lock, walking/running overrides the wheel
            // animation on the main controller.  Stop the cap controller's
            // EEP animation when the player moves.
            if (capWasPlaying && OpenYsmPlayerControllerRuntime.PENDING_ROAMING.getOrDefault("lock_wheel", 0.0) == 0
                && (event.isMoving() || !player.onGround)) {
                eep.stopAnimation();
                capWasPlaying = false;
                com.fox.ysmu.client.audio.YSMSoundManager.stopController(event.getController().getName());
                return PlayState.STOP;
            }
            if ("extra1".equals(anim)) anim = "extra1";
            else if ("extra2".equals(anim)) anim = "extra2";
            else if ("extra3".equals(anim)) anim = "extra3";
            capWasPlaying = true;
            return playAnimation(event, anim);
        }
        // --- 硬编码的攻击组合动画已被注释掉 (2025-06-26) ---
        // 原逻辑：通过 cap 控制器播放 ATTACK_COMBO / ATTACK_COMBO_IDLE 序列。
        // 问题：这些硬编码动画名大多数模型不存在，导致 cap 控制器卡死、动画异常。
        // Integer combo = swingCombo.get(player.getUniqueID());
        // if (combo != null) { ... }
        // No animation matches → cap controller stops → clean up sounds.
        if (capWasPlaying) {
            capWasPlaying = false;
            com.fox.ysmu.client.audio.YSMSoundManager.stopController(event.getController().getName());
        }
        return PlayState.STOP;
    }

    @NotNull
    public PlayState predicateMain(AnimationEvent<CustomPlayerEntity> event) {
        CustomPlayerEntity animatable = event.getAnimatable();
        EntityPlayer player = animatable.getPlayer();
        if (player == null) {
            // GUI preview context: play the model's preview_animation as the
            // base animation from the main controller. The cap_controller may
            // additionally play hover/focus as an overlay (they blend).
            // When GUI_ENHANCEMENTS is disabled, return STOP immediately.
            if (!animatable.areGuiAnimationsEnabled()) {
                return PlayState.STOP;
            }
            // Try guiBaseAnimation first (set by ModelButton from PREVIEW_ANIMATION).
            String baseAnim = null;
            if (animatable.hasGuiBaseAnimation()) {
                baseAnim = animatable.getGuiBaseAnimation();
            }
            // Fallback: look up PREVIEW_ANIMATION from the model registry.
            if (baseAnim == null || baseAnim.isEmpty()) {
                ResourceLocation mainModel = animatable.getMainModel();
                if (mainModel != null) {
                    baseAnim = ClientModelManager.PREVIEW_ANIMATION.get(mainModel);
                    // Also try the raw model ID (without /main suffix)
                    if ((baseAnim == null || baseAnim.isEmpty()) && mainModel.getResourcePath().endsWith("/main")) {
                        ResourceLocation rawId = new ResourceLocation(mainModel.getResourceDomain(),
                            mainModel.getResourcePath().substring(0, mainModel.getResourcePath().length() - 5));
                        baseAnim = ClientModelManager.PREVIEW_ANIMATION.get(rawId);
                    }
                }
            }
            if (baseAnim != null && !baseAnim.isEmpty()) {
                return playLoopAnimation(event, baseAnim);
            }
            // Final fallback: try "gui" first (models without an explicit
            // previewAnimation in ysm.json still usually define a "gui"
            // animation), then "idle".
            ResourceLocation mainModel = animatable.getMainModel();
            if (mainModel != null) {
                AnimationFile file = GeckoLibCache.getInstance().getAnimations().get(mainModel);
                if (file != null) {
                    if (file.getAnimation("gui") != null) {
                        return playLoopAnimation(event, "gui");
                    }
                    if (file.getAnimation("idle") != null) {
                        return playLoopAnimation(event, "idle");
                    }
                }
            }
            return PlayState.STOP;
        }
        // 防滑步（stride matching）：每帧先把 main 控制器倍速重置为 1.0。
        // 后续 legacy 状态循环选中 walk/run/sneak 等移动动画时会按真实速度
        // 覆盖此值；idle/fallback/STOP 等其余路径保持 1.0，避免上一次移动的
        // 倍速残留到待机动画上（如 idle 以 1.3x 播放）。
        event.getController().animationSpeed = 1.0d;
        // When wheel lock is active and a wheel animation is playing, force idle
        // to keep legs in a natural pose instead of T-pose. The cap_controller's
        // wheel animation overrides upper body bones.
        if (OpenYsmPlayerControllerRuntime.PENDING_ROAMING.getOrDefault("lock_wheel", 0.0) > 0
            && OpenYsmPlayerControllerRuntime.PENDING_ROAMING.getOrDefault("wheel_anim", 0.0) > 0) {
            return playLoopAnimation(event, "idle");
        }
        // 追踪骑乘状态变化用于下马检测
        UUID dismountId = player.getUniqueID();
        boolean currentlyRiding = player.isRiding();
        Boolean wasRidingPrev = wasRiding.put(dismountId, currentlyRiding);
        boolean justDismounted = Boolean.TRUE.equals(wasRidingPrev) && !currentlyRiding;

        // 下马状态管理：抑制其他控制器干扰，但不覆盖 MAIN 的动画选择
        String dismountAnimName = dismountAnim.get(dismountId);
        if (justDismounted || dismountAnimName != null) {
            Integer remaining = dismountTimer.get(dismountId);
            if (remaining != null && remaining <= 0) {
                dismountAnim.remove(dismountId);
                dismountTimer.remove(dismountId);
            } else {
                if (justDismounted) {
                    dismountAnim.put(dismountId, "");
                    dismountTimer.put(dismountId, 40);
                } else if (remaining != null) {
                    dismountTimer.put(dismountId, remaining - 1);
                }
                // 不下发 MAIN 覆盖，让 OpenYSM/legacy 自然选择动画
                // 仅靠 dismountAnim.containsKey 抑制其他控制器
            }
        }

        // player_ctrl_main 优先：动画控制脚本必须在 OpenYSM 控制器（tryApply）之前求值，
        // 否则模型自带 main 控制器一旦返回动画，脚本就永远拿不到控制权
        // （顺序与 use / pre_main 等槽位一致）。
        PlayState scriptState = applyControlScript(event, "main");
        if (scriptState != null) {
            legacyBodyActive = false;
            return scriptState;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            legacyBodyActive = false;
            return controllerState;
        }
        legacyBodyActive = true;
        ResourceLocation animId = getAnimationId(event);
        AnimationFile animFile = animId == null ? null
            : GeckoLibCache.getInstance().getAnimations().get(animId);
        // OpenYSM 模型：潜行动画可能由 player.pre_main 控制器负责
        // （例如把移动潜行接到 行走/后退1、站立潜行接到 sneaking_Control）。
        // main_controller 的 legacy 潜行（sneak/sneaking）会与 pre_main 同时
        // 播放并覆盖其 Root 位移（sneaking 的 Root [0,-7.625,0] 覆盖行走的
        // [0,3,0]），导致移动潜行显示成站立潜行蹲姿。
        // 因此仅当模型自身提供了身体控制器（player.pre_main / player.main /
        // player.base / player.move）时才跳过 legacy 的 sneak/sneaking 状态；
        // 只有 post_main/post_swing 等非身体控制器的 OpenYSM 模型
        // 没有自带的潜行处理，必须依赖 legacy 状态机播放
        // sneak/sneaking 动画，不能跳过。
        // 仅当模型身体控制器（pre_main/main/base/move）真正引用潜行逻辑
        // （sneak/sneaking 动画名或 ctrl.sneaking/ctrl.sneak/q.is_sneaking 条件）
        // 时才跳过 legacy 的 sneak/sneaking 状态。有的模型虽有 player.main 但只
        // 处理待机（无潜行状态），潜行时必须由 legacy 状态机播放 sneak/sneaking。
        boolean openYsmHandlesSneak = animId != null
            && OpenYsmAnimationControllerRegistry.hasControllerSneakHandling(animId,
                ControllerUtils.OPENYSM_PRE_MAIN_CONTROLLER, "player.main", "player.base", "player.move");
        for (int i = Priority.HIGHEST; i <= Priority.LOWEST; i++) {
            if (!data.containsKey(i)) {
                continue;
            }
            LinkedList<AnimationState> states = data.get(i);
            for (AnimationState state : states) {
                if (openYsmHandlesSneak) {
                    String legacyAnimName = state.getAnimationName();
                    if ("sneak".equals(legacyAnimName) || "sneaking".equals(legacyAnimName)) {
                        continue;
                    }
                }
                if (state.getPredicate().test(player, event)) {
                    String animationName = state.getAnimationName();
                    // 优先检查 molang 映射：当模型提供了 .molang 函数文件时，
                    // 使用映射的动画名（如 walk → 正常_行走）替代标准名
                    String mappedName = getMolangMappedAnimation(animId, animationName, event);
                    String targetName = mappedName != null ? mappedName : animationName;
                    // player_ctrl_main 已在 tryApply 之前统一求值（见本方法开头），
                    // 不再在这里重复求值，避免脚本每帧被跑两遍。
                    Animation anim = null;
                    if (animFile != null) {
                        anim = animFile.animations.get(targetName);
                    }
                    // Fallback to default model's animation file (e.g. "fly" not
                    // present in the current model but exists in the default model).
                    // Inject a shallow copy into the current model's file so GeckoLib's
                    // setAnimation finds it locally and never triggers the global
                    // GeckoLibCache-wide scan (which would pick up animations from
                    // unrelated models and cause cross-model bone name mismatch).
                    if (anim == null) {
                        Animation defaultAnim = com.fox.ysmu.client.ClientModelManager.DEFAULT_ANIMATION_FILE.animations.get(targetName);
                        if (defaultAnim != null && animFile != null) {
                            software.bernie.geckolib3.core.builder.Animation copy = new software.bernie.geckolib3.core.builder.Animation();
                            copy.animationLength = defaultAnim.animationLength;
                            copy.loop = defaultAnim.loop;
                            copy.animTimeUpdate = defaultAnim.animTimeUpdate;
                            copy.animSpeed = defaultAnim.animSpeed;
                            copy.boneAnimations = defaultAnim.boneAnimations;
                            animFile.animations.put(targetName, copy);
                            anim = copy;
                        }
                    }
                    if (anim != null) {
                        // 跳过空桩动画（loop:true 无 bones）
                        if (!isAnimationNonEmpty(anim)) {
                            continue;
                        }
                        ILoopType loopType = state.getLoopType();
                        logAnimChange(animId, "main_controller playing '" + targetName + "' (predicate '" + animationName + "') for " + animId);
                        return playAnimation(event, targetName, loopType);
                    }
                }
            }
        }
        // 所有优先级轮询完毕仍未找到有效动画 — 回退：
        // 1) 优先 idle（检查 molang 映射和直接名）
        // 2) idle 为空且模型显式定义了 idle（存在键），作为身份变换播放
        // 3) 最后兜底取其他非空动画
        if (animFile != null && !animFile.animations.isEmpty()) {
            // 优先尝试 idle
            String idleName = getMolangMappedAnimation(animId, "idle", event);
            if (idleName == null) idleName = "idle";
            Animation idleAnim = animFile.animations.get(idleName);
            if (isAnimationNonEmpty(idleAnim)) {
                logAnimChange(animId, "main_controller fallback idle '" + idleName + "' for " + animId);
                return playLoopAnimation(event, idleName);
            }
            // idle 存在但为空（loop:true 无 bones）— 模型设计者有意为之，
            // 期望主控制器保持身份变换，由 OpenYSM 并行动画负责显示。
            // 此时播放空 idle 比 fallback 到 swim/sleep 等状态依赖动画更正确。
            if (idleAnim != null) {
                logAnimChange(animId, "main_controller fallback idle (empty identity) '" + idleName + "' for " + animId);
                return playLoopAnimation(event, idleName);
            }
            // 遍历所有注册状态名，尝试从 molang 映射或直接名找非空动画
            // 注意：此循环不检查 predicate，仅看动画是否存在。
            // 因此需要跳过 death 状态依赖动画，避免 idle 为空时误播。
            // 如果遇到了模型一直在播放 sleep 睡觉动画 大概率就是这里触发的
            for (int i = Priority.HIGHEST; i <= Priority.LOWEST; i++) {
                LinkedList<AnimationState> states = data.get(i);
                if (states == null) continue;
                for (AnimationState state : states) {
                    String name = state.getAnimationName();
                    if ("death".equals(name)) continue;
                    String mapped = getMolangMappedAnimation(animId, name, event);
                    String target = mapped != null ? mapped : name;
                    Animation anim = animFile.animations.get(target);
                    if (isAnimationNonEmpty(anim)) {
                        logAnimChange(animId, "main_controller fallback '" + target + "' from state '" + name + "' for " + animId);
                        return playLoopAnimation(event, target);
                    }
                }
            }
            // 最后兜底：所有可用的回退状态（idle/run/walk）均为空动画。
            // 此时不应从动画文件中随机选取一个状态依赖动画（如 sleep/swim），
            // 那样会导致模型站立时播放错误姿态。返回 STOP 让主控制器停止，
            // 模型将保持绑定姿势，由 OpenYSM 并行动画控制器负责显示状态。
            logAnimChange(animId, "main_controller STOP for " + animId
                + " (openYsm=" + (animId != null ? OpenYsmPlayerControllerRuntime.hasAnyController(animId) : "?") + ")");
            return PlayState.STOP;
        }
        logAnimChange(animId, "main_controller STOP for " + animId
            + " (openYsm=" + (animId != null ? OpenYsmPlayerControllerRuntime.hasAnyController(animId) : "?") + ")");
        return PlayState.STOP;
    }

    public PlayState predicateOffhandHold(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        if (dismountAnim.containsKey(player.getUniqueID())) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }

        // 修改为使用BackhandCompat兼容层
        ItemStack offhandItem = BackhandCompat.getOffhandItem(player);
        if (offhandItem != null) {
            // 攻击/使用期间不暂停持握控制器。swing/use 控制器在之后处理，
            // 其骨骼动画会覆盖 hold 控制器的值。保持 Running 避免重复播放掏出动画。
            if (!checkSwingAndUse(player, false)) {
                return PlayState.CONTINUE;
            }
            // TiCon 十字弩已装填 → 显示蓄能待机动画
            if (com.fox.ysmu.compat.TinkersCrossbowCompat.isCrossbowLoaded(offhandItem)) {
                ResourceLocation animId = getAnimationId(event);
                if (animationExistsInFile(animId, "hold_offhand:charged_crossbow")) {
                    return playAnimation(event, "hold_offhand:charged_crossbow", ILoopType.EDefaultLoopTypes.LOOP);
                }
            }
            int hash = itemHash(offhandItem);
            Integer last = lastOffhandItemHash.put(player.getUniqueID(), hash);
            // Reload when item changes, coming from empty (-1), or coming from empty anim (0)
            if (last == null || last != hash || last == -1 || last == 0) {
                event.getController().markNeedsReload();
            }
            return playIfPresent(event, findHoldAnimation(event, player, false));
        } else {
            // 尝试空手持握动画 (hold_offhand:empty)
            String emptyAnim = findHoldAnimation(event, player, false);
            if (StringUtils.isNoneBlank(emptyAnim)) {
                Integer last = lastOffhandItemHash.put(player.getUniqueID(), 0); // 0 = 空手动画激活
                if (last == null || last != 0) {
                    event.getController().markNeedsReload();
                }
                return playAnimation(event, emptyAnim, ILoopType.EDefaultLoopTypes.LOOP);
            }
            lastOffhandItemHash.put(player.getUniqueID(), -1);
        }
        return PlayState.STOP;
    }

    public PlayState predicateMainhandHold(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        if (dismountAnim.containsKey(player.getUniqueID())) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        if (player.fishEntity != null) {
            return playAnimation(event, "hold_mainhand:fishing", ILoopType.EDefaultLoopTypes.LOOP);
        }

        if (player.getHeldItem() != null) {
            // 攻击/使用期间不暂停持握控制器。swing_controller/swing_controller
            // 在 hold_mainhand_controller 之后处理，其骨骼动画会覆盖 hold 控制器的值，
            // 所以视觉上挥动动画正常显示。保持 Running 状态可以避免动画结束后
            // setAnimation 因 Stopped 状态而重新从头播放掏出动画。
            if (!checkSwingAndUse(player, true)) {
                return PlayState.CONTINUE;
            }
            // TiCon 十字弩已装填 → 显示蓄能待机动画
            if (com.fox.ysmu.compat.TinkersCrossbowCompat.isCrossbowLoaded(player.getHeldItem())) {
                ResourceLocation animId = getAnimationId(event);
                if (animationExistsInFile(animId, "hold_mainhand:charged_crossbow")) {
                    return playAnimation(event, "hold_mainhand:charged_crossbow", ILoopType.EDefaultLoopTypes.LOOP);
                }
            }
            int hash = itemHash(player.getHeldItem());
            Integer last = lastMainhandItemHash.put(player.getUniqueID(), hash);
            // Reload when item changes, coming from empty (-1), or coming from empty anim (0)
            if (last == null || last != hash || last == -1 || last == 0) {
                event.getController().markNeedsReload();
            }
            String holdAnim = findHoldAnimation(event, player, true);
            if (com.fox.ysmu.Config.DEBUG_CONTROLLER && StringUtils.isNoneBlank(holdAnim)) {
                String loopStr = "?";
                ResourceLocation animId = getAnimationId(event);
                if (animId != null) {
                    software.bernie.geckolib3.file.AnimationFile f = software.bernie.geckolib3.resource.GeckoLibCache.getInstance().getAnimations().get(animId);
                    if (f != null) {
                        software.bernie.geckolib3.core.builder.Animation a = f.getAnimation(holdAnim);
                        if (a != null) {
                            loopStr = String.valueOf(a.loop);
                            // Force correct loop type for hold animations
                            a.loop = ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME;
                        }
                    }
                }
                com.fox.ysmu.ysmu.LOG.info("[YSMU-HOLD] model='{}' anim='{}' state={} loop={}",
                    animId, holdAnim,
                    event.getController().getAnimationState(),
                    loopStr + "->HOLD_ON_LAST_FRAME");
            } else if (StringUtils.isNoneBlank(holdAnim)) {
                // Always ensure hold animations have the correct loop type
                ResourceLocation animId = getAnimationId(event);
                if (animId != null) {
                    software.bernie.geckolib3.file.AnimationFile f = software.bernie.geckolib3.resource.GeckoLibCache.getInstance().getAnimations().get(animId);
                    if (f != null) {
                        software.bernie.geckolib3.core.builder.Animation a = f.getAnimation(holdAnim);
                        if (a != null) {
                            a.loop = ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME;
                        }
                    }
                }
            }
            return playIfPresent(event, holdAnim);
        } else {
            // 尝试空手持握动画 (hold_mainhand:empty)
            String emptyAnim = findHoldAnimation(event, player, true);
            if (StringUtils.isNoneBlank(emptyAnim)) {
                Integer last = lastMainhandItemHash.put(player.getUniqueID(), 0); // 0 = 空手动画激活
                if (last == null || last != 0) {
                    event.getController().markNeedsReload();
                }
                return playAnimation(event, emptyAnim, ILoopType.EDefaultLoopTypes.LOOP);
            }
            lastMainhandItemHash.put(player.getUniqueID(), -1);
        }
        com.fox.ysmu.client.audio.YSMSoundManager.stopController(event.getController().getName());
        return PlayState.STOP;
    }

    public PlayState predicateSwing(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        if (dismountAnim.containsKey(player.getUniqueID())) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        boolean openYsmProducedAnimation = controllerState != null;
        if (openYsmProducedAnimation) {
            return controllerState;
        }

        // 如果模型有 swing 相关的 OpenYSM 控制器（player.post_swing 等），
        // 则跳过 legacy 回退路径，避免 swing:sword/swing_hand（来自默认模型
        // 骨骼）与模型自身的攻击动画并行叠加导致骨骼变换冲突甚至模型消失。
        // 但需要清空 GeckoLib 的旧动画数据，防止"串动"。
        // 注意: 仅当 OpenYSM 控制器实际产生了动画时才跳过 legacy。
        // 如果控制器存在但没有任何可播放的动画（tryApply 返回 null），
        // 仍需回退到 legacy 系统播放默认挥动动画。
        ResourceLocation animId = getAnimationId(event);
        boolean modelHasOwnSwingCtrl = openYsmProducedAnimation
            && OpenYsmPlayerControllerRuntime.hasAnyController(animId)
            && (OpenYsmAnimationControllerRegistry.hasController(animId, "player.post_swing")
                || OpenYsmAnimationControllerRegistry.hasController(animId, "player.pre_swing")
                || OpenYsmAnimationControllerRegistry.hasController(animId, "player.swing")
                || OpenYsmAnimationControllerRegistry.hasController(animId, "swing"));

        UUID pid = player.getUniqueID();
        boolean nowSwinging = player.isSwingInProgress;

        if (!nowSwinging) {
            // Let the swing animation play to completion before stopping.
            // Without this, the animation is cut short as soon as the vanilla
            // swing progress ends (~0.5s), even when the animation itself is
            // much longer (e.g. swing:sword has animation_length=1.54s).
            // Always remove swingProgressByPlayer here so that a subsequent
            // click during the follow-through is detected as a new swing
            // (markSwingStart returns true when the key is absent).
            swingProgressByPlayer.remove(pid);
            if (event.getController().getAnimationState()
                == software.bernie.geckolib3.core.AnimationState.Stopped) {
                com.fox.ysmu.client.audio.YSMSoundManager.stopController(event.getController().getName());
                return PlayState.STOP;
            }
            // Still playing — keep going without interfering.
            return PlayState.CONTINUE;
        }
        if (!player.isPlayerSleeping()) {
            boolean newSwing = markSwingStart(player);
            if (newSwing || event.getController().getAnimationState() == software.bernie.geckolib3.core.AnimationState.Stopped) {
                event.getController().shouldResetTick = true;
                event.getController().markNeedsReload();
                event.getController()
                    .adjustTick(0);
            }
            // 模型有自己的 swing 控制器 → 检查是否需要跳过 legacy 回退路径。
            // 当 OpenYSM controller 仅处理剑/矛类攻击（v.swing_sword 仅在
            // ctrl.swing(':sword') 时被设置）时，非剑类物品（空手、斧头等）
            // 的挥动在 OpenYSM 侧无实际动画（default 状态播放 attack_empty
            // 空动画），需要让传统路径来提供 swing_hand/swing:axe 等。
            String conditionalAnimation = findSwingAnimation(event, player);
            if (modelHasOwnSwingCtrl) {
                // 只有剑/矛类的传统动画会与 OpenYSM 的攻击动画冲突
                if ("swing:sword".equals(conditionalAnimation)
                    || "swing:spear".equals(conditionalAnimation)) {
                    // 跳过 legacy 回退 —— OpenYSM 控制器会处理剑/矛攻击。
                    // 清空 currentAnimationBuilder 并返回 STOP，防止 GeckoLib
                    // 残留旧动画数据导致"串动"到下一次挥动或其他动作。
                    event.getController().currentAnimationBuilder = new AnimationBuilder();
                    com.fox.ysmu.client.audio.YSMSoundManager.stopController(event.getController().getName());
                    return PlayState.STOP;
                }
                // 非剑类（空手、斧头、镐等）→ 不走 OpenYSM 控制器，
                // 让传统回退路径（swing_hand / swing:axe 等）正常播放。
            }

            /*
            // 模型自定义 combo：检查是否有 Attackdown3/4/5 系列动画
            // 从 .molang 函数文件的 swing 控制器提取：v.attackStage 决定用哪个
            boolean hasCombo = animationExistsInFile(animId, "Attackdown3");
            if (hasCombo) {
                if (newSwing) {
                    Integer stage = swingComboStage.get(pid);
                    if (stage == null) stage = 0;
                    stage = (stage % 3) + 1; // 1→2→3→1 循环
                    swingComboStage.put(pid, stage);
                    // 新攻击阶段，触发 markNeedsReload 让 setAnimation 生效
                    event.getController().markNeedsReload();
                }
                Integer currentStage = swingComboStage.get(pid);
                if (currentStage != null && currentStage >= 1 && currentStage <= 3) {
                    String comboAnim = DEFAULT_COMBO_ANIMS[currentStage - 1];
                    if (animationExistsInFile(animId, comboAnim)) {
                        return playAnimation(event, comboAnim, ILoopType.EDefaultLoopTypes.LOOP);
                    }
                }
            }
            */

            if (StringUtils.isNoneBlank(conditionalAnimation)) {
                boolean exists = animationExistsInFile(animId, conditionalAnimation);
                if (Config.DEBUG_CONTROLLER) {
                    ysmu.LOG.info("[YSMU-SWING] legacy: conditionalAnimation='{}' exists={} modelHasOwnSwingCtrl={}",
                        conditionalAnimation, exists, modelHasOwnSwingCtrl);
                }
                if (exists) {
                    return playAnimation(event, conditionalAnimation, ILoopType.EDefaultLoopTypes.PLAY_ONCE);
                }
            }
            // Ensure swing_hand exists: check the current model's file first,
            // then fall back to the DEFAULT_ANIMATION_FILE (which contains
            // swing_hand from the built-in default model).  Without this,
            // models without their own swing_hand (e.g. 2_steve, 3_default_boy)
            // would silently fail to play any swing animation, leaving the
            // arm frozen in the bind/rest pose when the player clicks.
            if (!animationExistsInFile(animId, "swing_hand")) {
                software.bernie.geckolib3.core.builder.Animation defaultSwing =
                    com.fox.ysmu.client.ClientModelManager.DEFAULT_ANIMATION_FILE.animations.get("swing_hand");
                if (defaultSwing != null) {
                    software.bernie.geckolib3.file.AnimationFile animFile =
                        software.bernie.geckolib3.resource.GeckoLibCache.getInstance().getAnimations().get(animId);
                    if (animFile != null) {
                        software.bernie.geckolib3.core.builder.Animation copy =
                            new software.bernie.geckolib3.core.builder.Animation();
                        copy.animationLength = defaultSwing.animationLength;
                        copy.loop = defaultSwing.loop;
                        copy.boneAnimations = defaultSwing.boneAnimations;
                        animFile.animations.put("swing_hand", copy);
                    }
                }
            }
            return playAnimation(event, "swing_hand", ILoopType.EDefaultLoopTypes.PLAY_ONCE);
        }
        return PlayState.STOP;
    }

    private boolean markSwingStart(EntityPlayer player) {
        UUID playerId = player.getUniqueID();
        if (!player.isSwingInProgress) {
            swingProgressByPlayer.remove(playerId);
            return false;
        }
        // swingProgressInt 在 1.7.10 中是递减的（从最大值→0）。
        // 旧逻辑 currentProgress < previousProgress 在递减时每帧都 true，
        // 导致 markNeedsReload() 每帧重置动画，swing_hand 永远播不出来。
        // 改用 boolean 跟踪：只在新攻击的第一帧返回 true。
        boolean wasAlreadySwinging = swingProgressByPlayer.containsKey(playerId);
        swingProgressByPlayer.put(playerId, 0); // 仅用作标记
        return !wasAlreadySwinging;
    }

    public PlayState predicateUse(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        if (dismountAnim.containsKey(player.getUniqueID())) {
            return PlayState.STOP;
        }
        // 动画控制脚本优先（use 槽位）。
        PlayState scriptState = applyControlScript(event, controlSlotName(event.getController().getName()));
        if (scriptState != null) {
            return scriptState;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        boolean isUsingItem = (player.isUsingItem()
            || com.fox.ysmu.compat.TinkersCrossbowCompat.isCrossbowReloading(player.getHeldItem()))
            && !player.isPlayerSleeping();
        boolean isBlocking = com.fox.ysmu.compat.BlockingCompat.isBlocking(player);
        UUID playerId = player.getUniqueID();

        if (isUsingItem || isBlocking) {
            // 使用物品或格挡中：播动画
            if (com.fox.ysmu.Config.DEBUG_ANIMATION) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-ANIM] predicateUse: isUsingItem={} isBlocking={}",
                    isUsingItem, isBlocking);
            }
            // 设尾迹计数器：结束后让动画保持一小段时间再停
            blockingTailTicks.put(playerId, 5);

            boolean needReset = false;
            if (isUsingItem) {
                needReset = markUseStart(player);
            }
            if (needReset || event.getController().getAnimationState()
                == software.bernie.geckolib3.core.AnimationState.Stopped) {
                event.getController().shouldResetTick = true;
                event.getController().markNeedsReload();
                event.getController().adjustTick(0);
            }

            // Battlegear2 盾牌永远在副手
            boolean isMainHand = isBlocking && !isUsingItem ? false : BackhandCompat.getUsedItemHand(player);
            String conditionalAnimation = findUseAnimation(event, player, isMainHand);
            if (com.fox.ysmu.Config.DEBUG_ANIMATION) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-ANIM] predicateUse: isMainHand={} conditionalAnim={} blocking={}",
                    isMainHand, conditionalAnimation, isBlocking);
            }
            if (StringUtils.isNoneBlank(conditionalAnimation)) {
                return playAnimation(event, conditionalAnimation, ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME);
            }
            String fallbackAnim = isMainHand ? "use_mainhand" : "use_offhand";
            if (isBlocking) {
                return playBlockingAnimation(event, fallbackAnim);
            }
            return playAnimation(event, fallbackAnim, ILoopType.EDefaultLoopTypes.LOOP);
        }

        // 不使用物品也不格挡：由尾迹计数器决定何时停
        int tail = blockingTailTicks.getOrDefault(playerId, 0);
        if (tail > 0) {
            blockingTailTicks.put(playerId, tail - 1);
            return PlayState.CONTINUE;
        }
        blockingTailTicks.remove(playerId);
        useDurationByPlayer.remove(playerId);
        com.fox.ysmu.client.audio.YSMSoundManager.stopController(event.getController().getName());
        return PlayState.STOP;
    }

    private boolean markUseStart(EntityPlayer player) {
        UUID playerId = player.getUniqueID();
        if (!player.isUsingItem()) {
            useDurationByPlayer.remove(playerId);
            return false;
        }
        int currentDuration = player.getItemInUseDuration();
        Integer previousDuration = useDurationByPlayer.put(playerId, currentDuration);
        return previousDuration == null || currentDuration < previousDuration;
    }

    public PlayState predicateArmor(AnimationEvent<CustomPlayerEntity> event, int slotIndex) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        if (dismountAnim.containsKey(player.getUniqueID())) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        ItemStack itemBySlot = player.getEquipmentInSlot(slotIndex);
        if (itemBySlot == null) {
            return PlayState.STOP;
        }

        String conditionalAnimation = findArmorAnimation(event, player, slotIndex);
        if (StringUtils.isNoneBlank(conditionalAnimation)) {
            return playLoopAnimation(event, conditionalAnimation);
        }

        ResourceLocation animation = getAnimationId(event);
        String slotName = ConditionArmor.getSlotNameFromIndex(slotIndex);
        String defaultName = slotName + ":default";
        if (GeckoLibCache.getInstance()
            .getAnimations()
            .get(animation).animations.containsKey(defaultName)) {
            return playAnimation(event, defaultName, ILoopType.EDefaultLoopTypes.LOOP);
        }
        return PlayState.STOP;
    }

    private static PlayState playIfPresent(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        if (StringUtils.isNoneBlank(animationName)) {
            return playAnimation(event, animationName);
        }
        return PlayState.STOP;
    }

    private static String findHoldAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        boolean isMainHand) {
        ResourceLocation id = getAnimationId(event);
        ConditionalHold conditionalHold = isMainHand ? ConditionManager.getHoldMainhand(id)
            : ConditionManager.getHoldOffhand(id);
        return conditionalHold == null ? null : conditionalHold.doTest(player, isMainHand);
    }

    private static String findSwingAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player) {
        ConditionalSwing conditionalSwing = ConditionManager.getSwing(getAnimationId(event));
        return conditionalSwing == null ? null : conditionalSwing.doTest(player, BackhandCompat.swingingArm(player));
    }

    private static String findUseAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        boolean isMainHand) {
        ResourceLocation id = getAnimationId(event);
        ConditionalUse conditionalUse = isMainHand ? ConditionManager.getUseMainhand(id)
            : ConditionManager.getUseOffhand(id);
        return conditionalUse == null ? null : conditionalUse.doTest(player, isMainHand);
    }

    private static String findArmorAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        int slotIndex) {
        ConditionArmor conditionArmor = ConditionManager.getArmor(getAnimationId(event));
        return conditionArmor == null ? null : conditionArmor.doTest(player, slotIndex);
    }

    /**
     * 判断持握动画是否应暂停。
     * 攻击/使用期间返回 false，让 swing/use 控制器接管；但不标记物品为空，
     * 这样攻击结束后持握控制器恢复时不会触发 markNeedsReload()。
     */
    private boolean checkSwingAndUse(EntityPlayer player, boolean isMainHand) {
        if (player.isSwingInProgress && BackhandCompat.swingingArm(player) == isMainHand) {
            return false;
        }
        return !player.isUsingItem() || BackhandCompat.getUsedItemHand(player) != isMainHand;
    }

    /**
     * Returns a hash that changes when the held item type changes. */
    private static int itemHash(net.minecraft.item.ItemStack stack) {
        return stack == null || stack.getItem() == null ? 0 : stack.getItem().hashCode();
    }

    /**
     * 检测玩家是否刚下马（isRiding 从 true→false）。
     */

    // 攻击组合技 (hasActiveCombo) 已注释掉 (2025-06-26)
    // public static boolean hasActiveCombo(EntityPlayer player) {
    //     if (player == null || MANAGER == null) return false;
    //     return MANAGER.swingCombo.containsKey(player.getUniqueID());
    // }
}

