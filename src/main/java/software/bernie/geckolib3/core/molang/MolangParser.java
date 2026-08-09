package software.bernie.geckolib3.core.molang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import com.eliotlash.mclib.math.Constant;
import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.MathBuilder;
import com.eliotlash.mclib.math.Variable;
import com.eliotlash.mclib.math.functions.Function;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.bernie.geckolib3.core.molang.expressions.MolangAssignment;
import software.bernie.geckolib3.core.molang.expressions.MolangExpression;
import software.bernie.geckolib3.core.molang.expressions.MolangMultiStatement;
import software.bernie.geckolib3.core.molang.expressions.MolangValue;
import software.bernie.geckolib3.core.molang.functions.BonePosition;
import software.bernie.geckolib3.core.molang.functions.BoneRotation;
import software.bernie.geckolib3.core.molang.functions.BoneScale;
import software.bernie.geckolib3.core.molang.functions.CosDegrees;
import software.bernie.geckolib3.core.molang.functions.FirstOrder;
import software.bernie.geckolib3.core.molang.functions.SecondOrder;
import software.bernie.geckolib3.core.molang.functions.SinDegrees;

/**
 * MoLang 解析器
 * <a href="https://bedrock.dev/docs/1.19.0.0/1.19.30.23/Molang#Math%20Functions">Wiki</a>
 *
 * YSMU: Heavily modified — added function namespace remapping (ysm.*),
 * null-coalescing (??) operator, vector function rewriting (bone_rot/bone_pos),
 * string literal pooling, scoped variable support (ScopedMolangVariable),
 * and OpenYSM expression compatibility. The original MolangParser has been
 * largely rewritten to support YSMU's extended animation system.
 */
public class MolangParser extends MathBuilder {

    public static final Map<String, LazyVariable> VARIABLES = new Object2ObjectOpenHashMap<>();
    public static final MolangExpression ZERO = new MolangValue(null, new Constant(0));
    public static final MolangExpression ONE = new MolangValue(null, new Constant(1));
    public static final String RETURN = "return ";

    /**
     * Host-mod-injected hook: registers YSMU-specific Molang functions on every
     * newly constructed parser. Inverted control — this vendored file has no
     * compile-time dependency on mod code (same pattern as
     * AnimationFile.builtinFallback). Set once at mod init; read-only afterwards;
     * null → no YSMU-specific functions are registered (graceful fallback).
     */
    @FunctionalInterface
    public interface MolangFunctionRegistrar {
        void register(Map<String, Class<? extends Function>> functions);
    }

    public static volatile MolangFunctionRegistrar ysmFunctionRegistrar = null;

    /**
     * Host-mod-injected hook: decides, for the current render frame, whether a
     * {@code v.} variable was EXPLICITLY set (even to 0) vs merely
     * default-initialized. Used by the {@code ??} null-coalescing operator.
     * Receives the full variable name as written (e.g. {@code v.roaming.x}).
     * Null → treated as "not explicitly set" (graceful fallback).
     */
    @FunctionalInterface
    public interface ExplicitVariableLookup {
        boolean isExplicitlySet(String fullVariableName);
    }

    public static volatile ExplicitVariableLookup explicitVariableLookup = null;

    private static final Logger LOG = LogManager.getLogger("ysmu.molang");

    public MolangParser() {
        super();
        // 将函数重新映射为 MoLang 标准名
        doCoreRemaps();
    }

