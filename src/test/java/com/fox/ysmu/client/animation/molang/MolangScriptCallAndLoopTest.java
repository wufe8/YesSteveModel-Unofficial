package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * 脚本自定义函数（{@code fn.*}）与循环（{@code loop}/{@code for_each}）：
 * 调用链、递归、{@code t.*} 的按调用隔离、{@code break}/{@code continue}。
 * <p>
 * 脚本正文基本照抄 wiki「自定义函数」页的示例（`molang/script`）—— 参考模型库里
 * {@code fn.} 只出现在一句注释里、循环一次都没用，所以 wiki 示例才是这几条特性的真实规格。
 */
class MolangScriptCallAndLoopTest {

    /** MolangParser.VARIABLES 是全局静态 map；本测试的 parser 不读它，但仍按惯例清一次。 */
    @BeforeEach
    void clearGlobalVariables() {
        MolangParser.VARIABLES.clear();
    }

    // ---- fn.* 调用与 t.* 隔离 ----

    @Test
    void aFunctionCallRunsTheBodyAndReturnsItsValue() {
        ScriptedScope scope = new ScriptedScope().function("b", "return 7;");

        // wiki 的两种写法：fn.b; 与 fn.b();
        assertEquals(7.0d, evaluate("fn.b;", scope), 0.0001d);
        assertEquals(7.0d, evaluate("fn.b();", scope), 0.0001d);
    }

    @Test
    void functionArgumentsArriveAsArgs() {
        ScriptedScope scope = new ScriptedScope().function("add", "return args[0] + args[1];");

        assertEquals(5.0d, evaluate("fn.add(2, 3);", scope), 0.0001d);
    }

    /** wiki：「还支持调用自身以实现递归」（调用链长度上限 32）。 */
    @Test
    void aFunctionCanCallItself() {
        ScriptedScope scope = new ScriptedScope()
            .function("fact", "args[0] <= 1 ? { return 1; } : { return args[0] * fn.fact(args[0] - 1); }");

        assertEquals(1.0d, evaluate("fn.fact(1);", scope), 0.0001d);
        assertEquals(120.0d, evaluate("fn.fact(5);", scope), 0.0001d);
    }

    /** 带小括号的写法走 mclib 的函数机制，因此在普通表达式里也成立。 */
    @Test
    void aParenthesisedFunctionCallWorksInsideAnExpression() {
        ScriptedScope scope = new ScriptedScope().function("b", "return args[0] * 2;");

        assertEquals(7.0d, evaluate("return 1 + fn.b(3);", scope), 0.0001d);
    }

    /**
     * 不带小括号的 {@code fn.x} 只支持"整段就是它"的形态（wiki 的 {@code fn.b;}）。写在更大的
     * 表达式里时它会被当成一个未定义变量（0）—— 需要那种写法就用 {@code fn.x(...)}。
     */
    @Test
    void aBareFunctionReferenceInsideALargerExpressionIsNotACall() {
        ScriptedScope scope = new ScriptedScope().function("b", "return 7;");

        assertEquals(1.0d, evaluate("return 1 + fn.b;", scope), 0.0001d);
        assertEquals(8.0d, evaluate("return 1 + fn.b();", scope), 0.0001d);
    }

    /**
     * wiki 示例 a/b：{@code t.*} 在调用链的每个节点之间互相隔离（即使是同一个名字），
     * 所以 b 改了自己的 {@code t.num}，a 看不到。
     */
    @Test
    void temporaryVariablesDoNotLeakBetweenCalls() {
        ScriptedScope scope = new ScriptedScope()
            .function("b", "t.num = 222; return t.num;")
            .function("a", "t.num = 111; fn.b; return t.num;")
            .function("c", "return t.num;")
            .function("d", "t.num = 111; fn.c; return t.num;");

        assertEquals(222.0d, evaluate("fn.b;", scope), 0.0001d);
        // a 自己的 t.num=111 没有被 b 的赋值改掉
        assertEquals(111.0d, evaluate("fn.a;", scope), 0.0001d);
        // 新的一次调用看不到上一次的 t.num（调用结束后临时变量失效）
        assertEquals(0.0d, evaluate("fn.c;", scope), 0.0001d);
        assertEquals(111.0d, evaluate("fn.d;", scope), 0.0001d);
    }

    /** wiki：只有 {@code v.*} 可以在调用链上共享。 */
    @Test
    void hostVariablesAreSharedAcrossTheChain() {
        ScriptedScope scope = new ScriptedScope()
            .function("b", "v.num = 222; return v.num;")
            .function("a", "v.num = 111; fn.b; return v.num;");

        assertEquals(222.0d, evaluate("fn.a;", scope), 0.0001d);
        assertTrue(scope.writes.containsKey("v.num"), "v.* must go back to the host: " + scope.writes);
    }

