package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime.NamedParallelRoute;
import com.fox.ysmu.client.animation.molang.MolangScriptRegistry;

/**
 * 具名并行槽位的**运行时路由**：第 i 个备用池控制器
 * （{@code (player.)?<族>_extra_<i>_controller}）在当前模型下承载哪个具名槽位。
 * <p>
 * 槽位表本身（排序、索引稳定、缓存）在 {@link NamedParallelSlotsTest} 里测；这里测路由的
 * 三件事：①模型感知（换模型后同一池下标换槽位）；②只有控制脚本、没有 JSON 控制器的槽位
 * 也能被池承载（脚本路由）；③JSON 声明与脚本共用同一份排序；④池子不够时的溢出标记。
 * <p>
 * 这些断言不需要开游戏：路由只依赖 {@code OpenYsmAnimationControllerRegistry} +
 * {@code MolangScriptRegistry} + {@code Config} 的静态值。
 */
class NamedParallelRoutingTest {

    private static final ResourceLocation ID = new ResourceLocation("ysmu", "_test_named_routing");

    @AfterEach
    void tearDown() {
        OpenYsmAnimationControllerRegistry.clear();
        MolangScriptRegistry.clear();
    }

    @Test
    void poolControllersRouteToTheModelsNamedSlots() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "{\"player.pre_parallel_alpha\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"a\"]}}},"
                    + "\"player.pre_parallel_beta\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"b\"]}}}}")),
            Collections.emptyList());

        NamedParallelRoute first = OpenYsmPlayerControllerRuntime
            .routeNamedParallel(ID, "pre_parallel_extra_0_controller");
        assertNotNull(first);
        assertEquals("pre_parallel", first.family);
        assertEquals(0, first.index);
        assertEquals("pre_parallel_alpha", first.controlSlot);
        assertEquals("player.pre_parallel_alpha", first.controllerKey);
        assertFalse(first.overflow());

        NamedParallelRoute second = OpenYsmPlayerControllerRuntime
            .routeNamedParallel(ID, "pre_parallel_extra_1_controller");
        assertEquals("pre_parallel_beta", second.controlSlot);
        assertEquals("player.pre_parallel_beta", second.controllerKey);

        // 公共入口（AnimationManager 用它取脚本槽位）与内部路由结果一致。
        assertEquals("pre_parallel_alpha",
            OpenYsmPlayerControllerRuntime.namedParallelControlSlot(ID, "pre_parallel_extra_0_controller"));
    }

    @Test
    void theTwoFamiliesRouteIndependently() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "{\"player.pre_parallel_alpha\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"a\"]}}},"
                    + "\"player.parallel_beta\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"b\"]}}}}")),
            Collections.emptyList());

        assertEquals("pre_parallel_alpha",
            OpenYsmPlayerControllerRuntime.routeNamedParallel(ID, "pre_parallel_extra_0_controller").controlSlot);
        assertEquals("parallel_beta",
            OpenYsmPlayerControllerRuntime.routeNamedParallel(ID, "parallel_extra_0_controller").controlSlot);
    }

    /** 换模型后同一个池下标必须跟着当前模型走，不能留着上一个模型的槽位。 */
    @Test
    void thePoolFollowsTheModelWhenItSwitches() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "{\"player.pre_parallel_alpha\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"a\"]}}}}")),
            Collections.emptyList());
        assertEquals("pre_parallel_alpha",
            OpenYsmPlayerControllerRuntime.routeNamedParallel(ID, "pre_parallel_extra_0_controller").controlSlot);

        // 同一个动画 id 重新注册 = 玩家换了模型（换模型不会重建实体/控制器）。
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "{\"player.pre_parallel_gamma\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"g\"]}}}}")),
            Collections.emptyList());
        NamedParallelRoute route = OpenYsmPlayerControllerRuntime
            .routeNamedParallel(ID, "pre_parallel_extra_0_controller");
        assertEquals("pre_parallel_gamma", route.controlSlot);
        assertEquals("player.pre_parallel_gamma", route.controllerKey);
        // 上一个模型的槽位不再存在
        assertTrue(OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel")
            .contains("gamma"));
        assertFalse(OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel")
            .contains("alpha"));
    }

    /** 只有 {@code @player_ctrl_<槽位>.molang}、没有 controller/*.json 条目：池仍要承载它。 */
    @Test
    void scriptOnlySlotsAreCarriedByThePool() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "{\"player.main\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"idle\"]}}}}")),
            Collections.emptyList());
        MolangScriptRegistry.register(ID, Collections.emptyMap(), Collections.emptyMap(),
            Collections.singletonMap("parallel_car", "return ctrl.state_continue;"));

        assertEquals(Collections.singletonList("car"),
            OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "parallel"));
        NamedParallelRoute route = OpenYsmPlayerControllerRuntime
            .routeNamedParallel(ID, "parallel_extra_0_controller");
        assertEquals("parallel_car", route.controlSlot);
        assertNull(route.controllerKey, "脚本槽位没有 JSON 控制器可匹配");
    }

    /** 连 controller/*.json 都没有的模型（只有 functions/）：脚本槽位同样可路由。 */
    @Test
    void aModelWithNoControllerJsonStillRoutesItsScriptSlots() {
        MolangScriptRegistry.register(ID, Collections.emptyMap(), Collections.emptyMap(),
            Collections.singletonMap("pre_parallel_pet", "return ctrl.state_continue;"));

        assertEquals(Collections.singletonList("pet"),
            OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel"));
        assertEquals("pre_parallel_pet",
            OpenYsmPlayerControllerRuntime.namedParallelControlSlot(ID, "pre_parallel_extra_0_controller"));
    }

    /**
     * JSON 声明与脚本槽位共用同一份排序，且脚本在控制器之后登记也要被看到
     * （槽位表按帧缓存，缓存的失效条件包含脚本表版本）。
     */
    @Test
    void jsonAndScriptSlotsShareOneStableOrder() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "{\"player.pre_parallel_zeta\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"z\"]}}}}")),
            Collections.emptyList());

        java.util.List<String> before = OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel");
        assertEquals(Collections.singletonList("zeta"), before);
        assertSame(before, OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel"),
            "未变化时复用缓存");

        // 脚本后登记（控制器已注册、缓存可能已经算过）。
        MolangScriptRegistry.register(ID, Collections.emptyMap(), Collections.emptyMap(),
            Collections.singletonMap("pre_parallel_alpha", "return ctrl.state_continue;"));

        java.util.List<String> after = OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel");
        assertEquals(Arrays.asList("alpha", "zeta"), after, "按小写槽位名排序");
        // 下标 0 归脚本槽位、1 归 JSON 槽位；两边看到的顺序完全一致。
        assertEquals("pre_parallel_alpha",
            OpenYsmPlayerControllerRuntime.routeNamedParallel(ID, "pre_parallel_extra_0_controller").controlSlot);
        assertEquals("player.pre_parallel_zeta",
            OpenYsmPlayerControllerRuntime.routeNamedParallel(ID, "pre_parallel_extra_1_controller").controllerKey);
    }

    /** 模型声明的具名槽位比池子多：标记 overflow（不静默截断，由运行时打一次性告警）。 */
    @Test
    void slotsBeyondThePoolReportOverflow() {
        int declared = Config.NAMED_PARALLEL_EXTRA_SLOTS + 1;
        StringBuilder controllers = new StringBuilder("{");
        for (int i = 0; i < declared; i++) {
            if (i > 0) {
                controllers.append(',');
            }
            controllers.append("\"player.pre_parallel_slot")
                .append(i)
                .append("\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"a")
                .append(i)
                .append("\"]}}}");
        }
        controllers.append('}');
        OpenYsmAnimationControllerRegistry.register(ID,
            Collections.singletonList(controllerJson(controllers.toString())), Collections.emptyList());

        assertEquals(declared, OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel").size());
        NamedParallelRoute first = OpenYsmPlayerControllerRuntime
            .routeNamedParallel(ID, "pre_parallel_extra_0_controller");
        assertTrue(first.overflow(), "declared " + declared + " > pool " + Config.NAMED_PARALLEL_EXTRA_SLOTS);
        // 越出池子的下标仍能路由（这个控制器根本没注册），但 overflow 已经标出来了。
        NamedParallelRoute beyondPool = OpenYsmPlayerControllerRuntime
            .routeNamedParallel(ID, "pre_parallel_extra_" + Config.NAMED_PARALLEL_EXTRA_SLOTS + "_controller");
        assertEquals("pre_parallel_slot" + Config.NAMED_PARALLEL_EXTRA_SLOTS, beyondPool.controlSlot);
        assertTrue(beyondPool.overflow());
    }

    /** 模型没有这么多具名槽位：该池控制器本帧什么都不做（但仍归池子管）。 */
    @Test
    void anIndexBeyondTheModelsSlotsResolvesToNothing() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "{\"player.pre_parallel_only\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"o\"]}}}}")),
            Collections.emptyList());

        NamedParallelRoute route = OpenYsmPlayerControllerRuntime
            .routeNamedParallel(ID, "pre_parallel_extra_3_controller");
        assertNotNull(route);
        assertNull(route.controlSlot);
        assertNull(route.controllerKey);
        assertNull(OpenYsmPlayerControllerRuntime.namedParallelControlSlot(ID, "pre_parallel_extra_3_controller"));
    }

    @Test
    void nonPoolNamesDoNotRoute() {
        assertNull(OpenYsmPlayerControllerRuntime.routeNamedParallel(ID, "pre_parallel_0_controller"));
        assertNull(OpenYsmPlayerControllerRuntime.routeNamedParallel(ID, "parallel_7_controller"));
        assertNull(OpenYsmPlayerControllerRuntime.routeNamedParallel(ID, "pre_main"));
        assertNull(OpenYsmPlayerControllerRuntime.routeNamedParallel(ID, "main_controller"));
        assertNull(OpenYsmPlayerControllerRuntime.routeNamedParallel(ID, "pre_parallel_extra_controller"));
        assertNull(OpenYsmPlayerControllerRuntime.routeNamedParallel(ID, null));
    }

    // ---- ctrl.reset / state_stop 的控制器名匹配 ----

    /**
     * reset 只拿得到脚本里的 wiki 槽位名，但运行时状态键可能是 OpenYSM 名或 legacy 名。
     * 三种拼写必须指向同一个槽位，否则 reset 匹配不到、状态机不回到初始状态。
     */
    @Test
    void resetMatchesEveryControllerNameSpelling() {
        assertTrue(OpenYsmPlayerControllerRuntime.matchesControllerName("parallel_6", "player.parallel_6"));
        assertTrue(OpenYsmPlayerControllerRuntime.matchesControllerName("player.parallel_6", "parallel_6"));
        assertTrue(OpenYsmPlayerControllerRuntime.matchesControllerName("main", "main_controller"));
        assertTrue(OpenYsmPlayerControllerRuntime.matchesControllerName("player.main", "main_controller"));
        assertTrue(OpenYsmPlayerControllerRuntime.matchesControllerName("main", "player.main"));
        // 同一槽位的具名并行池：脚本槽位名 vs 池承载的 OpenYSM 键
        assertTrue(OpenYsmPlayerControllerRuntime.matchesControllerName("pre_parallel_表情", "player.pre_parallel_表情"));
        // 不同槽位 / 池控制器本身都不能被误命中
        assertFalse(OpenYsmPlayerControllerRuntime.matchesControllerName("parallel_6", "parallel_7"));
        assertFalse(OpenYsmPlayerControllerRuntime.matchesControllerName("parallel_6", "parallel_extra_0"));
        assertFalse(OpenYsmPlayerControllerRuntime.matchesControllerName(null, "player.main"));
        assertFalse(OpenYsmPlayerControllerRuntime.matchesControllerName("player.main", null));
    }

    private static byte[] controllerJson(String controllers) {
        return ("{\"animation_controllers\":" + controllers + "}").getBytes(StandardCharsets.UTF_8);
    }
}
