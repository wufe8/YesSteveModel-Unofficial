package com.fox.ysmu.client.animation.molang;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;

import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangStringPool;
import software.bernie.geckolib3.core.processor.AnimationProcessor;
import software.bernie.geckolib3.core.processor.IBone;

public final class MolangPhysicsRuntime {

    /** Replaces ThreadLocal to avoid ThreadLocalMap hash-collision overhead (~9% of profile).
     *  Safe because all client rendering happens on the Minecraft client thread. */
    private static FrameContext currentFrameContext;
    private static final Map<ScopeKey, ScopeState> STATES = new ConcurrentHashMap<>();

    /** Time delta (in seconds) since the last render frame, used by ysm.time_delta. */
    private static float timeDelta = 0f;
    private static double prevRenderTicks = -1;

    /** Returns the frame time delta in seconds (ysm.time_delta). */
    public static float getTimeDelta() { return timeDelta; }

    private MolangPhysicsRuntime() {}

    /**
     * Called at the start of each render frame for a player model.
     * Injects roaming variables set from outside the render loop (e.g. GUI)
     * into both this render frame's ScopeState and the controller RuntimeState.
     */
    public static void begin(CustomPlayerEntity animatable, double renderTicks, AnimationProcessor<?> processor) {
        if (animatable == null || processor == null) {
            currentFrameContext = null;
            return;
        }
        // Advance the global frame counter used by
        // OpenYsmPlayerControllerRuntime to detect model-switch re-entry.
        com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime.advanceFrameCounter();
        EntityPlayer player = animatable.getPlayer();
        ScopeKey key = ScopeKey.from(player, animatable.getMainModel());
        ScopeState state = STATES.computeIfAbsent(key, ignored -> new ScopeState());
        ResourceLocation modelId = animatable.getMainModel();
        // Only inject roaming variables that belong to the current model.
        // Using getRoamingVarsForModel() instead of directly iterating
        // PENDING_ROAMING prevents cross-model variable contamination.
        Map<String, Double> modelRoaming = OpenYsmPlayerControllerRuntime.getRoamingVarsForModel(modelId);
        if (!modelRoaming.isEmpty()) {
            for (Map.Entry<String, Double> entry : modelRoaming.entrySet()) {
                String prefixedKey = "v." + entry.getKey();
                state.variables.put(prefixedKey, entry.getValue());
                String lcKey = "v." + entry.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!lcKey.equals(prefixedKey)) {
                    state.variables.put(lcKey, entry.getValue());
                }
                // Also inject with the "roaming." prefix stripped so that
                // bone keyframes referencing the plain variable (e.g. v.bq_eye)
                // can find the value through ScopedMolangVariable →
                // MolangPhysicsRuntime.getVariable().  ScopedMolangVariable.get()
                // ONLY checks state.variables (via MolangPhysicsRuntime), NOT
                // MolangParser.VARIABLES, so without this injection the value
                // always falls back to 0.0 and expression wheel has no effect.
                // The raw roaming value (preset index) is injected as a fallback;
                // if a timeline instruction computes a derived blend factor,
                // it overrides this value when it fires (sameAnim re-execution).
                String rawKey = entry.getKey();
                if (rawKey.startsWith("roaming.")) {
                    String plainKey = "v." + rawKey.substring("roaming.".length());
                    state.variables.put(plainKey, entry.getValue());
                    String lcPlainKey = plainKey.toLowerCase(java.util.Locale.ROOT);
                    if (!lcPlainKey.equals(plainKey)) {
                        state.variables.put(lcPlainKey, entry.getValue());
                    }
                }
            }
            // car_stuff@player_ctrl_parallel_6.molang:
            //   v.show_car=v.roaming.car && !(ctrl.tac_hold_gun||...)
            // 简化桥接: v.roaming.car == 1 时设为 1，否则 0。
            // 同时写入 PENDING_ROAMING 让条件映射（evaluateSimpleCondition）能读到。
            Double roamingCar = state.variables.get("v.roaming.car");
            if (roamingCar != null) {
                double showCar = roamingCar > 0 ? 1.0 : 0.0;
                state.variables.put("v.show_car", showCar);
                OpenYsmPlayerControllerRuntime.PENDING_ROAMING.put("show_car", showCar);
            }
            // Also inject roaming values into MolangParser.VARIABLES so that
            // timeline instructions (executed via MolangInstructionExecutor)
            // can read current roaming values through the parser's normal
            // variable resolution path even after end() clears the frame context.
            // Keys in modelRoaming already include the "roaming." prefix
            // (e.g. "roaming.bq_eye"), so we prepend "v." to match the
            // variable name format used by MolangParser ("v.roaming.bq_eye").
            // Additionally, for keys with "roaming." prefix, also inject
            // under the plain key (e.g. "v.bq_eye") so that bone keyframes
            // referencing v.bq_eye directly see the current roaming value
            // every frame, without depending on a transient animation's
            // timeline instruction (like "v.bq_eye = v.roaming.bq_eye != 0
            // ? v.roaming.bq_eye : ...") which only fires when that specific
            // animation plays and is then cached in executedKeyFrames.
            for (Map.Entry<String, Double> roamingEntry : modelRoaming.entrySet()) {
                String varKey = "v." + roamingEntry.getKey();
                MolangParser.VARIABLES.computeIfAbsent(varKey,
                    k -> new LazyVariable(k, 0)).set(roamingEntry.getValue());
                // Strip "roaming." prefix so v.roaming.bq_eye also sets v.bq_eye
                String roamingKey = roamingEntry.getKey();
                if (roamingKey.startsWith("roaming.")) {
                    String plainKey = "v." + roamingKey.substring("roaming.".length());
                    MolangParser.VARIABLES.computeIfAbsent(plainKey,
                        k -> new LazyVariable(k, 0)).set(roamingEntry.getValue());
                }
            }
        }
        // 应用 .molang 函数文件中定义的变量派生规则。
        // @player_ctrl_pre_main.molang 开头: v.anim_ctrl=1;
        // 但我们不执行 .molang 文件，所以在这里设置默认值。
        if (!state.variables.containsKey("v.anim_ctrl")) {
            state.variables.put("v.anim_ctrl", 1.0);
        }
        state.physics.update(renderTicks);
        // Compute time delta for ysm.time_delta
        if (prevRenderTicks >= 0 && renderTicks > prevRenderTicks) {
            timeDelta = (float) ((renderTicks - prevRenderTicks) / 20.0);
        }
        prevRenderTicks = renderTicks;
        currentFrameContext = new FrameContext(state, processor);
    }

    public static void end() {
        currentFrameContext = null;
        // Clear the per-frame roaming variable cache so the next frame
        // picks up any PENDING_ROAMING changes from the GUI thread.
        OpenYsmPlayerControllerRuntime.invalidateFrameRoamingCache();
    }

    public static void clear() {
        STATES.clear();
        currentFrameContext = null;
        OpenYsmPlayerControllerRuntime.invalidateFrameRoamingCache();
    }

    public static double firstOrder(int nameId, double input, double response) {
        if (nameId == MolangStringPool.EMPTY_ID) {
            return 0.0D;
        }
        FrameContext context = currentFrameContext;
        if (context == null) {
            return input;
        }
        return context.state.physics.firstOrder(nameId, input, response);
    }

    public static double secondOrder(int nameId, double input, double frequency, double coefficient, double response) {
        if (nameId == MolangStringPool.EMPTY_ID) {
            return 0.0D;
        }
        FrameContext context = currentFrameContext;
        if (context == null) {
            return input;
        }
        return context.state.physics.secondOrder(nameId, input, frequency, coefficient, response);
    }

    private static final java.util.Set<String> LOGGED_VARS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static double getVariable(String name, double fallback) {
        FrameContext context = currentFrameContext;
        if (context == null) {
            if (name.startsWith("v.roaming.") && LOGGED_VARS.add(name)) {
                com.fox.ysmu.ysmu.LOG.info(
                    "YSM MolangPhysicsRuntime: getVariable('{}') – NO FRAME CONTEXT, fallback={}",
                    name, fallback);
            }
            return fallback;
        }
        Double value = context.state.variables.get(name);
        // 只记录 v.roaming.* 变量，每个变量名只记录第一次
        if (name.startsWith("v.roaming.") && LOGGED_VARS.add(name)) {
            com.fox.ysmu.ysmu.LOG.info(
                "YSM MolangPhysicsRuntime: getVariable('{}') = {} (fallback={})",
                name, value, fallback);
        }
        return value == null ? fallback : value;
    }

    /**
     * Checks whether a variable was explicitly set in the current frame context.
     * This is used by the null-coalescing operator (??) to distinguish between
     * "variable was explicitly set to 0" and "variable was never set (defaults to 0)".
     */
    public static boolean containsKey(String name) {
        FrameContext context = currentFrameContext;
        if (context == null) return false;
        return context.state.variables.containsKey(name);
    }

    public static boolean setVariable(String name, double value) {
        FrameContext context = currentFrameContext;
        if (context == null) {
            return false;
        }
        context.state.variables.put(name, value);
        return true;
    }

    /**
     * Syncs v.* variables set by animation keyframe Molang expressions
     * (e.g. {@code v.idle_time = v.idle_time + 3}) back into the controller's
     * RuntimeState so that YSM controller transition conditions can see
     * the updated values.
     * <p>
     * Keyframes write to {@link ScopeState#variables} (via
     * {@link ScopedMolangVariable}), while controller conditions read from
     * {@link com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime.RuntimeState#variables}.
     * Without this sync, variables only updated in keyframe Molang would
     * appear stuck at their initial value to the controller.
     */
    public static void syncToRuntimeState(Map<String, Double> target) {
        FrameContext context = currentFrameContext;
        if (context == null) return;
        for (Map.Entry<String, Double> entry : context.state.variables.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("v.")) {
                target.put(key.substring(2), entry.getValue());
            }
        }
    }

    public static double boneRotation(int nameId, char axis) {
        IBone bone = bone(nameId);
        if (bone == null) {
            return 0.0D;
        }
        if (axis == 'x') {
            return -Math.toDegrees(bone.getRotationX());
        }
        if (axis == 'y') {
            return -Math.toDegrees(bone.getRotationY());
        }
        return Math.toDegrees(bone.getRotationZ());
    }

    public static double bonePosition(int nameId, char axis) {
        IBone bone = bone(nameId);
        if (bone == null) {
            return 0.0D;
        }
        if (axis == 'x') {
            return bone.getPositionX();
        }
        if (axis == 'y') {
            return bone.getPositionY();
        }
        return bone.getPositionZ();
    }

    public static double boneScale(int nameId, char axis) {
        IBone bone = bone(nameId);
        if (bone == null) {
            return axis == 'x' || axis == 'y' || axis == 'z' ? 1.0D : 0.0D;
        }
        if (axis == 'x') {
            return bone.getScaleX();
        }
        if (axis == 'y') {
            return bone.getScaleY();
        }
        return bone.getScaleZ();
    }

    private static IBone bone(int nameId) {
        FrameContext context = currentFrameContext;
        if (context == null || nameId == MolangStringPool.EMPTY_ID) {
            return null;
        }
        String boneName = MolangStringPool.get(nameId);
        return boneName == null ? null : context.processor.getBone(boneName);
    }

    private static final class FrameContext {
        private final ScopeState state;
        private final AnimationProcessor<?> processor;

        private FrameContext(ScopeState state, AnimationProcessor<?> processor) {
            this.state = state;
            this.processor = processor;
        }
    }

    private static final class ScopeState {
        private final MolangPhysicsState physics = new MolangPhysicsState();
        /** Regular HashMap is safe: all ScopeState access is on the client render thread. */
        private final Map<String, Double> variables = new java.util.HashMap<>();
    }

    private static final class ScopeKey {
        private final UUID playerId;
        private final ResourceLocation modelId;

        private ScopeKey(UUID playerId, ResourceLocation modelId) {
            this.playerId = playerId;
            this.modelId = modelId;
        }

        private static ScopeKey from(EntityPlayer player, ResourceLocation modelId) {
            UUID playerId = player == null ? null : player.getUniqueID();
            return new ScopeKey(playerId, modelId);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ScopeKey)) {
                return false;
            }
            ScopeKey other = (ScopeKey) obj;
            if (playerId == null ? other.playerId != null : !playerId.equals(other.playerId)) {
                return false;
            }
            return modelId == null ? other.modelId == null : modelId.equals(other.modelId);
        }

        @Override
        public int hashCode() {
            int result = playerId == null ? 0 : playerId.hashCode();
            result = 31 * result + (modelId == null ? 0 : modelId.hashCode());
            return result;
        }
    }
}