    @Test
    void anUnknownFunctionIsZero() {
        ScriptedScope scope = new ScriptedScope();

        assertEquals(0.0d, evaluate("fn.missing;", scope), 0.0001d);
        assertEquals(0.0d, evaluate("fn.missing(1, 2);", scope), 0.0001d);
    }

    // ---- 括号内的嵌套赋值 ----

    /**
     * 回归测试：脚本里**夹在表达式里的**赋值（{@code (v.x = 5) + 0;}）必须写回宿主。
     *
     * <p>这条路径的坑和关键帧通道不同：脚本的变量由 {@code ScriptMolangParser.getVariable}
     * 造成 {@code LazyVariable(name, () -> readVariable(name))}，而 vendored
     * {@code LazyVariable.set(value)} 会把 supplier **替换成常量** —— 于是赋值既不经过
     * {@code writeVariable}（宿主看不到），也把这次执行里后续的读值冻住了。</p>
     */
    @Test
    void parenthesisedAssignmentWritesBackToTheHost() {
        ScriptedScope scope = new ScriptedScope();

        double result = evaluate("(v.x = 5) + 0; return v.x;", scope);

        assertEquals(5.0d, result, 0.0001d, "同一脚本内读回应当是 5");
        assertEquals(5.0d, scope.variables.get("v.x"), 0.0001d, "宿主必须收到这次写入");
        assertTrue(scope.writes.containsKey("v.x"), "写入必须经过 setVariableValue: " + scope.writes);
    }

    /** 同一脚本里：先括号赋值，后一条语句读到新值（而不是被冻结的旧值）。 */
    @Test
    void parenthesisedAssignmentIsVisibleToLaterStatements() {
        ScriptedScope scope = new ScriptedScope();
        scope.variables.put("v.x", 1.0d);

        double result = evaluate("v.x = v.x + 1; (v.x = v.x * 3) + 0; return v.x;", scope);

        assertEquals(6.0d, result, 0.0001d);
        assertEquals(6.0d, scope.variables.get("v.x"), 0.0001d);
    }

    // ---- 字符串实参 ----

    /**
     * wiki 的 {@code args[...]} 不止传数字：动画控制脚本里最典型的用法是
     * {@code ctrl.set_animation(args[0])} / {@code q.debug_output('v=', args[0])}。
     *
     * <p>字符串字面量在解析期就被 {@code MolangStringPool} 池化成整数 id，所以
     * {@code args[0]} 的 double 通道已经能承载它 —— 比较两侧会拿到同一个 id，
     * 语义与字符串相等一致（见 {@code MolangDebugOutput.formatArg} 的还原）。</p>
     */
    @Test
    void stringArgumentsTravelThroughArgs() {
        ScriptedScope scope = new ScriptedScope().function("pick", "return args[0] == 'abc' ? 1 : 0;");

        assertEquals(1.0d, evaluate("return fn.pick('abc');", scope), 0.0001d);
        assertEquals(0.0d, evaluate("return fn.pick('abcd');", scope), 0.0001d);
    }

    /** 字符串实参可以是下标的间接形式（wiki：{@code args[t.a + 1]}）。 */
    @Test
    void stringArgumentCanBeSelectedByAnIndexExpression() {
        ScriptedScope scope = new ScriptedScope()
            .function("second", "t.i = 1; return args[t.i] == 'two' ? 1 : 0;");

        assertEquals(1.0d, evaluate("return fn.second('one', 'two');", scope), 0.0001d);
    }

    /** 字符串实参经一层转发后仍然保持同一个池 id（调用链不丢值）。 */
    @Test
    void stringArgumentSurvivesForwarding() {
        ScriptedScope scope = new ScriptedScope()
            .function("inner", "return args[0];")
            .function("outer", "return fn.inner(args[0]);");

        assertEquals(software.bernie.geckolib3.core.molang.MolangStringPool.intern("abc"),
            evaluate("return fn.outer('abc');", scope), 0.0001d);
    }

    /** 调用链超过 32 时按 wiki 返回 0（而不是栈溢出）。 */
    @Test
    void theCallChainIsCappedAtTheWikiLimit() {
        ScriptedScope scope = new ScriptedScope().function("forever", "return fn.forever;");

        assertEquals(0.0d, evaluate("fn.forever;", scope), 0.0001d);
    }

    // ---- loop ----

    @Test
    void loopRunsTheBodyCountTimes() {
        assertEquals(10.0d, evaluate("t.n = 0; loop(10, { t.n = t.n + 1; }); return t.n;", new ScriptedScope()),
            0.0001d);
    }

