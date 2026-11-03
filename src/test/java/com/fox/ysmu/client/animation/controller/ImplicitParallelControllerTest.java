package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.AnimationManager;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Controller;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.ControllerSet;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.google.gson.JsonParser;

import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;
import software.bernie.geckolib3.util.json.JsonAnimationUtils;

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

    @Test
    void ladderKeepsTheImplicitOutfitVisibilityLayerRunning() throws Exception {
        TestPlayer player = player();
        try {
            Animation animation = JsonAnimationUtils.deserializeJsonToAnimation(
                new JsonParser().parse("{\"parallel0\":{\"loop\":true,\"bones\":{"
                    + "\"OutfitA\":{\"scale\":0},\"OutfitB\":{\"scale\":1}}}}")
                    .getAsJsonObject().entrySet().iterator().next(), new MolangParser());
            AnimationFile file = new AnimationFile();
            file.putAnimation("parallel0", animation);
            GeckoLibCache.getInstance().getAnimations().put(ID, file);
            OpenYsmAnimationControllerRegistry.register(ID, Collections.emptyList(),
                Collections.singletonList("parallel0"));
            CustomPlayerEntity actor = new CustomPlayerEntity();
            actor.setMainModel(ID);
            actor.setPlayer(player);
            // Record the selected animation without needing a renderer or OpenGL context.
            AnimationController<CustomPlayerEntity> controller = new AnimationController<CustomPlayerEntity>(
                actor, "parallel_0_controller", 0, event -> PlayState.CONTINUE) {
                @Override
                public void setAnimation(AnimationBuilder builder) {
                    currentAnimationBuilder = builder;
                }
            };
            AnimationEvent<CustomPlayerEntity> event = new AnimationEvent<>(actor, 0, 0, 0, false,
                Collections.emptyList());
            event.setController(controller);
            player.ladder = true;
            Method predicate = AnimationManager.class.getDeclaredMethod("predicateParallelUnpaused",
                AnimationEvent.class, String.class);
            predicate.setAccessible(true);

            assertEquals(PlayState.CONTINUE, predicate.invoke(AnimationManager.getInstance(), event, "parallel0"),
                "a ladder pose must not stop the high-priority outfit selector");
            assertEquals("parallel0", controller.currentAnimationBuilder.getRawAnimationList().get(0).animationName);
            assertEquals(0.0d, animation.boneAnimations.get(0).scaleKeyFrames.xKeyFrames.get(0).getEndValueDouble());
            assertEquals(1.0d, animation.boneAnimations.get(1).scaleKeyFrames.xKeyFrames.get(0).getEndValueDouble());
        } finally {
            GeckoLibCache.getInstance().getAnimations().remove(ID);
            OpenYsmPlayerControllerRuntime.clear();
        }
    }

    @Test
    void crawlConditionsUseTheSameWaterAirborneFallbackAsTheMainAnimation() throws Exception {
        TestPlayer player = player();
        CustomPlayerEntity actor = new CustomPlayerEntity();
        actor.setPlayer(player);
        AnimationEvent<CustomPlayerEntity> moving = new AnimationEvent<>(actor, 0, -0.1f, 0, true,
            Collections.emptyList());
        AnimationEvent<CustomPlayerEntity> still = new AnimationEvent<>(actor, 0, 0, 0, false,
            Collections.emptyList());

        player.ladder = true;
        assertFalse(OpenYsmControllerExpressionEvaluator.evaluateCondition("ctrl.climb", player, moving));
        assertFalse(OpenYsmControllerExpressionEvaluator.evaluateCondition("ctrl.climbing", player, still));
        assertEquals(0.0d, OpenYsmControllerExpressionEvaluator.evaluateCtrlState("climbing", player));
        player.ladder = false;
        assertFalse(OpenYsmControllerExpressionEvaluator.evaluateCondition("ctrl.climb", player, moving));
        assertFalse(OpenYsmControllerExpressionEvaluator.evaluateCondition("ctrl.climbing", player, still));

        // The water gate above uses the real Context; test its ground/movement fallback without a client singleton.
        assertTrue(OpenYsmControllerExpressionEvaluator.matchesCrawlFallback(false, -0.1f, true));
        assertTrue(OpenYsmControllerExpressionEvaluator.matchesCrawlFallback(false, 0.1f, false));
        assertFalse(OpenYsmControllerExpressionEvaluator.matchesCrawlFallback(false, 0, true));
        assertTrue(OpenYsmControllerExpressionEvaluator.matchesCrawlFallback(false, 0, false));
        assertFalse(OpenYsmControllerExpressionEvaluator.matchesCrawlFallback(false, 0.05f, true));
        assertFalse(OpenYsmControllerExpressionEvaluator.matchesCrawlFallback(true, 0.1f, true));
        assertFalse(OpenYsmControllerExpressionEvaluator.matchesCrawlFallback(true, 0.1f, false));
    }

    /** Allocate only the query inputs; no game client, server or world is started. */
    private static TestPlayer player() throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        TestPlayer player = (TestPlayer) unsafe.allocateInstance(TestPlayer.class);
        player.capabilities = new PlayerCapabilities();
        // Use the standard blocking path, not the local-client right-click compatibility fallback.
        player.blockingItem = new ItemStack(new Item() {
            @Override
            public EnumAction getItemUseAction(ItemStack stack) { return EnumAction.block; }
        });
        return player;
    }

    private static final class TestPlayer extends EntityPlayerMP {
        boolean ladder;
        boolean water;
        ItemStack blockingItem;

        private TestPlayer() { super(null, null, null, null); }

        @Override
        public boolean isOnLadder() { return ladder; }

        @Override
        public boolean isInWater() { return water; }

        @Override
        public boolean isUsingItem() { return blockingItem != null; }

        @Override
        public ItemStack getItemInUse() { return blockingItem; }

        @Override
        public UUID getUniqueID() { return new UUID(12, 34); }
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
