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
