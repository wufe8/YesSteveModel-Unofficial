package software.bernie.geckolib3.core.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * wiki: molang/var —— {@code query.} 可以缩写成 {@code q.}。
 *
 * <p>变量名在 {@code MolangParser.normalizeVariableName} 里做了缩写展开，但**函数名**没有：
 * 函数是在 {@code MathBuilder} 里按字面名在 {@code functions} 表里查的，于是模型里
 * {@code q.max_durability('mainhand')} 这种写法会直接抛
 * {@code Function 'q.max_durability' couldn't be found!}，整个关键帧表达式失效。
 * 参考模型库里 {@code q.xxx(...)} 的写法很常见（{@code q.position}、{@code q.is_item_name_any}、
 * {@code q.remaining_durability}…），所以关键帧通道必须和控制器通道一样支持缩写。</p>
 *
 * <p>本测试只要求"能解析"：没有渲染实体上下文时这些查询都优雅降级返回 0。真正的取值
 * 由 {@code QueryDurabilityFunctionTest}/{@code QueryItemNameAnyFunctionTest} 覆盖。</p>
 */
class MolangQueryAbbreviationTest {

    @BeforeEach
    void setUp() {
        // MolangParser.VARIABLES 是全局静态 map，测试间必须隔离。
        MolangParser.VARIABLES.clear();
        com.fox.ysmu.client.animation.AnimationRegister.registerMolangHooks();
    }

    @Test
    void queryFunctionsAcceptQAbbreviation() throws Exception {
        MolangParser parser = new MolangParser();

        assertEquals(0.0d, parser.parseExpression("q.max_durability('mainhand')")
            .get(), 0.0001d);
        assertEquals(0.0d, parser.parseExpression("q.remaining_durability('mainhand')")
            .get(), 0.0001d);
        assertEquals(0.0d, parser.parseExpression("q.is_item_name_any('mainhand', 'minecraft:apple')")
            .get(), 0.0001d);
        assertEquals(0.0d, parser.parseExpression("q.position(1)")
            .get(), 0.0001d);
        assertEquals(0.0d, parser.parseExpression("q.position_delta(1)")
            .get(), 0.0001d);
        // 调试输出同样只写 q. 缩写也能解析（未开动画调试模式时静默丢弃）。
        assertEquals(0.0d, parser.parseExpression("q.debug_output('lvl', 3)")
            .get(), 0.0001d);
    }

    /** 缩写只作用于函数调用形式，不能把 {@code v.q} / 标识符里的 {@code q} 改坏。 */
    @Test
    void abbreviationDoesNotTouchVariableNames() throws Exception {
        MolangParser parser = new MolangParser();

        assertEquals(7.0d, parser.parseExpression("v.q=7;v.q")
            .get(), 0.0001d);
        assertEquals(3.0d, parser.parseExpression("v.aq=3;v.aq")
            .get(), 0.0001d);
    }
}