    private void doCoreRemaps() {
        // 将 sin 和 cos 改成角度参数
        this.functions.put("cos", CosDegrees.class);
        this.functions.put("sin", SinDegrees.class);
        this.functions.put("ysm.first_order", FirstOrder.class);
        this.functions.put("ysm.second_order", SecondOrder.class);
        this.functions.put("ysm.bone_rot_x", BoneRotation.class);
        this.functions.put("ysm.bone_rot_y", BoneRotation.class);
        this.functions.put("ysm.bone_rot_z", BoneRotation.class);
        this.functions.put("ysm.bone_pos_x", BonePosition.class);
        this.functions.put("ysm.bone_pos_y", BonePosition.class);
        this.functions.put("ysm.bone_pos_z", BonePosition.class);
        this.functions.put("ysm.bone_position_x", BonePosition.class);
        this.functions.put("ysm.bone_position_y", BonePosition.class);
        this.functions.put("ysm.bone_position_z", BonePosition.class);
        this.functions.put("ysm.bone_scale_x", BoneScale.class);
        this.functions.put("ysm.bone_scale_y", BoneScale.class);
        this.functions.put("ysm.bone_scale_z", BoneScale.class);

        // YSMU 特有的 ysm.* / ctrl.* / query.* 函数由宿主 mod 通过
        // ysmFunctionRegistrar 钩子注入（反向控制，本文件不引用 mod 类）。
        MolangFunctionRegistrar registrar = ysmFunctionRegistrar;
        if (registrar != null) {
            registrar.register(this.functions);
        }

        remap("abs", "math.abs");
        remap("acos", "math.acos");
        remap("asin", "math.asin");
        remap("atan", "math.atan");
        remap("atan2", "math.atan2");
        remap("ceil", "math.ceil");
        remap("clamp", "math.clamp");
        remap("cos", "math.cos");
        // MathBuilder 将这些函数注册为短名，需要直接复制到 math.* 名下
        this.functions.put("math.die_roll", this.functions.get("roll"));
        this.functions.put("math.die_roll_integer", this.functions.get("rolli"));
        this.functions.put("math.hermite_blend", this.functions.get("hermite"));
        remap("exp", "math.exp");
        remap("floor", "math.floor");
        remap("lerp", "math.lerp");
        remap("lerprotate", "math.lerprotate");
        remap("ln", "math.ln");
        remap("max", "math.max");
        remap("min", "math.min");
        remap("mod", "math.mod");
        remap("pi", "math.pi");
        remap("pow", "math.pow");
        remap("random", "math.random");
        // MathBuilder 将 random_integer 注册为 "randomi"，而非 "random_integer"
        this.functions.put("math.random_integer", this.functions.get("randomi"));
        remap("round", "math.round");
        remap("sin", "math.sin");
        remap("sqrt", "math.sqrt");
        remap("trunc", "math.trunc");
    }

    @Override

    public void register(Variable variable) {
        if (!(variable instanceof LazyVariable)) {
            variable = LazyVariable.from(variable);
        }
        String name = normalizeVariableName(variable.getName());
        if (name.startsWith("v.") && !(variable instanceof ScopedMolangVariable)) {
            Variable fallback = variable;
            variable = new ScopedMolangVariable(name, fallback::get);
        }
        VARIABLES.put(name, (LazyVariable) variable);
    }

    /**
     * 重映射方法
     */
    public void remap(String old, String newName) {
        this.functions.put(newName, this.functions.remove(old));
    }

    @Deprecated
    public void setValue(String name, double value) {
        setValue(name, () -> value);
    }

    public void setValue(String name, DoubleSupplier value) {
        LazyVariable variable = getVariable(name);
        if (variable != null) {
            variable.set(value);
        }
    }

    @Override

    protected LazyVariable getVariable(String name) {
        name = normalizeVariableName(name);
        return VARIABLES.computeIfAbsent(name, MolangParser::newVariable);
    }

    public LazyVariable getVariable(String name, MolangMultiStatement currentStatement) {
        name = normalizeVariableName(name);
        LazyVariable variable;
        if (currentStatement != null) {
            variable = currentStatement.locals.get(name);
            if (variable != null) {
                return variable;
            }
        }
        return getVariable(name);
    }

    private static String normalizeVariableName(String name) {
        if (name.startsWith("q.")) {
            return "query." + name.substring(2);
        }
        if (name.startsWith("variable.")) {
            return "v." + name.substring("variable.".length());
        }
        return name;
    }

    private static LazyVariable newVariable(String key) {
        return key.startsWith("v.") ? new ScopedMolangVariable(key, 0) : new LazyVariable(key, 0);
    }

    public MolangExpression parseJson(JsonElement element) throws MolangException {
        if (!element.isJsonPrimitive()) {
            return ZERO;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return new MolangValue(this, new Constant(primitive.getAsDouble()));
        }
        if (primitive.isString()) {
            String string = primitive.getAsString();
            try {
                return new MolangValue(this, new Constant(Double.parseDouble(string)));
            } catch (NumberFormatException ex) {
                return parseExpression(string);
            }
        }
        return ZERO;
    }

