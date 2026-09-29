package com.fox.ysmu.network.message;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: {@code /ysm debug overlay [on|off|toggle]} 的客户端执行体。
 *
 * <p>Debug Overlay 是纯客户端状态（{@code DebugOverlay} 直接引用
 * {@code net.minecraft.client.*} 与 LWJGL），原先服务端命令在服务端调用它：连专用服务器时
 * 服务端根本没有那些类，子命令会以类解析失败告终。服务端只负责转发请求，真正的切换与反馈
 * 在目标客户端做（与 {@link S2CPlaySound} 同一模式）。</p>
 */
public class S2CSetDebugOverlay implements IMessage {

    public static final byte MODE_OFF = 0;
    public static final byte MODE_ON = 1;
    public static final byte MODE_TOGGLE = 2;

    private byte mode = MODE_TOGGLE;

    public S2CSetDebugOverlay() {}

    public S2CSetDebugOverlay(byte mode) {
        this.mode = mode;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.mode = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(mode);
    }

    public static class Handler implements IMessageHandler<S2CSetDebugOverlay, IMessage> {

        @Override
        public IMessage onMessage(S2CSetDebugOverlay message, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) return null;
            final byte mode = message.mode;
            Minecraft.getMinecraft()
                .func_152344_a(() -> {
                    boolean wasActive = com.fox.ysmu.client.gui.debug.DebugOverlay.isActive();
                    if (mode == MODE_OFF) {
                        if (wasActive) com.fox.ysmu.client.gui.debug.DebugOverlay.toggle();
                    } else if (mode == MODE_ON) {
                        if (!wasActive) com.fox.ysmu.client.gui.debug.DebugOverlay.toggle();
                    } else {
                        com.fox.ysmu.client.gui.debug.DebugOverlay.toggle();
                    }
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.thePlayer != null) {
                        String status = com.fox.ysmu.client.gui.debug.DebugOverlay.isActive() ? "\u00a7aON"
                            : "\u00a77OFF";
                        mc.thePlayer.addChatMessage(new ChatComponentText(
                            "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r Debug overlay: " + status));
                    }
                    if (com.fox.ysmu.client.gui.debug.DebugOverlay.isActive()) {
                        com.fox.ysmu.client.gui.debug.DebugOverlay.tryShowToggleHint();
                    }
                });
            return null;
        }
    }
}
