package com.fox.ysmu.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 卡片里放不下的文字怎么排。
 *
 * <p>1.7.10 的 {@code FontRenderer.listFormattedStringToWidth} 只在**空格**处断行：
 * 一个超过行宽的"词"（中文模型名很常见）会整段留在一行里，渲染时溢出卡片；而模型名
 * 带空格时又会被切成更多行，卡片底部的两行放不下时**后面的行直接被丢掉** ——
 * 用户看到的现象就是"带空格的名字显示不全"。
 *
 * <p>这里改成：先按空格断词，过宽的词再按字符切；放不下的部分用 {@code "..."} 收尾，
 * 让"被截断"这件事本身可见（完整名字仍可在 tooltip 里看到）。
 */
public final class GuiTextLayout {

    /** 截断标记。ASCII 点号：默认字体没有全角省略号，且中文 GUI 文本约定用 ASCII 标点。 */
    public static final String ELLIPSIS = "...";

    private GuiTextLayout() {}

    /**
     * 把 {@code text} 排成最多 {@code maxLines} 行，每行不超过 {@code maxWidth} 像素。
     *
     * <p>Formatting pairs and Unicode code points remain intact. An indivisible glyph wider than the
     * limit is kept on its own line; a truncation marker is shortened if even three dots cannot fit.
     *
     * @param widthOf 文本宽度测量函数（通常是 {@code FontRenderer::getStringWidth}）
     * @param text 原文
     * @param maxWidth 单行最大像素宽度
     * @param maxLines 最多几行
     * @return 要绘制的行；放不下时最后一行带 {@link #ELLIPSIS}
     */
    public static List<String> wrap(ToIntFunction<String> widthOf, String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty() || maxWidth <= 0 || maxLines <= 0) {
            return lines;
        }
        String line = "";
        for (String word : text.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            String separator = hasVisibleText(line) ? " " : "";
            if (widthOf.applyAsInt(line + separator + word) <= maxWidth) {
                line += separator + word;
                continue;
            }
            if (hasVisibleText(line)) {
                if (addWrappedLine(widthOf, lines, line, maxWidth, maxLines)) {
                    return lines;
                }
                line = activeFormatting(line);
            }
            // Measure with the inherited state: an otherwise short word may be too wide in bold.
            String rest = word;
            while (widthOf.applyAsInt(line + rest) > maxWidth) {
                int cut = fittingEnd(widthOf, line, rest, maxWidth);
                if (cut == rest.length()) {
                    // A single indivisible glyph can exceed the available width; still make progress.
                    break;
                }
                line += rest.substring(0, cut);
                rest = rest.substring(cut);
                if (addWrappedLine(widthOf, lines, line, maxWidth, maxLines)) {
                    return lines;
                }
                line = activeFormatting(line);
            }
            line += rest;
        }
        if (!line.isEmpty()) {
            lines.add(line);
        }
        return lines;
    }

    /** Called only when more text remains. */
    private static boolean addWrappedLine(
        ToIntFunction<String> widthOf,
        List<String> lines,
        String line,
        int maxWidth,
        int maxLines) {
        lines.add(line);
        if (lines.size() == maxLines) {
            lines.set(lines.size() - 1, ellipsize(widthOf, line, maxWidth));
            return true;
        }
        return false;
    }

    private static int fittingEnd(ToIntFunction<String> widthOf, String prefix, String text, int maxWidth) {
        int cut = 0;
        for (int end = nextTextEnd(text, 0); end <= text.length();) {
            if (widthOf.applyAsInt(prefix + text.substring(0, end)) > maxWidth) {
                return cut == 0 ? end : cut;
            }
            cut = end;
            if (end == text.length()) {
                break;
            }
            end = nextTextEnd(text, end);
        }
        return cut;
    }

    /** Keep formatting pairs with the next code point, never splitting a surrogate pair either. */
    private static int nextTextEnd(String text, int start) {
        int end = start;
        while (end + 1 < text.length() && text.charAt(end) == '\u00a7') {
            end += 1 + Character.charCount(text.codePointAt(end + 1));
        }
        return end < text.length() ? end + Character.charCount(text.codePointAt(end)) : end;
    }

    private static boolean hasVisibleText(String text) {
        for (int i = 0; i < text.length();) {
            if (text.charAt(i) != '\u00a7' || i + 1 == text.length()) {
                return true;
            }
            i += 1 + Character.charCount(text.codePointAt(i + 1));
        }
        return false;
    }

    /** Colors (and reset) clear all styles in Minecraft; styles otherwise accumulate. */
    private static String activeFormatting(String text) {
        String color = "";
        String styles = "";
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) != '\u00a7') {
                continue;
            }
            char code = Character.toLowerCase(text.charAt(++i));
            if ("0123456789abcdef".indexOf(code) >= 0) {
                color = "\u00a7" + code;
                styles = "";
            } else if (code == 'r') {
                color = "";
                styles = "";
            } else if ("klmno".indexOf(code) >= 0 && styles.indexOf(code) < 0) {
                styles += "\u00a7" + code;
            }
        }
        return color + styles;
    }

    /** Fit the marker in the actual retained format state, removing only complete text units. */
    private static String ellipsize(ToIntFunction<String> widthOf, String line, int maxWidth) {
        List<Integer> ends = new ArrayList<>();
        ends.add(0);
        for (int end = 0; end < line.length();) {
            end = nextTextEnd(line, end);
            ends.add(end);
        }
        for (int i = ends.size() - 1; i >= 0; i--) {
            String head = line.substring(0, ends.get(i));
            // A malformed trailing section sign must not consume the first dot as a format code.
            if (head.endsWith("\u00a7")) {
                continue;
            }
            if (widthOf.applyAsInt(head + ELLIPSIS) <= maxWidth) {
                return head + ELLIPSIS;
            }
        }
        String marker = ELLIPSIS;
        while (!marker.isEmpty() && widthOf.applyAsInt(marker) > maxWidth) {
            marker = marker.substring(0, marker.length() - 1);
        }
        return marker;
    }
}
