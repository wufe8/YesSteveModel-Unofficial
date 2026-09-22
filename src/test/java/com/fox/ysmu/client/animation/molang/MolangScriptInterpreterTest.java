package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 解释器核心的行为：闭包块、{@code args[...]}（含表达式下标）、{@code return} 穿透、以及
 * "没有 return 就用最后一条语句的值、空脚本为 0"。
 * <p>
 * 完全离线：变量 / 函数 / 参数都由 {@link FakeScope} 提供。
 */
class MolangScriptInterpreterTest {

    /** MolangParser.VARIABLES 是全局静态 map；本测试的 parser 不读它，但仍按惯例清一次。 */
    @BeforeEach
    void clearGlobalVariables() {
        MolangParser.VARIABLES.clear();
    }

    // ---- 闭包块 ----

    @Test
    void aClosureBlockIsAValidTernaryBranch() {
        assertEquals(2.0d, evaluate("v.a ? { 2; } : { 3; }", new FakeScope().var("v.a", 1)), 0.0001d);
        assertEquals(3.0d, evaluate("v.a ? { 2; } : { 3; }", new FakeScope().var("v.a", 0)), 0.0001d);
    }

    @Test
    void anEmptyBlockIsZero() {
        assertEquals(0.0d, evaluate("v.a ? { } : { 7; }", new FakeScope().var("v.a", 1)), 0.0001d);
        assertEquals(7.0d, evaluate("v.a ? { } : { 7; }", new FakeScope().var("v.a", 0)), 0.0001d);
        assertEquals(0.0d, evaluate("{ }", new FakeScope()), 0.0001d);
    }

    @Test
    void aBlockIsAlsoAWholeStatementWithoutATernary() {
        assertEquals(9.0d, evaluate("{ v.a ? { 9; } : { 1; }; }", new FakeScope().var("v.a", 1)), 0.0001d);
    }

    @Test
    void aMissingElseBranchIsZero() {
        assertEquals(4.0d, evaluate("v.a ? { 4; }", new FakeScope().var("v.a", 1)), 0.0001d);
        assertEquals(0.0d, evaluate("v.a ? { 4; }", new FakeScope().var("v.a", 0)), 0.0001d);
    }

    @Test
    void onlyTheTakenBranchIsExecuted() {
        FakeScope scope = new FakeScope().var("v.a", 1);
        evaluate("v.a ? { v.written = 1; } : { v.written = 2; }", scope);
        assertEquals(1.0d, scope.writes.get("v.written"), 0.0001d);
    }

    @Test
    void ternariesAreRightAssociative() {
        String script = "v.a ? 1 : v.b ? 2 : 3";
        assertEquals(1.0d, evaluate(script, new FakeScope().var("v.a", 1).var("v.b", 0)), 0.0001d);
        assertEquals(2.0d, evaluate(script, new FakeScope().var("v.a", 0).var("v.b", 1)), 0.0001d);
        assertEquals(3.0d, evaluate(script, new FakeScope().var("v.a", 0).var("v.b", 0)), 0.0001d);
    }

    @Test
    void nullCoalescingIsNotMistakenForATernary() {
        assertEquals(7.0d, evaluate("v.a ?? 7", new FakeScope().var("v.a", 0)), 0.0001d);
        assertEquals(5.0d, evaluate("v.a ?? 7", new FakeScope().var("v.a", 5)), 0.0001d);
    }

    @Test
    void nestedAssignmentStaysInsideTheCall() {
        // 括号里的赋值由 MolangParser 处理；同一次执行后续语句应当能读到。
        assertEquals(
            5.0d,
            evaluate("v.a ? (v.x = 5) : 0; v.x;", new FakeScope().var("v.a", 1)),
            0.0001d);
    }

    // ---- return 穿透 ----

    @Test
    void returnUnwindsOutOfNestedBlocks() {
        String script = "v.a ? { v.b ? { return 2; } : { return 3; }; } : { return 4; };";
        assertEquals(2.0d, evaluate(script, new FakeScope().var("v.a", 1).var("v.b", 1)), 0.0001d);
        assertEquals(3.0d, evaluate(script, new FakeScope().var("v.a", 1).var("v.b", 0)), 0.0001d);
        assertEquals(4.0d, evaluate(script, new FakeScope().var("v.a", 0)), 0.0001d);
    }

    @Test
    void statementsAfterReturnAreNotExecuted() {
        assertEquals(1.0d, evaluate("return 1; 2;", new FakeScope()), 0.0001d);
        FakeScope scope = new FakeScope();
        evaluate("v.a ? { return 5; v.written = 1; } : 0;", scope.var("v.a", 1));
        assertTrue(scope.writes.isEmpty(), "the statement after return must not run: " + scope.writes);
    }

