package com.fox.ysmu.client.animation.controller;

import static com.fox.ysmu.util.ControllerUtils.CAP_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.HOLD_MAINHAND_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.HOLD_OFFHAND_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.MAIN_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.OPENYSM_POST_SWING_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.SWING_CONTROLLER;
import static com.fox.ysmu.util.ControllerUtils.USE_CONTROLLER;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.AnimationEntry;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Controller;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.ControllerSet;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.State;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Transition;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.ysmu;

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
        OpenYsmControllerExpressionEvaluator.Context context = new OpenYsmControllerExpressionEvaluator.Context(
            event, player, runtimeState);
        prepareFrameVariables(geckoControllerName, player, runtimeState, context);
        State state = ensureState(event, match.controller, runtimeState, context);
        if (state == null) {
            return null;
        }
        for (int i = 0; i < 4; i++) {
            State nextState = applyTransition(event, match.controller, state, runtimeState, context);
            if (nextState == state) {
                break;
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
                state = forcedTarget;
            }
        }

        // Collect all active animations from the state. In OpenYSM, a state's
        // "animations" list plays all entries simultaneously; selectAnimation
        // only returns the first match for historical code that expects one.
        List<String> activeAnimations = collectActiveAnimations(state, animationId, context);
        // 以下4套蹲下过渡检测代码不知道为什么全都有用 如果你不知道你在干什么 一个都不要删
        // When moving while sneaking, let legacy handle (no ground state).
        if ("sky".equals(state.name) && event.isMoving()
            && activeAnimations.contains("sneaking_sky")) {
            runtimeState.wasMoving = true;
            return null;
        }
        // Transition when stopping from moving sneaking.
        if ("sky".equals(state.name) && activeAnimations.contains("sneaking_sky")
            && !event.isMoving()) {
            activeAnimations = java.util.Collections.singletonList("sneaking_start");
            runtimeState.currentState = "start";
            runtimeState.enteredTick = event.getAnimationTick();
            runtimeState.lastSelectedAnimationState = "";
            runtimeState.lastSelectedAnimation = "";
        }
        // One-shot transition when returning from movement to stationary.
        if (runtimeState.wasMoving && "default".equals(state.name) && !event.isMoving()
            && animationExists(animationId, "sneaking_start")) {
            runtimeState.wasMoving = false;
            activeAnimations = java.util.Collections.singletonList("sneaking_start");
            runtimeState.currentState = "start";
            runtimeState.enteredTick = event.getAnimationTick();
            runtimeState.lastSelectedAnimationState = "";
            runtimeState.lastSelectedAnimation = "";
        }
        // General fallback: when default has no animation but the player IS
        // sneaking (ctrl.sneaking failed to trigger default->start), redirect
        // to sneaking_start so the transition animation plays.
        if ("default".equals(state.name) && !event.isMoving()
            && animationExists(animationId, "sneaking_start")
            && Minecraft.getMinecraft().thePlayer != null
            && Minecraft.getMinecraft().thePlayer.isSneaking()) {
            activeAnimations = java.util.Collections.singletonList("sneaking_start");
            runtimeState.currentState = "start";
            runtimeState.enteredTick = event.getAnimationTick();
            runtimeState.lastSelectedAnimationState = "";
            runtimeState.lastSelectedAnimation = "";
        }
        if (activeAnimations.isEmpty()) {
            State initialState = match.controller.getInitialState();
            if (initialState != null && !runtimeState.currentState.equals(initialState.name)) {
                runtimeState.currentState = initialState.name;
                runtimeState.enteredTick = event.getAnimationTick();
                runtimeState.lastSelectedAnimationState = "";
                runtimeState.lastSelectedAnimation = "";
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
            return null;
        }
        if (SWING_CONTROLLER.equals(geckoControllerName) && existing.contains("attack_empty") && existing.size() == 1) {
            return null;
        }
        if (state.blendTransitionTicks >= 0f) {
            AnimationController<?> ctrl = event.getController();
            ctrl.transitionLengthTicks = state.blendTransitionTicks;
        }
        applyAnimations(event, runtimeState, state, existing, animationId);
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
            return target;
        }
        return state;
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
        AnimationBuilder builder = new AnimationBuilder().addAnimation(primaryName);
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
        // Merge bone animations from parallel animations into the queued animation.
        // CRITICAL: setAnimation() populates the queue with references to the ORIGINAL
        // Animation objects from the AnimationFile. We must NEVER mutate those
        // originals, or the cached data gets corrupted for all future playback.
        // Instead we build a new merged list and replace the queue entries with
        // new Animation copies that carry the merged data.
        if (animationNames.size() > 1) {
            List<software.bernie.geckolib3.core.keyframe.BoneAnimation> mergedBones = null;
            software.bernie.geckolib3.file.AnimationFile animFile =
                software.bernie.geckolib3.resource.GeckoLibCache.getInstance().getAnimations().get(animationId);
            // Start with a copy of the primary animation's bones
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
            // Merge additional animations' bones on top
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
            if (mergedBones != null) {
                AnimationController<?> ctrl = event.getController();
                // AnimationController.process() 每帧会从缓存重新加载 currentAnimation
                // （第 466-477 行），覆盖掉我们设置的合并数据。因此把合并后的动画存回
                // GeckoLibCache 中，使后续帧的重新加载也能拿到合并版本。
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
                } else {
                    mergedAnim.animationLength = null;
                    mergedAnim.loop = ILoopType.EDefaultLoopTypes.LOOP;
                }
                // 用合并后的动画名替换 builder，让 controller 从缓存加载合并版本
                event.getController().setAnimation(
                    new AnimationBuilder().addAnimation(mergedName, mergedAnim.loop));
            }
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
        if (isPostSwingController(geckoControllerName)) {
            boolean newSwing = player.isSwingInProgress
                && (!state.lastSwingActive || player.swingProgressInt < state.lastSwingProgress);
            if (newSwing) {
                boolean swordSwing = OpenYsmControllerExpressionEvaluator.evaluateBoolean(
                    "ctrl.swing('mainhand', ':sword')||ctrl.swing('offhand', ':sword')",
                    context);
                if (swordSwing) {
                    state.variables.put("swing_sword", 1.0d);
                    state.variables.put(
                        "jump",
                        OpenYsmControllerExpressionEvaluator.evaluateBoolean(
                            "q.is_jumping&&(q.vertical_speed<0)",
                            context) ? 1.0d : 0.0d);
                }
            }
            state.lastSwingActive = player.isSwingInProgress;
            state.lastSwingProgress = player.isSwingInProgress ? player.swingProgressInt : -1;
        }
    }

    private static boolean isPostSwingController(String geckoControllerName) {
        return OPENYSM_POST_SWING_CONTROLLER.equals(geckoControllerName)
            || SWING_CONTROLLER.equals(geckoControllerName);
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
        boolean wasMoving;
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
