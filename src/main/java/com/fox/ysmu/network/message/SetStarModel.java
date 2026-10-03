package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.eep.ExtendedStarModels;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import com.fox.ysmu.network.PacketReadUtil;

public class SetStarModel implements IMessage {

    private String modelId;
    private boolean isAdd;

    public SetStarModel() {}

    private SetStarModel(ResourceLocation modelId, boolean isAdd) {
        this.modelId = modelId.toString();
        this.isAdd = isAdd;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.modelId = PacketReadUtil.readString(buf, PacketReadUtil.MAX_ID_BYTES);
        this.isAdd = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.modelId);
        buf.writeBoolean(this.isAdd);
    }

    public static SetStarModel add(ResourceLocation modelId) {
        return new SetStarModel(modelId, true);
    }

    public static SetStarModel remove(ResourceLocation modelId) {
        return new SetStarModel(modelId, false);
    }

    public static class Handler implements IMessageHandler<SetStarModel, IMessage> {

        @Override
        public IMessage onMessage(SetStarModel message, MessageContext ctx) {
            EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
            if (sender != null) {
                handleEEP(message, sender);
            }
            return null;
        }

        private void handleEEP(SetStarModel message, EntityPlayerMP sender) {
            if (message.modelId == null || message.modelId.isEmpty()) return;
            ExtendedStarModels eep = ExtendedStarModels.get(sender);
            if (eep != null) {
                ResourceLocation modelLoc = new ResourceLocation(message.modelId);
                if (message.isAdd) {
                    eep.addModel(modelLoc);
                } else {
                    eep.removeModel(modelLoc);
                }
            }
        }
    }
}
