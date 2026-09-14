package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.molang.AnimationControlResult;
import com.fox.ysmu.client.animation.molang.AnimationControlScripts;
import com.fox.ysmu.client.animation.molang.MolangScriptInterpreter;
import com.fox.ysmu.client.animation.molang.MolangScriptRegistry;

/**
 * 动画控制脚本的求值结果 → {@link AnimationManager.ControlScriptDecision} 的映射。
 *
 * <p>这里是"脚本说了什么"与"这一帧真的怎么做"之间的那道闸门，也是最容易把模型弄坏的地方：
 * 只有明确的 {@code state_continue} + 动画存在时才允许覆盖；{@code bypass}/{@code pause}
 * 必须原样交回内置逻辑。求值本身（脚本 → 结果）在 {@code AnimationControlScriptTest} 里测。</p>
 */
class AnimationManagerControlScriptDecisionTest {

    private static final ResourceLocation MODEL = new ResourceLocation("ysmu", "ctrl_decision");
    private static final Map<String, Object> ANIMATIONS = new HashMap<>();

    static {
        ANIMATIONS.put("run", Boolean.TRUE);
        ANIMATIONS.put("walk", Boolean.TRUE);
    }

    @BeforeEach
    void reset() {
        MolangScriptRegistry.clear();
    }

    private static AnimationControlResult evaluate(String slot, String body) {
        MolangScriptRegistry.register(MODEL, Collections.emptyMap(), Collections.emptyMap(),
            Collections.singletonMap(slot, body));
        return AnimationControlScripts.evaluate(MODEL, slot, new EmptyScope());
    }

    @Test
    void continueWithExistingAnimationOverridesTheTarget() {
        AnimationManager.ControlScriptDecision decision = AnimationManager.decideControlScript(
            evaluate("main", "ctrl.set_animation('run'); return ctrl.state_continue;"), ANIMATIONS, "idle", MODEL,
            "main");

        assertNotNull(decision);
        assertEquals("run", decision.animationName);
        assertFalse(decision.stop);
    }

    /** 脚本算出了模型里没有的动画名：不能覆盖，否则目标动画会"消失"。 */
    @Test
    void unknownAnimationDoesNotOverride() {
        assertNull(AnimationManager.decideControlScript(
            evaluate("main", "ctrl.set_animation('does_not_exist'); return ctrl.state_continue;"), ANIMATIONS,
            "idle", MODEL, "main"));
    }

    /** {@code bypass} / 没有 return：交回内置逻辑。 */
    @Test
    void bypassAndNoPredicateKeepBuiltInLogic() {
        assertNull(AnimationManager.decideControlScript(
            evaluate("main", "ctrl.set_animation('run'); return ctrl.state_bypass;"), ANIMATIONS, "idle", MODEL,
            "main"));
        assertNull(AnimationManager.decideControlScript(
            evaluate("main", "ctrl.set_animation('run');"), ANIMATIONS, "idle", MODEL, "main"));
    }

    /** {@code state_pause}：GeckoLib 没有暂停原语，必须原样交回（不能当成 stop 把动画掐掉）。 */
    @Test
    void pauseKeepsBuiltInLogic() {
        assertNull(AnimationManager.decideControlScript(
            evaluate("main", "ctrl.set_animation('run'); return ctrl.state_pause;"), ANIMATIONS, "idle", MODEL,
            "main"));
    }

    @Test
    void stopAbortsWithoutResettingControllerState() {
        AnimationManager.ControlScriptDecision decision = AnimationManager.decideControlScript(
            evaluate("main", "return ctrl.state_stop;"), ANIMATIONS, "idle", MODEL, "main");

        assertNotNull(decision);
        assertTrue(decision.stop);
        assertFalse(decision.reset, "state_stop 不重置控制器状态机");
        assertNull(decision.animationName);
    }

    @Test
    void resetAbortsAndResetsControllerState() {
        AnimationManager.ControlScriptDecision decision = AnimationManager.decideControlScript(
            evaluate("main", "ctrl.reset; return ctrl.state_continue;"), ANIMATIONS, "idle", MODEL, "main");

        assertNotNull(decision);
        assertTrue(decision.stop);
        assertTrue(decision.reset, "ctrl.reset 要清掉该槽位的控制器状态");
    }

    /** 没有控制脚本（或功能开关关闭时返回 null）→ 调用方保持原样。 */
    @Test
    void missingScriptYieldsNoDecision() {
        assertNull(AnimationManager.decideControlScript(null, ANIMATIONS, "idle", MODEL, "main"));
    }


    // ---- 控制器名 → 槽位名 ----

    /** OpenYSM 槽位（``player.`` 前缀）与 legacy 控制器（``_controller`` 后缀）都要认。 */
    @Test
    void controllerNamesMapToWikiSlotNames() {
        assertEquals("main", AnimationManager.controlSlotName("player.main"));
        assertEquals("pre_main", AnimationManager.controlSlotName("player.pre_main"));
        assertEquals("parallel_6", AnimationManager.controlSlotName("player.parallel_6"));
        // legacy 的名字
        assertEquals("main", AnimationManager.controlSlotName("main_controller"));
        assertEquals("use", AnimationManager.controlSlotName("use_controller"));
        assertEquals("parallel_6", AnimationManager.controlSlotName("parallel_6_controller"));
        assertEquals("pre_parallel_0", AnimationManager.controlSlotName("pre_parallel_0_controller"));
        // 具名并行备用池不知道模型给的槽位名 —— 不能瞎猜
        assertNull(AnimationManager.controlSlotName("pre_parallel_extra_0_controller"));
        assertNull(AnimationManager.controlSlotName("parallel_extra_2_controller"));
        assertNull(AnimationManager.controlSlotName(null));
        assertNull(AnimationManager.controlSlotName(""));
    }

    /** 空宿主作用域：这些脚本不读写任何变量。 */
    private static final class EmptyScope implements MolangScriptInterpreter.MolangScriptScope {

        @Override
        public double variableValue(String name) {
            return 0.0d;
        }

        @Override
        public void setVariableValue(String name, double value) {}

        @Override
        public double functionValue(String name, List<MolangScriptInterpreter.Argument> args) {
            return 0.0d;
        }

        @Override
        public double argument(int index) {
            return 0.0d;
        }

        @Override
        public int argumentCount() {
            return 0;
        }

        @Override
        public String functionScript(String name) {
            return null;
        }
    }
}
