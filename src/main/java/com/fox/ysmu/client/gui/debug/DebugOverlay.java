package com.fox.ysmu.client.gui.debug;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.input.Keyboard;

import com.fox.ysmu.client.debug.MolangDebugSnapshot;

/**
 * Molang 变量 Debug Overlay。
 * 通过 Ctrl+P 或 /ysm debug overlay [on|off|toggle] 切换。
 * 渲染在 RenderGameOverlayEvent.Post 上，为半透明全屏表格。
 *
 * 功能：
 * - 显示所有 query.* / ysm.* / v.roaming.* 变量的实时值
 * - 过滤：直接打字实时筛选
 * - 滚动：↑↓ / PageUp / PageDown / Home / End
 * - Esc 关闭
 */
public final class DebugOverlay {

    private static boolean active = false;

    // ---- 渲染状态 ----
    private static String filterText = "";
    private static int scrollOffset = 0;
    /** 缓存的已过滤变量列表，每帧重建 */
    private static List<Map.Entry<String, Double>> displayEntries = new ArrayList<>();
    /** 当前帧的变量快照 */
    private static Map<String, Double> currentSnapshot = new LinkedHashMap<>();

    // ---- 布局常量 ----
    private static final int MARGIN = 10;
    private static final int HEADER_HEIGHT = 22;
    private static final int COL_NAME_X = 20;
    private static final int COL_VALUE_X = 250;
    private static final int ROW_HEIGHT = 10;
    private static final int VISIBLE_ROWS = 30;
    private static final int BG_COLOR = 0xCC1A1A2E;
    private static final int HEADER_BG = 0xCC333355;
    private static final int ROW_ODD = 0x22222244;
    private static final int ROW_EVEN = 0x33333344;
    private static final int COLOR_TITLE = 0xFFAA88FF;
    private static final int COLOR_NAME = 0xFFFFFFAA;
    private static final int COLOR_VALUE_TRUE = 0xFF55FF55;
    private static final int COLOR_VALUE_FALSE = 0xFF888888;
    private static final int COLOR_VALUE_NUM = 0xFF55FFFF;
    private static final int COLOR_FILTER = 0xFFFFFFFF;
    private static final int COLOR_HELP = 0xFF888888;

    private DebugOverlay() {}

    /** 切换 overlay 显隐 */
    public static void toggle() {
        active = !active;
        if (active) {
            // 重置状态
            filterText = "";
            scrollOffset = 0;
            refreshSnapshot();
        }
    }

    public static boolean isActive() {
        return active;
    }

    /** 设置过滤文本 */
    public static void setFilter(String text) {
        filterText = text;
        scrollOffset = 0;
        refreshSnapshot();
    }

    /** 追加过滤字符 */
    public static void appendFilter(char c) {
        filterText += c;
        scrollOffset = 0;
        refreshSnapshot();
    }

    /** 删除过滤最后一个字符 */
    public static void backspaceFilter() {
        if (filterText.length() > 0) {
            filterText = filterText.substring(0, filterText.length() - 1);
            scrollOffset = 0;
            refreshSnapshot();
        }
    }

    // ---- 键盘处理 ----

    /**
     * 处理键盘输入。当 overlay 激活时，由 DebugOverlayKey 或 ClientEventHandler 调用。
     * @return true 如果按键被消费
     */
    public static boolean handleKeyInput() {
        if (!active) return false;
        if (!Keyboard.getEventKeyState()) return false; // 只处理按下

        int key = Keyboard.getEventKey();
        char c = Keyboard.getEventCharacter();

        if (key == Keyboard.KEY_ESCAPE) {
            toggle();
            return true;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            return true;
        }
        if (key == Keyboard.KEY_UP) {
            if (scrollOffset > 0) scrollOffset--;
            return true;
        }
        if (key == Keyboard.KEY_DOWN) {
            if (scrollOffset < displayEntries.size() - 1) scrollOffset++;
            return true;
        }
        if (key == Keyboard.KEY_PRIOR) { // PageUp
            scrollOffset = Math.max(0, scrollOffset - VISIBLE_ROWS);
            return true;
        }
        if (key == Keyboard.KEY_NEXT) { // PageDown
            scrollOffset = Math.min(displayEntries.size() - 1, scrollOffset + VISIBLE_ROWS);
            return true;
        }
        if (key == Keyboard.KEY_HOME) {
            scrollOffset = 0;
            return true;
        }
        if (key == Keyboard.KEY_END) {
            scrollOffset = Math.max(0, displayEntries.size() - 1);
            return true;
        }
        if (key == Keyboard.KEY_BACK) {
            backspaceFilter();
            return true;
        }
        // 可打印字符
        if (c >= ' ' && c <= '~') {
            appendFilter(c);
            return true;
        }
        return false;
    }

    // ---- 渲染 ----

