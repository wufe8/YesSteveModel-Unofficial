package com.fox.ysmu.network.message;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fox.ysmu.network.NetworkHandler;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.Packet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;

/**
 * {@code ysm.sync(int1, int2...)} 的**上行**包（YSM-wiki: molang/script「主动同步」）。
 *
 * <p>客户端发起同步，服务端把它广播给服务器上的所有玩家（含发起者），各客户端用同样的参数
 * 触发该模型订阅 {@code sync} 事件的脚本。wiki 的限制：最多 16 个参数、只支持数值。</p>
 *
 * <p>包里带上**发起者当前的主模型**：下游要按"哪个玩家渲染的这个模型"去跑脚本，
 * 服务端自己并不知道客户端此刻渲染的是哪个模型。</p>
 */
public class C2SMolangSync implements IMessage {

    /** wiki：最多可以传递 16 个参数，超过会导致解析失败。 */
    public static final int MAX_ARGUMENTS = 16;

    private String modelId;
    private int[] arguments;

    public C2SMolangSync() {}

    public C2SMolangSync(ResourceLocation modelId, int[] arguments) {
        this.modelId = modelId == null ? "" : modelId.toString();
        this.arguments = clamp(arguments);
    }

    /** 只保留前 {@link #MAX_ARGUMENTS} 个参数（wiki 的行为是"超过解析失败"，这里截断更稳）。 */
    public static int[] clamp(int[] arguments) {
        if (arguments == null) {
            return new int[0];
        }
        if (arguments.length <= MAX_ARGUMENTS) {
            return arguments.clone();
        }
        int[] clamped = new int[MAX_ARGUMENTS];
        System.arraycopy(arguments, 0, clamped, 0, MAX_ARGUMENTS);
        return clamped;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.modelId = ByteBufUtils.readUTF8String(buf);
        // 长度按无符号字节读：恶意客户端写 0x80 时 readByte() 会得到负数，
        // 无符号读取 + 下面的 MAX_ARGUMENTS 截断保证参数数组永远不超过 16。
        int count = Math.min(buf.readUnsignedByte(), MAX_ARGUMENTS);
        this.arguments = new int[Math.max(0, count)];
        for (int i = 0; i < this.arguments.length; i++) {
            this.arguments[i] = buf.readInt();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.modelId == null ? "" : this.modelId);
        this.arguments = clamp(this.arguments);
        buf.writeByte(this.arguments.length);
        for (int argument : this.arguments) {
            buf.writeInt(argument);
        }
    }

    public String getModelId() {
        return this.modelId;
    }

    public int[] getArguments() {
        return this.arguments;
    }

    /** 同一玩家、**同一参数**的重复广播：1 秒内最多一次（wiki：「不要频繁发起」）。 */
    public static final long MIN_BROADCAST_INTERVAL_MS = 1000L;

    /**
     * 同一玩家每秒的**广播硬上限**（含参数变化）。
     * <p>
     * 这一层是防"改过的客户端"的放大攻击，必须有真上限；但"每秒 1 次且超出就丢"会把
     * 模型用 {@code ysm.sync} 传的**状态变化**也压掉：某车辆模型用
     * {@code ysm.sync(0,0/1)} 传"鸣笛按下/松开"，被压掉后"松开"要等到下一秒才生效，
     * 长鸣笛会一直响（上游没有限流，松手下一个 tick 就停）。
     * <p>
     * 所以判定按参数区分：参数变了立刻放行（状态切换），参数没变的重复仍按
     * {@link #MIN_BROADCAST_INTERVAL_MS} 压掉，而**两种都**受这个每秒硬上限约束 ——
     * 一个不停换参数的恶意客户端最多把广播放大到 4 次/秒（上游是**无上限**）。
     */
    public static final int MAX_BROADCASTS_PER_SECOND = 4;

    /** 限流表的清理阈值：条目超过这个数量时顺手清掉已过期的记录。 */
    private static final int THROTTLE_PRUNE_THRESHOLD = 512;

    /** 每个玩家的广播窗口状态（判定在 {@link ConcurrentHashMap#compute} 里按键串行化）。 */
    private static final class BroadcastWindow {

        int second = -1;
        int count;
        int[] lastPayload;
        int lastPayloadSecond = Integer.MIN_VALUE;

        boolean staleAt(long nowSecond) {
            return nowSecond - this.second >= 2 && nowSecond - this.lastPayloadSecond >= 2;
        }
    }

    private static final Map<UUID, BroadcastWindow> WINDOWS = new ConcurrentHashMap<>();

