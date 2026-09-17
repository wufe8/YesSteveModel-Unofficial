package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

/**
 * 静态"状态→动画"提取的槽位范围。
 *
 * <p>{@code MOLANG_STATE_MAP}/{@code MOLANG_CONDITIONAL_MAP} 的唯一消费者是 legacy 主状态机
 * {@code AnimationManager.predicateMain}，键是 walk/run/idle/sneak… 这类主身体状态。只有身体层槽位
 * （{@code main}、{@code pre_main}）可以写；覆盖层槽位的脚本由
 * {@code applyControlScript(event, 槽位)} 在自己的控制器上逐帧求值。</p>
 *
 * <p>回归点：某内置子模型的 {@code @player_ctrl_parallel_5}（碰墙抬手）守卫是
 * {@code ctrl.run || ctrl.walk}，而 {@code extractCtrlStateName} 取守卫里**最后一个** ctrl 名
 * = {@code walk}，于是走路状态被替换成只有 4 根骨骼的贴墙防御姿势 {@code defWall} —— 向前走路腿不动、
 * 角色直立。</p>
 */
class MolangScriptRegistryStaticMappingTest {

    @Test
    void bodySlotsMayWriteTheStaticStateMapping() {
        assertTrue(MolangScriptRegistry.allowsStaticStateMapping("简单倒走动画@player_ctrl_main"));
        assertTrue(MolangScriptRegistry.allowsStaticStateMapping("@player_ctrl_main.molang"));
        assertTrue(MolangScriptRegistry.allowsStaticStateMapping("@player_ctrl_pre_main.molang"));
        // 带描述前缀的写法
        assertTrue(MolangScriptRegistry.allowsStaticStateMapping("body@player_ctrl_pre_main.molang"));
        // 大小写不敏感
        assertTrue(MolangScriptRegistry.allowsStaticStateMapping("@PLAYER_CTRL_MAIN.molang"));
    }

    @Test
    void overlaySlotsMayNotWriteIt() {
        assertFalse(MolangScriptRegistry.allowsStaticStateMapping("碰墙抬手@player_ctrl_parallel_5.molang"),
            "并行动画槽位是覆盖层，不能替换主状态动画");
        assertFalse(MolangScriptRegistry.allowsStaticStateMapping("car_stuff@player_ctrl_parallel_6.molang"));
        assertFalse(MolangScriptRegistry.allowsStaticStateMapping("眨眼@player_ctrl_pre_parallel_3.molang"));
        assertFalse(MolangScriptRegistry.allowsStaticStateMapping("@player_ctrl_use.molang"));
        assertFalse(MolangScriptRegistry.allowsStaticStateMapping("@player_ctrl_swing.molang"));
        assertFalse(MolangScriptRegistry.allowsStaticStateMapping("@player_ctrl_hold_mainhand.molang"));
        assertFalse(MolangScriptRegistry.allowsStaticStateMapping("@player_ctrl_armor_0.molang"));
    }

    /**
     * 把"提取会把 walk 当成状态"和"槽位门禁拦住它"两件事一起钉住：
     * 若哪天有人放宽了 {@code allowsStaticStateMapping}，这个断言会指出后果。
     */
    @Test
    void extractionWouldHijackWalkButTheSlotGateBlocksIt() {
        // 就是 碰墙抬手@player_ctrl_parallel_5.molang 的形状：守卫里最后一个 ctrl 名是 walk。
        String body = "ctrl.run || ctrl.walk ? { ctrl.set_animation('defWall'); return ctrl.state_continue; };"
            + " return ctrl.state_stop;";
        Map<String, List<Pair<String, String>>> parsed = MolangFunctionParser
            .parseConditionalAnimations(body.getBytes(StandardCharsets.UTF_8));

        assertTrue(parsed.containsKey("walk"), "提取确实会把该块记到 walk 状态上（这就是劫持来源）");
        assertEquals("defWall", parsed.get("walk")
            .get(0)
            .getValue());
        // 但它属于覆盖层槽位，ClientModelManager 不会把这些映射 merge 进主状态机的映射表。
        assertFalse(MolangScriptRegistry.allowsStaticStateMapping("碰墙抬手@player_ctrl_parallel_5.molang"));
    }

    /** 没有 {@code @player_ctrl_} 槽位的普通函数 / 事件订阅保持历史行为。 */
    @Test
    void plainFunctionsAndEventSubscribersKeepHistoricalBehaviour() {
        assertTrue(MolangScriptRegistry.allowsStaticStateMapping("motorSynth.molang"));
        assertTrue(MolangScriptRegistry.allowsStaticStateMapping("eventsubscriber@sync.molang"));
        assertTrue(MolangScriptRegistry.allowsStaticStateMapping("move@player_update.molang"));
    }
}
