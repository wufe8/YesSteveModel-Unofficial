package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * GUI 预览的变量兜底。
 *
 * <p>预览实体是 {@code setPlayer(null)} 的假实体，{@code AnimationRegister.setParserValue} 整段不执行，
 * 而 parser 是全局共享的 —— 预览的动画求值因此读到**世界渲染上一次留下的实时值**（实测预览里
 * {@code query.yaw_speed≈130}、{@code body_y_rotation=-180}）。模型若把这类速率累加进 {@code v.}
 * 变量（头发/披风滞后），预览里输入长期非 0 且不衰减 → 头发在模型选择界面里无休止 360 度旋转。</p>
 *
 * <p>这个测试锁住两件事：① {@link AnimationRegister#setPreviewParserValues} 确实把"玩家在动/在看"
 * 的量归零；② 名单里的每个名字都**真实注册**过（否则 {@code setValue} 会静默失效，名单写错了也看不出来）。</p>
 */
class AnimationRegisterPreviewValuesTest {

    private static double read(MolangParser parser, String name) {
        try {
            return parser.parseExpression(name)
                .get();
        } catch (Exception e) {
            throw new AssertionError("求值失败: " + name, e);
        }
    }

    private static MolangParser parser() {
        AnimationRegister.registerVariables();
        return GeckoLibCache.getInstance().parser;
    }

    @Test
    void everyPreviewNeutralNameIsRegistered() {
        MolangParser parser = parser();
        for (String name : AnimationRegister.PREVIEW_NEUTRAL_QUERIES) {
            LazyVariable variable = MolangParser.VARIABLES.get(name);
            assertNotNull(variable, "预览中性名单里有未注册的名字（setValue 会静默失效）: " + name);
        }
    }

    @Test
    void previewValuesResetMotionAndLookQueries() {
        MolangParser parser = parser();
        // 模拟"世界里刚走过/刚转过视角"留下的值
        parser.setValue("query.yaw_speed", 130.0d);
        parser.setValue("query.ground_speed", 4.3d);
        parser.setValue("query.head_x_rotation", 22.4d);
        parser.setValue("query.head_y_rotation", -25.5d);
        parser.setValue("query.body_y_rotation", -180.0d);
        parser.setValue("ysm.head_yaw", 22.4d);
        parser.setValue("ysm.input_vertical", 1.0d);
        parser.setValue("ysm.ground_speed2", 5.6d);

        AnimationRegister.setPreviewParserValues(parser);

        assertEquals(0.0d, read(parser, "query.yaw_speed"), 1.0e-9);
        assertEquals(0.0d, read(parser, "query.ground_speed"), 1.0e-9);
        assertEquals(0.0d, read(parser, "query.vertical_speed"), 1.0e-9);
        assertEquals(0.0d, read(parser, "query.head_x_rotation"), 1.0e-9);
        assertEquals(0.0d, read(parser, "query.head_y_rotation"), 1.0e-9);
        assertEquals(0.0d, read(parser, "query.body_y_rotation"), 1.0e-9);
        assertEquals(0.0d, read(parser, "query.eye_target_x_rotation"), 1.0e-9);
        assertEquals(0.0d, read(parser, "ysm.head_yaw"), 1.0e-9);
        assertEquals(0.0d, read(parser, "ysm.head_pitch"), 1.0e-9);
        assertEquals(0.0d, read(parser, "ysm.input_vertical"), 1.0e-9);
        assertEquals(0.0d, read(parser, "ysm.ground_speed2"), 1.0e-9);
    }

    /** 会随时间自然推进的量不能被复位：预览动画本身还得播、fps/渲染位置标记要与实际一致。 */
    @Test
    void previewValuesLeaveProgressingQueriesAlone() {
        MolangParser parser = parser();
        parser.setValue("query.anim_time", 7.5d);
        parser.setValue("query.life_time", 3.25d);
        parser.setValue("query.health", 17.0d);

        AnimationRegister.setPreviewParserValues(parser);

        assertEquals(7.5d, read(parser, "query.anim_time"), 1.0e-9);
        assertEquals(3.25d, read(parser, "query.life_time"), 1.0e-9);
        assertEquals(17.0d, read(parser, "query.health"), 1.0e-9);
    }

    @Test
    void nullParserIsTolerated() {
        AnimationRegister.setPreviewParserValues(null);
    }
}
