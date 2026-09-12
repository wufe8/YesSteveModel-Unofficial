package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@code args[...]} 的解析前改写：mclib 的字符白名单不允许 {@code [} / {@code ]}，所以下标访问
 * 必须先改写成内部调用形式，且**索引表达式要原样保留**（可以是常量，也可以是变量表达式）。
 */
class MolangArgsRewriterTest {

    @Test
    void rewritesAConstantIndexToTheInternalCallForm() {
        assertEquals("args_get(0)", MolangArgsRewriter.rewrite("args[0]"));
        assertEquals("args_get(12)", MolangArgsRewriter.rewrite("args[12]"));
    }

    @Test
    void rewritesAnExpressionIndexWithoutEvaluatingIt() {
        assertEquals("args_get(t.a + 1)", MolangArgsRewriter.rewrite("args[t.a + 1]"));
        assertEquals("args_get(v.event_id - 1)", MolangArgsRewriter.rewrite("args[v.event_id - 1]"));
    }

    @Test
    void rewritesANestedIndexRecursively() {
        assertEquals("args_get(args_get(0))", MolangArgsRewriter.rewrite("args[args[0]]"));
        assertEquals("args_get(args_get(t.a)[0])", MolangArgsRewriter.rewrite("args[args[t.a][0]]"));
    }

    @Test
    void keepsTheSurroundingExpressionIntact() {
        assertEquals(
            "t.halo_no = args_get(0);",
            MolangArgsRewriter.rewrite("t.halo_no = args[0];"));
        assertEquals(
            "t.event_id == args_get(0) ? args_get(1) : 0",
            MolangArgsRewriter.rewrite("t.event_id == args[0] ? args[1] : 0"));
    }

    @Test
    void leavesStringLiteralsAndLongerNamesAlone() {
        assertEquals("'args[0]'", MolangArgsRewriter.rewrite("'args[0]'"));
        assertEquals("x.args[0]", MolangArgsRewriter.rewrite("x.args[0]"));
        assertEquals("args_get", MolangArgsRewriter.rewrite("args_get"));
        assertEquals("myargs[0]", MolangArgsRewriter.rewrite("myargs[0]"));
    }

    @Test
    void isIdempotent() {
        String once = MolangArgsRewriter.rewrite("args[0] + args[t.i]");
        assertEquals("args_get(0) + args_get(t.i)", once);
        assertEquals(once, MolangArgsRewriter.rewrite(once));
    }

    @Test
    void rejectsAnUnclosedIndex() {
        assertThrows(IllegalArgumentException.class, () -> MolangArgsRewriter.rewrite("args[0"));
    }
}
