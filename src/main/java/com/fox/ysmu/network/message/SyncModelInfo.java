package com.fox.ysmu.network.message;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.util.ModelIdUtil;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

public class SyncModelInfo implements IMessage {

    /** 等待被同步玩家实体出现的上限（原先 5 次 × 500 ms 的后台轮询）。 */
    private static final long EEP_ENTITY_WAIT_MS = 2500L;

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
                // 远程玩家可能在这个包之后才到达：短暂等待实体出现再应用。
                // 不能占着 THREAD_POOL 睡觉（ThreadCount=1 会堵住同步解析），也不该在
                // 后台线程改 EEP（渲染线程同时在读）——改为客户端 tick 驱动的重试，
                // 每次重试都在主线程上。
                final long deadline = System.currentTimeMillis() + EEP_ENTITY_WAIT_MS;
                com.fox.ysmu.util.TickScheduler.Handle[] retry = new com.fox.ysmu.util.TickScheduler.Handle[1];
                retry[0] = com.fox.ysmu.util.TickScheduler.client()
                    .scheduleEvery(250L, () -> {
                        Minecraft client = Minecraft.getMinecraft();
                        net.minecraft.entity.Entity entity = client.theWorld == null ? null
                            : client.theWorld.getEntityByID(message.entityId);
                        if (entity == null && System.currentTimeMillis() < deadline) {
                            return; // 再等一轮
                        }
                        retry[0].cancel();
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
                    });
            }
        }
    }
}
