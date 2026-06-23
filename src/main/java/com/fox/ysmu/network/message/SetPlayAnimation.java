package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.eep.ExtendedModelInfo;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class SetPlayAnimation implements IMessage {

    private String animationName;

    public SetPlayAnimation() {}

    public SetPlayAnimation(String animationName) {
        this.animationName = animationName;
    }

    public static SetPlayAnimation stop() {
        return new SetPlayAnimation(".stop");
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.animationName = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, animationName == null ? "" : animationName);
    }

    public static class Handler implements IMessageHandler<SetPlayAnimation, IMessage> {

        @Override
        public IMessage onMessage(SetPlayAnimation message, MessageContext ctx) {
            EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
            if (sender != null && message.animationName != null) {
                ExtendedModelInfo eep = ExtendedModelInfo.get(sender);
                if (eep != null) {
                    if (".stop".equals(message.animationName)) {
                        eep.stopAnimation();
                    } else if (!message.animationName.isEmpty()) {
                        eep.playAnimation(message.animationName);
                    }
                }
            }
            return null;
        }
    }
}
