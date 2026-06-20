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
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

public final class OpenYsmPlayerControllerRuntime {

    private static final Map<StateKey, RuntimeState> STATES = new ConcurrentHashMap<>();

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
            //// Global search disabled: picks wrong state for all parallel controllers
            //if (forcedTarget == null) {
            //    for (State candidate : match.controller.states.values()) {
            //        if (!candidate.animations.isEmpty() && !candidate.name.equals(state.name)) {
            //            forcedTarget = candidate;
            //            break;
            //        }
            //    }
            //}
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

        String animationName = selectAnimation(state, match.preferredAnimationIndex, context);
        if (StringUtils.isBlank(animationName) && !state.animations.isEmpty()) {
            animationName = state.animations.get(0).animationName;
        }
        if (StringUtils.isBlank(animationName) || !animationExists(animationId, animationName)) {
            State initialState = match.controller.getInitialState();
            if (initialState != null && !runtimeState.currentState.equals(initialState.name)) {
                runtimeState.currentState = initialState.name;
                runtimeState.enteredTick = event.getAnimationTick();
                runtimeState.lastSelectedAnimationState = "";
                runtimeState.lastSelectedAnimation = "";
            }
            return null;
        }
        if (SWING_CONTROLLER.equals(geckoControllerName) && "attack_empty".equals(animationName)) {
            return null;
        }
        if (state.blendTransitionTicks >= 0f) {
            event.getController().transitionLengthTicks = state.blendTransitionTicks;
        }
        applyAnimation(event, runtimeState, state, animationName);
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

    private static String selectAnimation(State state, int preferredAnimationIndex,
        OpenYsmControllerExpressionEvaluator.Context context) {
        if (state.animations.isEmpty()) {
            return null;
        }
        if (preferredAnimationIndex >= 0 && preferredAnimationIndex < state.animations.size()) {
            AnimationEntry entry = state.animations.get(preferredAnimationIndex);
            return animationEntryActive(entry, context) ? entry.animationName : null;
        }
        for (AnimationEntry entry : state.animations) {
            if (animationEntryActive(entry, context)) {
                return entry.animationName;
            }
        }
        return null;
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

    private static void applyAnimation(AnimationEvent<CustomPlayerEntity> event, RuntimeState runtimeState, State state,
        String animationName) {
        AnimationBuilder builder = new AnimationBuilder().addAnimation(animationName);
        boolean sameState = state.name.equals(runtimeState.lastSelectedAnimationState);
        boolean changedInSameState = sameState && StringUtils.isNotBlank(runtimeState.lastSelectedAnimation)
            && !runtimeState.lastSelectedAnimation.equals(animationName);
        runtimeState.lastAnimation = animationName;
        runtimeState.lastSelectedAnimationState = state.name;
        runtimeState.lastSelectedAnimation = animationName;
        if (changedInSameState) {
            double elapsedTick = Math.max(0.0D, event.getAnimationTick() - runtimeState.enteredTick);
            if (event.getController()
                .setAnimationPreservingTick(builder, event.getAnimationTick(), elapsedTick)) {
                return;
            }
        }
        event.getController()
            .setAnimation(builder);
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
