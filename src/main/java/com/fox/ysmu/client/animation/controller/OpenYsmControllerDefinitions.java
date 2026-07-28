package com.fox.ysmu.client.animation.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OpenYsmControllerDefinitions {

    private OpenYsmControllerDefinitions() {}

    static final class ControllerSet {
        final Map<String, Controller> controllers = new LinkedHashMap<>();
    }

    static final class Controller {
        String name = "";
        String initialState = "";
        final Map<String, State> states = new LinkedHashMap<>();
        /** true 表示该控制器的 condition/transition 中引用了 {@code ctrl.tac_*}
         *  控制变量。当 TacZ 模组未加载时可在运行时跳过此控制器，避免
         *  无效动画播放和音效误触发。在 {@code parseController()} 中设置。 */
        boolean hasTacZConditions = false;

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
