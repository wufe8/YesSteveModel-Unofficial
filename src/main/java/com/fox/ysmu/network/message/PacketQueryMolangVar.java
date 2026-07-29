package com.fox.ysmu.network.message;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.ChatComponentText;

import com.fox.ysmu.client.debug.MolangDebugSnapshot;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: query Molang variable values and display in chat.
 * Triggered by {@code /ysm debug query <name>}.
 *
 * 客户端收到后读取当前 Molang 变量值并直接在本地聊天框输出，
 * 无需服务端回包（所有值均为客户端本地状态）。
 */
public class PacketQueryMolangVar implements IMessage {

    private List<String> varNames;

    public PacketQueryMolangVar() {}

    public PacketQueryMolangVar(List<String> varNames) {
        this.varNames = varNames;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int size = buf.readInt();
        varNames = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            varNames.add(ByteBufUtils.readUTF8String(buf));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(varNames.size());
        for (String name : varNames) {
            ByteBufUtils.writeUTF8String(buf, name);
        }
    }

    public List<String> getVarNames() {
        return varNames;
    }

    public static class Handler implements IMessageHandler<PacketQueryMolangVar, IMessage> {
        @Override
        public IMessage onMessage(PacketQueryMolangVar msg, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) return null;
            if (msg.varNames == null || msg.varNames.isEmpty()) return null;

            // 先在聊天框输出标题，再逐个输出变量值
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc.thePlayer != null) {
                int count = msg.varNames.size();
                mc.thePlayer.addChatMessage(new ChatComponentText(
                    MolangDebugSnapshot.CHAT_PREFIX + " §aQuerying " + count + " variable(s)..."));
                for (String name : msg.varNames) {
                    MolangDebugSnapshot.printSingleToChat(name);
                }
            }
            return null;
        }
    }
}
