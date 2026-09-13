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

    /** wiki 说"一次同步开销相当大"：同一秒内最多真的发一次。 */
    private static final int MAX_PER_SECOND = 1;

    private static int windowSecond = -1;
    private static int windowCount;
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
        if (!allow(System.nanoTime() / 1_000_000_000L)) {
            if (!throttledHinted) {
                throttledHinted = true;
                if (Config.DEBUG_ANIMATION) {
                    ysmu.LOG.info("[YSMU-SYNC] ysm.sync 每秒最多一次，后续调用被忽略（只提示一次）");
                }
            }
            return 0.0d;
        }
        int[] values = toIntArray(arguments);
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
}
