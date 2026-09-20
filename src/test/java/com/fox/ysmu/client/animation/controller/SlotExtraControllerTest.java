package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.animation.controller.OpenYsmAnimationControllerRegistry.SlotExtra;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.ControllerSet;

/**
 * 槽位后缀控制器（{@code player.<slot>_<后缀>}）的运行时路由：第 i 个共享备用池控制器
 * （{@code openysm_slot_extra_<i>_controller}）在当前模型下承载哪个后缀控制器。
 * <p>
 * 背景：官方对同一槽位下匹配 {@code ^player\.<slot>(_.+)?$} 的**每个**名字都注册一个独立控制器
 * （OpenYSM {@code ControllerSlotBinder}），所以 {@code player.pre_main} 的控制脚本与
 * {@code player.pre_main_secondary} 状态机是并行控制器；wiki「动画控制器」2.6.3 也写明
 * 同组按名字字母序排序、越靠后优先级越高。YSMU 的 GeckoLib 控制器按名字固定注册，一个槽位只有一个，
 * 所以后缀控制器靠固定池 + 运行时路由承载（同具名并行槽位）。
 * <p>
 * 回归点：池控制器必须真的路由到后缀控制器。修复前后缀状态机只能通过基槽位
 * {@code player.pre_main} 到达，而那里的控制脚本每帧都会先返回、把整台状态机短路掉，
 * 于是它的 {@code on_entry} 赋值（形态开关那类变量）永不执行。
 */
class SlotExtraControllerTest {

    private static final ResourceLocation ID = new ResourceLocation("ysmu", "_test_slot_extra");
    private static final ResourceLocation OTHER = new ResourceLocation("ysmu", "_test_slot_extra_other");

    @AfterEach
    void tearDown() {
        OpenYsmAnimationControllerRegistry.clear();
    }

    @Test
    void suffixNamesAreRecognisedAndBaseSlotsAreNot() {
        assertEquals("pre_main", OpenYsmAnimationControllerRegistry.slotFamilyOf("player.pre_main_secondary"));
        assertEquals("pre_main", OpenYsmAnimationControllerRegistry.slotFamilyOf("pre_main_secondary"));
        assertEquals("post_main", OpenYsmAnimationControllerRegistry.slotFamilyOf("player.post_main_car_hood"));
        assertEquals("post_use", OpenYsmAnimationControllerRegistry.slotFamilyOf("player.post_use_x"));
        // 基槽位本身没有"后缀"，不是后缀控制器。
        for (String slot : com.fox.ysmu.util.ControllerUtils.OPENYSM_SLOTS) {
            assertNull(OpenYsmAnimationControllerRegistry.slotFamilyOf("player." + slot), slot);
        }
        // 并行槽位与"槽位名只是前缀但不是槽位"的名字都不算。
        assertNull(OpenYsmAnimationControllerRegistry.slotFamilyOf("player.pre_parallel_nozzle_anim"));
        assertNull(OpenYsmAnimationControllerRegistry.slotFamilyOf("player.parallel_2"));
        assertNull(OpenYsmAnimationControllerRegistry.slotFamilyOf("player.pre_maintenance"));
    }

