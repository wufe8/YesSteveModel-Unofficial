package com.fox.ysmu.network.message;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientEventHandler;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: toggle SHOW_WELCOME_MESSAGE config.
 * Triggered by /ysm welcome on|off.
 */
public class SetWelcomeConfig implements IMessage {

    private boolean show;

    public SetWelcomeConfig() {}

    public SetWelcomeConfig(boolean show) {
        this.show = show;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        show = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(show);
    }

    public static class Handler implements IMessageHandler<SetWelcomeConfig, IMessage> {
        @Override
        public IMessage onMessage(SetWelcomeConfig msg, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) return null;
            Config.SHOW_WELCOME_MESSAGE = msg.show;
            Config.save();
            return null;
        }
    }
}
