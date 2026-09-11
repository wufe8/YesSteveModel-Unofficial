package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Controller;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.ControllerSet;

/**
 * The implicit parallel controllers are what make a model that only declares
 * {@code player.parallel_0..7} still play its {@code pre_parallelN} animations —
 * the ones that scale/offset parts from {@code v.roaming.*}, which is what makes
 * the 轮盘 checkboxes and radios do anything. They mirror OpenYSM's
 * {@code ParallelProcessor}, whose animation-name matcher is
 * {@code ^(pre_)?parallel[0-7]$} and whose declared entries always win.
 */
class ImplicitParallelControllerTest {

    private static final ResourceLocation ID = new ResourceLocation("ysmu", "_test_parallel");

    @AfterEach
    void tearDown() {
        OpenYsmAnimationControllerRegistry.clear();
    }

    @Test
    void numericSlotsAreSynthesisedWhenTheModelDoesNotDeclareThem() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.emptyList(),
            Arrays.asList("idle", "pre_parallel0", "pre_parallel6", "parallel7", "pre_parallel8", "pre_parallelx"));

        ControllerSet set = OpenYsmAnimationControllerRegistry.get(ID);
        assertNotNull(set, "a set with synthesised controllers must be kept");
        assertEquals("pre_parallel0", soleAnimation(set, "player.pre_parallel_0"));
        assertEquals("pre_parallel6", soleAnimation(set, "player.pre_parallel_6"));
        assertEquals("parallel7", soleAnimation(set, "player.parallel_7"));
        // Off-spec names stay untouched: the wiki defines the slots as pre_parallel0..7 / parallel0..7.
        assertFalse(set.controllers.containsKey("player.pre_parallel_8"));
        assertFalse(set.controllers.containsKey("player.pre_parallel_x"));
        assertTrue(OpenYsmAnimationControllerRegistry.hasParallelController(ID));
    }

    @Test
    void declaredControllerEntriesWinOverSynthesisedOnes() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "{\"player.pre_parallel_2\":{\"initial_state\":\"default\",\"states\":{\"default\":{\"animations\":[\"declared_animation\"]}}},"
                    + "\"player.pre_parallel_3\":{\"initial_state\":\"default\",\"states\":{}}}")),
            Arrays.asList("pre_parallel2", "pre_parallel3"));

        ControllerSet set = OpenYsmAnimationControllerRegistry.get(ID);
        assertNotNull(set);
        // A declared controller owns its slot: the raw animation must not replace it.
        assertEquals("declared_animation", soleAnimation(set, "player.pre_parallel_2"));
        // ...and an entry that declares no states (a common way to "reserve" a slot while
        // another controller plays the animation) still suppresses the implicit one.
        assertTrue(set.declaredNames.contains("player.pre_parallel_3"));
        assertFalse(set.controllers.containsKey("player.pre_parallel_3"));
    }

    private static byte[] controllerJson(String controllers) {
        return ("{\"animation_controllers\":" + controllers + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static String soleAnimation(ControllerSet set, String controllerName) {
        Controller controller = set.controllers.get(controllerName);
        assertNotNull(controller, "missing controller " + controllerName);
        return controller.states.get(controller.initialState)
            .animations.get(0).animationName;
    }
}
