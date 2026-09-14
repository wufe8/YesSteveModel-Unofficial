package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * 动画控制脚本（{@code @player_ctrl_<槽位>.molang}）的执行（wiki: molang/script「动画控制」）。
 *
 * <p>覆盖三件事：文件名 → 槽位；{@code ctrl.set_animation} / 过渡 / reload / reset 的捕获；
 * {@code return ctrl.state_*} 谓词的还原。宿主作用域用假的，所以不依赖 Minecraft。</p>
 */
class AnimationControlScriptTest {

    private static final ResourceLocation MODEL = new ResourceLocation("ysmu", "test_control");

    @BeforeEach
    void clearRegistryAndVariables() {
        MolangScriptRegistry.clear();
        MolangParser.VARIABLES.clear();
    }

    // ---- 文件名 → 槽位 ----

    @Test
    void controlScriptFileNamesMapToSlots() {
        assertEquals("main", MolangScriptRegistry.controlSlotOf("@player_ctrl_main.molang"));
        assertEquals("pre_main", MolangScriptRegistry.controlSlotOf("@player_ctrl_pre_main.molang"));
        // 带描述前缀的写法（参考库里真实存在）
        assertEquals("parallel_6", MolangScriptRegistry.controlSlotOf("car_stuff@player_ctrl_parallel_6.molang"));
        assertEquals("use", MolangScriptRegistry.controlSlotOf("\u5370\u5ea6@player_ctrl_use.molang"));
        // 目录前缀不影响
        assertEquals("main", MolangScriptRegistry.controlSlotOf("functions/@player_ctrl_main.molang"));
        assertEquals("main", MolangScriptRegistry.controlSlotOf("@PLAYER_CTRL_MAIN.molang"));
    }

    @Test
    void ordinaryScriptsAreNotControlScripts() {
        assertNull(MolangScriptRegistry.controlSlotOf("my_func.molang"));
        assertNull(MolangScriptRegistry.controlSlotOf("setup@player_init.molang"));
        assertNull(MolangScriptRegistry.controlSlotOf("setup@sync.molang"));
        assertNull(MolangScriptRegistry.controlSlotOf("@player_ctrl_.molang"), "空槽位不算控制脚本");
        assertNull(MolangScriptRegistry.controlSlotOf("@player_ctrl.molang"));
        // 扩展名由收集阶段（functions/ 资源）保证，这里不重复校验。
        assertEquals("main", MolangScriptRegistry.controlSlotOf("@player_ctrl_main"));
    }

    // ---- 求值 ----

    @Test
    void missingScriptEvaluatesToNull() {
        assertNull(AnimationControlScripts.evaluate(MODEL, "main", new FakeScope()));
    }

    @Test
    void emptyScriptIsANoOp() {
        register("main", "   \n\t ");

        AnimationControlResult result = AnimationControlScripts.evaluate(MODEL, "main", new FakeScope());

        assertNotNull(result);
        assertTrue(result.isNoOp());
        assertNull(result.animationName());
    }

    @Test
    void setAnimationAndStateContinueAreCaptured() {
        register("main", "ctrl.set_animation('\u6b63\u5e38_\u884c\u8d70'); return ctrl.state_continue;");

        AnimationControlResult result = AnimationControlScripts.evaluate(MODEL, "main", new FakeScope());

        assertEquals(AnimationControlResult.Action.CONTINUE, result.action());
        assertEquals("\u6b63\u5e38_\u884c\u8d70", result.animationName());
    }

    /** wiki 的示例形态：条件守卫 + 三元 + 两处 return。 */
    @Test
    void conditionalBranchPicksTheAnimationAndBypassesOtherwise() {
        register("main",
            "v.test_main == 2 ? { ctrl.set_animation('walk', ctrl.loop); return ctrl.state_continue; };"
                + "return ctrl.state_bypass;");
        FakeScope scope = new FakeScope();
        scope.variables.put("v.test_main", 2.0d);

        AnimationControlResult result = AnimationControlScripts.evaluate(MODEL, "main", scope);

        assertEquals(AnimationControlResult.Action.CONTINUE, result.action());
        assertEquals("walk", result.animationName());
        assertEquals(AnimationControlResult.LoopType.LOOP, result.loopType());
    }

    @Test
    void bypassIsReturnedWhenNoBranchApplies() {
        register("main",
            "v.test_main == 1 ? { ctrl.set_animation('run'); return ctrl.state_continue; };"
                + "return ctrl.state_bypass;");

        AnimationControlResult result = AnimationControlScripts.evaluate(MODEL, "main", new FakeScope());

        assertEquals(AnimationControlResult.Action.BYPASS, result.action());
        assertTrue(result.isNoOp());
        assertNull(result.animationName());
    }

    @Test
    void pauseAndStopPredicatesAreRecognised() {
        register("main", "return ctrl.state_pause;");
        assertEquals(AnimationControlResult.Action.PAUSE,
            AnimationControlScripts.evaluate(MODEL, "main", new FakeScope()).action());

        register("main", "return ctrl.state_stop;");
        assertEquals(AnimationControlResult.Action.STOP,
            AnimationControlScripts.evaluate(MODEL, "main", new FakeScope()).action());
    }

