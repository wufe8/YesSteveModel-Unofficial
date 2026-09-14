package com.fox.ysmu.network.message;

import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;

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

    /** 服务端限流窗口：同一玩家约 1 秒内最多广播一次（wiki：「不要频繁发起」）。 */
    public static final long MIN_BROADCAST_INTERVAL_MS = 1000L;

    /** 限流表的清理阈值：条目超过这个数量时顺手清掉已过期的记录。 */
    private static final int THROTTLE_PRUNE_THRESHOLD = 512;

    private static final Map<UUID, Long> LAST_BROADCAST_MS = new ConcurrentHashMap<>();

    /**
     * 服务端限流判定（纯逻辑，便于单测）：同一 UUID 距离上次广播不足
     * {@link #MIN_BROADCAST_INTERVAL_MS} 时返回 {@code false}。
     *
     * <p>客户端已经有一层限流，但那层可以被改过的客户端绕过；限流必须在服务端做，
     * 否则每帧一次的 {@code ysm.sync} 会把广播放大成无界流量。</p>
     *
     * <p>网络包可能在不同线程上被处理，所以“读上次时间 + 判断 + 写回”必须是原子的：
     * 早先的 {@code get}/{@code put} 组合存在竞态，同一 UUID 的两个并发包会同时读到
     * “无记录/已过期”然后都被放行。这里改用 {@link ConcurrentHashMap#compute}，
     * 它按键加锁把整个“判断 + 写入”串行化，只有第一个调用能看到可用的旧时间戳。</p>
     */
    static boolean allowBroadcast(UUID senderId, long nowMillis) {
        if (senderId == null) {
            return false;
        }
        pruneExpired(nowMillis);
        // compute 的映射函数内不能提前返回，用单元素数组把判定结果带出来。
        boolean[] allowed = { false };
        LAST_BROADCAST_MS.compute(senderId, (id, last) -> {
            if (last != null && nowMillis - last < MIN_BROADCAST_INTERVAL_MS) {
                // 仍在限流窗口内：保留旧时间戳，不放行（也不刷新窗口）。
                return last;
            }
            allowed[0] = true;
            return nowMillis;
        });
        return allowed[0];
    }

    /** 条目超过阈值时顺手清掉已过期的记录；弱一致遍历对限流判定本身没有影响。 */
    private static void pruneExpired(long nowMillis) {
        if (LAST_BROADCAST_MS.size() > THROTTLE_PRUNE_THRESHOLD) {
            LAST_BROADCAST_MS.values()
                .removeIf(last -> nowMillis - last >= MIN_BROADCAST_INTERVAL_MS);
        }
    }

    /** 清空限流状态（测试用）。 */
    static void resetThrottle() {
        LAST_BROADCAST_MS.clear();
    }

    private static List<EntityPlayerMP> allPlayers() {
        List<EntityPlayerMP> players = new ArrayList<>();
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null && server.getConfigurationManager() != null) {
            for (Object player : server.getConfigurationManager().playerEntityList) {
                if (player instanceof EntityPlayerMP) {
                    players.add((EntityPlayerMP) player);
                }
            }
        }
        return players;
    }

    public static class Handler implements IMessageHandler<C2SMolangSync, IMessage> {

        @Override
        public IMessage onMessage(C2SMolangSync message, MessageContext ctx) {
            EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
            if (sender == null) {
                return null;
            }
            // 限流放在最前面：无论包体是否合法，同一玩家的广播频率都不会超过约 1 次/秒。
            if (!allowBroadcast(sender.getUniqueID(), System.currentTimeMillis())) {
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
            for (EntityPlayerMP player : allPlayers()) {
                NetworkHandler.CHANNEL.sendTo(broadcast, player);
            }
            return null;
        }
    }
}