    // ---- 没有 return 时的结果约定 ----

    @Test
    void noReturnYieldsTheLastExpressionOrZeroWhenThereIsNone() {
        assertEquals(2.0d, evaluate("1 + 1;", new FakeScope()), 0.0001d);
        assertEquals(5.0d, evaluate("t.x = 5; t.x;", new FakeScope()), 0.0001d);
        assertEquals(0.0d, evaluate("", new FakeScope()), 0.0001d);
        assertEquals(0.0d, evaluate("; ;", new FakeScope()), 0.0001d);
    }

    // ---- args ----

    @Test
    void argsSupportConstantAndExpressionIndexes() {
        FakeScope scope = new FakeScope().args(10, 20, 30);
        assertEquals(10.0d, evaluate("args[0]", scope), 0.0001d);
        // t.* 归脚本自己的调用帧所有（宿主给的同名值不参与），所以在脚本里赋值。
        assertEquals(30.0d, evaluate("t.i = 1; args[t.i + 1]", scope), 0.0001d);
        // 越界与负下标：未定义即 0
        assertEquals(0.0d, evaluate("args[9]", scope), 0.0001d);
        assertEquals(0.0d, evaluate("args[-1]", scope), 0.0001d);
    }

    @Test
    void anExpressionIndexIsReadAtCallTime() {
        FakeScope scope = new FakeScope().args(1, 2, 3, 4);
        assertEquals(4.0d, evaluate("t.halo_no = 3; args[t.halo_no]", scope), 0.0001d);
    }

    // ---- 局部变量与宿主写回 ----

    @Test
    void localAssignmentsAreScopedToTheCall() {
        assertEquals(4.0d, evaluate("t.x = args[0]; t.x;", new FakeScope().args(4)), 0.0001d);
        assertEquals(7.0d, evaluate("t.x = args[0]; t.x;", new FakeScope().args(7)), 0.0001d);
    }

    @Test
    void hostVisibleAssignmentsAreForwardedToTheScope() {
        FakeScope scope = new FakeScope();
        evaluate("v.roaming.horn_checker = 1;", scope);
        assertEquals(1.0d, scope.writes.get("v.roaming.horn_checker"), 0.0001d);
    }

    @Test
    void unknownFunctionsAreForwardedToTheScope() {
        FakeScope scope = new FakeScope();
        assertEquals(6.0d, evaluate("script.double(3)", scope), 0.0001d);
        assertTrue(scope.calledFunctions.contains("script.double"), "calls: " + scope.calledFunctions);
    }

    // ---- 预处理 ----

    @Test
    void commentsAreStrippedBeforeParsing() {
        assertEquals(1.0d, evaluate("// ; { } 说明\nt.x = 1; t.x;", new FakeScope()), 0.0001d);
    }

    /**
     * {@code prepare} 必须是幂等的 —— 这正是"把已 prepare 的正文再 prepare 一遍"可以省掉的前提。
     *
     * <p>回归点（VisualVM 采样）：{@code AnimationControlScripts.evaluate} 先 prepare 一次判空，
     * 再调 {@code evaluate} 又 prepare 一次，采样里 {@code prepare → lowerCase} 出现两个节点、
     * 各约 3ms/s。去掉第二遍的依据就是这里。</p>
     */
    @Test
    void prepareIsIdempotent() {
        String script = "// 注释\nv.Roaming.Horn = args[0] + 1; v.roaming.horn;";
        String once = MolangScriptInterpreter.prepare(script);
        assertEquals(once, MolangScriptInterpreter.prepare(once), "prepare 对已处理文本必须是不动点");
        assertEquals(once, MolangScriptInterpreter.prepareCached(script), "缓存版必须与 prepare 结果一致");
        assertEquals(
            once,
            MolangScriptInterpreter.prepareCached(script),
            "第二次命中缓存也必须返回同一段正文");
    }

    /**
     * {@code evaluatePrepared(prepare(x), scope)} 必须与 {@code evaluate(x, scope)} 完全等价 ——
     * 这是 {@code AnimationControlScripts} 改走"单次 prepare"的行为保证。
     */
    @Test
    void evaluatingAPreparedScriptMatchesTheUnpreparedPath() {
        String script = "// 说明\nv.Wet = args[0] * 2;\nt.x = v.wet + 1;\nreturn t.x;";
        FakeScope direct = new FakeScope().args(3.0d);
        FakeScope preparedScope = new FakeScope().args(3.0d);

        double viaEvaluate = MolangScriptInterpreter.evaluate(script, direct);
        double viaPrepared = MolangScriptInterpreter
            .evaluatePrepared(MolangScriptInterpreter.prepareCached(script), preparedScope);

        assertEquals(viaEvaluate, viaPrepared, 1.0e-9d, "两条入口的返回值必须一致");
        assertEquals(direct.writes, preparedScope.writes, "两条入口的写回必须一致");
    }