    @Test
    void poolControllersRouteToTheModelsSuffixControllersInNameOrder() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                // 一个"只声明不实现"的占位条目：没有 states，不能占池位。
                "\"player.pre_main_empty\":{\"initial_state\":\"d\",\"states\":{}},"
                    + "\"player.post_main_car_trunk\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"t\"]}}},"
                    + "\"player.pre_main_secondary\":{\"initial_state\":\"closed\",\"states\":{\"closed\":{}}},"
                    + "\"player.post_main_car_hood\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"h\"]}}},"
                    + "\"player.pre_main\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"body\"]}}},"
                    + "\"player.parallel_2\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"p\"]}}}")),
            Collections.emptyList());

        List<SlotExtra> extras = OpenYsmAnimationControllerRegistry.slotExtraControllers(ID);
        // 名字小写排序：player.post_main_car_hood < ..._trunk < player.pre_main_secondary。
        assertEquals(3, extras.size(), () -> "extras=" + extras);
        assertEquals("player.post_main_car_hood", extras.get(0).controllerKey);
        assertEquals("post_main", extras.get(0).family);
        assertEquals("post_main_car_hood", extras.get(0).controlSlot);
        assertEquals("player.post_main_car_trunk", extras.get(1).controllerKey);
        assertEquals("player.pre_main_secondary", extras.get(2).controllerKey);
        assertEquals("pre_main", extras.get(2).family);
        assertEquals("pre_main_secondary", extras.get(2).controlSlot);

        assertTrue(OpenYsmPlayerControllerRuntime.isSlotExtraController("openysm_slot_extra_0_controller"));
        assertFalse(OpenYsmPlayerControllerRuntime.isSlotExtraController("player.pre_main"));
        assertFalse(OpenYsmPlayerControllerRuntime.isSlotExtraController("pre_parallel_extra_0_controller"));

        // 公共入口（AnimationManager 用它取控制脚本槽位）与内部路由结果一致。
        assertEquals("pre_main_secondary",
            OpenYsmPlayerControllerRuntime.slotExtraControlSlot(ID, "openysm_slot_extra_2_controller"));
        // 池里没有分配到的下标：路由为空，池控制器就不出动画。
        assertNull(OpenYsmPlayerControllerRuntime.routeSlotExtra(ID, "openysm_slot_extra_3_controller"));
        assertNull(OpenYsmPlayerControllerRuntime.slotExtraControlSlot(ID, "openysm_slot_extra_3_controller"));
        // 不是池控制器时路由必须为空（否则会抢别的槽位的名字）。
        assertNull(OpenYsmPlayerControllerRuntime.routeSlotExtra(ID, "player.pre_main_secondary"));
    }

    @Test
    void poolIndicesRoutePerModel() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "\"player.pre_main_secondary\":{\"initial_state\":\"d\",\"states\":{\"d\":{}}}")),
            Collections.emptyList());
        OpenYsmAnimationControllerRegistry.register(
            OTHER,
            Collections.singletonList(controllerJson(
                "\"player.post_main_car_hood\":{\"initial_state\":\"d\",\"states\":{\"d\":{}}}")),
            Collections.emptyList());

        // 同一个池下标在不同模型下承载不同的后缀控制器（路由是按当前模型算的）。
        assertEquals("pre_main_secondary",
            OpenYsmPlayerControllerRuntime.slotExtraControlSlot(ID, "openysm_slot_extra_0_controller"));
        assertEquals("post_main_car_hood",
            OpenYsmPlayerControllerRuntime.slotExtraControlSlot(OTHER, "openysm_slot_extra_0_controller"));
        // 模型没有后缀控制器时，池控制器什么都不承载。
        assertEquals(Collections.emptyList(), OpenYsmAnimationControllerRegistry.slotExtraControllers(
            new ResourceLocation("ysmu", "_test_slot_extra_missing")));
    }

    @Test
    void shortAndPrefixedSpellingsAreOneSlot() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "\"pre_main_secondary\":{\"initial_state\":\"d\",\"states\":{\"d\":{}}},"
                    + "\"player.pre_main_secondary\":{\"initial_state\":\"d\",\"states\":{\"d\":{}}}")),
            Collections.emptyList());

        List<SlotExtra> extras = OpenYsmAnimationControllerRegistry.slotExtraControllers(ID);
        assertEquals(1, extras.size(), () -> "extras=" + extras);
        // 首次见到的拼写被保留（JSON 里的插入顺序：短名在前）。
        assertEquals("pre_main_secondary", extras.get(0).controllerKey);
        assertEquals("pre_main_secondary", extras.get(0).controlSlot);
    }

    @Test
    void poolOwnsTheSuffixControllersSoTheBaseSlotDoesNotReplayThem() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "\"player.pre_main\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"body\"]}}},"
                    + "\"player.pre_main_secondary\":{\"initial_state\":\"d\",\"states\":{\"d\":{}}},"
                    + "\"player.post_main_car_hood\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"h\"]}}}")),
            Collections.emptyList());

        ControllerSet set = OpenYsmAnimationControllerRegistry.get(ID);
        assertNotNull(set);

        // 池承载的后缀控制器不再从基槽位跑一遍（否则同一份动画会在两个控制器里各播一遍，
        // timeline/音效关键帧触发两次）。基槽位只留它自己的 JSON 控制器。
        assertEquals(Collections.singletonList("player.pre_main"),
            matchedNames(set, "player.pre_main"));
        assertEquals(Collections.emptyList(),
            matchedNames(set, "player.post_main"));
        // 池控制器各自路由到自己的后缀控制器（名字小写排序：player.post_main_* < player.pre_main_*）。
        assertEquals(Collections.singletonList("player.post_main_car_hood"),
            matchedNames(set, "openysm_slot_extra_0_controller"));
        assertEquals(Collections.singletonList("player.pre_main_secondary"),
            matchedNames(set, "openysm_slot_extra_1_controller"));
    }

    @Test
    void disabledPoolFallsBackToTheBaseSlotsFirstMatch() {
        OpenYsmAnimationControllerRegistry.register(
            ID,
            Collections.singletonList(controllerJson(
                "\"player.post_main_car_hood\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"h\"]}}},"
                    + "\"player.post_main_car_trunk\":{\"initial_state\":\"d\",\"states\":{\"d\":{\"animations\":[\"t\"]}}}")),
            Collections.emptyList());
        ControllerSet set = OpenYsmAnimationControllerRegistry.get(ID);
        assertNotNull(set);

        int saved = Config.SLOT_EXTRA_CONTROLLERS;
        try {
            // 池关掉（= 0）：后缀控制器必须仍旧可达，回到基槽位"第一个非空匹配"的老路径，
            // 否则把它调成 0 就等于让这些动画消失。
            Config.SLOT_EXTRA_CONTROLLERS = 0;
            assertEquals(Arrays.asList("player.post_main_car_hood", "player.post_main_car_trunk"),
                matchedNames(set, "player.post_main"));
        } finally {
            Config.SLOT_EXTRA_CONTROLLERS = saved;
        }
    }

    private static List<String> matchedNames(ControllerSet set, String geckoControllerName) {
        List<String> names = new java.util.ArrayList<>();
        for (OpenYsmPlayerControllerRuntime.ControllerMatch match : OpenYsmPlayerControllerRuntime
            .resolveControllers(set, ID, geckoControllerName)) {
            names.add(match.controller.name);
        }
        return names;
    }

    private static byte[] controllerJson(String controllers) {
        return ("{\"animation_controllers\":{" + controllers + "}}").getBytes(StandardCharsets.UTF_8);
    }
}
