package com.fox.ysmu.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 模型卡片上的名字排版。
 *
 * <p>背景：模型名带空格时，1.7.10 的 {@code listFormattedStringToWidth} 会把它切成更多行，
 * 而卡片底部只有两行的位置 —— 旧代码只画前两行，剩下的**直接丢掉**（"名字显示不全"）；
 * 中文名（一整段没有空格）还会整段留在一行里、渲染时溢出卡片。
 */
class GuiTextLayoutTest {

    /** 每个字符固定 6 像素（与 MC 默认字体接近）。 */
    private static final java.util.function.ToIntFunction<String> FIXED_WIDTH = s -> s.length() * 6;

    @Test
    void shortNameStaysOnOneLine() {
        List<String> lines = GuiTextLayout.wrap(FIXED_WIDTH, "Steve", 45, 2);
        assertEquals(java.util.Arrays.asList("Steve"), lines);
    }

    @Test
    void nameWithSpacesWrapsOnTheSpace() {
        List<String> lines = GuiTextLayout.wrap(FIXED_WIDTH, "aa bb cc", 45, 2);
        assertEquals(java.util.Arrays.asList("aa bb", "cc"), lines);
    }

    @Test
    void overlongWordIsBrokenByCharacter() {
        // 一整段没有空格的 CJK 名：不能整段留在一行里（会溢出卡片）。
        List<String> lines = GuiTextLayout.wrap(FIXED_WIDTH, "一二三四五六七八九十", 45, 2);
        assertEquals(2, lines.size());
        for (String line : lines) {
            assertTrue(FIXED_WIDTH.applyAsInt(line) <= 45, "line too wide: " + line);
        }
        // 每字 6px、行宽 45px → 一行最多 7 个字（42px），第 8 个字（48px）必须换行。
        assertEquals("一二三四五六七", lines.get(0));
        assertEquals("八九十", lines.get(1));
    }

    @Test
    void truncatedNameGetsAnEllipsisInsteadOfLosingText() {
        List<String> lines = GuiTextLayout.wrap(FIXED_WIDTH, "aa bb cc dd ee", 45, 2);
        assertEquals(2, lines.size());
        assertEquals("aa bb", lines.get(0));
        assertTrue(lines.get(1).endsWith(GuiTextLayout.ELLIPSIS), lines.get(1));
        assertTrue(FIXED_WIDTH.applyAsInt(lines.get(1)) <= 45, "ellipsis line too wide: " + lines.get(1));
    }

    @Test
    void ellipsisNeverOverflowsTheLine() {
        // 名字刚好占满两行还要加标记时，末行要收缩到塞得下为止。
        List<String> lines = GuiTextLayout.wrap(FIXED_WIDTH, "aaaa bbbb cccc", 45, 1);
        assertEquals(1, lines.size());
        assertTrue(FIXED_WIDTH.applyAsInt(lines.get(0)) <= 45, lines.get(0));
        assertTrue(lines.get(0).endsWith(GuiTextLayout.ELLIPSIS), lines.get(0));
    }

