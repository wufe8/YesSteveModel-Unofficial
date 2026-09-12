package com.fox.ysmu.client.animation.molang;

import java.util.ArrayList;
import java.util.List;

import com.eliotlash.mclib.math.IValue;

import software.bernie.geckolib3.core.molang.MolangException;

/**
 * {@code .molang} 脚本解释器核心（二期第 1–3 步）：{@code args[...]}、闭包块（花括号代码块）与
 * {@code return} 穿透。
 * <p>
 * YSM-wiki: {@code molang/script}。脚本是语句序列：{@code t.x=args[0];} 这样的赋值、{@code return}
 * 语句，以及可以出现在 {@code ? :} 分支里的闭包块。表达式层（运算符、{@code math.*}、字符串池、
 * {@code ??}）完全复用 {@link software.bernie.geckolib3.core.molang.MolangParser}；本类只补
 * "语句 / 块 / 控制流"那一层，因为 {@code MathBuilder} 的字符白名单不允许花括号，{@code
 * MolangMultiStatement} 也装不下块体。
 * <p>
 * 与 Minecraft 无关：变量、函数调用与调用参数都通过 {@link MolangScriptScope} 注入；同一次执行里
 * {@code t.*} 是局部变量（不写回宿主），其余名字（{@code v.*} 等）写回 scope。
 * <p>
 * 约定（wiki 与现有 {@code MolangMultiStatement} 一致）：
 * <ul>
 *   <li>{@code return <表达式>} 结束整个调用，并从任意深度的嵌套块中穿透出来；</li>
 *   <li>没有 {@code return} 时，结果是**最后一条语句**的值；空脚本为 0；</li>
 *   <li>{@code cond ? value} 缺省 {@code : 0}；块作为分支时先执行块再取块的值。</li>
 * </ul>
 */
public final class MolangScriptInterpreter {

    /** {@code t.*} 是按调用帧隔离的临时变量，不写回宿主。 */
    static final String LOCAL_PREFIX = "t.";

    /** 脚本自定义函数的调用前缀（wiki「链式调用」）。 */
    static final String FUNCTION_PREFIX = "fn.";

    static final String RETURN_KEYWORD = "return";

    static final String BREAK_KEYWORD = "break";

    static final String CONTINUE_KEYWORD = "continue";

    /** 循环关键字（wiki：`loop(n, {...})` / `for_each(x, args, {...})`）。 */
    static final String LOOP_KEYWORD = "loop";

    static final String FOR_EACH_KEYWORD = "for_each";

    /** {@code for_each} 的第二个参数只支持 {@code args}（wiki 的写法）。 */
    static final String ARGS_KEYWORD = "args";

    /** 调用链长度上限（wiki：超过 32 返回 null；数值上下文里就是 0）。 */
    public static final int MAX_CALL_DEPTH = 32;

    private MolangScriptInterpreter() {}

    /**
     * 解释器所需的**最小环境**：读变量、写变量、调函数、取调用参数。
     * <p>
     * 与 {@code OpenYsmControllerExpressionEvaluator.ConditionScope} 同源，但多出"写变量"与
     * {@code args[]} 两个入口 —— 脚本的赋值（如 {@code v.roaming.horn_checker=1}）必须能落回宿主，
     * 否则解释器对渲染没有任何意义。游戏内的实现负责把名字映射到
     * {@code MolangParser.VARIABLES} / {@code MolangPhysicsRuntime} 等现有变量表。
     * <p>
     * 变量名按脚本里写的样子原样传入（不会替宿主把 {@code q.} 折成 {@code query.}），
     * 因为不同宿主的变量表键就可能不同 —— 映射是宿主 scope 的职责。
     */
    public interface MolangScriptScope {

        /**
         * 读一个变量；未定义按 Molang 语义返回 0。
         * <p>
         * {@code t.*} 不会走到这里：临时变量按**调用帧**解析（{@code ScriptMolangParser}），
         * 宿主给的同名值不参与 —— 这正是 wiki 说的"调用链各节点互相隔离"。
         */
        double variableValue(String name);

        /** 写一个变量；{@code t.*} 局部变量不会走到这里。 */
        void setVariableValue(String name, double value);

        /** 调用一个函数；{@code args_get} 与内置 {@code math.*} 不会走到这里。 */
        double functionValue(String name, List<Argument> arguments);

