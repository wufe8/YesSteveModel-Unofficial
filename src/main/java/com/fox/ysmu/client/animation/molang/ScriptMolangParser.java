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
import software.bernie.geckolib3.core.molang.MolangStringPool;

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

    /**
     * 当前这次执行的作用域。
     *
     * <p><b>可变</b>：这个 parser 会被 {@code MolangScriptInterpreter} 跨帧复用（缓存解析好的 AST），
     * 每次执行前用 {@link #resetFor} 换绑到新的 scope。AST 里所有"会随执行变化"的引用都指向
     * **这个 parser**（{@link FrameVariable} / {@link ArgsGetFunction} / {@link ScriptFunctionCall} /
     * {@link ScopeFunction}），求值时再从这里读当前 scope 与帧栈 —— 所以换绑 scope 就够了。</p>
     */
    private MolangScriptInterpreter.MolangScriptScope scope;
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

    /**
     * 把这次执行换绑到 {@code newScope}，并把"本次执行"的状态清干净，让同一个 parser
     * （连同它解析好的 AST）可以跨帧复用。
     *
     * <p>哪些要清、哪些不能清：</p>
     * <ul>
     *   <li>{@code variables} <b>不能清</b> —— AST 里的 {@link FrameVariable} 就靠这张表持有，
     *       清掉会让已解析的表达式拿到另一批变量对象；它们的取值本来就是动态的
     *       （{@code readVariable} 读当前的 scope 与帧栈），换绑后自然读到新值。</li>
     *   <li>{@code localFrames} / {@code argumentFrames} / {@code writtenVariables} / {@code callDepth}
     *       是"本次执行"的状态，必须复位，否则上一个 tick 的 {@code t.*} 与写入会漏进来。</li>
     *   <li>{@code preparedFunctions} 是 {@code fn.*} 函数体的解析缓存，函数体文本不变，
     *       保留即可（这正是复用 parser 的第二个收益）。</li>
     * </ul>
     */
    void resetFor(MolangScriptInterpreter.MolangScriptScope newScope) {
        this.scope = newScope;
        this.localFrames.clear();
        this.localFrames.push(new HashMap<>());
        this.argumentFrames.clear();
        this.writtenVariables.clear();
        this.callDepth = 0;
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
            // 不同调用帧里读到各自的 t.*。
            variable = new FrameVariable(name);
            this.variables.put(name, variable);
        }
        return variable;
    }

    /**
     * supplier 只读、写入回落到 {@link #writeVariable} 的变量。
     *
     * <p>直接返回 {@code new LazyVariable(name, () -> readVariable(name))} 会踩 vendored
     * {@link LazyVariable#set(double)}：它把 supplier **替换成常量**，于是夹在表达式里的赋值
     * （{@code (v.x = 5) + 0}）既不经过 {@code writeVariable}（宿主永远收不到这次写入），
     * 又把这次执行后续的读值冻住。脚本的写回必须和块级赋值走同一条路，
     * 否则"脚本写了 v.x，骨骼却读不到"。</p>
     */
    private final class FrameVariable extends LazyVariable {

        FrameVariable(String name) {
            super(name, () -> readVariable(name));
        }

        @Override
        public void set(double value) {
            writeVariable(getName(), value);
        }

        @Override
        public void set(java.util.function.DoubleSupplier valueSupplier) {
            writeVariable(getName(), valueSupplier == null ? 0.0d : valueSupplier.getAsDouble());
        }
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
        String prepared = MolangScriptInterpreter.prepareCached(body);
        if (prepared.trim()
            .isEmpty()) {
            return 0.0d;
        }
        this.callDepth++;
        pushCallFrame(arguments);
        try {
            // 函数体文本是模型里写死的常量，而且这个 parser 会被跨帧复用 —— 解析一次就够。
            MolangScriptInterpreter.Node root = this.preparedFunctions.get(prepared);
            if (root == null) {
                root = new MolangScriptInterpreter.ScriptParser(prepared, this).parseScript();
                this.preparedFunctions.put(prepared, root);
            }
            MolangScriptInterpreter.ScriptRuntime runtime =
                new MolangScriptInterpreter.ScriptRuntime(this.scope, this);
            double value = root.eval(runtime);
            return runtime.returned ? runtime.returnValue : value;
        } finally {
            popCallFrame();
            this.callDepth--;
        }
    }

    /** {@code fn.*} 函数体（已预处理文本）→ 解析好的 AST。见 {@link #invoke}。 */
    private final Map<String, MolangScriptInterpreter.Node> preparedFunctions = new HashMap<>();

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
            return new ScopeFunction(first, parseArguments(args), this);
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

    /**
     * 内置函数表里没有的名字：转发给宿主 scope（宿主的函数表）。
     *
     * <p>注意这里持的是 **parser** 而不是 scope：parser 会被跨帧复用、scope 每帧换绑，
     * 若在解析期把 scope 存成 final 字段，复用后就会一直问第一帧的那个 scope。</p>
     */
    private static final class ScopeFunction extends Function {

        private final ScriptMolangParser parser;

        ScopeFunction(String name, List<IValue> values, ScriptMolangParser parser) throws Exception {
            super(values.toArray(new IValue[0]), name);
            this.parser = parser;
        }

        @Override
        public double get() {
            List<MolangScriptInterpreter.Argument> arguments = new ArrayList<>(this.args.length);
            for (IValue value : this.args) {
                arguments.add(toArgument(value.get()));
            }
            return this.parser.scope.functionValue(this.name, arguments);
        }
    }

    /**
     * 实参还原：字符串字面量在解析期被 {@code MolangStringPool} 池化成整数 id，直接当数字
     * 传下去的话，宿主的 {@code ctrl.set_animation('正常_待命')} 只能看到 {@code 1000001}
     * 这样的数字。用 {@link MolangStringPool#isStringId(int)} 把池化 id 还原成字符串实参
     * （{@link MolangScriptInterpreter.Argument#string}），其余保持数字。
     *
     * <p>池 id 从 {@link MolangStringPool#STRING_ID_BASE} 起编号，所以模型里正常的数值实参
     * 不会落进该区间。</p>
     */
    private static MolangScriptInterpreter.Argument toArgument(double value) {
        int asInt = (int) value;
        if (asInt == value && MolangStringPool.isStringId(asInt)) {
            String pooled = MolangStringPool.get(asInt);
            if (pooled != null) {
                return MolangScriptInterpreter.Argument.string(pooled);
            }
        }
        return MolangScriptInterpreter.Argument.number(value);
    }
}
