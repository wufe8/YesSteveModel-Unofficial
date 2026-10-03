package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;
import com.fox.ysmu.client.animation.controller.OpenYsmScriptScope;
import com.fox.ysmu.client.animation.controller.RoamingVariables;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.ScopedMolangVariable;

import org.junit.jupiter.api.AfterEach;

import org.junit.jupiter.api.Test;

/**
 * {@code MolangPhysicsRuntime.setVariable} needs a frame context. Script events that
 * run outside the render pass — {@code @sync}, dispatched from a scheduled task
 * between frames — previously had none, so every {@code v.*} assignment in them was
 * silently dropped while the code still claimed cross-client sync worked.
 * {@code runWithVariableScope} installs the (player, model) scope for the duration of
 * the body, keeps the write visible to that scope's next render frame, and removes it
 * afterwards without touching any other player/model.
 */
class MolangVariableScopeTest {

    private static final ResourceLocation MODEL = new ResourceLocation("ysmu", "molang_scope_test");
    private static final ResourceLocation OTHER = new ResourceLocation("ysmu", "molang_scope_test_b");
    private static final UUID LOCAL = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REMOTE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_PLAYER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @AfterEach
    void cleanup() {
        MolangPhysicsRuntime.clear();
        RoamingVariables.clearModelRoamingVars();
        RoamingVariables.resetAllUserRoamingVars();
    }

    private static void actor(UUID id, ResourceLocation model, boolean local, Runnable body) {
        MolangPhysicsRuntime.runWithVariableScope(id, model, local, body);
    }

    @Test
    void remoteSyncAndRenderWritesCannotChangeLocalGuiOrOtherActors() {
        RoamingVariables.setModelRoamingDefault(MODEL, "roaming.mode", 2.0d);
        RoamingVariables.PENDING_ROAMING.put("roaming.mode", 9.0d);
        RoamingVariables.markRoamingExplicit(MODEL, "roaming.mode");
        RoamingVariables.PENDING_ROAMING.put("lock_wheel", 1.0d);

        actor(REMOTE, MODEL, false, () -> {
            assertEquals(2.0d, MolangPhysicsRuntime.getVariable("v.roaming.mode", -1), 0.0d);
            assertFalse(OpenYsmPlayerControllerRuntime.isRoamingExplicit(MODEL, "roaming.mode"),
                "a remote actor must not inherit the local GUI's explicit mark");
            OpenYsmScriptScope scope = new OpenYsmScriptScope(null, null, MODEL, Collections.singletonList(4.0d));
            MolangScriptInterpreter.evaluate("v.roaming.mode=args[0];", scope);
            assertEquals(4.0d, scope.variableValue("v.roaming.mode"), 0.0d);
            assertTrue(OpenYsmPlayerControllerRuntime.isRoamingExplicit(MODEL, "roaming.mode"));
            assertEquals(0.0d, scope.variableValue("v.lock_wheel"), 0.0d,
                "controller fallback must not read the local wheel state");
        });
        assertEquals(9.0d, RoamingVariables.PENDING_ROAMING.get("roaming.mode"), 0.0d);

        actor(LOCAL, MODEL, true, () -> {
            assertEquals(9.0d, MolangPhysicsRuntime.getVariable("v.roaming.mode", -1), 0.0d);
            actor(OTHER_PLAYER, MODEL, false, () -> {
                assertEquals(2.0d, MolangPhysicsRuntime.getVariable("v.roaming.mode", -1), 0.0d);
                assertFalse(OpenYsmPlayerControllerRuntime.isRoamingExplicit(MODEL, "roaming.mode"));
            });
            assertEquals(9.0d, MolangPhysicsRuntime.getVariable("v.roaming.mode", -1), 0.0d,
                "nested remote work must restore the local scope");
        });
        actor(REMOTE, MODEL, false, () -> {
            assertEquals(4.0d, MolangPhysicsRuntime.getActorRoamingVariables(MODEL).get("roaming.mode"), 0.0d,
                "next pass must retain this actor's sync write instead of defaults or GUI values");
            Map<String, Double> controller = new HashMap<>();
            MolangPhysicsRuntime.syncToRuntimeState(controller);
            assertEquals(4.0d, controller.get("roaming.mode"), 0.0d);
            assertEquals(4.0d, new OpenYsmScriptScope(null, null, MODEL, null)
                .variableValue("v.roaming.mode"), 0.0d);
            MolangPhysicsRuntime.setVariable("v.roaming.mode", 5.0d);
        });
        actor(REMOTE, OTHER, false, () ->
            assertEquals(0.0d, MolangPhysicsRuntime.getVariable("v.roaming.mode", 0), 0.0d));
        assertEquals(9.0d, RoamingVariables.PENDING_ROAMING.get("roaming.mode"), 0.0d);
    }