    @Test
    void loopWithZeroOrNegativeCountRunsNothing() {
        assertEquals(
            0.0d,
            evaluate("t.n = 0; loop(0, { t.n = 1; }); loop(-5, { t.n = 2; }); return t.n;", new ScriptedScope()),
            0.0001d);
    }

    /** wiki 的 loop 求和示例：1 加到 10。 */
    @Test
    void loopSumsOneToTen() {
        String script = "t.num = 0; t.sum = 0; loop(10, { t.num = t.num + 1; t.sum = t.sum + t.num; }); return t.sum;";

        assertEquals(55.0d, evaluate(script, new ScriptedScope()), 0.0001d);
    }

    /** wiki 的 break 示例：斐波那契递推到 v.y > 20 就停。 */
    @Test
    void loopWithBreakStopsAtTheCondition() {
        String script = "v.x = 1; v.y = 1; loop(10, { t.x = v.x + v.y; v.x = v.y; v.y = t.x; (v.y > 20) ? break; }); return v.y;";

        assertEquals(21.0d, evaluate(script, new ScriptedScope()), 0.0001d);
    }

    /** wiki 的 continue 示例：v.x > 5 之后不再自增。 */
    @Test
    void loopWithContinueSkipsTheRestOfTheIteration() {
        String script = "v.x = 0; loop(10, { (v.x > 5) ? continue; v.x = v.x + 1; }); return v.x;";

        assertEquals(6.0d, evaluate(script, new ScriptedScope()), 0.0001d);
    }

    @Test
    void breakOnlyLeavesTheInnermostLoop() {
        String script = "t.n = 0; loop(3, { loop(3, { t.n = t.n + 1; break; }); }); return t.n;";

        assertEquals(3.0d, evaluate(script, new ScriptedScope()), 0.0001d);
    }

    @Test
    void returnUnwindsOutOfALoop() {
        assertEquals(5.0d, evaluate("loop(10, { return 5; }); return 9;", new ScriptedScope()), 0.0001d);
    }

    @Test
    void aMalformedLoopIsRejected() {
        assertThrows(MolangScriptInterpreter.MolangScriptException.class,
            () -> evaluate("loop(10);", new ScriptedScope()));
        assertThrows(MolangScriptInterpreter.MolangScriptException.class,
            () -> evaluate("loop(1, 2);", new ScriptedScope()));
    }

    // ---- for_each ----

    /** wiki 的 for_each 求和示例（第二个参数只写 args）。 */
    @Test
    void forEachIteratesTheCallArguments() {
        assertEquals(
            10.0d,
            evaluate("t.sum = 0; for_each(t.arg, args, { t.sum = t.sum + t.arg; }); return t.sum;",
                new ScriptedScope().args(1, 2, 3, 4)),
            0.0001d);
        assertEquals(
            0.0d,
            evaluate("t.sum = 0; for_each(t.arg, args, { t.sum = t.sum + t.arg; }); return t.sum;",
                new ScriptedScope()),
            0.0001d);
    }

    /** 函数内部遍历的是**这次调用**的实参，而且累加用的 t.* 不会漏到调用者。 */
    @Test
    void forEachInsideAFunctionUsesThatCallsArguments() {
        ScriptedScope scope = new ScriptedScope()
            .function("sumall", "t.sum = 0; for_each(t.arg, args, { t.sum = t.sum + t.arg; }); return t.sum;");

        assertEquals(6.0d, evaluate("fn.sumall(1, 2, 3);", scope), 0.0001d);
        assertEquals(100.0d, evaluate("t.sum = 100; fn.sumall(1, 2, 3); return t.sum;", scope), 0.0001d);
    }

    @Test
    void forEachRejectsAnythingButArgs() {
        assertThrows(MolangScriptInterpreter.MolangScriptException.class,
            () -> evaluate("for_each(t.a, v.list, { });", new ScriptedScope()));
        assertThrows(MolangScriptInterpreter.MolangScriptException.class,
            () -> evaluate("for_each(1, args, { });", new ScriptedScope()));
    }

    // ---- 夹具 ----

    private static double evaluate(String script, ScriptedScope scope) {
        return MolangScriptInterpreter.evaluate(script, scope);
    }

    /** 假 scope：变量 / 参数 / 脚本函数体 / 写回都能在线观察。 */
    static final class ScriptedScope implements MolangScriptInterpreter.MolangScriptScope {

        final Map<String, Double> variables = new HashMap<>();
        final Map<String, String> functions = new LinkedHashMap<>();
        final List<Double> arguments = new ArrayList<>();
        final Map<String, Double> writes = new LinkedHashMap<>();

        ScriptedScope args(double... values) {
            for (double value : values) {
                this.arguments.add(value);
            }
            return this;
        }

        ScriptedScope function(String name, String body) {
            this.functions.put(name, body);
            return this;
        }

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
            return this.functions.get(name);
        }
    }
}
