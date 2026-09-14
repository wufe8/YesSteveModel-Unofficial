package com.fox.ysmu.network.message;

import java.util.UUID;

import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.util.ResourceLocation;

/**
 * {@code ysm.sync(...)} 的**下行**包（YSM-wiki: molang/script「主动同步」）：
 * 服务端把同步请求广播给所有玩家，客户端据此触发发起者模型的 {@code sync} 事件脚本。
 */
public class S2CMolangSync implements IMessage {

    private UUID senderId;
    private String modelId;
    private int[] arguments;

    public S2CMolangSync() {}

    public S2CMolangSync(UUID senderId, ResourceLocation modelId, int[] arguments) {
        this.senderId = senderId;
        this.modelId = modelId == null ? "" : modelId.toString();
        this.arguments = C2SMolangSync.clamp(arguments);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.senderId = new UUID(buf.readLong(), buf.readLong());
        this.modelId = ByteBufUtils.readUTF8String(buf);
        // 长度按无符号字节读，和上行包保持一致：坏包写 0x80 时 readByte() 会得到负数，
        // 无符号读取 + MAX_ARGUMENTS 截断保证参数数组永远是 0..16 个。
        int count = Math.min(buf.readUnsignedByte(), C2SMolangSync.MAX_ARGUMENTS);
        this.arguments = new int[Math.max(0, count)];
        for (int i = 0; i < this.arguments.length; i++) {
            this.arguments[i] = buf.readInt();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        UUID id = this.senderId == null ? new UUID(0L, 0L) : this.senderId;
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
        ByteBufUtils.writeUTF8String(buf, this.modelId == null ? "" : this.modelId);
        this.arguments = C2SMolangSync.clamp(this.arguments);
        buf.writeByte(this.arguments.length);
        for (int argument : this.arguments) {
            buf.writeInt(argument);
        }
    }

    public UUID getSenderId() {
        return this.senderId;
    }

    public String getModelId() {
        return this.modelId;
    }

    public int[] getArguments() {
        return this.arguments;
    }

    public static class Handler implements IMessageHandler<S2CMolangSync, IMessage> {

        @Override
        public IMessage onMessage(S2CMolangSync message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT) {
                // 网络线程只负责投递：handleMolangSync 会触发模型脚本求值并改动客户端
                // 渲染/注册状态，必须回到 Minecraft 客户端主线程执行。
                net.minecraft.client.Minecraft.getMinecraft()
                    .func_152344_a(new Runnable() {

                        @Override
                        public void run() {
                            ysmu.proxy.handleMolangSync(message);
                        }
                    });
            }
            return null;
        }
    }
}
