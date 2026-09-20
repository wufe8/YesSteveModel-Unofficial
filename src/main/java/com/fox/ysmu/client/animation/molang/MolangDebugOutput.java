package com.fox.ysmu.client.animation.molang;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.gui.debug.DebugOverlay;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code q.debug_output(...)} 与 {@code ysm.dump_*} 共用的调试输出通道。
 *
 * <p>YSM-wiki: molang/ref —— 这些函数"仅在动画调试模式下有效"。YSMU 里对应的开关是
 * {@link DebugOverlay}（Ctrl+P 或 {@code /ysm debug overlay on}）；{@link Config#DEBUG_ANIMATION}
 * 也会同时打开它，这样不开 overlay 时也能从日志侧看到输出。</p>
 *
 * <p><b>必须限流。</b>模型作者会把 {@code q.debug_output} 写在 {@code @player_update} 脚本或
 * 每帧 timeline 里，一条消息就够刷爆聊天框；这里做 20 条/秒的令牌桶，超出部分直接丢弃，
 * 并且只提示一次"被限流"。丢弃而不是排队，是为了不把渲染线程拖慢。</p>
 */
public final class MolangDebugOutput {

    private static final int MAX_PER_SECOND = 20;

    private static int windowSecond = -1;
    private static int windowCount;
    private static boolean throttledHinted;

    private MolangDebugOutput() {}

    /** 动画调试模式是否开启。 */
    public static boolean isEnabled() {
        return Config.DEBUG_ANIMATION || DebugOverlay.isActive();
    }

    /** 每秒最多 {@value #MAX_PER_SECOND} 条的令牌桶；{@code nowSeconds} 必须单调不减。 */
    static synchronized boolean allow(long nowSeconds) {
        if (nowSeconds != windowSecond) {
            windowSecond = (int) nowSeconds;
            windowCount = 0;
        }
        if (windowCount >= MAX_PER_SECOND) {
            return false;
        }
        windowCount++;
        return true;
    }

    static synchronized void resetThrottle() {
        windowSecond = -1;
        windowCount = 0;
        throttledHinted = false;
    }

    /** 输出一行（未开启调试模式时静默丢弃）。 */
    public static void emit(String message) {
        if (!isEnabled() || message == null || message.isEmpty()) {
            return;
        }
        if (!allow(System.nanoTime() / 1_000_000_000L)) {
            if (!throttledHinted) {
                throttledHinted = true;
                if (Config.DEBUG_ANIMATION) {
                    ysmu.LOG.info(
                        "[YSMU-DEBUG-OUT] more than {} lines per second; further output is throttled (noted once)",
                        MAX_PER_SECOND);
                }
            }
            return;
        }
        if (Config.DEBUG_ANIMATION) {
            ysmu.LOG.info("[YSMU-DEBUG-OUT] {}", message);
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }

    /**
     * 单个参数 → 文本。
     *
     * <p>字符串字面量在解析期被池化成 {@link MolangStringPool} 的 id，这里用
     * {@link MolangStringPool#isStringId(int)} 区分：是字符串就还原，否则按数字输出
     * （整数不带 {@code .0}，小数保留 4 位，避免每次输出长度都在抖）。</p>
     */
    public static String formatArg(double value) {
        int asInt = (int) value;
        if (MolangStringPool.isStringId(asInt) && asInt == value) {
            String pooled = MolangStringPool.get(asInt);
            if (pooled != null) {
                return pooled;
            }
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Double.toString(value);
        }
        if (value == Math.rint(value) && Math.abs(value) < 9.0e15d) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