    /**
     * 主渲染入口，由 ClientEventHandler.onRenderOverlay 调用。
     */
    public static void render(ScaledResolution res) {
        if (!active) return;

        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRenderer;
        if (font == null) return;

        int w = res.getScaledWidth();
        int h = res.getScaledHeight();

        // 刷新数据
        refreshSnapshot();
        rebuildDisplayList();

        // 1. 背景
        Gui.drawRect(0, 0, w, h, BG_COLOR);

        // 2. 标题栏
        Gui.drawRect(0, 0, w, HEADER_HEIGHT, HEADER_BG);
        String title = String.format("\u00a7l[Molang Debug]  %d vars",
            displayEntries.size());
        font.drawStringWithShadow(title, MARGIN, 6, COLOR_TITLE);

        // 过滤框
        String filterDisplay = filterText.isEmpty()
            ? "\u00a77[type to filter...]"
            : "\u00a7f" + filterText + "\u00a77_";
        font.drawStringWithShadow(filterDisplay, 200, 6, COLOR_FILTER);

        // 3. 表头
        int headerY = HEADER_HEIGHT;
        Gui.drawRect(0, headerY, w, headerY + ROW_HEIGHT + 2, 0xCC444466);
        font.drawStringWithShadow("\u00a77Name", COL_NAME_X, headerY + 1, 0xFFAAAAAA);
        font.drawStringWithShadow("\u00a77Value", COL_VALUE_X, headerY + 1, 0xFFAAAAAA);

        // 4. 数据行
        int startY = headerY + ROW_HEIGHT + 4;
        int maxRows = Math.min(VISIBLE_ROWS, displayEntries.size() - scrollOffset);
        for (int i = 0; i < maxRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= displayEntries.size()) break;

            Map.Entry<String, Double> entry = displayEntries.get(idx);
            int y = startY + i * ROW_HEIGHT;

            // 行背景（交替色）
            Gui.drawRect(0, y, w, y + ROW_HEIGHT, (idx % 2 == 0) ? ROW_ODD : ROW_EVEN);

            // 列：变量名
            String name = entry.getKey();
            // 高亮过滤匹配部分
            if (!filterText.isEmpty()) {
                int matchIdx = name.toLowerCase(java.util.Locale.ROOT)
                    .indexOf(filterText.toLowerCase(java.util.Locale.ROOT));
                if (matchIdx >= 0) {
                    String before = name.substring(0, matchIdx);
                    String match = name.substring(matchIdx, matchIdx + filterText.length());
                    String after = name.substring(matchIdx + filterText.length());
                    font.drawStringWithShadow(before, COL_NAME_X, y, COLOR_NAME);
                    font.drawStringWithShadow(match,
                        COL_NAME_X + font.getStringWidth(before), y, 0xFFFFFFFF);
                    font.drawStringWithShadow(after,
                        COL_NAME_X + font.getStringWidth(before + match), y, COLOR_NAME);
                } else {
                    font.drawStringWithShadow(name, COL_NAME_X, y, COLOR_NAME);
                }
            } else {
                font.drawStringWithShadow(name, COL_NAME_X, y, COLOR_NAME);
            }

            // 列：值
            double value = entry.getValue();
            font.drawStringWithShadow(formatValue(value), COL_VALUE_X, y, getValueColor(value));

            // 列：简短类型提示
            font.drawStringWithShadow(getTypeHint(name), w - 40, y, 0xFF666666);
        }

        // 5. 底部帮助
        String help = String.format(
            "\u00a77[\u00a7f\u2191\u2192\u00a77/\u00a7fPgUp/PgDn\u00a77 scroll]  "
            + "[\u00a77type\u00a77 filter]  [\u00a77Esc\u00a77 close]  "
            + "(\u00a77%d\u00a77 filtered, \u00a77%d\u00a77 total)",
            displayEntries.size(), currentSnapshot.size());
        font.drawStringWithShadow(help, MARGIN, h - font.FONT_HEIGHT - 4, COLOR_HELP);
    }

    // ---- 内部 ----

    private static void refreshSnapshot() {
        currentSnapshot = MolangDebugSnapshot.getAllVariables();
    }

    private static void rebuildDisplayList() {
        displayEntries = new ArrayList<>();
        String lowerFilter = filterText.toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<String, Double> entry : currentSnapshot.entrySet()) {
            if (filterText.isEmpty()
                || entry.getKey().toLowerCase(java.util.Locale.ROOT).contains(lowerFilter)) {
                displayEntries.add(entry);
            }
        }
        // 限制滚动范围
        if (scrollOffset >= displayEntries.size()) {
            scrollOffset = Math.max(0, displayEntries.size() - 1);
        }
    }

    private static String formatValue(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return (value > 0 ? "+Inf" : "-Inf");
        if (Math.abs(value) < 0.000001) return "0.0 (false)";
        if (Math.abs(value - 1.0) < 0.000001) return "1.0 (true)";
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return (long) value + ".0";
        }
        return String.format("%.4f", value);
    }

    private static int getValueColor(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0xFFFF5555;
        if (Math.abs(value) < 0.000001) return COLOR_VALUE_FALSE;
        if (Math.abs(value - 1.0) < 0.000001) return COLOR_VALUE_TRUE;
        return COLOR_VALUE_NUM;
    }

    private static String getTypeHint(String name) {
        if (name.startsWith("v.")) return "v";
        if (name.startsWith("ctrl.")) return "ctrl";
        if (name.startsWith("ysm.")) return "ysm";
        if (name.startsWith("query.")) return "q";
        if (name.startsWith("math.")) return "math";
        return "";
    }
}
