package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.AnimationRegister;

import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * {@code ysm.fps} 的取值规则，以及**两条求值路径一致性**。
 *
 * <p>回归的是 R4：控制器表达式路径原先每次求值都
 * {@code Minecraft.class.getDeclaredField("debugFPS")}。字符串字面量不会被 reobf 重映射，而正式包
 * 运行时该字段只有 SRG 名 {@code field_71470_ab}（mcp-srg.srg：{@code FD: .../Minecraft/debugFPS
 * .../Minecraft/field_71470_ab}），于是正式包里每次都抛 {@code NoSuchFieldException} → 返回 60，
 * 并且每帧分配一个异常对象；开发环境是 MCP 名，所以这条路径在开发/测试里"看起来是对的"。</p>
 *
 * <p>本环境没有 LWJGL，无法直接反射 {@code Minecraft} 的字段，所以字段解析逻辑用**夹具类**
 * 验证：夹具里的字段名就是生产环境的两个真名（含 SRG 名）。这比"在开发环境跑通"更贴近正式包 ——
 * 开发环境只有 MCP 名，恰好是漏掉这个 bug 的原因。</p>
 */
class FpsQueryTest {

    /** 夹具：与 Minecraft.debugFPS 同形（private static int）。 */
    @SuppressWarnings("unused")
    private static final class SrgNamedHolder {

        private static int field_71470_ab = 144;
    }

    @SuppressWarnings("unused")
    private static final class McpNamedHolder {

        private static int debugFPS = 90;
    }

    @SuppressWarnings("unused")
    private static final class BothNamesHolder {

        private static int debugFPS = 91;
        private static int field_71470_ab = 92;
    }

    @SuppressWarnings("unused")
    private static final class ZeroHolder {

        private static int field_71470_ab = 0;
    }

    @SuppressWarnings("unused")
    private static final class WrongTypeHolder {

        private static final String field_71470_ab = "nope";
        private static final int debugFPS = 7; // 非 final 才可写；这里只验证类型检查
    }

    @SuppressWarnings("unused")
    private static final class MissingHolder {

        private static int unrelated = 12;
    }

    @Test
    void resolvesTheSrgNameUsedByTheShippedJar() {
        Field field = FpsQuery.findFpsField(SrgNamedHolder.class, FpsQuery.FIELD_NAMES);
        assertNotNull(field, "SRG name field_71470_ab must be resolved");
        assertEquals(144.0d, FpsQuery.readFps(field));
    }

    @Test
    void resolvesTheMcpNameUsedInDevelopment() {
        Field field = FpsQuery.findFpsField(McpNamedHolder.class, FpsQuery.FIELD_NAMES);
        assertNotNull(field);
        assertEquals(90.0d, FpsQuery.readFps(field));
    }

    @Test
    void prefersTheFirstMatchingName() {
        Field field = FpsQuery.findFpsField(BothNamesHolder.class, FpsQuery.FIELD_NAMES);
        assertNotNull(field);
        assertEquals("debugFPS", field.getName());
    }

    @Test
    void nonIntAndMissingFieldsFallBackInsteadOfThrowing() {
        // 类型不符（String）不得被当成帧率字段；完全找不到时返回中性基准。
        assertEquals(FpsQuery.FALLBACK_FPS, FpsQuery.readFps(FpsQuery.findFpsField(WrongTypeHolder.class,
            new String[] { "field_71470_ab" })));
        assertEquals(FpsQuery.FALLBACK_FPS, FpsQuery.readFps(FpsQuery.findFpsField(MissingHolder.class,
            FpsQuery.FIELD_NAMES)));
        assertEquals(FpsQuery.FALLBACK_FPS, FpsQuery.readFps(null));
    }

    @Test
    void zeroFrameRateFallsBackToTheNeutralBaseline() {
        // debugFPS 在第一次满 1 秒刷新之前就是 0；0 会让 60 / ysm.fps 这类帧率补偿表达式
        // 变成 Inf/NaN，所以必须回落到中性值 60。
        Field field = FpsQuery.findFpsField(ZeroHolder.class, FpsQuery.FIELD_NAMES);
        assertNotNull(field);
        assertEquals(FpsQuery.FALLBACK_FPS, FpsQuery.readFps(field));
    }

    @Test
    void publicAccessorNeverThrowsAndNeverReportsZero() {
        // 生产入口：即使本环境无法解析 Minecraft 的字段（缺 LWJGL），也必须给出可用数值。
        double fps = FpsQuery.clientFps();
        assertTrue(fps > 0, "ysm.fps must never be 0 or negative, was " + fps);
        assertEquals(fps, FpsQuery.clientFps(), "cached resolution must stay stable");
    }

    @Test
    void keyframePathIsRegisteredAndAgreesWithTheSharedAccessor() {
        MolangParser.VARIABLES.clear();
        AnimationRegister.registerVariables();
        // 未注册的非 v. 变量会走 newVariable() 默认 0 —— 这正是要防的"静默 0"。
        assertTrue(
            MolangParser.VARIABLES.containsKey("ysm.fps"),
            "ysm.fps must be registered so it cannot silently default to 0");
        double fromKeyframeParser = MolangParser.VARIABLES.get("ysm.fps")
            .get();
        assertEquals(
            FpsQuery.clientFps(),
            fromKeyframeParser,
            "keyframe path must read the same value as the controller path (was: unregistered -> 0)");
        assertTrue(fromKeyframeParser > 0, "keyframe path must not report 0");
    }
}
