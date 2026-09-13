package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code functions/*.molang} 的分类与登记（YSM-wiki: molang/script）。
 * <p>
 * 文件名决定用途：{@code 名字.molang} 只能被 {@code fn.*} 调用，
 * {@code 名字@player_init.molang} 既是函数又订阅事件，
 * {@code @player_ctrl_main.molang} 是动画控制脚本（没有函数名，只做状态→动画映射）。
 */
class MolangScriptRegistryTest {

    private static final ResourceLocation ID = new ResourceLocation("ysmu", "_test_scripts");

    @AfterEach
    void tearDown() {
        MolangScriptRegistry.clear();
    }

    @Test
    void aPlainFileIsAFunction() {
        assertEquals("halo_battery_indicator", MolangScriptRegistry.functionNameOf("halo_battery_indicator"));
        assertEquals("halo_battery_indicator", MolangScriptRegistry.functionNameOf("halo_battery_indicator.molang"));
        assertEquals("halo_battery_indicator",
            MolangScriptRegistry.functionNameOf("functions/halo_battery_indicator.molang"));
        assertNull(MolangScriptRegistry.eventOf("halo_battery_indicator.molang"));
    }

    @Test
    void anEventFileIsBothAFunctionAndASubscription() {
        assertEquals("setup", MolangScriptRegistry.functionNameOf("setup@player_init"));
        assertEquals("player_init", MolangScriptRegistry.eventOf("setup@player_init"));
        assertEquals("player_update", MolangScriptRegistry.eventOf("move@player_update.molang"));
        assertEquals("sync", MolangScriptRegistry.eventOf("eventsubscriber@sync.molang"));
    }

    /**
     * 带 {@code @} 但后缀不是已知事件的，是**动画控制脚本**而不是函数：参考库里既有
     * {@code @player_ctrl_pre_main.molang}，也有加描述前缀的
     * {@code car_stuff@player_ctrl_parallel_6.molang}（内置包里就有）。
     * 它们只做状态→动画的静态提取；登记成函数不仅有歧义，描述前缀还可能顶掉同名的真函数。
     */
    @Test
    void aControllerScriptIsNotAFunctionEvenWithADescriptionPrefix() {
        assertNull(MolangScriptRegistry.functionNameOf("car_stuff@player_ctrl_parallel_6.molang"));
        assertNull(MolangScriptRegistry.eventOf("car_stuff@player_ctrl_parallel_6.molang"));
        assertNull(MolangScriptRegistry.functionNameOf("简单倒走动画@player_ctrl_main.molang"));
        assertNull(MolangScriptRegistry.functionNameOf("碰墙抬手@player_ctrl_parallel_5.molang"));
        // 没有描述前缀的写法同理（函数名为空）。
        assertNull(MolangScriptRegistry.functionNameOf("@player_ctrl_pre_main.molang"));
        assertNull(MolangScriptRegistry.eventOf("@player_ctrl_pre_main.molang"));
    }



    @Test
    void namesAreCaseInsensitive() {
        assertEquals("myfunc", MolangScriptRegistry.functionNameOf("MyFunc.molang"));
        assertEquals("mover", MolangScriptRegistry.functionNameOf("Mover@Player_Update.molang"));
        assertEquals("player_update", MolangScriptRegistry.eventOf("Mover@Player_Update.molang"));
    }

    @Test
    void registrationsAreReadBackPerModelAndCleared() {
        Map<String, String> functions = new LinkedHashMap<>();
        functions.put("setup", "v.ready = 1;");
        Map<String, List<String>> events = new LinkedHashMap<>();
        events.put(MolangScriptRegistry.EVENT_PLAYER_INIT, new ArrayList<>(Collections.singletonList("setup")));

        MolangScriptRegistry.register(ID, functions, events, Collections.emptyMap());

        assertTrue(MolangScriptRegistry.hasScripts(ID));
        assertEquals("v.ready = 1;", MolangScriptRegistry.functionScript(ID, "SETUP"));
        assertEquals(Collections.singletonList("v.ready = 1;"),
            MolangScriptRegistry.eventScripts(ID, MolangScriptRegistry.EVENT_PLAYER_INIT));
        assertEquals(Collections.singletonList("setup"),
            MolangScriptRegistry.eventFunctionNames(ID, MolangScriptRegistry.EVENT_PLAYER_INIT));
        assertTrue(MolangScriptRegistry.eventScripts(ID, MolangScriptRegistry.EVENT_PLAYER_UPDATE)
            .isEmpty());

        MolangScriptRegistry.clear();
        assertFalse(MolangScriptRegistry.hasScripts(ID));
        assertNull(MolangScriptRegistry.functionScript(ID, "setup"));
    }

    /**
     * 端到端：解释器通过 {@code MolangScriptScope.functionScript} 取函数体，
     * 这个 scope 在游戏里就转发到本注册表 —— 所以这里用一个直连注册表的假 scope 验证
     * {@code fn.*} 能真的调用到 {@code functions/*.molang}。
     */
    @Test
    void theInterpreterResolvesFunctionsThroughTheRegistry() {
        Map<String, String> functions = new LinkedHashMap<>();
        functions.put("add", "return args[0] + args[1];");
        functions.put("twice", "return fn.add(1, 2) * 2;");
        MolangScriptRegistry.register(ID, functions, Collections.emptyMap(), Collections.emptyMap());

        assertEquals(3.0d, MolangScriptInterpreter.evaluate("fn.add(1, 2);", new RegistryScope()), 0.0001d);
        // 函数里再调另一个函数（链式调用）
        assertEquals(6.0d, MolangScriptInterpreter.evaluate("fn.twice();", new RegistryScope()), 0.0001d);
    }

    /** 只把函数体接到注册表的假 scope（其余变量/参数都为空）。 */
    private static final class RegistryScope implements MolangScriptInterpreter.MolangScriptScope {

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
            return MolangScriptRegistry.functionScript(ID, name);
        }
    }
}
