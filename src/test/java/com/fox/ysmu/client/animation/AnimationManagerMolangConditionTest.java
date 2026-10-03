package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.animation.controller.RoamingVariables;
import com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

/**
 * {@code .molang} 名称映射的第二档：**条件替代动画**的选取规则。
 * <p>
 * 规则来自脚本本身的写法：一个 {@code ctrl.<state>} 块里可能有多个条件分支，脚本顺序就是
 * 优先级，第一个成立的那条决定动画名；一条都不成立才回落到该状态的默认映射
 * （{@link AnimationManager#pickConditionalAnimation} 的调用点）。
 */
class AnimationManagerMolangConditionTest {

    private static Pair<String, String> pair(String condition, String animation) {
        return Pair.of(condition, animation);
    }

    @Test
    void theFirstMatchingConditionWins() {
        List<Pair<String, String>> alternatives = Arrays.asList(
            pair("v.show_car", "driving"),
            pair("!v.show_car", "walking"));

        assertEquals("driving", pick(alternatives, "v.show_car"));
        assertEquals("walking", pick(alternatives, "!v.show_car"));
    }

    @Test
    void noMatchFallsThroughToNullSoTheCallerCanUseTheDefault() {
        List<Pair<String, String>> alternatives = Collections.singletonList(pair("v.never", "unused"));

        assertNull(pick(alternatives, "v.other"));
    }

    @Test
    void missingAlternativesAndNullEntriesAreIgnored() {
        assertNull(AnimationManager.pickConditionalAnimation(null, condition -> true));
        assertNull(AnimationManager.pickConditionalAnimation(Collections.emptyList(), condition -> true));
        assertNull(AnimationManager.pickConditionalAnimation(
            Collections.singletonList(pair(null, null)), condition -> true));
        assertEquals("b", AnimationManager.pickConditionalAnimation(
            Arrays.asList(pair("a", null), pair("b", "b")), condition -> true));
    }

    /** Exercise the actual mapping condition path without constructing a Minecraft world. */
    @Test
    void plainAndCompoundConditionsReadTheRemoteActorNotTheLocalGui() throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        EntityPlayerMP first = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        EntityPlayerMP second = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        Field uuid = Entity.class.getDeclaredField("entityUniqueID");
        uuid.setAccessible(true);
        uuid.set(first, UUID.fromString("44444444-4444-4444-4444-444444444444"));
        uuid.set(second, UUID.fromString("55555555-5555-5555-5555-555555555555"));
        Method evaluate = AnimationManager.class.getDeclaredMethod("evaluateSimpleCondition", String.class,
            AnimationEvent.class);
        evaluate.setAccessible(true);
        ResourceLocation model = new ResourceLocation("ysmu", "condition_actor");
        RoamingVariables.registerModelRoamingVar(model, "roaming.visible");
        RoamingVariables.setModelRoamingDefault(model, "roaming.visible", 0);
        RoamingVariables.PENDING_ROAMING.put("roaming.visible", 1.0d);
        RoamingVariables.markRoamingExplicit(model, "roaming.visible");
        try {
            CustomPlayerEntity entity = new CustomPlayerEntity();
            entity.setPlayer(first);
            AnimationEvent<CustomPlayerEntity> event = new AnimationEvent<>(entity, 0, 0, 0, false,
                Collections.emptyList());
            MolangPhysicsRuntime.runWithVariableScope(first, model, () -> {
                assertEquals(false, condition(evaluate, "v.roaming.visible", event));
                assertEquals(true, condition(evaluate, "!v.roaming.visible", event));
                assertEquals(false, condition(evaluate, "v.roaming.visible && true", event));
                MolangPhysicsRuntime.setVariable("v.roaming.visible", 1);
                assertEquals(true, condition(evaluate, "v.roaming.visible", event));
                assertEquals(true, condition(evaluate, "v.roaming.visible && true", event));
            });
            entity.setPlayer(second);
            MolangPhysicsRuntime.runWithVariableScope(second, model, () ->
                assertEquals(false, condition(evaluate, "v.roaming.visible", event)));
            entity.setPlayer(first);
            MolangPhysicsRuntime.runWithVariableScope(first, model, () ->
                assertEquals(true, condition(evaluate, "v.roaming.visible", event)));
            assertEquals(1.0d, RoamingVariables.PENDING_ROAMING.get("roaming.visible"), 0.0d);
        } finally {
            MolangPhysicsRuntime.clear();
            RoamingVariables.clearModelRoamingVars();
            RoamingVariables.resetAllUserRoamingVars();
        }
    }

    private static boolean condition(Method method, String expression, AnimationEvent<?> event) {
        try {
            return (Boolean) method.invoke(null, expression, event);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** 只有一个条件成立时，不管它在列表的哪个位置都取它。 */
    private static String pick(List<Pair<String, String>> alternatives, String... trueConditions) {
        Set<String> truthy = new HashSet<>(Arrays.asList(trueConditions));
        return AnimationManager.pickConditionalAnimation(alternatives, truthy::contains);
    }
}
