package com.fox.ysmu.network;

import java.util.HashSet;
import java.util.Set;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/** Reject wrong-direction messages before the shared FML codec constructs or decodes them. */
final class IncomingMessageGuard extends ChannelInboundHandlerAdapter {

    private final Set<Integer> allowedIds;
    private final int maxPayloadBytes;

    IncomingMessageGuard(Side receivingSide, Set<Integer> allowedIds) {
        this.allowedIds = new HashSet<>(allowedIds);
        // C17's real transport limit; clientbound legacy model files can approach 2 MiB.
        this.maxPayloadBytes = receivingSide == Side.SERVER ? 32766 : 2 * 1024 * 1024 + 16384;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
        if (message instanceof FMLProxyPacket packet) {
            ByteBuf payload = packet.payload();
            if (payload == null || !payload.isReadable() || payload.readableBytes() > maxPayloadBytes
                || !allowedIds.contains((int) payload.getUnsignedByte(payload.readerIndex()))) {
                // These are shared embedded channels: closing ctx would affect every player.
                // No decoder or client-only handler may run for a rejected packet.
                return;
            }
        }
        ctx.fireChannelRead(message);
    }
}
