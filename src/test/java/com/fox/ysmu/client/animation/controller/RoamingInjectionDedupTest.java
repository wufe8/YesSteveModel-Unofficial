package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime.RuntimeState;

/**
 * 漫游变量注入的"每模型每 pass 一次"去重（{@code markRoamingInjectedIfNeeded}）与它依赖的前提。
 *
 * <p>回归点：{@code tryApplyController} 是每控制器每 tick 的路径，原来每次都把该模型的漫游变量
 * 重新派生别名键、逐个写进 RuntimeState。既然同一个渲染 pass 内所有控制器拿到的是同一份 map
 * 实例，而 RuntimeState 跨帧存活，就可以只注入一次。这个授权的前提必须成立且必须被测：
 * <b>换 pass / 轮盘改值后 {@code getRoamingVarsForModel} 必须给一个新实例</b> ——
 * 否则去重会把旧值冻住（轮盘切了没反应）。
 */
class RoamingInjectionDedupTest {

    private static final ResourceLocation MODEL = new ResourceLocation("ysmu", "_test_roaming_inject_dedup");

    @AfterEach
    void cleanup() {
        com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime.clear();
        OpenYsmPlayerControllerRuntime.clearModelRoamingVars();
        OpenYsmPlayerControllerRuntime.resetAllUserRoamingVars();
    }

    /** 同一 pass 内跳过重复注入；空 map 不注入；换实例必须重新注入。 */
    @Test
    void injectionHappensOncePerMapInstance() {
        RuntimeState state = new RuntimeState("parallel_0");

        Map<String, Double> pass1 = new HashMap<>();
        pass1.put("roaming.x", 0.0d);
        assertTrue(
            OpenYsmPlayerControllerRuntime.markRoamingInjectedIfNeeded(pass1, state),
            "第一次见到这份漫游变量必须注入");
        assertFalse(
            OpenYsmPlayerControllerRuntime.markRoamingInjectedIfNeeded(pass1, state),
            "同一 pass 内的其它控制器不该重复注入同一份值");

        Map<String, Double> pass2 = new HashMap<>(pass1);
        pass2.put("roaming.x", 1.0d);
        assertTrue(
            OpenYsmPlayerControllerRuntime.markRoamingInjectedIfNeeded(pass2, state),
            "换 pass（新实例）必须重新注入，否则轮盘改值不会生效");

        assertFalse(
            OpenYsmPlayerControllerRuntime.markRoamingInjectedIfNeeded(Collections.emptyMap(), state),
            "空 map 没有东西可注入");
    }

    /** 去重依赖的前提：同一 pass 内同一个实例，pass 之间是新实例（值变了对，没变也是新实例）。 */
    @Test
    void theFrameCacheHandsOutOneInstancePerPass() {
        OpenYsmPlayerControllerRuntime.registerModelRoamingVar(MODEL, "roaming.x");
        OpenYsmPlayerControllerRuntime.setModelRoamingDefault(MODEL, "roaming.x", 0.0d);

        Map<String, Double> first = OpenYsmPlayerControllerRuntime.getRoamingVarsForModel(MODEL);
        assertSame(first, OpenYsmPlayerControllerRuntime.getRoamingVarsForModel(MODEL),
            "同一 pass 内所有调用方共用一份（否则每个控制器都会重新算一遍）");

        OpenYsmPlayerControllerRuntime.invalidateFrameRoamingCache();
        OpenYsmPlayerControllerRuntime.noteRoamingWrite(MODEL, "v.roaming.x", 1.0d);
        Map<String, Double> second = OpenYsmPlayerControllerRuntime.getRoamingVarsForModel(MODEL);

        assertNotSame(first, second, "换 pass 必须换实例 —— 注入去重就是按实例判定的");
        org.junit.jupiter.api.Assertions.assertEquals(1.0d, second.get("roaming.x"), 1.0e-9d,
            "新实例里要是改过之后的值");
    }
}
