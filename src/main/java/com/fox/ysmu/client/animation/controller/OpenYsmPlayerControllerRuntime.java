package com.fox.ysmu.client.animation.controller;

import static com.fox.ysmu.util.ControllerUtils.CAP_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.HOLD_MAINHAND_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.HOLD_OFFHAND_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.MAIN_CONTROLLER;
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
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.AnimationEntry;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Controller;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.ControllerSet;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.State;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Transition;
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
    /** Roaming variables set from outside the render loop (e.g. GUI config panel).
     *  Key is the variable name WITHOUT the "v." prefix (e.g. "roaming.ef"). */
    public static final Map<String, Double> PENDING_ROAMING = new ConcurrentHashMap<>();

    private OpenYsmPlayerControllerRuntime() {}

    public static PlayState tryApply(AnimationEvent<CustomPlayerEntity> event) {
        if (event == null || event.getController() == null || event.getAnimatable() == null) {
            return null;
        }
        CustomPlayerEntity animatable = event.getAnimatable();
        EntityPlayer player = animatable.getPlayer();
        if (player == null) {
            return null;
        }
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
        // Inject any roaming variables set from outside the render loop (e.g. GUI config panel)
        // Inject both original case and lowercase for compatibility.
        if (!PENDING_ROAMING.isEmpty()) {
            for (Map.Entry<String, Double> entry : PENDING_ROAMING.entrySet()) {
                runtimeState.variables.put(entry.getKey(), entry.getValue());
                String lcKey = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!lcKey.equals(entry.getKey())) {
                    runtimeState.variables.put(lcKey, entry.getValue());
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
        int transitionCount = 0;
        for (int i = 0; i < 4; i++) {
            String prevStateName = state.name;
            State nextState = applyTransition(event, match.controller, state, runtimeState, context);
            if (nextState == state) {
                break;
            }
            transitionCount++;
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

        // Collect all active animations from the state. In OpenYSM, a state's
        // "animations" list plays all entries simultaneously; selectAnimation
        // only returns the first match for historical code that expects one.
        List<String> activeAnimations = collectActiveAnimations(state, animationId, context);
        if (activeAnimations.isEmpty()) {
            State initialState = match.controller.getInitialState();
            if (initialState != null && !runtimeState.currentState.equals(initialState.name)) {
                runtimeState.currentState = initialState.name;
                runtimeState.enteredTick = event.getAnimationTick();
                runtimeState.lastSelectedAnimationState = "";
                runtimeState.lastSelectedAnimation = "";
            }
            com.fox.ysmu.client.audio.YSMSoundManager.stopController(geckoControllerName);
            event.getController().currentAnimationBuilder = new AnimationBuilder();
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
        applyAnimations(event, runtimeState, state, existing, animationId);
        if (prevAnim == null || !prevAnim.equals(existing.get(0))) {
            com.fox.ysmu.client.audio.YSMSoundManager.stopController(geckoControllerName);
        }
        return PlayState.CONTINUE;
    }

    private static RuntimeState runtimeState(EntityPlayer player, ResourceLocation animationId,
        String geckoControllerName, String openYsmControllerName) {
        StateKey key = new StateKey(player.getUniqueID(), animationId, geckoControllerName, openYsmControllerName);
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
        RuntimeState runtimeState, OpenYsmControllerExpressionEvaluator.Context context) {
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
            // Delay start→sky so sneaking_start is visible for at least 5
            // ticks before transitioning to the stationary crouch pose.
            if ("sky".equals(target.name) && "start".equals(state.name)
                && event.getAnimationTick() - runtimeState.enteredTick < 5.0) {
                continue;
            }
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
        if (file == null || !file.animations.containsKey(animationName)) {
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
        String ctrlName = event.getController().getName();
        boolean excludeRoot = ctrlName != null
            && !MAIN_CONTROLLER.equals(ctrlName)
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
        // Determine the final animation name: if we have merged bones and either
        // need Root filtering or have multiple animations, use a cached merged copy.
        boolean needsMergedCopy = mergedBones != null
            && (excludeRoot || animationNames.size() > 1);
        String finalName;
        ILoopType finalLoop;
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
            if (primaryAnim != null) {
                mergedAnim.animationLength = primaryAnim.animationLength;
                mergedAnim.loop = primaryAnim.loop;
                finalLoop = primaryAnim.loop;
            } else {
                mergedAnim.animationLength = null;
                mergedAnim.loop = ILoopType.EDefaultLoopTypes.LOOP;
                finalLoop = ILoopType.EDefaultLoopTypes.LOOP;
            }
            finalName = mergedName;
        } else {
            finalName = primaryName;
            finalLoop = primaryAnim != null ? primaryAnim.loop : ILoopType.EDefaultLoopTypes.LOOP;
        }
        // Only call setAnimation ONCE with the final name, so GeckoLib does NOT
        // reset shouldResetTick every frame (which would freeze the animation at tick 0).
        finalName = finalName != null ? finalName : primaryName;
        AnimationBuilder builder = new AnimationBuilder().addAnimation(finalName, finalLoop);
        boolean sameState = state.name.equals(runtimeState.lastSelectedAnimationState);
        boolean changedInSameState = sameState && StringUtils.isNotBlank(runtimeState.lastSelectedAnimation)
            && !runtimeState.lastSelectedAnimation.equals(primaryName);
        runtimeState.lastAnimation = primaryName;
        runtimeState.lastSelectedAnimationState = state.name;
        runtimeState.lastSelectedAnimation = primaryName;
        if (changedInSameState) {
            double elapsedTick = Math.max(0.0D, event.getAnimationTick() - runtimeState.enteredTick);
            if (event.getController()
                .setAnimationPreservingTick(builder, event.getAnimationTick(), elapsedTick)) {
                return;
            }
        }
        event.getController().setAnimation(builder);
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
        // Each controller uses its OWN lastSwingActive/lastSwingProgress so that
        // on_exit variable clearance (v.swing_sword=0) is never overwritten.
        boolean swingJustStarted = player.isSwingInProgress && !state.lastSwingActive;
        boolean swingReset = player.isSwingInProgress && state.lastSwingActive
            && player.swingProgressInt < state.lastSwingProgress;
        boolean newSwing = swingJustStarted || swingReset;
        if (newSwing) {
            boolean swordSwing = OpenYsmControllerExpressionEvaluator.evaluateBoolean(
                "ctrl.swing('mainhand', ':sword')||ctrl.swing('offhand', ':sword')",
                context);
            if (swordSwing) {
                state.variables.put("swing_sword", 1.0d);
            }
            state.variables.put(
                "jump",
                OpenYsmControllerExpressionEvaluator.evaluateBoolean(
                    "q.is_jumping&&(q.vertical_speed<0)",
                    context) ? 1.0d : 0.0d);
        }
        state.lastSwingActive = player.isSwingInProgress;
        state.lastSwingProgress = player.isSwingInProgress ? player.swingProgressInt : -1;
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

    static final class RuntimeState {
        String currentState = "";
        /** Whether the controller has ever transitioned away from its initial state. */
        boolean hasLeftInitial = false;
        String lastAnimation = "";
        String lastSelectedAnimationState = "";
        String lastSelectedAnimation = "";
        double enteredTick;
        boolean lastSwingActive;
        int lastSwingProgress = -1;
        final Map<String, Double> variables = new ConcurrentHashMap<>();
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
            return playerId.equals(other.playerId) && animationId.equals(other.animationId)
                && geckoControllerName.equals(other.geckoControllerName)
                && openYsmControllerName.equals(other.openYsmControllerName);
        }

        @Override
        public int hashCode() {
            int result = playerId.hashCode();
            result = 31 * result + animationId.hashCode();
            result = 31 * result + geckoControllerName.hashCode();
            result = 31 * result + openYsmControllerName.hashCode();
            return result;
        }
    }
}
