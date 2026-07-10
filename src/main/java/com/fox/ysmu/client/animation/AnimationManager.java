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

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.condition.*;
import com.fox.ysmu.client.animation.controller.OpenYsmAnimationControllerRegistry;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.compat.BackhandCompat;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.ysmu;

import com.google.common.collect.Lists;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
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
    /** Remaining ticks to suppress other controllers during dismount. */
    private final Map<UUID, Integer> dismountTimer = new ConcurrentHashMap<>();
    /** Tracks isSwingInProgress across frames to detect new swing cycles (false→true). */
    private final Map<UUID, Boolean> swingWasActive = new ConcurrentHashMap<>();
    /** Tracks last swingProgressInt to detect rapid-click resets. */
    private final Map<UUID, Integer> lastSwingProgress = new ConcurrentHashMap<>();
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
        swingWasActive.remove(playerId);
        lastSwingProgress.remove(playerId);
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
        event.getController()
            .setAnimation(new AnimationBuilder().addAnimation(animationName, loopType));
        return PlayState.CONTINUE;
    }

    @NotNull
    private static <P extends IAnimatable> PlayState playAnimation(AnimationEvent<P> event, String animationName) {
        event.getController()
            .setAnimation(new AnimationBuilder().addAnimation(animationName));
        return PlayState.CONTINUE;
    }

    /**
     * 只在动画存在时播放。防止 GeckoLib 的 setAnimation() 在动画不存在时静默失败
     * （不设 animationQueue），导致控制器处于 Stopped 状态且模型冻结。
     */
    private static <P extends IAnimatable> PlayState playIfAnimExists(AnimationEvent<P> event, String animationName,
        ILoopType loopType, ResourceLocation animId) {
        if (animationExistsInFile(animId, animationName)) {
            return playAnimation(event, animationName, loopType);
        }
        return PlayState.STOP;
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
            // No matching OpenYSM controller — fall back to legacy animation
            return playLoopAnimation(event, animationName);
        }
        return playLoopAnimation(event, animationName);
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
            String anim = eep.getAnimation();
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
            // First, check the entity's guiBaseAnimation (set directly by ModelButton).
            if (animatable.hasGuiBaseAnimation()) {
                return playLoopAnimation(event, animatable.getGuiBaseAnimation());
            }
            // Fallback: look up PREVIEW_ANIMATION from the model registry.
            ResourceLocation mainModel = animatable.getMainModel();
            if (mainModel != null) {
                String previewAnim = ClientModelManager.PREVIEW_ANIMATION.get(mainModel);
                // Also try the raw model ID (without /main suffix)
                if ((previewAnim == null || previewAnim.isEmpty()) && mainModel.getResourcePath().endsWith("/main")) {
                    ResourceLocation rawId = new ResourceLocation(mainModel.getResourceDomain(),
                        mainModel.getResourcePath().substring(0, mainModel.getResourcePath().length() - 5));
                    previewAnim = ClientModelManager.PREVIEW_ANIMATION.get(rawId);
                }
                if (previewAnim != null && !previewAnim.isEmpty()) {
                    return playLoopAnimation(event, previewAnim);
                }
                // Final fallback: if the model has "idle" animation, play it
                AnimationFile file = GeckoLibCache.getInstance().getAnimations().get(mainModel);
                if (file != null && file.getAnimation("idle") != null) {
                    return playLoopAnimation(event, "idle");
                }
            }
            return PlayState.STOP;
        }
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
        for (int i = Priority.HIGHEST; i <= Priority.LOWEST; i++) {
            if (!data.containsKey(i)) {
                continue;
            }
            LinkedList<AnimationState> states = data.get(i);
            for (AnimationState state : states) {
                if (state.getPredicate().test(player, event)) {
                    String animationName = state.getAnimationName();
                    // 优先检查 molang 映射：当模型提供了 .molang 函数文件时，
                    // 使用映射的动画名（如 walk → 正常_行走）替代标准名
                    String mappedName = getMolangMappedAnimation(animId, animationName);
                    String targetName = mappedName != null ? mappedName : animationName;
                    if (animFile != null && animFile.animations.containsKey(targetName)) {
                        Animation anim = animFile.animations.get(targetName);
                        // if ("sneak".equals(animationName) || "sneaking".equals(animationName)
                        //     || "sneaking_sky".equals(animationName) || "sneaking_start".equals(animationName)) {
                        //     com.fox.ysmu.ysmu.LOG.info("[YSMU-DBG] predicateMain: state={} mapped={} exists={} nonEmpty={} limbSwing={} isMoving={} isSneaking={}",
                        //         animationName, targetName, true, isAnimationNonEmpty(anim),
                        //         String.format("%.4f", event.getLimbSwingAmount()),
                        //         event.isMoving(), player.isSneaking());
                        // }
                        // 跳过空桩动画（loop:true 无 bones）
                        if (!isAnimationNonEmpty(anim)) {
                            continue;
                        }
                        ILoopType loopType = state.getLoopType();
                        return playAnimation(event, targetName, loopType);
                    }
                }
            }
        }
        // 所有优先级轮询完毕仍未找到有效动画 — 回退：
        // 1) 优先 idle（检查 molang 映射和直接名）
        // 2) 最后兜底取任意非空动画
        if (animFile != null && !animFile.animations.isEmpty()) {
            // 优先尝试 idle
            String idleName = getMolangMappedAnimation(animId, "idle");
            if (idleName == null) idleName = "idle";
            Animation idleAnim = animFile.animations.get(idleName);
            if (isAnimationNonEmpty(idleAnim)) {
                return playLoopAnimation(event, idleName);
            }
            // 遍历所有注册状态名，尝试从 molang 映射或直接名找非空动画
            // 注意：此循环不检查 predicate，仅看动画是否存在。
            // 因此需要跳过 death 等终端状态，避免 idle 为空时误播。
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
                        return playLoopAnimation(event, target);
                    }
                }
            }
            // 最后兜底：取文件中任意第一个非空动画（跳过 death 等终端状态动画，
            // 防止 idle 为空时误播死亡动画）
            for (Map.Entry<String, Animation> entry : animFile.animations.entrySet()) {
                String animName = entry.getKey();
                if ("death".equals(animName)) continue;
                if (isAnimationNonEmpty(entry.getValue())) {
                    return playLoopAnimation(event, animName);
                }
            }
        }
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
            // 攻击/使用期间暂停持握动画，让挥砍/使用控制器接管；但不标记为空(-1)
            // 这样攻击结束后持握控制器恢复时，last == hash 不会触发 markNeedsReload()，
            // 避免了过渡动画重复播放
            if (!checkSwingAndUse(player, false)) {
                return PlayState.STOP;
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
            // 攻击/使用期间暂停持握动画，让挥砍/使用控制器接管；但不标记为空(-1)
            if (!checkSwingAndUse(player, true)) {
                return PlayState.STOP;
            }
            int hash = itemHash(player.getHeldItem());
            Integer last = lastMainhandItemHash.put(player.getUniqueID(), hash);
            // Reload when item changes, coming from empty (-1), or coming from empty anim (0)
            if (last == null || last != hash || last == -1 || last == 0) {
                event.getController().markNeedsReload();
            }
            return playIfPresent(event, findHoldAnimation(event, player, true));
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
        if (controllerState != null) {
            return controllerState;
        }
        UUID pid = player.getUniqueID();
        // 检测新挥剑：完整 false→true 转换，或 swingProgressInt 跳高（快速连点）
        boolean nowSwinging = player.isSwingInProgress;
        boolean wasSwinging = swingWasActive.getOrDefault(pid, false);
        swingWasActive.put(pid, nowSwinging);
        boolean swingStarted = !wasSwinging && nowSwinging;
        boolean progressReset = false;
        if (nowSwinging) {
            int prev = lastSwingProgress.getOrDefault(pid, -1);
            if (prev >= 0 && player.swingProgressInt < prev) progressReset = true;
            lastSwingProgress.put(pid, player.swingProgressInt);
        } else {
            lastSwingProgress.remove(pid);
        }

        if (!nowSwinging) {
            swingProgressByPlayer.remove(pid);
            com.fox.ysmu.client.audio.YSMSoundManager.stopController(event.getController().getName());
            return PlayState.STOP;
        }
        if (!player.isPlayerSleeping()) {
            boolean newSwing = markSwingStart(player);
            if (newSwing) {
                event.getController().shouldResetTick = true;
                event.getController().markNeedsReload();
                event.getController()
                    .adjustTick(0);
            }
            String conditionalAnimation = findSwingAnimation(event, player);
            ResourceLocation animId = getAnimationId(event);

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
                //com.fox.ysmu.ysmu.LOG.info(
                //    "YSM predicateSwing: conditional='{}', exists={}, animId={}, "
                //    + "idle={}, moving={}, onGround={}",
                //    conditionalAnimation, exists, animId,
                //    !event.isMoving() && player.onGround, event.isMoving(), player.onGround);
                if (exists) {
                    return playAnimation(event, conditionalAnimation, ILoopType.EDefaultLoopTypes.LOOP);
                }
            } else {
                boolean swingHandExists = animationExistsInFile(animId, "swing_hand");
                //com.fox.ysmu.ysmu.LOG.info(
                //    "YSM predicateSwing: no conditional, fallback swing_hand exists={}, animId={}, "
                //    + "idle={}, moving={}, onGround={}",
                //    swingHandExists, animId,
                //    !event.isMoving() && player.onGround, event.isMoving(), player.onGround);
            }
            return playAnimation(event, "swing_hand", ILoopType.EDefaultLoopTypes.LOOP);
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
        if (player.isUsingItem() && !player.isPlayerSleeping()) {
            if (markUseStart(player)) {
                event.getController().shouldResetTick = true;
                event.getController().markNeedsReload();
                event.getController()
                    .adjustTick(0);
            }
            boolean isMainHand = BackhandCompat.getUsedItemHand(player);
            String conditionalAnimation = findUseAnimation(event, player, isMainHand);
            if (StringUtils.isNoneBlank(conditionalAnimation)) {
                return playAnimation(event, conditionalAnimation);
            }
            return playAnimation(event, isMainHand ? "use_mainhand" : "use_offhand", ILoopType.EDefaultLoopTypes.LOOP);
        }
        useDurationByPlayer.remove(player.getUniqueID());
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

