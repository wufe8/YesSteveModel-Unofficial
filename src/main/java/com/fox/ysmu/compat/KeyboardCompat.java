package com.fox.ysmu.compat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fox.ysmu.Config;
import com.fox.ysmu.ysmu;

/**
 * GLFW 键码 → LWJGL2 键码的转换层（1.7.10）。
 * <p>
 * wiki（molang 参考表）写得很明确：{@code ysm.keyboard} / {@code ysm.mouse} 的键码是
 * **GLFW** 的（链接到 {@code glfw.org/docs/latest/group__keys.html}），而且
 * {@code ysm.keyboard} 支持 1 至多个参数、"只要有一个按键按下就返回 true"。
 * 1.7.10 用的是 LWJGL2，两套键码完全不同，而且 LWJGL2 的按键表**只有 256 项**：
 * 模型常见的 {@code ysm.keyboard(265)}（GLFW 上箭头）在 LWJGL2 里越界，
 * {@code Keyboard.isKeyDown(265)} 会抛 {@code ArrayIndexOutOfBoundsException} —— 以前外层
 * 那个 {@code catch} 把它当成"没按下"，于是按键驱动的模型（小游戏、车辆鸣笛）全都收不到输入。
 * <p>
 * 表按**键名**构建（{@code Keyboard.getKeyIndex("UP") == 200}），比手写数字不容易错；
 * 名字查表是纯静态的，没有显示环境也能用（单测里验证过）。
 */
public final class KeyboardCompat {

    /** 无法映射的键码。 */
    public static final int UNMAPPED = -1;

    /** GLFW 键码上限（glfw3.h 的 {@code GLFW_KEY_LAST} = 348）。 */
    private static final int GLFW_KEY_LAST = 348;

    private static final int[] GLFW_TO_LWJGL = buildKeyTable();

    /** 已经打过"状态翻转"日志的键码（避免每帧刷屏）。 */
    private static final Map<Integer, Boolean> LAST_KEY_STATE = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> LAST_BUTTON_STATE = new ConcurrentHashMap<>();
    private static final Set<Integer> WARNED_KEY = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> WARNED_BUTTON = ConcurrentHashMap.newKeySet();

    private KeyboardCompat() {}

    // ---- 键码转换 ----

    /** GLFW 键码 → LWJGL2 键码；不是已知键码返回 {@link #UNMAPPED}。 */
    public static int toLwjglKeyCode(int glfwKeyCode) {
        if (glfwKeyCode < 0 || glfwKeyCode >= GLFW_TO_LWJGL.length) {
            return UNMAPPED;
        }
        return GLFW_TO_LWJGL[glfwKeyCode];
    }

    private static int[] buildKeyTable() {
        int[] table = new int[GLFW_KEY_LAST + 1];
        Arrays.fill(table, UNMAPPED);
        // LWJGL2 码取自 LWJGL 2.9.4 的 Keyboard 常量表（javap -constants 导出），注释里的名字
        // 就是那张表里的键名。**硬编码**而不是运行时 Keyboard.getKeyIndex(name)：一是不依赖
        // LWJGL 的类加载（专用的测试/工具环境里没有它，曾经因此静默得到一张空表），
        // 二是离线单测能直接钉住这张表。
        put(table, 48, 11);          // 0
        put(table, 49, 2);          // 1
        put(table, 50, 3);          // 2
        put(table, 51, 4);          // 3
        put(table, 52, 5);          // 4
        put(table, 53, 6);          // 5
        put(table, 54, 7);          // 6
        put(table, 55, 8);          // 7
        put(table, 56, 9);          // 8
        put(table, 57, 10);          // 9
        put(table, 65, 30);          // A
        put(table, 66, 48);          // B
        put(table, 67, 46);          // C
        put(table, 68, 32);          // D
        put(table, 69, 18);          // E
        put(table, 70, 33);          // F
        put(table, 71, 34);          // G
        put(table, 72, 35);          // H
        put(table, 73, 23);          // I
        put(table, 74, 36);          // J
        put(table, 75, 37);          // K
        put(table, 76, 38);          // L
        put(table, 77, 50);          // M
        put(table, 78, 49);          // N
        put(table, 79, 24);          // O
        put(table, 80, 25);          // P
        put(table, 81, 16);          // Q
        put(table, 82, 19);          // R
        put(table, 83, 31);          // S
        put(table, 84, 20);          // T
        put(table, 85, 22);          // U
        put(table, 86, 47);          // V
        put(table, 87, 17);          // W
        put(table, 88, 45);          // X
        put(table, 89, 21);          // Y
        put(table, 90, 44);          // Z
        put(table, 32, 57);          // SPACE
        put(table, 39, 40);          // APOSTROPHE
        put(table, 44, 51);          // COMMA
        put(table, 45, 12);          // MINUS
        put(table, 46, 52);          // PERIOD
        put(table, 47, 53);          // SLASH
        put(table, 59, 39);          // SEMICOLON
        put(table, 61, 13);          // EQUALS
        put(table, 91, 26);          // LBRACKET
        put(table, 92, 43);          // BACKSLASH
        put(table, 93, 27);          // RBRACKET
        put(table, 96, 41);          // GRAVE
        put(table, 256, 1);         // ESCAPE
        put(table, 257, 28);         // RETURN
        put(table, 258, 15);         // TAB
        put(table, 259, 14);         // BACK
        put(table, 260, 210);         // INSERT
        put(table, 261, 211);         // DELETE
        put(table, 262, 205);         // RIGHT
        put(table, 263, 203);         // LEFT
        put(table, 264, 208);         // DOWN
        put(table, 265, 200);         // UP
        put(table, 266, 201);         // PRIOR
        put(table, 267, 209);         // NEXT
        put(table, 268, 199);         // HOME
        put(table, 269, 207);         // END
        put(table, 280, 58);         // CAPITAL
        put(table, 281, 70);         // SCROLL
        put(table, 282, 69);         // NUMLOCK
        put(table, 283, 183);         // SYSRQ
        put(table, 284, 197);         // PAUSE
        put(table, 290, 59);         // F1
        put(table, 291, 60);         // F2
        put(table, 292, 61);         // F3
        put(table, 293, 62);         // F4
        put(table, 294, 63);         // F5
        put(table, 295, 64);         // F6
        put(table, 296, 65);         // F7
        put(table, 297, 66);         // F8
        put(table, 298, 67);         // F9
        put(table, 299, 68);         // F10
        put(table, 300, 87);         // F11
        put(table, 301, 88);         // F12
        put(table, 320, 82);         // NUMPAD0
        put(table, 321, 79);         // NUMPAD1
        put(table, 322, 80);         // NUMPAD2
        put(table, 323, 81);         // NUMPAD3
        put(table, 324, 75);         // NUMPAD4
        put(table, 325, 76);         // NUMPAD5
        put(table, 326, 77);         // NUMPAD6
        put(table, 327, 71);         // NUMPAD7
        put(table, 328, 72);         // NUMPAD8
        put(table, 329, 73);         // NUMPAD9
        put(table, 330, 83);         // DECIMAL
        put(table, 331, 181);         // DIVIDE
        put(table, 332, 55);         // MULTIPLY
        put(table, 333, 74);         // SUBTRACT
        put(table, 334, 78);         // ADD
        put(table, 335, 156);         // NUMPADENTER
        put(table, 340, 42);         // LSHIFT
        put(table, 341, 29);         // LCONTROL
        put(table, 342, 56);         // LMENU
        put(table, 343, 219);         // LMETA
        put(table, 344, 54);         // RSHIFT
        put(table, 345, 157);         // RCONTROL
        put(table, 346, 184);         // RMENU
        put(table, 347, 220);         // RMETA
        put(table, 348, 221);         // APPS
        return table;
    }