    @Test
    void remoteExplicitZeroIsScopedAndSurvivesReinjection() throws Exception {
        RoamingVariables.setModelRoamingDefault(MODEL, "roaming.zero", 0.0d);
        RoamingVariables.PENDING_ROAMING.put("roaming.zero", 0.0d);
        RoamingVariables.markRoamingExplicit(MODEL, "roaming.zero");
        MolangParser.ExplicitVariableLookup previous = MolangParser.explicitVariableLookup;
        ScopedMolangVariable.ScopedVariableStore previousStore = ScopedMolangVariable.store;
        try {
            // Same callbacks as AnimationRegister, without initializing the Minecraft client.
            ScopedMolangVariable.store = new ScopedMolangVariable.ScopedVariableStore() {
                public boolean contains(String name) { return MolangPhysicsRuntime.containsKey(name); }
                public double get(String name, double fallback) { return MolangPhysicsRuntime.getVariable(name, fallback); }
                public boolean set(String name, double value) { return MolangPhysicsRuntime.setVariable(name, value); }
            };
            MolangParser.explicitVariableLookup = name -> MolangPhysicsRuntime.containsKey(name)
                && OpenYsmPlayerControllerRuntime.isRoamingExplicit(MolangPhysicsRuntime.getCurrentModelId(),
                    name.startsWith("v.") ? name.substring(2) : name);
            MolangParser parser = new MolangParser();
            software.bernie.geckolib3.core.molang.expressions.MolangExpression zero =
                parser.parseExpression("v.roaming.zero ?? 7");
            software.bernie.geckolib3.core.molang.expressions.MolangExpression longPrefixZero =
                parser.parseExpression("variable.roaming.zero ?? 7");
            actor(REMOTE, MODEL, false, () -> {
                assertEquals(7.0d, zero.get(), 0.0d,
                    "a local explicit zero must not suppress the remote default");
                new OpenYsmScriptScope(null, null, MODEL, null).setVariableValue("v.roaming.zero", 0.0d);
                assertEquals(0.0d, zero.get(), 0.0d);
                assertEquals(0.0d, longPrefixZero.get(), 0.0d);
            });
            actor(REMOTE, MODEL, false, () ->
                assertEquals(0.0d, zero.get(), 0.0d));
            actor(OTHER_PLAYER, MODEL, false, () ->
                assertEquals(7.0d, zero.get(), 0.0d));
        } finally {
            MolangParser.explicitVariableLookup = previous;
            ScopedMolangVariable.store = previousStore;
            MolangParser.VARIABLES.remove("v.roaming.zero");
        }
    }

    @Test
    void timelineGlobalFallbackChecksActorAsWellAsModel() {
        actor(REMOTE, MODEL, false, () -> {
            MolangPhysicsRuntime.noteGlobalVarOwner("v.roaming.timeline");
            assertEquals(8.0d, MolangPhysicsRuntime.getGlobalScopedValue("v.roaming.timeline", 8), 0.0d);
        });
        actor(LOCAL, MODEL, true, () ->
            assertEquals(0.0d, MolangPhysicsRuntime.getGlobalScopedValue("v.roaming.timeline", 8), 0.0d));
        actor(OTHER_PLAYER, MODEL, false, () ->
            assertEquals(0.0d, MolangPhysicsRuntime.getGlobalScopedValue("v.roaming.timeline", 8), 0.0d));
        actor(LOCAL, MODEL, true, () -> {
            MolangPhysicsRuntime.noteGlobalVarOwner("v.roaming.timeline");
            assertEquals(8.0d, MolangPhysicsRuntime.getGlobalScopedValue("v.roaming.timeline", 8), 0.0d);
        });
        actor(REMOTE, MODEL, false, () ->
            assertEquals(0.0d, MolangPhysicsRuntime.getGlobalScopedValue("v.roaming.timeline", 8), 0.0d));
    }