    /** Formatting controls have no width; bold adds one pixel to each visible code point. */
    private static int formattedWidth(String text) {
        int width = 0;
        boolean bold = false;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint == '\u00a7' && i < text.length()) {
                char code = Character.toLowerCase(text.charAt(i++));
                if (code == 'l') {
                    bold = true;
                } else if (code == 'r' || "0123456789abcdef".indexOf(code) >= 0) {
                    bold = false;
                }
            } else {
                width += bold ? 7 : 6;
            }
        }
        return width;
    }

    private static void assertFormattedLines(int maxWidth, List<String> lines, String... expected) {
        assertEquals(java.util.Arrays.asList(expected), lines);
        for (String line : lines) {
            assertTrue(formattedWidth(line) <= maxWidth, "line too wide: " + line);
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\u00a7') {
                    assertTrue(++i < line.length(), "split format pair: " + line);
                } else if (Character.isHighSurrogate(c)) {
                    assertTrue(++i < line.length() && Character.isLowSurrogate(line.charAt(i)), line);
                } else {
                    assertFalse(Character.isLowSurrogate(c), line);
                }
            }
        }
    }

    @Test
    void boldCarriesAcrossWordsAndMakesTheNextWordRequireAHardSplit() {
        assertFormattedLines(45, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            "\u00a7laa bbbbbbb", 45, 3), "\u00a7laa", "\u00a7lbbbbbb", "\u00a7lb");
    }

    @Test
    void colorAndEveryStyleCarryAcrossWordWraps() {
        String format = "\u00a7a\u00a7k\u00a7l\u00a7m\u00a7n\u00a7o";
        assertFormattedLines(28, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            format + "ab cd ef", 28, 3), format + "ab", format + "cd", format + "ef");
    }

    @Test
    void resetClearsInheritedColorAndStylesOnFollowingLines() {
        assertFormattedLines(28, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            "\u00a7a\u00a7laa \u00a7rbb cc", 28, 3),
            "\u00a7a\u00a7laa", "\u00a7a\u00a7l\u00a7rbb", "cc");
    }

    @Test
    void colorChangeClearsBoldAndCarriesOnlyTheNewColor() {
        assertFormattedLines(28, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            "\u00a7laa \u00a7cbb cc", 28, 3), "\u00a7laa", "\u00a7l\u00a7cbb", "\u00a7ccc");
    }

    @Test
    void hardSplitsPreservePairsAndApplyMidWordFormatChanges() {
        assertFormattedLines(28, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            "\u00a7lab\u00a7rcdef\u00a7lghij", 28, 3), "\u00a7lab\u00a7rcd", "ef\u00a7lgh", "\u00a7lij");
    }

    @Test
    void upperCaseFormattingAlsoCarriesAcrossHardSplits() {
        assertFormattedLines(14, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            "\u00a7A\u00a7Labcd", 14, 2), "\u00a7A\u00a7Lab", "\u00a7a\u00a7lcd");
    }

    @Test
    void boldEllipsisIsMeasuredInRetainedFormatting() {
        assertFormattedLines(35, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            "\u00a7labcdefgh", 35, 1), "\u00a7lab...");
    }

    @Test
    void ellipsisKeepsACompleteResetPairAndUsesItsNormalWidth() {
        assertFormattedLines(40, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            "\u00a7lab\u00a7rcdefghij", 40, 1), "\u00a7lab\u00a7rc...");
    }

    @Test
    void hardSplitsAndEllipsisNeverDivideUnicodeCodePoints() {
        String face = "\ud83d\ude00";
        assertFormattedLines(12, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            face + face + face, 12, 2), face + face, face);
        assertFormattedLines(24, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            face + face + face + face + face, 24, 1), face + "...");
    }

    @Test
    void markerIsShortenedWhenEvenThreeDotsCannotFit() {
        assertFormattedLines(8, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            "\u00a7labc", 8, 1), ".");
        assertFormattedLines(1, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            "abc", 1, 1), "");
    }

    @Test
    void hardSplitDoesNotIntroduceSpacesInsideWords() {
        assertFormattedLines(24, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            "abcdef gh", 24, 3), "abcd", "ef", "gh");
    }

    @Test
    void explicitLineBreaksStaySeparateEvenWhenBothNamesFitTogether() {
        assertEquals(java.util.Arrays.asList("A", "B"), GuiTextLayout.wrap(FIXED_WIDTH, "A\nB", 45, 2));
        assertEquals(java.util.Arrays.asList("A", "B"), GuiTextLayout.wrap(FIXED_WIDTH, "A\r\nB", 45, 2));
        assertEquals(java.util.Arrays.asList("A", "B"), GuiTextLayout.wrap(FIXED_WIDTH, "A\rB", 45, 2));
    }

    @Test
    void explicitEmptyLinesIncludeLeadingConsecutiveAndTrailingBreaks() {
        assertEquals(java.util.Arrays.asList("", "A"), GuiTextLayout.wrap(FIXED_WIDTH, "\nA", 45, 2));
        assertEquals(java.util.Arrays.asList("A", "", "B"), GuiTextLayout.wrap(FIXED_WIDTH, "A\n\nB", 45, 3));
        assertEquals(java.util.Arrays.asList("A", ""), GuiTextLayout.wrap(FIXED_WIDTH, "A\n", 45, 2));
        assertEquals(java.util.Arrays.asList("", ""), GuiTextLayout.wrap(FIXED_WIDTH, "\n", 45, 2));
    }

    @Test
    void hardBreaksBeyondTheBudgetEllipsizeTheLastAvailableLine() {
        assertEquals(java.util.Arrays.asList("A", "B..."), GuiTextLayout.wrap(FIXED_WIDTH, "A\nB\nC", 45, 2));
        assertEquals(java.util.Arrays.asList("A", "..."), GuiTextLayout.wrap(FIXED_WIDTH, "A\n\nB", 45, 2));
        assertEquals(java.util.Arrays.asList("", "..."), GuiTextLayout.wrap(FIXED_WIDTH, "\n\n", 45, 2));
        assertEquals(java.util.Arrays.asList("A..."), GuiTextLayout.wrap(FIXED_WIDTH, "A\nB", 45, 1));
    }

    @Test
    void softAndHardBreaksShareOneBudget() {
        assertEquals(java.util.Arrays.asList("aaaa", "bbbb", "cc"),
            GuiTextLayout.wrap(FIXED_WIDTH, "aaaa bbbb\ncc", 24, 3));
        assertEquals(java.util.Arrays.asList("aaaa", "b..."),
            GuiTextLayout.wrap(FIXED_WIDTH, "aaaa bbbb\ncc", 24, 2));
    }

    @Test
    void hardBreaksCarryFormattingThroughEmptyLinesAndSoftWraps() {
        String format = "\u00a7a\u00a7l";
        assertFormattedLines(45, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            format + "A\n\nB", 45, 3), format + "A", format, format + "B");
        assertFormattedLines(14, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            format + "A\nBCDE", 14, 3), format + "A", format + "BC", format + "DE");
        assertFormattedLines(45, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            format + "A\n\u00a7rB\nC", 45, 3), format + "A", format + "\u00a7rB", "C");
    }

    @Test
    void hardBreakTruncationPreservesUnicodeAndInheritedBoldWidth() {
        String face = "\ud83d\ude00";
        assertFormattedLines(35, GuiTextLayout.wrap(GuiTextLayoutTest::formattedWidth,
            "\u00a7l" + face + "\n" + face + face + face + face + face + face, 35, 2),
            "\u00a7l" + face, "\u00a7l" + face + face + "...");
    }

    @Test
    void emptyInputProducesNoLines() {
        assertTrue(GuiTextLayout.wrap(FIXED_WIDTH, "", 45, 2).isEmpty());
        assertTrue(GuiTextLayout.wrap(FIXED_WIDTH, null, 45, 2).isEmpty());
    }
}
