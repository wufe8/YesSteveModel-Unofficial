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
    /** true 时键盘输入会进入过滤框；false 时仅响应方向键/Esc */
    private static boolean searchMode = false;
    /** 每会话一次，通过命令开启时提示快捷键 */
    private static boolean overlayHintShown = false;

    // ---- 渲染状态 ----
    private static String filterText = "";
    private static int scrollOffset = 0;
    /** 缓存的已过滤变量列表，每帧重建 */
    private static List<Map.Entry<String, Double>> displayEntries = new ArrayList<>();
    /** 当前帧的变量快照 */
    private static Map<String, Double> currentSnapshot = new LinkedHashMap<>();

    // ---- 布局常量 ----
    private static final int MARGIN = 10;
    /** 标题变量数量的最大位数：用于计算 [search] 过滤框的固定位置 */
    private static final int TITLE_COUNT_DIGITS = 5;
    private static final int HEADER_HEIGHT = 22;
    private static final int HELP_HEIGHT = 10;
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
            filterText = "";
            searchMode = false;
            scrollOffset = 0;
            refreshSnapshot();
        }
    }

    /** 从命令开启时，会话内首次提示快捷键 */
    public static void tryShowToggleHint() {
        if (!overlayHintShown) {
            overlayHintShown = true;
            String hint = MolangDebugSnapshot.CHAT_PREFIX
                + " \u00a77Toggle with \u00a7eCtrl+P key\u00a77 in-game.";
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(hint));
            }
        }
    }

    public static boolean isActive() {
        return active;
    }

    /** 是否处于搜索模式（输入焦点在过滤框，需拦截其他快捷键） */
    public static boolean isSearching() {
        return searchMode;
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
            if (searchMode) {
                searchMode = false;
            } else {
                toggle();
            }
            return true;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            if (searchMode) {
                searchMode = false; // 回车确认搜索
            } else {
                // 回车进入搜索模式（保留已有过滤内容，不清空；清空用 Esc）
                searchMode = true;
                scrollOffset = 0;
                refreshSnapshot();
            }
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
        if (key == Keyboard.KEY_LEFT || key == Keyboard.KEY_PRIOR) { // ← / PageUp
            scrollOffset = Math.max(0, scrollOffset - VISIBLE_ROWS);
            return true;
        }
        if (key == Keyboard.KEY_RIGHT || key == Keyboard.KEY_NEXT) { // → / PageDown
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
        // 搜索模式下：Backspace + 可打印字符
        if (searchMode) {
            if (key == Keyboard.KEY_BACK) {
                backspaceFilter();
                return true;
            }
            if (c >= ' ' && c <= '~') {
                appendFilter(c);
                return true;
            }
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
        // [search] 过滤框固定放在"最大位数参考标题"之后的固定位置，
        // 不随实际变量数量位数变化而跳动。不用空格补齐数字：
        // Minecraft 字体里空格与数字宽度不同，补空格仍会造成错位。
        int varCount = displayEntries.size();
        String countStr = Integer.toString(varCount);
        String title = "\u00a7l[Molang Debug]  " + countStr + " vars";
        font.drawStringWithShadow(title, MARGIN, 6, COLOR_TITLE);
        // 固定位置：以最大位数参考标题计算；若实际位数超过预留位数则退回实际宽度
        String refTitle = countStr.length() > TITLE_COUNT_DIGITS
            ? title
            : "\u00a7l[Molang Debug]  " + repeatChar('9', TITLE_COUNT_DIGITS) + " vars";
        int filterX = MARGIN + font.getStringWidth(refTitle) + 10;

        // 过滤框（紧随标题之后，随字体宽度自适应）
        String filterDisplay;
        if (searchMode) {
            filterDisplay = filterText.isEmpty()
                ? "\u00a7a[search] \u00a77type..."
                : "\u00a7a[search] \u00a7f" + filterText + "\u00a77_";
        } else {
            filterDisplay = filterText.isEmpty()
                ? "\u00a7e[Enter] \u00a77search"
                : "\u00a7e[Enter] \u00a7f" + filterText;
        }

        // 帮助信息（标题栏最右侧，右对齐）。
        // 去掉末尾 (已显示/总数) 括号：左上角标题已用紫色显示变量数量，避免重复。
        String helpBar = "\u00a77[\u00a7f\u2191\u2193\u00a77 scroll]  "
            + "[\u00a7f\u2190\u2192\u00a77 page]  "
            + "\u00a77[\u00a7fEsc\u00a77 close]";
        int helpX = w - font.getStringWidth(helpBar) - MARGIN;

        // 宽度自适应：有搜索内容时过滤框始终显示（搜索中或退出搜索后都显示）；
        // 无内容时 "[Enter] search" 提示可在空间不足时省略；
        // 帮助说明只在不与左侧内容重叠时显示。
        boolean hasFilter = !filterText.isEmpty();
        boolean roomForBoth = filterX + font.getStringWidth(filterDisplay) < helpX - 8;
        if (hasFilter) {
            font.drawStringWithShadow(filterDisplay, filterX, 6, COLOR_FILTER);
            if (roomForBoth) {
                font.drawStringWithShadow(helpBar, helpX, 6, COLOR_HELP);
            }
        } else {
            if (roomForBoth) {
                font.drawStringWithShadow(filterDisplay, filterX, 6, COLOR_FILTER);
            }
            if (helpX > MARGIN + font.getStringWidth(title) + 6) {
                font.drawStringWithShadow(helpBar, helpX, 6, COLOR_HELP);
            }
        }

        // 3. 表头
        int headerY = HEADER_HEIGHT + HELP_HEIGHT;
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
            // 高亮过滤匹配部分（q. 别名展开为 query. 后再匹配）
            if (!filterText.isEmpty()) {
                String lowerMatch = MolangDebugSnapshot.expandSearchAlias(
                    filterText.toLowerCase(java.util.Locale.ROOT));
                int matchIdx = name.toLowerCase(java.util.Locale.ROOT).indexOf(lowerMatch);
                if (matchIdx >= 0) {
                    String before = name.substring(0, matchIdx);
                    String match = name.substring(matchIdx, matchIdx + lowerMatch.length());
                    String after = name.substring(matchIdx + lowerMatch.length());
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

            // 列：变量来源（@模型）或简短类型提示。
            // 来源列优先：重名 v.*（如 v.roaming.a）可看到当前值由哪个模型
            // 注入/写入（残留来源 = 跨模型串变量的元凶）；无来源记录的
            // 非 v.* 保持原类型提示。
            String src = MolangDebugSnapshot.getVariableSource(name);
            if (src != null && !src.isEmpty()) {
                String srcText = "@" + src;
                if (srcText.length() > 20) {
                    srcText = srcText.substring(0, 20) + "\u2026";
                }
                font.drawStringWithShadow(srcText,
                    w - font.getStringWidth(srcText) - 6, y, 0xFF88AAFF);
            } else {
                font.drawStringWithShadow(getTypeHint(name), w - 50, y, 0xFF666666);
            }
        }

        // 5. 底部提示（搜索模式时显示操作提示）
        if (searchMode) {
            font.drawStringWithShadow(
                "\u00a7a[search mode] \u00a77Enter=confirm  Esc=cancel",
                MARGIN, h - font.FONT_HEIGHT - 4, COLOR_HELP);
        }
    }

    // ---- 内部 ----

    /** 生成 n 个重复字符（JVM8 无 String.repeat） */
    private static String repeatChar(char c, int n) {
        if (n <= 0) return "";
        char[] chars = new char[n];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    private static void refreshSnapshot() {
        currentSnapshot = MolangDebugSnapshot.getAllVariables();
    }

    private static void rebuildDisplayList() {
        displayEntries = new ArrayList<>();
        String lowerFilter = filterText.toLowerCase(java.util.Locale.ROOT);
        // 语法糖：q. → query.，过滤框可直接输 q.xxx 匹配 query.xxx
        String matchFilter = MolangDebugSnapshot.expandSearchAlias(lowerFilter);
        for (Map.Entry<String, Double> entry : currentSnapshot.entrySet()) {
            if (matchFilter.isEmpty()
                || entry.getKey().toLowerCase(java.util.Locale.ROOT).contains(matchFilter)) {
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
        if (name.startsWith("v.")) return "variable";
        if (name.startsWith("ctrl.")) return "ctrl";
        if (name.startsWith("ysm.")) return "ysm";
        if (name.startsWith("query.")) return "query";
        if (name.startsWith("math.")) return "math";
        return "";
    }
}
