package com.fox.ysmu.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

/**
 * GLFW 键码 → LWJGL2 键码（wiki 的 {@code ysm.keyboard}/{@code ysm.mouse} 用的是 GLFW 键码，
 * 而 1.7.10 是 LWJGL2）。
 * <p>
 * 这张表是"按键没反应"那个 bug 的核心：LWJGL2 的按键表只有 256 项，
 * 模型里常见的 {@code ysm.keyboard(265)}（GLFW 上箭头）在 LWJGL2 里越界。
 * 表按键名构建，这里把结果钉住。
 */
class KeyboardCompatTest {

    @Test
    void arrowKeysAndTabFollowTheGlfwToLwjglMapping() {
        // 参考模型库里的真实用法：方向键 262..265、Tab 258
        assertEquals(205, KeyboardCompat.toLwjglKeyCode(262), "GLFW RIGHT -> LWJGL RIGHT");
        assertEquals(203, KeyboardCompat.toLwjglKeyCode(263), "GLFW LEFT -> LWJGL LEFT");
        assertEquals(208, KeyboardCompat.toLwjglKeyCode(264), "GLFW DOWN -> LWJGL DOWN");
        assertEquals(200, KeyboardCompat.toLwjglKeyCode(265), "GLFW UP -> LWJGL UP");
        assertEquals(15, KeyboardCompat.toLwjglKeyCode(258), "GLFW TAB -> LWJGL TAB");
    }

    @Test
    void lettersAndDigitsUseTheirAsciiGlfwCodes() {
        assertEquals(30, KeyboardCompat.toLwjglKeyCode('A'));
        assertEquals(18, KeyboardCompat.toLwjglKeyCode('E'));
        assertEquals(44, KeyboardCompat.toLwjglKeyCode('Z'));
        assertEquals(11, KeyboardCompat.toLwjglKeyCode('0'));
        assertEquals(10, KeyboardCompat.toLwjglKeyCode('9'));
    }

    @Test
    void controlAndNumpadKeysAreMapped() {
        assertEquals(1, KeyboardCompat.toLwjglKeyCode(256), "ESCAPE");
        assertEquals(28, KeyboardCompat.toLwjglKeyCode(257), "ENTER");
        assertEquals(14, KeyboardCompat.toLwjglKeyCode(259), "BACKSPACE");
        assertEquals(199, KeyboardCompat.toLwjglKeyCode(268), "HOME");
        assertEquals(59, KeyboardCompat.toLwjglKeyCode(290), "F1");
        assertEquals(88, KeyboardCompat.toLwjglKeyCode(301), "F12");
        assertEquals(82, KeyboardCompat.toLwjglKeyCode(320), "KP_0");
        assertEquals(73, KeyboardCompat.toLwjglKeyCode(329), "KP_9");
        assertEquals(42, KeyboardCompat.toLwjglKeyCode(340), "LEFT SHIFT");
        assertEquals(219, KeyboardCompat.toLwjglKeyCode(343), "LEFT SUPER");
    }

    /** LWJGL2 只能收 0..255 的键码：表里绝不允许出现越界值。 */
    @Test
    void everyMappedCodeIsWithinTheLwjglTable() {
        int mapped = 0;
        for (int glfw = 0; glfw <= 348; glfw++) {
            int lwjgl = KeyboardCompat.toLwjglKeyCode(glfw);
            if (lwjgl == KeyboardCompat.UNMAPPED) {
                continue;
            }
            mapped++;
            assertTrue(lwjgl > 0 && lwjgl < 256, "code " + glfw + " -> " + lwjgl + " is out of LWJGL range");
        }
        assertTrue(mapped > 60, "the table should cover the keys models use, mapped=" + mapped);
    }

    @Test
    void unknownCodesAreUnmappedAndNeverPressed() {
        assertEquals(KeyboardCompat.UNMAPPED, KeyboardCompat.toLwjglKeyCode(-1));
        assertEquals(KeyboardCompat.UNMAPPED, KeyboardCompat.toLwjglKeyCode(9999));
        // GLFW 里没有的键码（例如 97）不该被猜成 LWJGL 的某个键
        assertEquals(KeyboardCompat.UNMAPPED, KeyboardCompat.toLwjglKeyCode(97));

        // 无显示环境下所有查询都必须是 false，而不是抛异常
        assertFalse(KeyboardCompat.isKeyDown(265));
        assertFalse(KeyboardCompat.isKeyDown(9999));
        assertFalse(KeyboardCompat.isMouseButtonDown(0));
        assertFalse(KeyboardCompat.isMouseButtonDown(42));
    }

    /** 多参数语义：任一按下即真；空参数表即假（wiki）。 */
    @Test
    void anyOfSeveralCodesIsTrueWhenOneIsPressed() {
        assertFalse(KeyboardCompat.isAnyKeyDown(null));
        assertFalse(KeyboardCompat.isAnyKeyDown(Collections.<Double>emptyList()));
        assertFalse(KeyboardCompat.isAnyKeyDown(Arrays.asList(265.0d, 264.0d)));
        assertFalse(KeyboardCompat.isAnyMouseButtonDown(null));
        assertFalse(KeyboardCompat.isAnyMouseButtonDown(Collections.<Double>emptyList()));
        assertFalse(KeyboardCompat.isAnyMouseButtonDown(Arrays.asList(0.0d, 1.0d)));
    }
}