    /**
     * 解析一个 MoLang 表达式
     */
    public MolangExpression parseExpression(String expression) throws MolangException {
        MolangMultiStatement result = null;
        for (String split : splitStatements(lowerCaseOutsideStrings(expression).trim())) {
            String trimmed = split.trim();
            if (!trimmed.isEmpty()) {
                if (result == null) {
                    result = new MolangMultiStatement(this);
                }
                result.expressions.add(parseOneLine(trimmed, result));
            }
        }
        if (result == null) {
            throw new MolangException("Molang expression cannot be blank!");
        }
        return result;
    }

    public static List<String> splitStatements(String expression) throws MolangException {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder(expression.length());
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quoted) {
                current.append(c);
                if (c == '\\' && i + 1 < expression.length()) {
                    current.append(expression.charAt(++i));
                } else if (c == quote) {
                    quoted = false;
                }
            } else if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
                current.append(c);
            } else if (c == ';') {
                statements.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quoted) {
            throw new MolangException("Unclosed string literal in Molang expression!");
        }
        statements.add(current.toString());
        return statements;
    }

    @Override
    public String[] breakdown(String expression) throws Exception {
        return super.breakdown(rewriteOpenYsmExpression(expression));
    }

    /**
     * 解析单个 MoLang 表达式
     */
    protected MolangExpression parseOneLine(String expression, MolangMultiStatement currentStatement)
        throws MolangException {
        if (expression.startsWith(RETURN)) {
            try {
                return new MolangValue(this, parse(expression.substring(RETURN.length()))).addReturn();
            } catch (Exception e) {
                throw new MolangException("Couldn't parse return '" + expression + "' expression!");
            }
        }

        // Handle null-coalescing operator: a ?? b
        // Intended semantics: use a if it was explicitly set (even to 0),
        // otherwise fall back to b.  The original implementation checked
        // l != 0, which treated explicit 0 the same as "never set".
        int ncIdx = findNullCoalesce(expression);
        if (ncIdx > 0) {
            String leftExpr = expression.substring(0, ncIdx).trim();
            String rightExpr = expression.substring(ncIdx + 2).trim();
            MolangExpression leftVal = parseOneLine(leftExpr, currentStatement);
            MolangExpression rightVal = parseOneLine(rightExpr, currentStatement);
            return new MolangValue(this, new com.eliotlash.mclib.math.IValue() {
                @Override
                public double get() {
                    double l = leftVal.get();
                    double r = rightVal.get();
                    // Check if the variable was EXPLICITLY set by the user (via GUI/config),
                    // not just default-initialized.  Default-initialized variables are in
                    // PENDING_ROAMING but NOT in EXPLICIT_ROAMING, so they still fall
                    // through to the default when their value is 0.
                    boolean userSet = false;
                    ExplicitVariableLookup lookup = explicitVariableLookup;
                    if (lookup != null) {
                        userSet = lookup.isExplicitlySet(leftExpr);
                    }
                    double result = userSet ? l : (l != 0 ? l : r);
                    return result;
                }
            });
        }

        try {
            // 将表达式拆分
            List<Object> symbols = breakdownChars(this.breakdown(expression));
            // 如果是赋值表达式
            if (symbols.size() >= 3 && (symbols.get(0) instanceof String name)
                && isVariable(symbols.get(0))
                && symbols.get(1)
                    .equals("=")) {
                symbols = symbols.subList(2, symbols.size());
                name = normalizeVariableName(name);
                LazyVariable variable;
                if (!name.startsWith("v.")
                    && !VARIABLES.containsKey(name)
                    && !currentStatement.locals.containsKey(name)) {
                    currentStatement.locals.put(name, (variable = new LazyVariable(name, 0)));
                } else {
                    variable = getVariable(name, currentStatement);
                }
                return new MolangAssignment(this, variable, parseSymbolsMolang(symbols));
            }
            // 如果是其他表达式
            return new MolangValue(this, parseSymbolsMolang(symbols));
        } catch (Exception e) {
            throw new MolangException("Couldn't parse '" + expression + "' expression!");
        }
    }

    /**
     * 将 parseSymbols 方法包装，并抛出 MolangException
     */
    private IValue parseSymbolsMolang(List<Object> symbols) throws MolangException {
        try {
            return this.parseSymbols(symbols);
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Molang parse error: {}", e.getMessage());
            }
            throw new MolangException("Couldn't parse an expression!");
        }
    }

    /**
     * OpenYSM 支持三元/括号内的嵌套赋值（如 {@code (cond) ? (v.wet = 30) : 0}）。
     * mclib 的 {@link Operation#ASSIGN} 只是纯函数（返回右值、不写回变量），
     * 无法产生副作用，因此这里把 {@code [var, "=", expr...]} 模式改写为带
     * 副作用的 {@link MolangAssignment}。
     */
    @Override
    public IValue parseSymbols(List<Object> symbols) throws Exception {
        if (symbols.size() >= 3
            && symbols.get(0) instanceof String
            && symbols.get(1) instanceof String
            && "=".equals(symbols.get(1))
            && isVariable(symbols.get(0))) {
            String name = normalizeVariableName((String) symbols.get(0));
            LazyVariable variable = getVariable(name);
            IValue value = parseSymbols(symbols.subList(2, symbols.size()));
            return new MolangAssignment(this, variable, value);
        }
        return super.parseSymbols(symbols);
    }

    /**
     * Finds the first {@code ??} (null-coalescing) operator in the expression,
     * skipping over parenthesized groups and string literals.
     * Returns the index of the first {@code ?}, or -1 if not found.
     */
    private static int findNullCoalesce(String expression) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < expression.length() - 1; i++) {
            char c = expression.charAt(i);
            if (c == '\'' || c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }
            if (depth == 0 && c == '?' && expression.charAt(i + 1) == '?') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 拓展此方法，从而让 {@link #breakdownChars(String[])} 能够解析等号
     * 这样就能更加轻松解析赋值表达式
     */
    @Override

    protected boolean isOperator(String s) {
        return super.isOperator(s) || s.equals("=");
    }

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

    private static String rewriteOpenYsmExpression(String expression) throws MolangException {
        String rewritten = replaceStringLiterals(expression);
        rewritten = rewriteVectorFunction(rewritten, "ysm.bone_rot", "ysm.bone_rot");
        rewritten = rewriteVectorFunction(rewritten, "ysm.bone_pos", "ysm.bone_pos");
        rewritten = rewriteVectorFunction(rewritten, "ysm.bone_position", "ysm.bone_position");
        rewritten = rewriteVectorFunction(rewritten, "ysm.bone_scale", "ysm.bone_scale");
        rewritten = rewriteVectorFunction(rewritten, "ysm.bone_pivot_abs", "ysm.bone_pivot_abs");
        return rewritten;
    }

    private static String replaceStringLiterals(String expression) throws MolangException {
        StringBuilder out = new StringBuilder(expression.length());
        boolean quoted = false;
        char quote = 0;
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < expression.length()) {
                    literal.append(expression.charAt(++i));
                } else if (c == quote) {
                    quoted = false;
                    out.append(MolangStringPool.intern(literal.toString()));
                    literal.setLength(0);
                } else {
                    literal.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
            } else {
                out.append(c);
            }
        }
        if (quoted) {
            throw new MolangException("Unclosed string literal in Molang expression!");
        }
        return out.toString();
    }

    private static String rewriteVectorFunction(String expression, String sourceFunction, String targetPrefix) {
        String out = expression;
        for (char axis : new char[] { 'x', 'y', 'z' }) {
            String suffix = ")." + axis;
            int searchFrom = 0;
            while (true) {
                int functionStart = out.indexOf(sourceFunction + "(", searchFrom);
                if (functionStart < 0) {
                    break;
                }
                int argsStart = functionStart + sourceFunction.length() + 1;
                int argsEnd = findMatchingParen(out, argsStart - 1);
                if (argsEnd < 0 || !out.startsWith(suffix, argsEnd)) {
                    searchFrom = argsStart;
                    continue;
                }
                String args = out.substring(argsStart, argsEnd);
                String replacement = targetPrefix + "_" + axis + "(" + args + ")";
                out = out.substring(0, functionStart) + replacement + out.substring(argsEnd + suffix.length());
                searchFrom = functionStart + replacement.length();
            }
        }
        return out;
    }

    private static int findMatchingParen(String expression, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < expression.length(); i++) {
            char c = expression.charAt(i);
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
}
