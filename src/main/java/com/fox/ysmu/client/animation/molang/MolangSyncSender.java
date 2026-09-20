package com.fox.ysmu.client.animation.molang;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.C2SMolangSync;
import com.fox.ysmu.ysmu;

/**
 * {@code ysm.sync(int1, int2...)} 的客户端发起端（YSM-wiki: molang/script「主动同步」）。
 *
 * <p>wiki 的语义：把参数同步给服务器上的其他玩家，让他们以相同参数触发 {@code sync} 事件；
 * 调用后**立刻返回**（异步），不等待事件触发。参数只支持数值、最多 16 个，
 * 而且"开销相当大，不要频繁发起"，所以这里额外做了每秒一次的软限流（超出只记一条提示），
 * 避免模型作者把它写在每帧的脚本里把服务器打爆。</p>
 */
public final class MolangSyncSender {

    /**
     * 每秒最多真的发这么多次（含"状态变化"）。
     * <p>
     * 原来这里是"每秒固定 1 次、超出直接丢"，但 {@code ysm.sync} 在两个用途上语义不同：
     * <ul>
     *   <li><b>重复</b>：模型把 {@code ysm.sync} 写在每帧脚本里，参数一直不变 —— 这种要压掉；</li>
     *   <li><b>状态变化</b>：模型用参数表示一个开关（某车辆模型用 {@code ysm.sync(0,0/1)}
     *       传"鸣笛按下/松开"）—— 这种被压掉就是可见的行为差异：上游没有限流，
     *       松开按键下一个 tick 就停，而 YSMU 会把"松开"压到下一秒，长鸣笛要响约 1 秒。</li>
     * </ul>
     * 所以判定改成按**参数**区分：参数变了立刻放行（只受这个硬上限约束），
     * 参数没变的重复仍按每秒 1 次压掉（且不占用硬上限，模型每帧重试不会把额度吃光）。
     */
    private static final int MAX_PER_SECOND = 4;

    private static int windowSecond = -1;
    private static int windowCount;
    /** 上一次真正发出去的参数：用来识别"同一状态的重复"。 */
    private static int[] lastPayload;
    private static int lastPayloadSecond = Integer.MIN_VALUE;
    private static boolean throttledHinted;

    private MolangSyncSender() {}

    /**
     * 发起一次同步。
     *
     * @param arguments 脚本求值出来的参数（按 wiki 截断到 16 个、取整）
     * @return 恒为 0（wiki：「发起同步后将立刻结束并返回 null」，数值形态就是 0）
     */
    public static double request(List<Double> arguments) {
        if (Minecraft.getMinecraft() == null || Minecraft.getMinecraft().thePlayer == null) {
            return 0.0d;
        }
        ResourceLocation modelId = MolangPhysicsRuntime.getCurrentModelId();
        if (modelId == null) {
            return 0.0d;
        }
        // 先算参数再判限流：判定本身要看参数有没有变化。
        int[] values = toIntArray(arguments);
        if (!allow(System.nanoTime() / 1_000_000_000L, values)) {
            if (!throttledHinted) {
                throttledHinted = true;
                if (Config.DEBUG_ANIMATION) {
                    ysmu.LOG.info(
                        "[YSMU-SYNC] repeated ysm.sync calls are limited to once per second (and {} sends per "
                            + "second in total); further calls are ignored (noted once)",
                        MAX_PER_SECOND);
                }
            }
            return 0.0d;
        }
        NetworkHandler.CHANNEL.sendToServer(new C2SMolangSync(modelId, values));
        if (Config.DEBUG_ANIMATION) {
            ysmu.LOG.info("[YSMU-SYNC] ysm.sync({}) sent for {}", java.util.Arrays.toString(values), modelId);
        }
        return 0.0d;
    }

    /** 参数取整并截断到 {@link C2SMolangSync#MAX_ARGUMENTS} 个（纯函数，便于单测）。 */
    static int[] toIntArray(List<Double> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return new int[0];
        }
        int size = Math.min(arguments.size(), C2SMolangSync.MAX_ARGUMENTS);
        int[] values = new int[size];
        for (int i = 0; i < size; i++) {
            Double value = arguments.get(i);
            values[i] = value == null ? 0 : (int) (double) value;
        }
        return values;
    }

    /**
     * 限流判定（纯逻辑，便于单测）：见 {@link #MAX_PER_SECOND} 的说明。
     * <ul>
     *   <li>参数与上一次发出的一样 → 同一状态的重复 → 同一秒内只放行一次，且**不占**硬上限
     *       （模型每帧重试不会把额度吃光）；</li>
     *   <li>参数变了 → 状态切换 → 立刻放行；</li>
     *   <li>无论哪种，每秒总数不超过 {@link #MAX_PER_SECOND}（防"每帧换一个参数"刷屏）。</li>
     * </ul>
     */
    static synchronized boolean allow(long nowSeconds, int[] values) {
        if (nowSeconds != windowSecond) {
            windowSecond = (int) nowSeconds;
            windowCount = 0;
        }
        boolean samePayload = lastPayload != null && java.util.Arrays.equals(lastPayload, values);
        if (samePayload && nowSeconds == lastPayloadSecond) {
            return false;
        }
        if (windowCount >= MAX_PER_SECOND) {
            return false;
        }
        windowCount++;
        lastPayload = values == null ? null : values.clone();
        lastPayloadSecond = (int) nowSeconds;
        return true;
    }

    static synchronized void resetThrottle() {
        windowSecond = -1;
        windowCount = 0;
        lastPayload = null;
        lastPayloadSecond = Integer.MIN_VALUE;
        throttledHinted = false;
    }
}
