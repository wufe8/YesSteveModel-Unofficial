package com.fox.ysmu.client.animation.molang;

/**
 * 把脚本里的 {@code args[<表达式>]} 改写成内部调用形式 {@code args_get(<表达式>)}。
 * <p>
 * 为什么不直接扩展 mclib：{@code MathBuilder.breakdown()} 有一道字符白名单
 * （不允许 {@code [} / {@code ]}），带下标的参数访问根本进不了解析器，而改 vendored mclib
 * 会影响所有既有表达式路径。这里改为**解析前纯文本改写**：索引表达式原样保留 —— 既可以是
 * 常量（{@code args[0]}），也可以是变量表达式（{@code args[t.a + 1]}）—— 由
 * {@link ScriptMolangParser} 注册的 {@code args_get} 函数在求值时读取当前调用的参数表，
 * 所以参数个数在调用点已知这件事对改名毫无影响。
 * <p>
 * 改写是幂等的（{@code args_get(0)} 不会再被改写一次），跳过字符串字面量，也不会命中
 * {@code x.args[0]} 这类更长的标识符。
 */
public final class MolangArgsRewriter {

    /** 脚本关键字 {@code args}。 */
    static final String ARGS = "args";

    /** 改写后的内部函数名。 */
    static final String ARGS_GET_FUNCTION = "args_get";

    private MolangArgsRewriter() {}

    /**
     * 把 {@code args[...]} 全部改写为 {@code args_get(...)}；嵌套下标会先递归改写内层。
     *
     * @param script 已剥掉注释的脚本
     * @return 改写后的脚本；{@code null} 原样返回
     */
    public static String rewrite(String script) {
        if (script == null || script.isEmpty()) {
            return script;
        }
        StringBuilder out = new StringBuilder(script.length());
        int i = 0;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (c == '\'' || c == '"') {
                int end = skipQuoted(script, i);
                out.append(script, i, end);
                i = end;
                continue;
            }
            if (isArgsAccess(script, i)) {
                int bracket = skipWhitespace(script, i + ARGS.length());
                if (bracket < script.length() && script.charAt(bracket) == '[') {
                    int close = findMatchingBracket(script, bracket);
                    if (close < 0) {
                        throw new IllegalArgumentException("Unclosed 'args[' index in Molang script");
                    }
                    out.append(ARGS_GET_FUNCTION)
                        .append('(')
                        .append(rewrite(script.substring(bracket + 1, close)))
                        .append(')');
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** {@code args} 必须是一个独立标识符，后面可以跟空白再跟 {@code [}。 */
    private static boolean isArgsAccess(String s, int i) {
        if (i + ARGS.length() > s.length() || !s.regionMatches(true, i, ARGS, 0, ARGS.length())) {
            return false;
        }
        if (i > 0 && isIdentifierChar(s.charAt(i - 1))) {
            return false;
        }
        int after = i + ARGS.length();
        return after >= s.length() || !isIdentifierChar(s.charAt(after));
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    /** 返回 {@code quoteIndex} 处引号**之后**的下标（未闭合则返回字符串末尾）。 */
    private static int skipQuoted(String s, int quoteIndex) {
        char quote = s.charAt(quoteIndex);
        for (int i = quoteIndex + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
        }
        return s.length();
    }

    private static int skipWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** 找到与 {@code openIndex} 处 {@code [} 匹配的 {@code ]}，找不到返回 -1。 */
    private static int findMatchingBracket(String s, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(s, i) - 1;
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
