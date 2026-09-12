package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 具名并行槽位（{@code player.pre_parallel_<非数字>}）的槽位表。
 * <p>
 * wiki 只定义数字槽位 {@code pre_parallel0..7} / {@code parallel0..7}，但官方对非数字后缀
 * 也发控制器，模型因此会把一整块状态机挂在一个具名槽位上。YSMU 用**固定的备用池**
 * （{@code *_extra_<i>_controller}）承载这些槽位：第 i 个池控制器 = 这份有序列表的第 i 项。
 * 固定的理由是 {@code registerControllers} 对每个 animatable 只跑一次，而模型可以随时切换，
 * 按当前模型动态注册会在换模型后失效。
 * <p>
 * 列表必须**索引稳定**：某个具名槽位只声明了空 states（常见于
 * {@code player.pre_parallel_0..7} 那种占位）时也要占一个位置，否则它后面的槽位会整体前移、
 * 错播别人的动画。
 */
class NamedParallelSlotsTest {

    private static final ResourceLocation ID = new ResourceLocation("ysmu", "_test_named_parallel");

    @AfterEach
    void tearDown() {
        OpenYsmAnimationControllerRegistry.clear();
    }

    @Test
    void numericSlotsAreNotNamedSlots() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "{\"player.pre_parallel_0\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"a0\"]}}},"
                    + "\"player.pre_parallel_7\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"a7\"]}}},"
                    + "\"player.pre_parallel_\u8868\u60c5\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"emo\"]}}}}")),
            Collections.emptyList());

        assertEquals(Collections.singletonList("\u8868\u60c5"),
            OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel"));
    }

    @Test
    void aDeclaredButEmptySlotStillOccupiesItsIndex() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                // 甲 只有占位（没有 states），乙 才是真的控制器；两者都必须占位。
                "{\"player.pre_parallel_\u7532\":{\"initial_state\":\"d\",\"states\":{}},"
                    + "\"player.pre_parallel_\u4e59\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"b\"]}}}}")),
            Collections.emptyList());

        List<String> slots = OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel");
        assertEquals(2, slots.size(), "both slots must be listed: " + slots);
        assertEquals(Arrays.asList("\u4e59", "\u7532"), slots, "sorted by slot name, deterministically");
    }

    @Test
    void theTwoFamiliesAreKeptApart() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "{\"player.pre_parallel_low\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"l\"]}}},"
                    + "\"player.parallel_high\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"h\"]}}}}")),
            Collections.emptyList());

        assertEquals(Collections.singletonList("low"),
            OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel"));
        assertEquals(Collections.singletonList("high"),
            OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "parallel"));
        // parallel 族的名字不能被 pre_parallel 族误收
        assertEquals(Collections.emptyList(),
            OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "parallel_car"));
    }

    @Test
    void theShortNameIsFoundWhenTheModelOmitsThePlayerPrefix() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "{\"pre_parallel_short\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"s\"]}}}}")),
            Collections.emptyList());

        assertEquals(Collections.singletonList("short"),
            OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel"));
        assertEquals("pre_parallel_short",
            OpenYsmAnimationControllerRegistry.resolveParallelSlotKey(ID, "pre_parallel", "short"));
    }

    @Test
    void thePlayerPrefixedNameWinsWhenBothExist() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "{\"player.pre_parallel_dup\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"long\"]}}},"
                    + "\"pre_parallel_dup\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"short\"]}}}}")),
            Collections.emptyList());

        // 同一个槽位只占一个位置（按槽位名去重），前缀写法优先。
        assertEquals(Collections.singletonList("dup"),
            OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel"));
        assertEquals("player.pre_parallel_dup",
            OpenYsmAnimationControllerRegistry.resolveParallelSlotKey(ID, "pre_parallel", "dup"));
    }

    @Test
    void unknownModelsAndSlotsResolveToNothing() {
        assertEquals(Collections.emptyList(),
            OpenYsmAnimationControllerRegistry.namedParallelSlots(new ResourceLocation("ysmu", "_absent"), "parallel"));
        assertEquals(Collections.emptyList(),
            OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "parallel"));
        assertEquals(null, OpenYsmAnimationControllerRegistry.resolveParallelSlotKey(ID, "parallel", "nope"));
    }

    /**
     * 池控制器的谓词每帧都要问一次槽位表，所以结果是缓存的 —— 缓存挂在 ControllerSet 上
     * （注册时整体替换），因此重新注册同一个模型必须立刻看到新槽位，不能留着旧的。
     */
    @Test
    void reRegisteringTheSameModelReplacesTheSlotList() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "{\"player.pre_parallel_first\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"f\"]}}}}")),
            Collections.emptyList());
        assertEquals(Collections.singletonList("first"),
            OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel"));

        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "{\"player.pre_parallel_second\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"s\"]}}}}")),
            Collections.emptyList());
        assertEquals(Collections.singletonList("second"),
            OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel"));
    }

    @Test
    void repeatedQueriesReturnTheCachedList() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "{\"player.pre_parallel_cached\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"c\"]}}}}")),
            Collections.emptyList());

        List<String> first = OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel");
        List<String> second = OpenYsmAnimationControllerRegistry.namedParallelSlots(ID, "pre_parallel");
        assertEquals(first, second);
        org.junit.jupiter.api.Assertions.assertSame(first, second, "the cached list must be reused");
    }

    /**
     * 池名解析：池控制器名也以 pre_parallel_ / parallel_ 开头，必须先认出来，
     * 否则会掉进数字槽位解析（对 extra_0 返回 -1，什么都不匹配）。
     */
    @Test
    void poolControllerNamesAreParsedAndNotMistakenForNumericSlots() {
        assertArrayEquals(new String[] { "pre_parallel", "0" },
            OpenYsmPlayerControllerRuntime.parseNamedParallelPoolController("pre_parallel_extra_0_controller"));
        assertArrayEquals(new String[] { "parallel", "3" },
            OpenYsmPlayerControllerRuntime.parseNamedParallelPoolController("parallel_extra_3_controller"));
        assertArrayEquals(new String[] { "pre_parallel", "1" },
            OpenYsmPlayerControllerRuntime.parseNamedParallelPoolController("player.pre_parallel_extra_1_controller"));

        // 数字槽位、缺序号、别家的控制器都不是池
        assertNull(OpenYsmPlayerControllerRuntime.parseNamedParallelPoolController("pre_parallel_0_controller"));
        assertNull(OpenYsmPlayerControllerRuntime.parseNamedParallelPoolController("parallel_7_controller"));
        assertNull(OpenYsmPlayerControllerRuntime.parseNamedParallelPoolController("pre_parallel_extra_controller"));
        assertNull(OpenYsmPlayerControllerRuntime.parseNamedParallelPoolController("main_controller"));
        assertNull(OpenYsmPlayerControllerRuntime.parseNamedParallelPoolController(null));
    }

    private static byte[] controllerJson(String controllers) {
        return ("{\"animation_controllers\":" + controllers + "}").getBytes(StandardCharsets.UTF_8);
    }
}
