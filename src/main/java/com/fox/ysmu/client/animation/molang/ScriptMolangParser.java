package com.fox.ysmu.client.animation.molang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * 解释器专用的 {@link MolangParser} 子类：叶子表达式里的变量、函数与 {@code args[]} 都走调用方
 * 注入的 {@link MolangScriptInterpreter.MolangScriptScope}，因此解释器核心不依赖 Minecraft，
 * 用 JUnit 喂一个假 scope 即可执行真实脚本。
 * <p>
 * 与全局 {@link MolangParser} 不同的几点：
 * <ul>
 *   <li>变量不落在静态的 {@code MolangParser.VARIABLES}，而是本执行自己的
 *       {@code HashMap<String, LazyVariable>} —— 于是它在测试之间不会互相污染，
 *       而且每个 {@code LazyVariable} 的取值是**动态**的（见 {@link #readVariable}），
 *       同一个名字在不同调用帧里自然读到不同的值；</li>
 *   <li>{@code t.*} 是**按调用帧**隔离的（wiki：临时变量在调用链的每个节点之间互不共享，
 *       只有 {@code v.*} 共享）。栈底是脚本文本自己那一层，每次 {@code fn.*} 调用再压一层；</li>
 *   <li>非 {@code t.*} 的写入额外记一份**本次执行的覆盖层**，于是"先写后读"不依赖宿主是否
 *       立刻在自己的变量表里反映这次写入；同时仍会转发给宿主（{@code setVariableValue}）；</li>
 *   <li>{@code args[]} 先看本次调用的实参帧（{@code fn.x(a, b)} 传进来的），没有才问宿主；</li>
 *   <li>函数表里没有的名字不报错，而是转发给 scope（宿主的函数表）；{@code fn.*} 特殊，
 *       它由 {@link ScriptFunctionCall} 回到解释器执行脚本函数体。</li>
 * </ul>
 */
final class ScriptMolangParser extends MolangParser {

    private final MolangScriptInterpreter.MolangScriptScope scope;
    /** 本执行里出现过的变量名 → 动态取值的 LazyVariable（取值时按当前帧解析）。 */
    private final Map<String, LazyVariable> variables = new HashMap<>();
    /** {@code t.*} 的调用帧栈；栈底是脚本文本自己那一层，永远不空。 */
    private final Deque<Map<String, Double>> localFrames = new ArrayDeque<>();
    /** 实参帧栈；空表示"用宿主给的参数"（脚本最外层就是这种情况）。 */
    private final Deque<List<Double>> argumentFrames = new ArrayDeque<>();
    /** 非 {@code t.*} 变量在本次执行里的最新写入值。 */
    private final Map<String, Double> writtenVariables = new HashMap<>();
    private int callDepth;

    ScriptMolangParser(MolangScriptInterpreter.MolangScriptScope scope) {
        this.scope = scope;
        this.localFrames.push(new HashMap<>());
    }

    // ---- 变量读写（t.* 走帧栈，其余走覆盖层 + 宿主） ----

    double readVariable(String name) {
        if (MolangScriptInterpreter.isLocalVariable(name)) {
            Double local = this.localFrames.peek()
                .get(name);
            return local == null ? 0.0d : local;
        }
        Double written = this.writtenVariables.get(name);
        return written != null ? written : this.scope.variableValue(name);
    }

    void writeVariable(String name, double value) {
        if (MolangScriptInterpreter.isLocalVariable(name)) {
            this.localFrames.peek()
                .put(name, value);
            return;
        }
        this.writtenVariables.put(name, value);
        this.scope.setVariableValue(name, value);
    }

    @Override
    protected LazyVariable getVariable(String name) {
        LazyVariable variable = this.variables.get(name);
        if (variable == null) {
            // 取值延迟到 eval 时按**当时的**帧解析，所以同一个 LazyVariable 在
            // 不同调用帧里读到各自的 t.*；也因此绝不能对它 set() 成常量。
            variable = new LazyVariable(name, () -> readVariable(name));
            this.variables.put(name, variable);
        }
        return variable;
    }

    // ---- 调用帧 / 实参帧 ----

    void pushCallFrame(List<Double> arguments) {
        this.localFrames.push(new HashMap<>());
        this.argumentFrames.push(arguments);
    }

    void popCallFrame() {
        if (this.localFrames.size() > 1) {
            this.localFrames.pop();
        }
        if (!this.argumentFrames.isEmpty()) {
            this.argumentFrames.pop();
        }
    }

    /** 第 {@code index} 个实参：先看本次调用的实参帧，没有才问宿主；越界与负下标都是 0。 */
    double argument(int index) {
        if (this.argumentFrames.isEmpty()) {
            return this.scope.argument(index);
        }
        List<Double> arguments = this.argumentFrames.peek();
        return index >= 0 && index < arguments.size() ? arguments.get(index) : 0.0d;
    }

    /** 本次调用的实参个数（{@code for_each} 需要知道边界）。 */
    int argumentCount() {
        return this.argumentFrames.isEmpty() ? this.scope.argumentCount() : this.argumentFrames.peek()
            .size();
    }

    // ---- fn.* ----

    /**
     * 执行一次脚本函数调用（wiki「链式调用」）：函数体来自
     * {@link MolangScriptInterpreter.MolangScriptScope#functionScript(String)}，压一层 {@code t.*}
     * 帧与实参帧后按**独立脚本**执行 —— {@code return} 只结束这次调用。调用链超过
     * {@link MolangScriptInterpreter#MAX_CALL_DEPTH} 按 wiki 返回 0（"null"的数值形态）。
     */
    double invoke(String function, List<Double> arguments) {
        if (this.callDepth >= MolangScriptInterpreter.MAX_CALL_DEPTH) {
            return 0.0d;
        }
        String body = this.scope.functionScript(function);
        if (body == null) {
            return 0.0d;
        }
        String prepared = MolangScriptInterpreter.prepare(body);
        if (prepared.trim()
            .isEmpty()) {
            return 0.0d;
        }
        this.callDepth++;
        pushCallFrame(arguments);
        try {
            MolangScriptInterpreter.Node root =
                new MolangScriptInterpreter.ScriptParser(prepared, this).parseScript();
            MolangScriptInterpreter.ScriptRuntime runtime =
                new MolangScriptInterpreter.ScriptRuntime(this.scope, this);
            double value = root.eval(runtime);
            return runtime.returned ? runtime.returnValue : value;
        } finally {
            popCallFrame();
            this.callDepth--;
        }
    }

    // ---- mclib 的函数构造 ----

    @Override
    protected IValue createFunction(String first, List<Object> args) throws Exception {
        if (MolangArgsRewriter.ARGS_GET_FUNCTION.equals(first)) {
            return new ArgsGetFunction(parseArguments(args), this);
        }
        if (first.startsWith(MolangScriptInterpreter.FUNCTION_PREFIX)) {
            // fn.<名字>(...)：回到解释器执行脚本函数体（参数由 mclib 先求值，与普通函数一致）。
            return new ScriptFunctionCall(first, parseArguments(args), this);
        }
        if (!this.functions.containsKey(first) && !first.startsWith("!") && !first.startsWith("-")) {
            return new ScopeFunction(first, parseArguments(args), this.scope);
        }
        return super.createFunction(first, args);
    }

    /**
     * 把 {@code createFunction} 拿到的符号列表按 {@code ,} 切成求值参数
     * （与 {@code MathBuilder.createFunction} 同一套切分规则，但参数值由我们自己构造）。
     */
    private List<IValue> parseArguments(List<Object> args) throws Exception {
        List<IValue> values = new ArrayList<>();
        List<Object> buffer = new ArrayList<>();
        for (Object arg : args) {
            if (",".equals(arg)) {
                values.add(parseSymbols(buffer));
                buffer = new ArrayList<>();
            } else {
                buffer.add(arg);
            }
        }
        if (!buffer.isEmpty()) {
            values.add(parseSymbols(buffer));
        }
        return values;
    }

    /** {@code args_get(<下标>)}：按当前调用的实参帧读值，越界与负下标都算未定义（0）。 */
    private static final class ArgsGetFunction extends Function {

        private final ScriptMolangParser parser;

        ArgsGetFunction(List<IValue> values, ScriptMolangParser parser) throws Exception {
            super(values.toArray(new IValue[0]), MolangArgsRewriter.ARGS_GET_FUNCTION);
            this.parser = parser;
        }

        @Override
        public double get() {
            return this.parser.argument((int) getArg(0));
        }
    }

    /** {@code fn.<名字>(...)}：把实参求值后交给解释器执行脚本函数体。 */
    private static final class ScriptFunctionCall extends Function {

        private final String function;
        private final ScriptMolangParser parser;

        ScriptFunctionCall(String callName, List<IValue> values, ScriptMolangParser parser) throws Exception {
            super(values.toArray(new IValue[0]), callName);
            this.function = callName.substring(MolangScriptInterpreter.FUNCTION_PREFIX.length());
            this.parser = parser;
        }

        @Override
        public double get() {
            List<Double> arguments = new ArrayList<>(this.args.length);
            for (IValue value : this.args) {
                arguments.add(value.get());
            }
            return this.parser.invoke(this.function, arguments);
        }
    }

    /** 内置函数表里没有的名字：转发给宿主 scope（宿主的函数表）。 */
    private static final class ScopeFunction extends Function {

        private final MolangScriptInterpreter.MolangScriptScope scope;

        ScopeFunction(String name, List<IValue> values, MolangScriptInterpreter.MolangScriptScope scope)
            throws Exception {
            super(values.toArray(new IValue[0]), name);
            this.scope = scope;
        }

        @Override
        public double get() {
            List<MolangScriptInterpreter.Argument> arguments = new ArrayList<>(this.args.length);
            for (IValue value : this.args) {
                arguments.add(MolangScriptInterpreter.Argument.number(value.get()));
            }
            return this.scope.functionValue(this.name, arguments);
        }
    }
}