    /** 直接喂未处理正文给 evaluatePrepared 也必须能用（只是没有省下预处理开销）。 */
    @Test
    void evaluatePreparedAcceptsRawTextAndRejectsEmpty() {
        assertEquals(2.0d, MolangScriptInterpreter.evaluatePrepared("t.x = 2; t.x;", new FakeScope()), 1.0e-9d);
        assertEquals(0.0d, MolangScriptInterpreter.evaluatePrepared(null, new FakeScope()), 1.0e-9d);
        assertEquals(0.0d, MolangScriptInterpreter.evaluatePrepared("   \n  ", new FakeScope()), 1.0e-9d);
    }

    // ---- 解析好的 AST 跨执行复用（按正文缓存） ----

    /**
     * 同一段脚本第二次求值会命中缓存的 AST，但**每次必须问当次调用的 scope**。
     *
     * <p>回归点：{@code ScopeFunction}（宿主函数转发）以前在**解析期**就把 scope 存进 final
     * 字段。parser 复用后如果不管它，第二次求值会一直问第一次那个 scope —— 动画控制脚本里
     * {@code ctrl.*} 这类宿主函数会读到上一帧的模型状态。</p>
     */
    @Test
    void aCachedAstRebindsHostFunctionsToEachCallScope() {
        String script = "t.x = script.double(v.in) + 1; return t.x;";
        FakeScope first = new FakeScope().var("v.in", 2.0d); // 2*2+1 = 5
        FakeScope second = new FakeScope().var("v.in", 10.0d); // 10*2+1 = 21

        assertEquals(5.0d, MolangScriptInterpreter.evaluate(script, first), 1.0e-9d);
        assertEquals(
            21.0d,
            MolangScriptInterpreter.evaluate(script, second),
            1.0e-9d,
            "第二次求值必须问第二次的 scope");
        assertTrue(second.calledFunctions.contains("script.double"), "第二次的 scope 必须真的被调用");

        assertEquals(5.0d, MolangScriptInterpreter.evaluate(script, first), 1.0e-9d, "换回来仍然正确");
    }

    /** {@code t.*} 是"单次执行"的局部变量：AST 复用时必须复位，否则会跨帧累加。 */
    @Test
    void aCachedAstStartsEachCallWithCleanLocalFrames() {
        String script = "t.acc = t.acc + 1; return t.acc;";
        FakeScope scope = new FakeScope();

        assertEquals(1.0d, MolangScriptInterpreter.evaluate(script, scope), 1.0e-9d);
        assertEquals(
            1.0d,
            MolangScriptInterpreter.evaluate(script, scope),
            1.0e-9d,
            "t.* 不能从上一次执行漏进来");
    }

    /**
     * 非 {@code t.*} 名字的"本次执行覆盖层"也必须复位：宿主把变量改回原值后，
     * 上一次执行写下的覆盖值不能继续被读到。
     */
    @Test
    void aCachedAstDoesNotLeakWritesBetweenCalls() {
        String script = "v.count = v.count + 1; return v.count;";
        FakeScope scope = new FakeScope().var("v.count", 0.0d);

        assertEquals(1.0d, MolangScriptInterpreter.evaluate(script, scope), 1.0e-9d);
        // 宿主把值改回 0；若 writtenVariables 没随执行复位，第二次会从 1 继续加到 2。
        scope.variables.put("v.count", 0.0d);

        assertEquals(
            1.0d,
            MolangScriptInterpreter.evaluate(script, scope),
            1.0e-9d,
            "上一次执行的写入不能漏进下一次");
    }

    private static double evaluate(String script, FakeScope scope) {
        return MolangScriptInterpreter.evaluate(script, scope);
    }

    /** 假 scope：变量 / 参数 / 写回 / 函数调用都能在线观察，用来断言脚本的返回值与副作用。 */
    static final class FakeScope implements MolangScriptInterpreter.MolangScriptScope {

        final Map<String, Double> variables = new HashMap<>();
        final List<Double> arguments = new ArrayList<>();
        final Map<String, Double> writes = new LinkedHashMap<>();
        final List<String> calledFunctions = new ArrayList<>();

        FakeScope var(String name, double value) {
            this.variables.put(name, value);
            return this;
        }

        FakeScope args(double... values) {
            for (double value : values) {
                this.arguments.add(value);
            }
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
            this.calledFunctions.add(name);
            if ("script.double".equals(name) && !args.isEmpty()) {
                return args.get(0)
                    .asNumber() * 2.0d;
            }
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
