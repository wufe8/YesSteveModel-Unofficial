package com.fox.ysmu.network.message;

import net.minecraft.util.ChatComponentText;

import com.fox.ysmu.client.debug.MolangDebugSnapshot;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: evaluate a Molang expression/function and show the result
 * in chat. Triggered by {@code /ysm debug eval <expression>}.
 *
 * 客户端收到后调用 MolangParser 解析并求值（MolangParser 为客户端单例），
 * 结果直接在本地聊天框输出，无需服务端回包。
 */
public class PacketEvalMolang implements IMessage {

    private String expression;

    public PacketEvalMolang() {}

    public PacketEvalMolang(String expression) {
        this.expression = expression;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        expression = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, expression);
    }

    public String getExpression() {
        return expression;
    }

    public static class Handler implements IMessageHandler<PacketEvalMolang, IMessage> {
        @Override
        public IMessage onMessage(PacketEvalMolang msg, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) return null;
            if (msg.expression == null) return null;

            // 调度到主线程执行，确保求值在客户端主线程进行
            final String expr = msg.expression;
            net.minecraft.client.Minecraft.getMinecraft().func_152344_a(new Runnable() {
                @Override
                public void run() {
                    if (net.minecraft.client.Minecraft.getMinecraft().thePlayer == null) return;
                    MolangDebugSnapshot.evalToChat(expr);
                }
            });
            return null;
        }
    }
}