        /** 第 {@code index} 个调用参数；越界返回 0。 */
        double argument(int index);

        /** 本次调用的参数个数（{@code for_each} 的遍历边界）；不知道就返回 0。 */
        int argumentCount();

        /**
         * 取一个脚本自定义函数的**函数体**（模型包里 {@code functions/<名字>.molang} 的正文）。
         * 名字不含 {@code fn.} 前缀；没有这个函数返回 {@code null}（{@code fn.*} 求值为 0）。
         */
        String functionScript(String name);
    }

    /** 函数参数：Molang 里字符串与数字共用一套调用约定（与控制器求值器的 {@code Argument} 同形）。 */
    public static final class Argument {

        private final String stringValue;
        private final double numberValue;
        private final boolean string;

        private Argument(String stringValue, double numberValue, boolean string) {
            this.stringValue = stringValue;
            this.numberValue = numberValue;
            this.string = string;
        }

        public static Argument string(String value) {
            return new Argument(value, 0.0d, true);
        }

        public static Argument number(double value) {
            return new Argument("", value, false);
        }

        public boolean isString() {
            return this.string;
        }

        public String asString() {
            return this.string ? this.stringValue : Double.toString(this.numberValue);
        }

        public double asNumber() {
            if (!this.string) {
                return this.numberValue;
            }
            try {
                return Double.parseDouble(this.stringValue);
            } catch (NumberFormatException e) {
                return 0.0d;
            }
        }
    }

    /** 脚本无法解析时抛出（与"未定义即 0"不同，属于脚本错误，调用方自行决定是否兜底）。 */
    public static final class MolangScriptException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MolangScriptException(String message) {
            super(message);
        }

        MolangScriptException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 编译并执行脚本。
     *
     * @param script 脚本正文（可以带行注释与块注释）
     * @param scope  变量 / 函数 / 参数来源
     * @return 有 {@code return} 用它，否则用最后一条语句的值；空脚本为 0
     * @throws MolangScriptException 脚本无法解析
     */
    public static double evaluate(String script, MolangScriptScope scope) {
        if (script == null) {
            return 0.0d;
        }
        String prepared = prepare(script);
        if (prepared.trim()
            .isEmpty()) {
            return 0.0d;
        }
        ScriptMolangParser leafParser = new ScriptMolangParser(scope);
        ScriptRuntime runtime = new ScriptRuntime(scope, leafParser);
        Node root = new ScriptParser(prepared, leafParser).parseScript();
        double value = root.eval(runtime);
        return runtime.returned ? runtime.returnValue : value;
    }

    /**
     * 解析前的整篇预处理：剥注释 → 字符串外统一小写（与 {@code MolangParser.parseExpression}
     * 对单个表达式的处理一致）→ 改写 {@code args[...]}。
     */
    static String prepare(String script) {
        String stripped = MolangFunctionParser.stripComments(script);
        String lowered = lowerCaseOutsideStrings(stripped);
        return MolangArgsRewriter.rewrite(lowered);
    }

