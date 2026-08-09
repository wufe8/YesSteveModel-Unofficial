package software.bernie.geckolib3.core.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.eliotlash.mclib.math.IValue;

/**
 * 验证 OpenYSM 风格粒子 Molang 指令（particle/abs_particle）的通用解析与求值：
 * 三元分支 + 副作用函数、字符串参数池化（MolangStringPool）、嵌套函数调用。
 *
 * <p>只覆盖 Molang 语言层语义，不绑定任何具体模型（无模型名/控制器名/动画名），
 * 也不依赖 Minecraft 环境——无实体上下文时 particle() 应安全返回 0 而不抛异常。</p>
 */
class ParticleMolangExpressionTest {

    /** MolangParser.VARIABLES 是全局静态 map，测试间必须隔离，防止变量值互相污染。 */
    @BeforeEach
    void clearGlobalVariables() {
        MolangParser.VARIABLES.clear();
        // ysm.particle / ysm.equipped_enchantment_level 等函数由宿主 mod 通过
        // MolangParser.ysmFunctionRegistrar 钩子注册（vendored 解析器零 mod 引用），
        // 测试需显式注册钩子才能解析这些函数名。
        com.fox.ysmu.client.animation.AnimationRegister.registerMolangHooks();
    }

    /** 三元条件 + 粒子调用：条件为真时进入 then 分支（particle 为副作用函数）。 */
    @Test
    void particleCallInsideTernaryIsParsed() throws Exception {
        MolangParser parser = new MolangParser();
        parser.register(new LazyVariable("v.condition", 1));

        IValue value = parser.parseExpression(
            "(v.condition) ? (ysm.particle('flame', 0, 1, 0, 0.5, 0, 0.5, 0.1, 1, 8)) : 0;");
        assertNotNull(value);
        // 无实体上下文（测试环境没有玩家）时 particle() 返回 0，且不抛异常
        assertEquals(0.0D, value.get(), 0.0001D,
            "无实体上下文时 particle() 应返回 0（不抛异常）");
    }

    /** 完整粒子指令：三元 + 嵌套函数调用 + 多个字符串参数，解析求值不应抛异常。 */
    @Test
    void fullParticleInstructionWithNestedCallsDoesNotThrow() throws Exception {
        MolangParser parser = new MolangParser();
        parser.register(new LazyVariable("v.condition", 0));
        parser.register(new LazyVariable("v.flag", 0));
        parser.register(new LazyVariable("query.rain_level", 0));

        String expr =
            "((v.condition > 0 || ysm.equipped_enchantment_level('mainhand', 'fire_aspect') > 0) && !v.flag)"
                + " ? (ysm.particle(query.rain_level > 0 ? 'poof' : 'flame', 0, 1, 0, 0.5, 0, 0.5, 0.1,"
                + " v.condition / 4 + math.min(10, ysm.equipped_enchantment_level('mainhand', 'fire_aspect') * 5), 8)) : 0;";

        IValue value = parser.parseExpression(expr);
        assertNotNull(value);
        // v.condition=0 且 equipped_enchantment_level 无实体上下文（返回 0）→ 条件 false → 三元返回 0
        assertEquals(0.0D, value.get(), 0.0001D,
            "条件 false 时完整粒子指令应返回 0（不抛异常）");
    }

    /** 粒子字符串 id 经 MolangStringPool 池化后可往返还原。 */
    @Test
    void particleStringIdRoundTripsThroughPool() {
        int id = MolangStringPool.intern("minecraft:flame");
        assertTrue(id > 0, "池化 id 应大于 0");
        assertEquals("minecraft:flame", MolangStringPool.get(id));
        // 相同字符串应返回同一 id（幂等）
        assertEquals(id, MolangStringPool.intern("minecraft:flame"));
    }
}