    private static void put(int[] table, int glfwCode, int lwjglCode) {
        // LWJGL2 的按键数组只有 256 项，越界值一律不进表。
        if (glfwCode >= 0 && glfwCode < table.length && lwjglCode > 0 && lwjglCode < 256) {
            table[glfwCode] = lwjglCode;
        }
    }

    // ---- 查询 ----

    /** {@code ysm.keyboard(键码)} 的单个键语义（键码是 GLFW 的）。 */
    public static boolean isKeyDown(int glfwKeyCode) {
        int lwjglCode = toLwjglKeyCode(glfwKeyCode);
        if (lwjglCode == UNMAPPED) {
            warnUnmapped(WARNED_KEY, glfwKeyCode, "keyboard");
            return false;
        }
        boolean down;
        try {
            down = org.lwjgl.input.Keyboard.isKeyDown(lwjglCode);
        } catch (Throwable t) {
            return false;
        }
        if (Config.DEBUG_CONTROLLER && !Boolean.valueOf(down)
            .equals(LAST_KEY_STATE.put(glfwKeyCode, down))) {
            // 只在按下/松开翻转时打一条：用户按住某个键就能确认"到底认不认得这个键码"。
            ysmu.LOG.info("[YSMU-KEY] ysm.keyboard({}) -> LWJGL {} ({}) = {}", glfwKeyCode, lwjglCode,
                safeKeyName(lwjglCode), down ? 1 : 0);
        }
        return down;
    }

    /** wiki：{@code ysm.keyboard(a, b, ...)} 只要有一个按下就为真。 */
    public static boolean isAnyKeyDown(List<Double> glfwKeyCodes) {
        if (glfwKeyCodes == null) {
            return false;
        }
        for (Double code : glfwKeyCodes) {
            if (code != null && isKeyDown((int) (double) code)) {
                return true;
            }
        }
        return false;
    }

    /** {@code ysm.mouse(键码)}：GLFW 鼠标键码 0=左 1=右 2=中，与 LWJGL2 相同。 */
    public static boolean isMouseButtonDown(int glfwButton) {
        if (glfwButton < 0 || glfwButton > 7) {
            warnUnmapped(WARNED_BUTTON, glfwButton, "mouse");
            return false;
        }
        boolean down;
        try {
            down = org.lwjgl.input.Mouse.isButtonDown(glfwButton);
        } catch (Throwable t) {
            return false;
        }
        if (Config.DEBUG_CONTROLLER && !Boolean.valueOf(down)
            .equals(LAST_BUTTON_STATE.put(glfwButton, down))) {
            ysmu.LOG.info("[YSMU-KEY] ysm.mouse({}) = {}", glfwButton, down ? 1 : 0);
        }
        return down;
    }

    /** wiki：与 {@code ysm.keyboard} 一样支持多个参数，任一按下即真。 */
    public static boolean isAnyMouseButtonDown(List<Double> glfwButtons) {
        if (glfwButtons == null) {
            return false;
        }
        for (Double button : glfwButtons) {
            if (button != null && isMouseButtonDown((int) (double) button)) {
                return true;
            }
        }
        return false;
    }

    private static void warnUnmapped(Set<Integer> warned, int code, String which) {
        if (Config.DEBUG_CONTROLLER && warned.add(code)) {
            ysmu.LOG.warn("[YSMU-KEY] ysm.{}: no 1.7.10 (LWJGL2) mapping for code {} — expected a GLFW code", which,
                code);
        }
    }

    private static String safeKeyName(int lwjglCode) {
        try {
            String name = org.lwjgl.input.Keyboard.getKeyName(lwjglCode);
            return name == null ? "?" : name;
        } catch (Throwable t) {
            return "?";
        }
    }
}