    /** 只把字符串字面量之外的大小写折叠掉（变量名与函数名在 Molang 里大小写不敏感）。 */
    private static String lowerCaseOutsideStrings(String expression) {
        StringBuilder out = new StringBuilder(expression.length());
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quoted) {
                out.append(c);
                if (c == '\\' && i + 1 < expression.length()) {
                    out.append(expression.charAt(++i));
                } else if (c == quote) {
                    quoted = false;
                }
            } else if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
                out.append(c);
            } else {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    // ---- 执行状态与 AST ----

    /** 一次调用的状态：宿主 scope、叶子解析器（变量表）、以及 {@code return} 短路标记。 */
    static final class ScriptRuntime {

        final MolangScriptScope scope;
        final ScriptMolangParser parser;
        boolean returned;
        double returnValue;
        /** {@code break} / {@code continue} 的信号位：由最近的循环消费，跨调用边界会被清掉。 */
        boolean broke;
        boolean continued;

        ScriptRuntime(MolangScriptScope scope, ScriptMolangParser parser) {
            this.scope = scope;
            this.parser = parser;
        }
    }

    interface Node {

        double eval(ScriptRuntime runtime);
    }

    static final class ConstantNode implements Node {

        private final double value;

        ConstantNode(double value) {
            this.value = value;
        }

        @Override
        public double eval(ScriptRuntime runtime) {
            return this.value;
        }
    }

    /** 一段可由 {@link ScriptMolangParser} 求值的纯表达式（不含块、不含顶层三元）。 */
    static final class LeafNode implements Node {

        private final IValue value;

        LeafNode(IValue value) {
            this.value = value;
        }

        @Override
        public double eval(ScriptRuntime runtime) {
            return this.value.get();
        }
    }

    /**
     * 闭包块 / 脚本正文：顺序执行语句，遇到 {@code return} 立刻停下。
     * 块的值是**最后一条执行过的语句**的值（空块为 0）。
     */
    static final class BlockNode implements Node {

        private final List<Node> statements;

        BlockNode(List<Node> statements) {
            this.statements = statements;
        }

        @Override
        public double eval(ScriptRuntime runtime) {
            double value = 0.0d;
            for (Node statement : this.statements) {
                value = statement.eval(runtime);
                if (runtime.returned || runtime.broke || runtime.continued) {
                    // return / break / continue 都是"本层剩下的语句不再执行"，
                    // 由最近的消费者（调用边界或循环）清掉信号。
                    break;
                }
            }
            return value;
        }
    }

    /** {@code cond ? then : else}：惰性求值 —— 只有命中的分支会执行（闭包块由此获得分支语义）。 */
    static final class TernaryNode implements Node {

        private final Node condition;
        private final Node whenTrue;
        private final Node whenFalse;

        TernaryNode(Node condition, Node whenTrue, Node whenFalse) {
            this.condition = condition;
            this.whenTrue = whenTrue;
            this.whenFalse = whenFalse;
        }

        @Override
        public double eval(ScriptRuntime runtime) {
            Node branch = this.condition.eval(runtime) != 0.0d ? this.whenTrue : this.whenFalse;
            return branch.eval(runtime);
        }
    }

    /** {@code target = <表达式>}：{@code t.*} 只写本次执行的变量表，其余名字同时回写宿主。 */
    static final class AssignmentNode implements Node {

        private final String target;
        private final Node value;

        AssignmentNode(String target, Node value) {
            this.target = target;
            this.value = value;
        }

        @Override
        public double eval(ScriptRuntime runtime) {
            double result = this.value.eval(runtime);
            // 不用 LazyVariable.set()：那会把动态取值换成常量，跨调用帧就不再隔离了。
            runtime.parser.writeVariable(this.target, result);
            return result;
        }
    }

    /** {@code return <表达式>}：记录返回值并置位短路标记，由 {@link BlockNode} 逐层退出。 */
    static final class ReturnNode implements Node {

        private final Node value;

        ReturnNode(Node value) {
            this.value = value;
        }

        @Override
        public double eval(ScriptRuntime runtime) {
            double result = this.value.eval(runtime);
            runtime.returnValue = result;
            runtime.returned = true;
            return result;
        }
    }

    /** {@code fn.<名字>}（不带小括号）：执行脚本函数，无实参。 */
    static final class CallNode implements Node {

        private final String function;

        CallNode(String function) {
            this.function = function;
        }

        @Override
        public double eval(ScriptRuntime runtime) {
            return runtime.parser.invoke(this.function, java.util.Collections.emptyList());
        }
    }

    /** {@code break}：置位信号，由最近的循环消费。 */
    static final class BreakNode implements Node {

        @Override
        public double eval(ScriptRuntime runtime) {
            runtime.broke = true;
            return 0.0d;
        }
    }

    /** {@code continue}：置位信号，由最近的循环消费（跳过本层剩下的语句）。 */
    static final class ContinueNode implements Node {

        @Override
        public double eval(ScriptRuntime runtime) {
            runtime.continued = true;
            return 0.0d;
        }
    }

    /**
     * {@code loop(<次数>, { ... })}：执行次数次（向下取整，负数/0 不执行）。
     * 值是最后一次迭代的值；{@code return} 会直接穿出去，{@code break}/{@code continue} 在这里被消费。
     */
    static final class LoopNode implements Node {

        private final Node count;
        private final Node body;

        LoopNode(Node count, Node body) {
            this.count = count;
            this.body = body;
        }

        @Override
        public double eval(ScriptRuntime runtime) {
            int times = (int) this.count.eval(runtime);
            double value = 0.0d;
            for (int i = 0; i < times; i++) {
                value = this.body.eval(runtime);
                if (runtime.returned) {
                    break;
                }
                if (runtime.broke) {
                    runtime.broke = false;
                    break;
                }
                runtime.continued = false;
            }
            return value;
        }
    }

    /**
     * {@code for_each(<变量>, args, { ... })}：遍历**本次调用**的实参（wiki 里第二个参数只写
     * {@code args}），每次迭代把当前元素写进变量（通常是 {@code t.*}，于是按调用帧隔离）。
     * <p>
     * 迭代变量在循环结束后仍留在当前帧里（用它当累加器的写法因此也能工作）；调用结束时整帧丢弃。
     */
    static final class ForEachNode implements Node {

        private final String variable;
        private final Node body;

        ForEachNode(String variable, Node body) {
            this.variable = variable;
            this.body = body;
        }

        @Override
        public double eval(ScriptRuntime runtime) {
            int count = runtime.parser.argumentCount();
            double value = 0.0d;
            for (int i = 0; i < count; i++) {
                runtime.parser.writeVariable(this.variable, runtime.parser.argument(i));
                value = this.body.eval(runtime);
                if (runtime.returned) {
                    break;
                }
                if (runtime.broke) {
                    runtime.broke = false;
                    break;
                }
                runtime.continued = false;
            }
            return value;
        }
    }

    /**
     * 递归下降解析器：把脚本切成语句，把 {@code ? :} 与闭包块建成 AST，叶子交给
     * {@link ScriptMolangParser}。
     * <p>
     * 块体（{@code {...}} 内部）与脚本正文用同一套语句规则；三元分支既可以是块，也可以是普通
     * 表达式，缺失的 {@code else} 补 0。
     */
    static final class ScriptParser {

        private final String script;
        private final ScriptMolangParser leafParser;

        ScriptParser(String script, ScriptMolangParser leafParser) {
            this.script = script;
            this.leafParser = leafParser;
        }

        Node parseScript() {
            return parseBlockBody(this.script, 0, this.script.length());
        }

        /** 解析 {@code [start, end)} 之间的语句序列（块的内部或整篇脚本）。 */
        private Node parseBlockBody(String body, int start, int end) {
            List<Node> statements = new ArrayList<>();
            int i = start;
            while (i < end) {
                char c = body.charAt(i);
                if (c == ';' || Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                int statementEnd = findStatementEnd(body, i, end);
                String statement = body.substring(i, statementEnd)
                    .trim();
                if (!statement.isEmpty()) {
                    statements.add(parseStatement(statement));
                }
                i = statementEnd + 1;
            }
            return new BlockNode(statements);
        }

        private Node parseStatement(String statement) {
            if (isReturnKeyword(statement)) {
                String rest = statement.substring(RETURN_KEYWORD.length())
                    .trim();
                return new ReturnNode(rest.isEmpty() ? new ConstantNode(0.0d) : parseExpression(rest));
            }
            Node loop = parseLoopStatement(statement);
            if (loop != null) {
                return loop;
            }
            int assignment = findTopLevelAssignment(statement);
            if (assignment > 0) {
                String target = statement.substring(0, assignment)
                    .trim();
                if (isVariableName(target)) {
                    String value = statement.substring(assignment + 1)
                        .trim();
                    Node valueNode = value.isEmpty() ? new ConstantNode(0.0d) : parseExpression(value);
                    return new AssignmentNode(target, valueNode);
                }
            }
            return parseExpression(statement);
        }

        /** 解析一个完整表达式：可能是块、三元（分支可含块），或纯叶子表达式。 */
        private Node parseExpression(String expression) {
            String text = expression.trim();
            if (text.isEmpty()) {
                return new ConstantNode(0.0d);
            }
            // 这三个是"整段就是关键字/函数引用"的形态；带括号的 fn.x(...) 走 mclib 的函数机制。
            if (isKeyword(text, BREAK_KEYWORD)) {
                return new BreakNode();
            }
            if (isKeyword(text, CONTINUE_KEYWORD)) {
                return new ContinueNode();
            }
            String function = bareFunctionReference(text);
            if (function != null) {
                return new CallNode(function);
            }
            if (text.charAt(0) == '{') {
                int close = findMatchingBrace(text, 0);
                if (close < 0) {
                    throw new MolangScriptException("Unclosed '{' in Molang block: " + text);
                }
                if (close == text.length() - 1) {
                    return parseBlockBody(text, 1, close);
                }
            }
            int question = findTopLevelQuestion(text);
            if (question < 0) {
                return leaf(text);
            }
            Node condition = leaf(text.substring(0, question));
            String rest = text.substring(question + 1);
            int branchStart = skipWhitespace(rest, 0);
            Node whenTrue;
            Node whenFalse;
            if (branchStart < rest.length() && rest.charAt(branchStart) == '{') {
                int close = findMatchingBrace(rest, branchStart);
                if (close < 0) {
                    throw new MolangScriptException("Unclosed '{' in Molang ternary branch: " + text);
                }
                whenTrue = parseBlockBody(rest, branchStart + 1, close);
                String tail = rest.substring(close + 1)
                    .trim();
                whenFalse = tail.startsWith(":") ? parseExpression(tail.substring(1)) : new ConstantNode(0.0d);
            } else {
                int colon = findMatchingColon(rest);
                if (colon < 0) {
                    // 简写三元 cond ? value ≡ cond ? value : 0
                    whenTrue = parseExpression(rest);
                    whenFalse = new ConstantNode(0.0d);
                } else {
                    whenTrue = parseExpression(rest.substring(0, colon));
                    whenFalse = parseExpression(rest.substring(colon + 1));
                }
            }
            return new TernaryNode(condition, whenTrue, whenFalse);
        }

        /**
         * {@code loop(<次数>, { ... })} 与 {@code for_each(<变量>, args, { ... })} 两种语句形态。
         * 不是这两种形态就返回 null（交回普通语句/表达式处理）。
         */
        private Node parseLoopStatement(String statement) {
            String text = statement.trim();
            if (isKeywordCall(text, LOOP_KEYWORD)) {
                List<String> parts = splitCallArguments(text, LOOP_KEYWORD);
                if (parts == null || parts.size() != 2) {
                    throw new MolangScriptException(
                        "loop(...) needs exactly (count, { ... }): <" + text + ">");
                }
                return new LoopNode(parseExpression(parts.get(0)), parseBlockArgument(parts.get(1), text));
            }
            if (isKeywordCall(text, FOR_EACH_KEYWORD)) {
                List<String> parts = splitCallArguments(text, FOR_EACH_KEYWORD);
                if (parts == null || parts.size() != 3) {
                    throw new MolangScriptException(
                        "for_each(...) needs exactly (variable, args, { ... }): <" + text + ">");
                }
                String variable = parts.get(0).trim();
                if (!ARGS_KEYWORD.equals(parts.get(1)
                    .trim())) {
                    throw new MolangScriptException(
                        "for_each(...) only iterates 'args' in 1.7.10 YSMU: <" + text + ">");
                }
                if (!isPlausibleVariableName(variable)) {
                    throw new MolangScriptException("for_each(...) needs a variable name: <" + text + ">");
                }
                return new ForEachNode(variable, parseBlockArgument(parts.get(2), text));
            }
            return null;
        }

        /** 是不是 {@code <关键字>(...)} 形态（关键字后紧跟左括号，且最外层括号包到结尾）。 */
        private boolean isKeywordCall(String text, String keyword) {
            if (!isKeyword(text, keyword)) {
                return false;
            }
            int open = text.indexOf('(', keyword.length());
            if (open < 0) {
                return false;
            }
            for (int i = keyword.length(); i < open; i++) {
                if (!Character.isWhitespace(text.charAt(i))) {
                    return false;
                }
            }
            return findMatchingParen(text, open) == text.length() - 1;
        }

        /** 拆 {@code <关键字>(a, b, c)} 的实参文本（顶层逗号分隔）；括号不匹配返回 null。 */
        private List<String> splitCallArguments(String text, String keyword) {
            int open = text.indexOf('(', keyword.length());
            if (open < 0 || findMatchingParen(text, open) != text.length() - 1) {
                return null;
            }
            return splitTopLevel(text.substring(open + 1, text.length() - 1));
        }

        /** 循环体的实参必须是一个 {@code { ... }} 块，返回其语句序列。 */
        private Node parseBlockArgument(String argument, String statement) {
            String text = argument.trim();
            if (text.isEmpty() || text.charAt(0) != '{') {
                throw new MolangScriptException("a loop body must be a { ... } block: <" + statement + ">");
            }
            int close = findMatchingBrace(text, 0);
            if (close < 0) {
                throw new MolangScriptException("Unclosed '{' in loop body: <" + statement + ">");
            }
            return parseBlockBody(text, 1, close);
        }

        private Node leaf(String expression) {
            String text = expression.trim();
            if (text.isEmpty()) {
                return new ConstantNode(0.0d);
            }
            try {
                return new LeafNode(this.leafParser.parseExpression(text));
            } catch (MolangException e) {
                throw new MolangScriptException("Couldn't parse Molang expression '" + text + "'", e);
            }
        }
    }

    // ---- 纯文本扫描 ----

    private static boolean isReturnKeyword(String statement) {
        return statement.regionMatches(true, 0, RETURN_KEYWORD, 0, RETURN_KEYWORD.length())
            && (statement.length() == RETURN_KEYWORD.length()
                || !isIdentifierChar(statement.charAt(RETURN_KEYWORD.length())));
    }

    private static boolean isVariableName(String target) {
        if (target.isEmpty()) {
            return false;
        }
        for (int i = 0; i < target.length(); i++) {
            if (!isIdentifierChar(target.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 循环变量要比 {@link #isVariableName} 严一点：首字符必须是字母或下划线，
     * 这样 {@code for_each(1, args, ...)} 这种把字面量当变量名的写法会被明确拒绝。
     */
    private static boolean isPlausibleVariableName(String name) {
        if (name.isEmpty() || !(Character.isLetter(name.charAt(0)) || name.charAt(0) == '_')) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!isIdentifierChar(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    /** 是不是按调用帧隔离的 {@code t.*} 临时变量。 */
    static boolean isLocalVariable(String name) {
        return name != null && name.startsWith(LOCAL_PREFIX);
    }

    /**
     * 整个表达式就是 {@code fn.<名字>}（**不带**小括号）时返回名字，否则返回 null。
     * <p>
     * wiki 的写法 {@code fn.b;} 就是这种形态；带括号的 {@code fn.x(...)} 交给 mclib 的函数
     * 机制（{@code ScriptMolangParser.ScriptFunctionCall}），所以它在任意表达式里都成立
     * （{@code return n * fn.fact(n - 1);} 这类递归也是这样工作的）。
     */
    private static String bareFunctionReference(String text) {
        if (!text.startsWith(FUNCTION_PREFIX)) {
            return null;
        }
        int end = FUNCTION_PREFIX.length();
        while (end < text.length() && isIdentifierChar(text.charAt(end))) {
            end++;
        }
        if (end == FUNCTION_PREFIX.length() || end != text.length()) {
            return null;
        }
        return text.substring(FUNCTION_PREFIX.length());
    }

    private static boolean isKeyword(String text, String keyword) {
        return text.regionMatches(true, 0, keyword, 0, keyword.length())
            && (text.length() == keyword.length() || !isIdentifierChar(text.charAt(keyword.length())));
    }

    /**
     * 找当前语句的结束位置：括号 / 块 / 下标深度全为 0 的 {@code ;}；找不到就返回 {@code end}。
     * 嵌套块里的 {@code ;} 属于块自己，不会误切。
     */
    private static int findStatementEnd(String body, int from, int end) {
        int paren = 0;
        int brace = 0;
        int bracket = 0;
        for (int i = from; i < end; i++) {
            char c = body.charAt(i);
            if (c == '\'' || c == '"') {
                i = quoteEnd(body, i, end);
                continue;
            }
            if (c == '(') {
                paren++;
            } else if (c == ')') {
                paren--;
            } else if (c == '{') {
                brace++;
            } else if (c == '}') {
                brace--;
            } else if (c == '[') {
                bracket++;
            } else if (c == ']') {
                bracket--;
            } else if (c == ';' && paren == 0 && brace == 0 && bracket == 0) {
                return i;
            }
        }
        return end;
    }

    /** 第一个顶层 {@code ?}（跳过 {@code ??} 与括号 / 块 / 下标内部），没有返回 -1。 */
    private static int findTopLevelQuestion(String s) {
        int paren = 0;
        int brace = 0;
        int bracket = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = quoteEnd(s, i, s.length());
                continue;
            }
            if (c == '(') {
                paren++;
            } else if (c == ')') {
                paren--;
            } else if (c == '{') {
                brace++;
            } else if (c == '}') {
                brace--;
            } else if (c == '[') {
                bracket++;
            } else if (c == ']') {
                bracket--;
            } else if (c == '?' && paren == 0 && brace == 0 && bracket == 0) {
                if (i + 1 < s.length() && s.charAt(i + 1) == '?') {
                    i++;
                    continue;
                }
                return i;
            }
        }
        return -1;
    }

    /**
     * 找与当前 {@code ?} 配对的 {@code :}：先出现的、且前面没有未配对的嵌套 {@code ?}。
     * 嵌套三元 / 块 / 括号 / 下标 / 字符串都会跳过，因此 {@code a ? b ? c : d : e} 会正确取到
     * 最后一个 {@code :}。找不到返回 -1（简写三元）。
     */
    private static int findMatchingColon(String s) {
        int paren = 0;
        int brace = 0;
        int bracket = 0;
        int questions = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = quoteEnd(s, i, s.length());
                continue;
            }
            if (c == '(') {
                paren++;
                continue;
            }
            if (c == ')') {
                paren--;
                continue;
            }
            if (c == '{') {
                brace++;
                continue;
            }
            if (c == '}') {
                brace--;
                continue;
            }
            if (c == '[') {
                bracket++;
                continue;
            }
            if (c == ']') {
                bracket--;
                continue;
            }
            if (paren != 0 || brace != 0 || bracket != 0) {
                continue;
            }
            if (c == '?') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '?') {
                    i++;
                    continue;
                }
                questions++;
            } else if (c == ':') {
                if (questions == 0) {
                    return i;
                }
                questions--;
            }
        }
        return -1;
    }

    /** 第一个顶层 {@code =} 赋值号（排除 {@code == != <= >= ?=}），没有返回 -1。 */
    private static int findTopLevelAssignment(String s) {
        int paren = 0;
        int brace = 0;
        int bracket = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = quoteEnd(s, i, s.length());
                continue;
            }
            if (c == '(') {
                paren++;
                continue;
            }
            if (c == ')') {
                paren--;
                continue;
            }
            if (c == '{') {
                brace++;
                continue;
            }
            if (c == '}') {
                brace--;
                continue;
            }
            if (c == '[') {
                bracket++;
                continue;
            }
            if (c == ']') {
                bracket--;
                continue;
            }
            if (c != '=' || paren != 0 || brace != 0 || bracket != 0) {
                continue;
            }
            char prev = i > 0 ? s.charAt(i - 1) : '\0';
            char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            if (prev == '=' || prev == '!' || prev == '<' || prev == '>' || prev == '?') {
                continue;
            }
            if (next == '=') {
                continue;
            }
            return i;
        }
        return -1;
    }

    /** 按顶层逗号切分（跳过字符串 / 括号 / 块 / 下标内部），用于函数实参与循环实参。 */
    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int paren = 0;
        int brace = 0;
        int bracket = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = quoteEnd(s, i, s.length());
                continue;
            }
            if (c == '(') {
                paren++;
            } else if (c == ')') {
                paren--;
            } else if (c == '{') {
                brace++;
            } else if (c == '}') {
                brace--;
            } else if (c == '[') {
                bracket++;
            } else if (c == ']') {
                bracket--;
            } else if (c == ',' && paren == 0 && brace == 0 && bracket == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    /** 与 {@code openIndex} 处的 {@code (} 配对的下标；不匹配返回 -1。 */
    private static int findMatchingParen(String s, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = quoteEnd(s, i, s.length());
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findMatchingBrace(String s, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = quoteEnd(s, i, s.length());
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 返回 {@code quoteIndex} 处引号的**闭合**引号下标；未闭合则返回 {@code end - 1}。 */
    private static int quoteEnd(String s, int quoteIndex, int end) {
        char quote = s.charAt(quoteIndex);
        for (int i = quoteIndex + 1; i < end; i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == quote) {
                return i;
            }
        }
        return end - 1;
    }

    private static int skipWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }
}
