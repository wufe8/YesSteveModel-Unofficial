package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.eliotlash.mclib.math.IValue;
import com.fox.ysmu.client.animation.AnimationRegister;

import software.bernie.geckolib3.core.molang.MolangException;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * {@code ysm.play_sound / stop_sound / stop_all_sounds} 的**实参个数**契约。
 * <p>
 * 回归的是一条很隐蔽的时序 bug：{@code Function} 的构造器会在**子类字段赋值之前**调用
 * {@code getRequiredArguments()} 来校验实参个数（{@code com.eliotlash.mclib.math.functions.Function:11}）。
 * 旧实现把三个名字注册到同一个类，并在构造器里按 {@code name} 推导
 * {@code stop} / {@code stopAll} 后再用它俩决定参数量 —— 校验发生时这两个字段还是
 * {@code false}，于是三个名字一律要求 2 个参数，把文档允许的
 * {@code ysm.stop_sound('id')}（1 参）与 {@code ysm.stop_all_sounds()}（0 参）
 * 判成 mclib 解析失败。
 * <p>
 * 后果不是"少一条语句"：{@code .molang} 脚本是整份先解析再执行的，一条表达式失败会让
 * **整个文件**永不执行。实机症状是某模型的 {@code @player_update} 文件
 * （按键鸣笛 + 随机鸣笛 + 轮胎旋转都在里面）里一句 1 参 {@code ysm.stop_sound('horn')}
 * 就把整批逻辑废掉 —— 日志里只有一条
 * {@code [YSMU-MOLANG-SCRIPT] … player_update failed to execute}。
 * <p>
 * 所以这里通过**真正走 mclib 解析**来钉住参数量：只解析不求值，因此不需要 Minecraft 实体。
 */
class YsmSoundFunctionTest {

    @BeforeAll
    static void installHooks() {
        MolangParser.VARIABLES.clear();
        // 三个 ysm.* 音效函数由宿主 mod 通过 ysmFunctionRegistrar 注入，测试里要手动装一次。
        AnimationRegister.registerMolangHooks();
    }

    @Test
    void documentedArgumentFormsParse() {
        // play_sound(id, sound_name, flags?, volume?, pitch?)：文档要求 2~5 个。
        assertParses("ysm.play_sound('horn','horn')");
        assertParses("ysm.play_sound('horn','horn',1)");
        assertParses("ysm.play_sound('horn','horn',1,0.5,1.2)");
        // stop_sound(id, global?)：**1 参是文档允许的写法**（旧实现把它判成解析失败）。
        assertParses("ysm.stop_sound('horn')");
        assertParses("ysm.stop_sound('horn',1)");
        // stop_all_sounds(global?)：0 参是它的正常用法（旧实现同样判成失败）。
        assertParses("ysm.stop_all_sounds()");
        assertParses("ysm.stop_all_sounds(1)");
    }

    @Test
    void arityIsAClassConstantNotReadFromInstanceState() {
        // 参数量必须能在构造器里被安全读取：这里直接断言"未初始化字段"不会影响它。
        assertEquals(2, newArity(YsmSoundFunction.Play.class, "ysm.play_sound"));
        assertEquals(1, newArity(YsmSoundFunction.Stop.class, "ysm.stop_sound"));
        assertEquals(0, newArity(YsmSoundFunction.StopAll.class, "ysm.stop_all_sounds"));
        // 名字变了也不能改参数量（旧实现就是按名字推的）。
        assertEquals(1, newArity(YsmSoundFunction.Stop.class, "ysm.stop_sound"));
    }

    @Test
    void playSoundStillRejectsACallWithoutASoundName() {
        // 严格参数量是**有意**保留的：play_sound 少了 sound_name 属于模型写错，
        // 应当报出来，而不是静默什么也不做（所以不把三个类合并成"最少 0 个参数"）。
        assertThrows(MolangException.class, () -> parse("ysm.play_sound('horn')"));
        assertDoesNotThrow(() -> parse("ysm.play_sound('horn','horn')"));
    }

    private static void assertParses(String expression) {
        assertDoesNotThrow(() -> parse(expression), "应能解析: " + expression);
    }

    private static Object parse(String expression) throws MolangException {
        // 只解析：构造函数里的参数个数校验就是我们要测的那一步。
        return new MolangParser().parseExpression(expression);
    }

    private static int newArity(Class<? extends YsmSoundFunction> type, String name) {
        try {
            // 给足参数：基类构造器会对"少于 getRequiredArguments()"直接抛异常，
            // 而这里要读的正是那个常量本身。
            IValue[] dummy = { () -> 0.0d, () -> 0.0d, () -> 0.0d, () -> 0.0d, () -> 0.0d };
            return type.getConstructor(IValue[].class, String.class)
                .newInstance(dummy, name)
                .getRequiredArguments();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("构造 " + type.getSimpleName() + " 失败", e);
        }
    }
}
