package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

/**
 * {@code @player_init} 的触发时机（wiki: molang/script「事件订阅」—— "玩家切换到该模型或玩家实体
 * 加载时"）。
 * <p>
 * 原来的实现用一张"sorted (玩家, 模型)"集合去重，一旦跑过就永不再跑，于是 A→B→A 回到 A 时
 * {@code player_init} 不再触发，init 里建立的随机种子/预置变量会停留在上一轮的值。
 * {@link OpenYsmScriptRuntime#shouldRunPlayerInit} 把这段状态机抽成纯函数，这里用普通
 * HashSet/HashMap 覆盖切换序列（不需要 Minecraft 环境）。
 */
class OpenYsmScriptRuntimeInitTest {

    private static final ResourceLocation MODEL_A = new ResourceLocation("ysmu", "switch_a");
    private static final ResourceLocation MODEL_B = new ResourceLocation("ysmu", "switch_b");
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private final Set<String> initialised = new HashSet<>();
    private final Map<UUID, ResourceLocation> lastModel = new HashMap<>();

    private boolean frame(UUID player, ResourceLocation model) {
        return OpenYsmScriptRuntime.shouldRunPlayerInit(initialised, lastModel, player, model);
    }

    @Test
    void playerInitRunsOnEntryOnceAndAgainAfterSwitchingBack() {
        assertTrue(frame(PLAYER, MODEL_A), "第一次进入 A → 触发 player_init");
        assertFalse(frame(PLAYER, MODEL_A), "同一模型连续渲染 → 不重复触发");
        assertFalse(frame(PLAYER, MODEL_A));

        assertTrue(frame(PLAYER, MODEL_B), "切到 B → 触发 B 的 player_init");
        assertFalse(frame(PLAYER, MODEL_B));

        assertTrue(frame(PLAYER, MODEL_A), "A→B→A 回到 A 必须重新触发（wiki 的触发时机）");
        assertFalse(frame(PLAYER, MODEL_A));

        assertTrue(frame(PLAYER, MODEL_B), "B 侧同样重新武装");
    }

    /** 玩家之间互不影响：另一个玩家进入同一模型也要触发自己的 init，且不被别人的切换牵连。 */
    @Test
    void eachPlayerTracksItsOwnModelIndependently() {
        assertTrue(frame(PLAYER, MODEL_A));
        assertTrue(frame(OTHER, MODEL_A), "另一名玩家首次进入 A 同样触发");
        assertFalse(frame(PLAYER, MODEL_A));
        assertFalse(frame(OTHER, MODEL_A));

        // PLAYER 切到 B 再回 A：只重新武装 PLAYER 自己；OTHER 一直待在 A，不该被影响。
        assertTrue(frame(PLAYER, MODEL_B));
        assertTrue(frame(PLAYER, MODEL_A), "PLAYER 回到 A 重新触发");
        assertFalse(frame(OTHER, MODEL_A), "OTHER 没换过模型，不应被 PLAYER 的切换重新武装");
        assertFalse(frame(PLAYER, MODEL_A));
    }

    /** 重复在两个模型之间切换，每次进入都只触发一次。 */
    @Test
    void repeatedSwitchingTriggersExactlyOncePerEntry() {
        for (int i = 0; i < 3; i++) {
            assertTrue(frame(PLAYER, MODEL_A), "第 " + (i + 1) + " 次进入 A");
            assertFalse(frame(PLAYER, MODEL_A));
            assertTrue(frame(PLAYER, MODEL_B), "第 " + (i + 1) + " 次进入 B");
            assertFalse(frame(PLAYER, MODEL_B));
        }
    }

    @Test
    void nullInputsAreRejected() {
        assertFalse(OpenYsmScriptRuntime.shouldRunPlayerInit(null, lastModel, PLAYER, MODEL_A));
        assertFalse(OpenYsmScriptRuntime.shouldRunPlayerInit(initialised, null, PLAYER, MODEL_A));
        assertFalse(OpenYsmScriptRuntime.shouldRunPlayerInit(initialised, lastModel, null, MODEL_A));
        assertFalse(OpenYsmScriptRuntime.shouldRunPlayerInit(initialised, lastModel, PLAYER, null));
        assertTrue(initialised.isEmpty(), "被拒的调用不能留下状态");
    }
}
