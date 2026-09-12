package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.util.json.JsonAnimationUtils;

/**
 * timeline 指令串：数组→串的拼接、注释剥离、切句。
 * <p>
 * 这里锁的是一个**回归**：模型把 timeline 写成字符串数组（YSM 扩展），里面带 C 风格行注释。
 * {@code instructionString()} 原先用 {@code ";"} 拼、**不保留换行**，而"行注释吃到行尾"在没有
 * 换行的整串上会一路吃到结尾 —— 实测一条 21959 字符的 timeline 被剥成 0 字符，模型的 Molang
 * 一行都不执行（表现为"游戏完全没反应、按方向键也无效"）。现在用 {@code ";\n"} 拼接。
 */
class MolangInstructionExecutorTest {

    /** 按 {@code instructionString()} 的真实拼接方式把数组元素接起来（数组元素之间加换行）。 */
    private static String joined(String... elements) {
        return String.join(";\n", elements);
    }

    @Test
    void aLeadingCommentMustNotSwallowTheRestOfTheTimeline() throws Exception {
        String instructions = joined(
            "// ============================================",
            "// 阶段一：初始化",
            "v.init = (v.init == 0) ? 1 : v.init;",
            "v.grid_size = (v.init == 1) ? 19.25 : v.grid_size;",
            "v.snake_left0 = 1;");

        List<String> statements = MolangInstructionExecutor.executableStatements(instructions);

        assertEquals(3, statements.size(), "只有三条真代码：" + statements);
        assertEquals("v.init = (v.init == 0) ? 1 : v.init", statements.get(0));
        assertEquals("v.grid_size = (v.init == 1) ? 19.25 : v.grid_size", statements.get(1));
        assertEquals("v.snake_left0 = 1", statements.get(2));
        assertFalse(MolangFunctionParser.stripComments(instructions).trim()
            .isEmpty(), "注释剥离不能吃掉整条 timeline");
    }

    /**
     * 没有换行时"整串剥注释"就是这个 bug 的形态 —— 这里把两种拼法摆在一起，
     * 说明为什么 {@code ";\n"} 是必须的。
     */
    @Test
    void joiningWithoutNewlinesIsExactlyWhatBreaksWholeStringCommentStripping() {
        String withNewlines = joined("// 说明", "v.a = 1;");
        String withoutNewlines = "// 说明;v.a = 1;";

        assertFalse(MolangFunctionParser.stripComments(withNewlines).trim()
            .isEmpty(), "有换行：注释在行尾结束");
        assertTrue(MolangFunctionParser.stripComments(withoutNewlines).trim()
            .isEmpty(), "没有换行：注释吃掉整串（回归的形态）");
    }

    @Test
    void anInlineCommentWithASemicolonIsDroppedButTheCodeSurvives() throws Exception {
        String instructions = joined(
            "v.max_snake = (v.init == 1) ? 30 : v.max_snake;  // 改为 30;",
            "v.speed = (v.init == 1) ? 0.2 : v.speed;");

        List<String> statements = MolangInstructionExecutor.executableStatements(instructions);

        assertEquals(2, statements.size(), "注释碎片必须被丢掉：" + statements);
        assertEquals("v.max_snake = (v.init == 1) ? 30 : v.max_snake", statements.get(0));
        assertEquals("v.speed = (v.init == 1) ? 0.2 : v.speed", statements.get(1));
    }

    @Test
    void commentsInsideStringLiteralsAreKept() throws Exception {
        String instructions = joined("ysm.play_sound('a//b','c');", "v.x = 1;");

        List<String> statements = MolangInstructionExecutor.executableStatements(instructions);

        assertEquals(2, statements.size(), statements.toString());
        assertEquals("ysm.play_sound('a//b','c')", statements.get(0), "字符串里的 // 不是注释");
    }

    @Test
    void blockCommentsAreAlsoRemoved() throws Exception {
        String instructions = joined("v.a = 1; /* 说明 ; 还有分号 */ v.b = 2;", "v.c = 3;");

        List<String> statements = MolangInstructionExecutor.executableStatements(instructions);

        assertEquals(3, statements.size(), statements.toString());
        assertEquals("v.a = 1", statements.get(0));
        assertEquals("v.b = 2", statements.get(1));
        assertEquals("v.c = 3", statements.get(2));
    }

    @Test
    void anEmptyOrCommentOnlyInstructionYieldsNothing() throws Exception {
        assertTrue(MolangInstructionExecutor.executableStatements("// 只有注释").isEmpty());
        assertTrue(MolangInstructionExecutor.executableStatements("; ; ;").isEmpty());
        assertTrue(MolangInstructionExecutor.executableStatements("").isEmpty());
    }

    /**
     * 端到端：按真实的 timeline 数组形态（含注释元素、注释里带 {@code ;}）走一遍
     * {@code JsonAnimationUtils} 的解析，确认拼出来的指令串里**每一行代码都还在**。
     */
    @Test
    void aTimelineArrayKeepsEveryCodeLineAfterParsing() throws Exception {
        JsonObject animation = new JsonObject();
        animation.addProperty("animation_length", 0.0417);
        animation.addProperty("loop", true);
        JsonObject timeline = new JsonObject();
        JsonArray lines = new JsonArray();
        lines.add(new com.google.gson.JsonPrimitive("// 初始化"));
        lines.add(new com.google.gson.JsonPrimitive("v.init = 1;"));
        lines.add(new com.google.gson.JsonPrimitive("v.grid_size = 19.25;  // 网格"));
        lines.add(new com.google.gson.JsonPrimitive("// 改 30 注释里带分号;"));
        lines.add(new com.google.gson.JsonPrimitive("v.max_snake = 30;"));
        timeline.add("0.0", lines);
        animation.add("timeline", timeline);
        JsonObject root = new JsonObject();
        JsonObject animations = new JsonObject();
        animations.add("probe", animation);
        root.add("animations", animations);

        JsonElement entry = root.getAsJsonObject("animations")
            .get("probe");
        Animation parsed = JsonAnimationUtils.deserializeJsonToAnimation(
            new java.util.AbstractMap.SimpleEntry<>("probe", entry), new MolangParser());

        assertEquals(1, parsed.customInstructionKeyframes.size());
        Object keyframe = parsed.customInstructionKeyframes.get(0);
        String instruction = String.valueOf(
            ((software.bernie.geckolib3.core.keyframe.EventKeyFrame<?>) keyframe).getEventData());

        List<String> statements = MolangInstructionExecutor.executableStatements(instruction);
        assertEquals(3, statements.size(), "三条代码都要在：" + statements);
        assertEquals("v.init = 1", statements.get(0));
        assertEquals("v.grid_size = 19.25", statements.get(1));
        assertEquals("v.max_snake = 30", statements.get(2));
    }
}
