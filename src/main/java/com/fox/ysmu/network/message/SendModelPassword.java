package com.fox.ysmu.network.message;

import java.util.UUID;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.network.PacketReadUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

public class SendModelPassword implements IMessage {

    /** The encrypted server password is a few dozen bytes; bound it before allocating. */
    private static final int MAX_PASSWORD_BYTES = 4096;

    private UUID playerId;
    private byte[] password;

    public SendModelPassword() {}

    public SendModelPassword(UUID playerId, byte[] password) {
        this.playerId = playerId;
        this.password = password;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.playerId = new UUID(buf.readLong(), buf.readLong());
        int length = buf.readInt();
        PacketReadUtil.requireLength(buf, length, MAX_PASSWORD_BYTES);
        this.password = new byte[length];
        buf.readBytes(this.password);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.playerId.getMostSignificantBits());
        buf.writeLong(this.playerId.getLeastSignificantBits());
        buf.writeInt(this.password.length);
        buf.writeBytes(this.password);
    }

    public static class Handler implements IMessageHandler<SendModelPassword, IMessage> {

        @Override
        public IMessage onMessage(SendModelPassword message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT) {
                ClientModelManager.PASSWORD_UUID = message.playerId;
                ClientModelManager.PASSWORD = message.password;
            }
            return null;
        }
    }
}
