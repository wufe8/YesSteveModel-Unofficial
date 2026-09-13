package com.fox.ysmu.network.message;

import java.util.ArrayList;
import java.util.List;

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
        int count = Math.min(buf.readByte(), MAX_ARGUMENTS);
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