    /** 脚本没有 return（或 return 0）时按 NONE 处理 —— 不动内置逻辑。 */
    @Test
    void missingPredicateIsNone() {
        register("main", "ctrl.set_animation('run');");

        AnimationControlResult result = AnimationControlScripts.evaluate(MODEL, "main", new FakeScope());

        assertEquals(AnimationControlResult.Action.NONE, result.action());
        assertTrue(result.isNoOp());
        // set_animation 仍然记录下来（调用方只在 CONTINUE 时使用它）
        assertEquals("run", result.animationName());
    }

    @Test
    void transitionLengthAndIndicateReloadAreCaptured() {
        register("main",
            "ctrl.set_beginning_transition_length(0.25); ctrl.indicate_reload; ctrl.set_animation('x');"
                + " return ctrl.state_continue;");

        AnimationControlResult result = AnimationControlScripts.evaluate(MODEL, "main", new FakeScope());

        assertEquals(0.25d, result.transitionSeconds(), 0.0001d);
        assertTrue(result.indicateReload());
        assertEquals("x", result.animationName());
    }

    /** {@code ctrl.indicate_reload()} 带小括号的写法同样要认。 */
    @Test
    void indicateReloadWithParenthesesWorksToo() {
        register("main", "ctrl.indicate_reload(); return ctrl.state_continue;");

        assertTrue(AnimationControlScripts.evaluate(MODEL, "main", new FakeScope())
            .indicateReload());
    }

    @Test
    void resetIsCaptured() {
        register("main", "ctrl.reset; return ctrl.state_continue;");

        assertTrue(AnimationControlScripts.evaluate(MODEL, "main", new FakeScope())
            .reset());
    }

    /** 动画名可以来自 {@code args[...]}：字符串实参在脚本通道里必须还原成字符串。 */
    @Test
    void animationNameCanComeFromArguments() {
        register("main", "ctrl.set_animation(args[0]); return ctrl.state_continue;");
        int pooled = MolangStringPool.intern("\u8dd1\u6b65");
        FakeScope scope = new FakeScope();
        scope.arguments.add((double) pooled);

        AnimationControlResult result = AnimationControlScripts.evaluate(MODEL, "main", scope);

        assertEquals(AnimationControlResult.Action.CONTINUE, result.action());
        assertEquals("\u8dd1\u6b65", result.animationName());
    }

    /** 宿主作用域里未知的名字仍然要转发下去（否则 ctrl.* 谓词之外的查询会全部变 0）。 */
    @Test
    void unknownNamesAreForwardedToTheHostScope() {
        register("main", "v.host_value == 5 ? { ctrl.set_animation('run'); return ctrl.state_continue; };"
            + "return ctrl.state_bypass;");
        FakeScope scope = new FakeScope();
        scope.variables.put("v.host_value", 5.0d);

        assertEquals("run", AnimationControlScripts.evaluate(MODEL, "main", scope).animationName());
    }

    /** 脚本里有语法错误时不能抛出去（否则动画每帧都在异常路径上）。 */
    @Test
    void brokenScriptDegradesToNoOpInsteadOfThrowing() {
        register("main", "ctrl.set_animation('run' return ctrl.state_continue;");

        AnimationControlResult result = AnimationControlScripts.evaluate(MODEL, "main", new FakeScope());

        assertNotNull(result);
        assertTrue(result.isNoOp());
    }

    /** 宿主作用域建不出来时也不能抛出去 —— prepare/建 scope 同样受保护。 */
    @Test
    void hostScopeCreationFailureDegradesToNoOp() {
        register("main", "ctrl.set_animation('run'); return ctrl.state_continue;");

        AnimationControlResult result = AnimationControlScripts.evaluate(MODEL, "main", () -> {
            throw new IllegalStateException("host scope unavailable");
        });

        assertNotNull(result);
        assertTrue(result.isNoOp());
        assertNull(result.animationName());
    }

    // ---- 夹具 ----

    private static void register(String slot, String body) {
        MolangScriptRegistry.register(MODEL, Collections.emptyMap(), Collections.emptyMap(),
            Collections.singletonMap(slot, body));
    }

    /** 假宿主作用域：变量与实参可注入，其余什么都不做。 */
    static final class FakeScope implements MolangScriptInterpreter.MolangScriptScope {

        final Map<String, Double> variables = new HashMap<>();
        final List<Double> arguments = new ArrayList<>();
        final Map<String, Double> writes = new LinkedHashMap<>();

        @Override
        public double variableValue(String name) {
            Double value = this.variables.get(name);
            return value == null ? 0.0d : value;
        }

        @Override
        public void setVariableValue(String name, double value) {
            this.writes.put(name, value);
            this.variables.put(name, value);
        }

        @Override
        public double functionValue(String name, List<MolangScriptInterpreter.Argument> args) {
            return 0.0d;
        }

        @Override
        public double argument(int index) {
            return index >= 0 && index < this.arguments.size() ? this.arguments.get(index) : 0.0d;
        }

        @Override
        public int argumentCount() {
            return this.arguments.size();
        }

        @Override
        public String functionScript(String name) {
            return null;
        }
    }
}