    @Test
    void remoteBareAliasCalibrationDoesNotDisableLocalAliases() {
        RoamingVariables.setModelRoamingDefault(MODEL, "roaming.alias", 2.0d);
        actor(REMOTE, MODEL, false, () -> MolangPhysicsRuntime.setVariable("v.alias", 6.0d));
        actor(REMOTE, MODEL, false, () ->
            assertEquals(6.0d, MolangPhysicsRuntime.getVariable("v.alias", -1), 0.0d));
        actor(LOCAL, MODEL, true, () ->
            assertEquals(2.0d, MolangPhysicsRuntime.getVariable("v.alias", -1), 0.0d,
                "another actor's self-calibration cannot remove the local wheel alias"));
        actor(OTHER_PLAYER, MODEL, false, () ->
            assertEquals(2.0d, MolangPhysicsRuntime.getVariable("v.alias", -1), 0.0d));
    }

    @Test
    void localAnimationWriteStillUpdatesGuiAndPlayerClearDropsRemoteState() {
        actor(LOCAL, MODEL, true, () -> MolangPhysicsRuntime.setVariable("v.roaming.form", 3.0d));
        assertEquals(3.0d, RoamingVariables.PENDING_ROAMING.get("roaming.form"), 0.0d);
        actor(REMOTE, MODEL, false, () -> MolangPhysicsRuntime.setVariable("v.roaming.form", 4.0d));
        MolangPhysicsRuntime.clearPlayer(REMOTE);
        actor(REMOTE, MODEL, false, () -> {
            assertEquals(0.0d, MolangPhysicsRuntime.getVariable("v.roaming.form", 0), 0.0d);
            assertFalse(OpenYsmPlayerControllerRuntime.isRoamingExplicit(MODEL, "roaming.form"));
        });
        assertEquals(3.0d, RoamingVariables.PENDING_ROAMING.get("roaming.form"), 0.0d);
    }

    @Test
    void renderMirroringDoesNotMarkDefaultsExplicitOrOverwriteOwnedAliases() {
        ResourceLocation model = com.fox.ysmu.client.model.CustomPlayerModel.DEFAULT_MAIN_MODEL;
        RoamingVariables.setModelRoamingDefault(model, "roaming.mirror_probe", 2.0d);
        com.fox.ysmu.client.entity.CustomPlayerEntity entity = new com.fox.ysmu.client.entity.CustomPlayerEntity();
        software.bernie.geckolib3.core.processor.AnimationProcessor<com.fox.ysmu.client.entity.CustomPlayerEntity>
            processor = new software.bernie.geckolib3.core.processor.AnimationProcessor<>(null);
        ScopedMolangVariable.ScopedVariableStore previous = ScopedMolangVariable.store;
        try {
            ScopedMolangVariable.store = new ScopedMolangVariable.ScopedVariableStore() {
                public boolean contains(String name) { return MolangPhysicsRuntime.containsKey(name); }
                public double get(String name, double fallback) { return MolangPhysicsRuntime.getVariable(name, fallback); }
                public boolean set(String name, double value) { return MolangPhysicsRuntime.setVariable(name, value); }
            };
            MolangPhysicsRuntime.begin(entity, 0, processor);
            assertFalse(OpenYsmPlayerControllerRuntime.isRoamingExplicit(model, "roaming.mirror_probe"));
            MolangPhysicsRuntime.setVariable("v.mirror_probe", 6.0d);
            MolangPhysicsRuntime.end();
            MolangPhysicsRuntime.begin(entity, 0, processor);
            assertEquals(6.0d, MolangPhysicsRuntime.getVariable("v.mirror_probe", 0), 0.0d,
                "global compatibility mirroring must not undo alias self-calibration");
            assertFalse(OpenYsmPlayerControllerRuntime.isRoamingExplicit(model, "roaming.mirror_probe"));
        } finally {
            MolangPhysicsRuntime.end();
            ScopedMolangVariable.store = previous;
            MolangParser.VARIABLES.remove("v.roaming.mirror_probe");
            MolangParser.VARIABLES.remove("v.mirror_probe");
        }
    }

