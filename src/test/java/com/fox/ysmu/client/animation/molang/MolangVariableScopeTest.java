package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.ResourceLocation;

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
