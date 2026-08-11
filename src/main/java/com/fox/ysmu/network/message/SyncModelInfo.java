package com.fox.ysmu.network.message;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.util.ThreadTools;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

public class SyncModelInfo implements IMessage {

    private int entityId;
    private NBTTagCompound modelInfoNBT;

    public SyncModelInfo() {}

    public SyncModelInfo(int entityId, ExtendedModelInfo modelInfo) {
        this.entityId = entityId;
        this.modelInfoNBT = new NBTTagCompound();
        if (modelInfo != null) {
            modelInfo.saveNBTData(this.modelInfoNBT);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        this.modelInfoNBT = ByteBufUtils.readTag(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.entityId);
        ByteBufUtils.writeTag(buf, this.modelInfoNBT);
    }

    public static class Handler implements IMessageHandler<SyncModelInfo, IMessage> {

        @Override
        public IMessage onMessage(SyncModelInfo message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT) {
                handleEEP(message);
            }
            return null;
        }

        private void handleEEP(SyncModelInfo message) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld != null) {
                // Remote players can arrive after this packet, so wait briefly before applying synced EEP data.
                ThreadTools.THREAD_POOL.submit(() -> {
                    try {
                        int time = 0;
                        while (mc.theWorld.getEntityByID(message.entityId) == null && time < 5) {
                            Thread.sleep(500);
                            time++;
                        }
                        net.minecraft.entity.Entity entity = mc.theWorld.getEntityByID(message.entityId);
                        if (entity instanceof EntityPlayer player) {
                            ExtendedModelInfo eep = ExtendedModelInfo.get(player);
                            if (eep != null) {
                                eep.loadNBTData(message.modelInfoNBT);
                                // 进存档/进服：本地玩家自己的模型立即预暖（唯一允许的主动预暖
                                // 目标，1 个模型无内存成本），不等第一帧渲染才触发懒加载。
                                if (player.equals(Minecraft.getMinecraft().thePlayer)) {
                                    ResourceLocation mainId =
                                        ModelIdUtil.getMainId(ModelIdUtil.getModelIdFromSubId(eep.getModelId()));
                                    // 进存档：本地玩家模型立即预暖，并标记 apply 优先（若还在
                                    // apply 队列中则提前到最前），避免整批模型 apply 完才显示。
                                    com.fox.ysmu.client.ClientModelManager.warmModel(mainId);
                                    com.fox.ysmu.client.ClientModelManager.markModelPriority(mainId);
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }
}
