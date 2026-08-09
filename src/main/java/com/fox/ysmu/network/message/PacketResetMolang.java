package com.fox.ysmu.network.message;

import net.minecraft.util.ChatComponentText;

import com.fox.ysmu.client.animation.molang.MolangReset;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;

/**
 * Server → Client: reset Molang variable state. Triggered by
 * {@code /ysm reset <selector>} (resetting OTHER players; resetting yourself
 * is client-side via {@code /ysmclient reset}).
 *
 * 客户端收到后清空自己的 Molang 变量状态（漫游变量、帧间物理/控制器状态、v.*
 * 残留），保留模型定义与实时 query/ysm 变量。模型文件缓存不受影响，无需重载。
 */
public class PacketResetMolang implements IMessage {

    public PacketResetMolang() {}

    @Override
    public void fromBytes(io.netty.buffer.ByteBuf buf) {}

    @Override
    public void toBytes(io.netty.buffer.ByteBuf buf) {}

    public static class Handler implements IMessageHandler<PacketResetMolang, IMessage> {
        @Override
        public IMessage onMessage(PacketResetMolang msg, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) return null;
            // 调度到主线程执行，确保与渲染线程的访问一致
            net.minecraft.client.Minecraft.getMinecraft().func_152344_a(new Runnable() {
                @Override
                public void run() {
                    if (net.minecraft.client.Minecraft.getMinecraft().thePlayer == null) return;
                    MolangReset.resetSelf();
                    net.minecraft.client.Minecraft.getMinecraft().thePlayer.addChatMessage(
                        new ChatComponentText(
                            "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r "
                                + "\u00a77Molang variables reset."));
                }
            });
            return null;
        }
    }
}
