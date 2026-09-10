package com.fox.ysmu.client.animation.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class OpenYsmControllerDefinitions {

    private OpenYsmControllerDefinitions() {}

    static final class ControllerSet {
        final Map<String, Controller> controllers = new LinkedHashMap<>();
        /** Every controller name declared by the model's controller JSON, including
         *  entries that carry no states at all. OpenYSM's CompositeAnimationController
         *  prefers a declared animation entry over the implicit named-animation
         *  predicate, so a declared-but-empty entry has to shadow the implicit
         *  controller instead of falling back to the raw animation
         *  (smx/yomon declare empty player.pre_parallel_1..7 placeholders). */
        final Set<String> declaredNames = new LinkedHashSet<>();
    }

    static final class Controller {
        String name = "";
        String initialState = "";
        final Map<String, State> states = new LinkedHashMap<>();
        /** 该控制器依赖的可选模组 modId 集合。
         *  在控制器解析阶段通过扫描条件和动画关键帧自动检测。
         *  运行时若集合中任一 mod 未加载，则跳过此控制器。 */
        final Set<String> modDependencies = new LinkedHashSet<>();

        State getInitialState() {
            if (states.containsKey(initialState)) {
                return states.get(initialState);
            }
            return states.isEmpty() ? null : states.values().iterator().next();
        }

        List<State> getStatesWithAnimations() {
            List<State> result = new ArrayList<>();
            for (State s : states.values()) {
                if (!s.animations.isEmpty()) {
                    result.add(s);
                }
            }
            return result;
        }
    }

    static final class State {
        String name = "";
        final List<AnimationEntry> animations = new ArrayList<>();
        final List<Transition> transitions = new ArrayList<>();
        final List<String> onEntry = new ArrayList<>();
        final List<String> onExit = new ArrayList<>();
        final List<String> soundEffects = new ArrayList<>();
        float blendTransitionTicks = -1f;
        boolean blendViaShortestPath;
    }

    static final class AnimationEntry {
        final String animationName;
        final String condition;

        AnimationEntry(String animationName, String condition) {
            this.animationName = animationName;
            this.condition = condition;
        }
    }

    static final class Transition {
        final String targetState;
        final String condition;

        Transition(String targetState, String condition) {
            this.targetState = targetState;
            this.condition = condition;
        }
    }
}
