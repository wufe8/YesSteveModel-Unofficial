package com.fox.ysmu.network.message;

import com.fox.ysmu.client.gui.PlayerModelScreen;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

/**
 * 服务端 → 客户端：为某个实体打开模型 GUI（{@code npcId} 非 -1 时表示这是 NPC / 非玩家实体，
 * 之后 GUI 的按钮会把它送回服务端的 {@link SetNpcModelAndTexture}）。
 *
 * <p><b>本树没有发送方</b>：NPC 模型是上游 Bukkit 侧集成的特性，1.7.10 端只搬了包与注册，
 * 触发器没搬过来，所以 {@link #CURRENT_NPC_ID} 恒为 -1、非本地玩家的模型 GUI 也不会被打开。
 * 详见 {@code NetworkHandler#initBukkit()} 的注释。</p>
 */
public class OpenModelGuiMessage implements IMessage {
    public static int CURRENT_NPC_ID = -1;
    private int entityId;
    private int npcId;

    public OpenModelGuiMessage() {}

    public OpenModelGuiMessage(int entityId, int npcId) {
        this.entityId = entityId;
        this.npcId = npcId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        this.npcId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.entityId);
        buf.writeInt(this.npcId);
    }

    public static class Handler implements IMessageHandler<OpenModelGuiMessage, IMessage> {
        @Override
        public IMessage onMessage(OpenModelGuiMessage message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT) {
                handleMessage(message);
            }
            return null;
        }
    }

    private static void handleMessage(OpenModelGuiMessage message) {
        EntityPlayer localPlayer = Minecraft.getMinecraft().thePlayer;
        if (localPlayer != null) {
            Entity entity = localPlayer.worldObj.getEntityByID(message.entityId);
            if (entity instanceof EntityPlayer player) {
                CURRENT_NPC_ID = message.npcId;
                Minecraft.getMinecraft().displayGuiScreen(new PlayerModelScreen(player));
            }
        }
    }
}