    /**
     * 服务端限流判定（纯逻辑，便于单测）：见 {@link #MAX_BROADCASTS_PER_SECOND}。
     * <ul>
     *   <li>参数与上次广播的一样 → 同一状态的重复 → {@link #MIN_BROADCAST_INTERVAL_MS} 内压掉；</li>
     *   <li>参数变了 → 状态切换 → 立刻放行；</li>
     *   <li>无论哪种，每秒总数不超过 {@link #MAX_BROADCASTS_PER_SECOND}。</li>
     * </ul>
     *
     * <p>网络包可能在不同线程上被处理，所以“读窗口 + 判断 + 写回”必须是原子的：
     * 早先的 {@code get}/{@code put} 组合存在竞态，同一 UUID 的两个并发包会同时读到
     * “无记录/已过期”然后都被放行。这里用 {@link ConcurrentHashMap#compute}，
     * 它按键加锁把整个“判断 + 写入”串行化，只有第一个调用能看到可用的旧窗口。</p>
     */
    static boolean allowBroadcast(UUID senderId, long nowMillis, int[] arguments) {
        if (senderId == null) {
            return false;
        }
        long nowSecond = nowMillis / 1000L;
        pruneExpired(nowSecond);
        // compute 的映射函数内不能提前返回，用单元素数组把判定结果带出来。
        boolean[] allowed = { false };
        int[] payload = clamp(arguments);
        WINDOWS.compute(senderId, (id, window) -> {
            BroadcastWindow w = window == null ? new BroadcastWindow() : window;
            if (nowSecond != w.second) {
                w.second = (int) nowSecond;
                w.count = 0;
            }
            boolean samePayload = w.lastPayload != null && java.util.Arrays.equals(w.lastPayload, payload);
            if (samePayload && nowSecond == w.lastPayloadSecond) {
                return w;
            }
            if (w.count >= MAX_BROADCASTS_PER_SECOND) {
                return w;
            }
            w.count++;
            w.lastPayload = payload;
            w.lastPayloadSecond = (int) nowSecond;
            allowed[0] = true;
            return w;
        });
        return allowed[0];
    }

    /** 条目超过阈值时顺手清掉已过期的记录；弱一致遍历对限流判定本身没有影响。 */
    private static void pruneExpired(long nowSecond) {
        if (WINDOWS.size() > THROTTLE_PRUNE_THRESHOLD) {
            WINDOWS.values()
                .removeIf(window -> window.staleAt(nowSecond));
        }
    }

    /** 清空限流状态（测试用）。 */
    static void resetThrottle() {
        WINDOWS.clear();
    }

    public static class Handler implements IMessageHandler<C2SMolangSync, IMessage> {

        @Override
        public IMessage onMessage(C2SMolangSync message, MessageContext ctx) {
            EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
            if (sender == null) {
                return null;
            }
            // 限流放在最前面：无论包体是否合法，同一玩家的广播频率都受限（同一参数每秒一次、
            // 且每秒总数不超过 MAX_BROADCASTS_PER_SECOND）。
            if (!allowBroadcast(sender.getUniqueID(), System.currentTimeMillis(), message.arguments)) {
                return null;
            }
            // 参数数量与模型 id 都在客户端给过，但下行包一律以这里的校验结果为准：
            // 服务端不相信客户端传来的长度。
            ResourceLocation modelId = message.modelId == null || message.modelId.isEmpty()
                ? null
                : new ResourceLocation(message.modelId);
            if (modelId == null) {
                return null;
            }
            S2CMolangSync broadcast = new S2CMolangSync(sender.getUniqueID(), modelId, message.arguments);
            // 只发给"能看到发起者"的玩家 + 发起者自己 —— 与上游 OpenYSM 的
            // sendToTrackingEntityAndSelf 一致（1.7.10 的 EntityTracker.func_151248_b
            // 就是 tracking players + self）。原先发给全服所有玩家：无关客户端也要跑一遍
            // 该模型的 @sync 脚本，人多时纯属浪费；而 @sync 的效果本来就只跟"渲染该玩家"有关。
            if (sender.worldObj instanceof WorldServer) {
                Packet packet = NetworkHandler.CHANNEL.getPacketFrom(broadcast);
                ((WorldServer) sender.worldObj).getEntityTracker()
                    .func_151248_b(sender, packet);
            } else {
                // 理论上服务端玩家的 worldObj 一定是 WorldServer；兜底只发给发起者。
                NetworkHandler.CHANNEL.sendTo(broadcast, sender);
            }
            return null;
        }
    }
}