    @Test
    void outOfFrameWritesWouldBeDroppedButAScopedBodyKeepsThem() {
        MolangPhysicsRuntime.clear();

        assertFalse(MolangPhysicsRuntime.setVariable("v.probe", 1.0d), "no frame context -> the write is dropped");

        MolangPhysicsRuntime.runWithVariableScope(null, MODEL, () -> {
            assertTrue(MolangPhysicsRuntime.setVariable("v.probe", 7.0d), "the scope accepts the write");
            assertEquals(7.0d, MolangPhysicsRuntime.getVariable("v.probe", 0.0d), 0.0d);
            assertEquals(MODEL, MolangPhysicsRuntime.getCurrentModelId());
        });

        assertFalse(MolangPhysicsRuntime.setVariable("v.probe", 9.0d), "the scope is gone afterwards");

        // The value written inside the scope survives in that (player, model) ScopeState,
        // so the next render frame of the same model reads it.
        MolangPhysicsRuntime.runWithVariableScope(null, MODEL, () -> {
            assertEquals(7.0d, MolangPhysicsRuntime.getVariable("v.probe", 0.0d), 0.0d);
        });
        MolangPhysicsRuntime.clear();
    }

    /** Scopes are per model: a write for one model must not leak into another. */
    @Test
    void scopesAreIsolatedPerModel() {
        MolangPhysicsRuntime.clear();

        MolangPhysicsRuntime.runWithVariableScope(null, MODEL, () -> MolangPhysicsRuntime.setVariable("v.x", 1.0d));
        MolangPhysicsRuntime.runWithVariableScope(null, OTHER, () -> {
            assertEquals(0.0d, MolangPhysicsRuntime.getVariable("v.x", 0.0d), 0.0d,
                "another model must not see the write");
        });
        MolangPhysicsRuntime.runWithVariableScope(null, MODEL, () -> {
            assertEquals(1.0d, MolangPhysicsRuntime.getVariable("v.x", 0.0d), 0.0d, "the owning model still sees it");
        });
        MolangPhysicsRuntime.clear();
    }

    /** A nested body sees only its own scope and the outer one is restored after it. */
    @Test
    void aNestedScopeRestoresTheOuterOne() {
        MolangPhysicsRuntime.clear();

        MolangPhysicsRuntime.runWithVariableScope(null, MODEL, () -> {
            MolangPhysicsRuntime.setVariable("v.x", 1.0d);
            MolangPhysicsRuntime.runWithVariableScope(null, OTHER, () -> {
                assertEquals(0.0d, MolangPhysicsRuntime.getVariable("v.x", 0.0d), 0.0d,
                    "the inner scope belongs to another model");
                MolangPhysicsRuntime.setVariable("v.y", 2.0d);
            });
            assertEquals(MODEL, MolangPhysicsRuntime.getCurrentModelId(), "the outer scope is restored");
            assertEquals(1.0d, MolangPhysicsRuntime.getVariable("v.x", 0.0d), 0.0d);
        });
        MolangPhysicsRuntime.clear();
    }

    /** A null body is a no-op; a null model id runs the body with no scope at all. */
    @Test
    void nullBodyAndNullModelDegradeSafely() {
        MolangPhysicsRuntime.clear();
        MolangPhysicsRuntime.runWithVariableScope(null, MODEL, null);

        MolangPhysicsRuntime.runWithVariableScope(null, null, () -> {
            assertFalse(MolangPhysicsRuntime.setVariable("v.x", 1.0d));
        });
        MolangPhysicsRuntime.clear();
    }
}
