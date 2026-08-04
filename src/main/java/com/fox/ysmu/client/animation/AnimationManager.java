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
     * 当模型提供了 .molang 函数文件（如 @player_ctrl_pre_main.molang）时，
     * 从中提取 ctrl.<state> → 动画名 映射。传统谓词系统应优先使用映射名。
     * 同时会检查有条件分支的替代动画（如 v.show_car → 开车动画）。
     *
     * @param animId      模型的动画 ResourceLocation
     * @param stateName   标准谓词状态名（如 "walk"、"idle"）
     * @return 映射的动画名（如 "正常_行走"），若无可返回 null
     */
    @Nullable
    private static String getMolangMappedAnimation(ResourceLocation animId, String stateName) {
        if (animId == null) return null;
        // 1) 检查有条件分支的替代动画
        Map<String, List<org.apache.commons.lang3.tuple.Pair<String, String>>> condMap =
            MOLANG_CONDITIONAL_MAP.get(animId);
        if (condMap != null) {
            List<org.apache.commons.lang3.tuple.Pair<String, String>> alternatives = condMap.get(stateName);
            if (alternatives != null) {
                for (org.apache.commons.lang3.tuple.Pair<String, String> alt : alternatives) {
                    if (evaluateSimpleCondition(alt.getKey())) {
                        return alt.getValue();
                    }
                }
            }
        }
        // 2) 检查默认映射
        Map<String, String> mapping = MOLANG_STATE_MAP.get(animId);
        if (mapping == null) return null;
        return mapping.get(stateName);
    }

    /**
     * 简单求值 .molang 函数文件中的条件表达式。
     * 仅支持 v.<name> 和 !v.<name> 形式的纯变量条件。
     * 含有 && || <= >= < > == != 的复杂条件无法处理，跳过（返回 false）。
     * 完整条件评估需要执行完整 Molang 脚本，暂不支持。
     */
    private static boolean evaluateSimpleCondition(String condition) {
        if (StringUtils.isBlank(condition)) return true;
        String trimmed = condition.trim();
        // 拒绝含运算符的复杂条件
        if (trimmed.contains("&&") || trimmed.contains("||")
            || trimmed.contains("<=") || trimmed.contains(">=")
            || trimmed.contains("==") || trimmed.contains("!=")
            || trimmed.contains("<") || trimmed.contains(">")) {
            return false;
        }
        // 取反: !v.xxx
        if (trimmed.startsWith("!")) {
            String varName = trimmed.substring(1).trim();
            if (varName.startsWith("v.")) {
                return getMolangVariable(varName) == 0;
            }
            return false;
        }
        // 正向: v.xxx
        if (trimmed.startsWith("v.")) {
            return getMolangVariable(trimmed) != 0;
        }
        return false;
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

    /** 防滑步（stride matching）+ 逐动画倍速（anim_speed）：legacy 控制器路径。
     *  在公共 playAnimation 入口调用，因此所有 legacy 控制器都会应用 anim_speed；
     *  防滑步仅对 main_controller 生效。GUI 预览（player==null）不干预，
     *  避免覆盖预览页的暂停/冻结倍速。 */
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
        boolean strideOn = Config.ANIMATION_SPEED_MATCH
            && ControllerUtils.MAIN_CONTROLLER.equals(event.getController().getName())
            && StringUtils.isNotBlank(animationName);
        double strideMultiplier = 1.0d;
        double animSpeed = 1.0d;
        ResourceLocation animId = animatable != null ? animatable.getAnimation() : null;
        AnimationFile file = animId != null ? GeckoLibCache.getInstance().getAnimations().get(animId) : null;
        // 防滑步：仅主控制器按真实速度缩放
        if (strideOn) {
            double cycleSeconds = MovementSpeedMatcher.cycleSeconds(file, animationName);
            strideMultiplier = MovementSpeedMatcher.computeMultiplier(
                player, animationName, MovementSpeedMatcher.DEFAULT_PROVIDER, cycleSeconds);
        }
        // anim_speed：模型作者逐动画播放倍率
        if (StringUtils.isNotBlank(animationName)) {
            animSpeed = MovementSpeedMatcher.animSpeedFor(file, animationName, GeckoLibCache.getInstance().parser);
        }
        // 最终倍率 = anim_speed × 防滑步倍率
        event.getController().animationSpeed = strideMultiplier * animSpeed;
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
        event.getController()
            .setAnimation(new AnimationBuilder().addAnimation(animationName));
        return PlayState.CONTINUE;
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
            // No matching OpenYSM controller — fall back to legacy animation.
            // Skip if the animation doesn't exist in the model's file to avoid
            // GeckoLib's System.out.printf spam ("Could not load animation: ...").
            if (animationExistsInFile(animId, animationName)) {
                return playLoopAnimation(event, animationName);
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
        // （乐魂：移动潜行→行走/后退1，站立潜行→sneaking_Control）。
        // main_controller 的 legacy 潜行（sneak/sneaking）会与 pre_main 同时
        // 播放并覆盖其 Root 位移（sneaking 的 Root [0,-7.625,0] 覆盖行走的
        // [0,3,0]），导致移动潜行显示成站立潜行蹲姿。
        // 因此仅当模型自身提供了身体控制器（player.pre_main / player.main /
        // player.base / player.move）时才跳过 legacy 的 sneak/sneaking 状态；
        // 只有 post_main/post_swing 等非身体控制器的 OpenYSM 模型（如
        // Endfield Rossi）没有自带的潜行处理，必须依赖 legacy 状态机播放
        // sneak/sneaking 动画，不能跳过。
        // 仅当模型身体控制器（pre_main/main/base/move）真正引用潜行逻辑
        // （sneak/sneaking 动画名或 ctrl.sneaking/ctrl.sneak/q.is_sneaking 条件）
        // 时才跳过 legacy 的 sneak/sneaking 状态。mingf 虽有 player.main 但只
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
                    String mappedName = getMolangMappedAnimation(animId, animationName);
                    String targetName = mappedName != null ? mappedName : animationName;
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
            String idleName = getMolangMappedAnimation(animId, "idle");
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
                    String mapped = getMolangMappedAnimation(animId, name);
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

