package com.fox.ysmu.network;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleIndexedCodec;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

class IncomingMessageGuardTest {

    public static class CountDecoded implements IMessage {
        static final AtomicInteger calls = new AtomicInteger();
        @Override public void fromBytes(ByteBuf buf) { calls.incrementAndGet(); }
        @Override public void toBytes(ByteBuf buf) {}
    }

    @Test
    void wrongDirectionAndUnknownIdsNeverReachTheSharedDecoder() {
        CountDecoded.calls.set(0);
        SimpleIndexedCodec codec = new SimpleIndexedCodec();
        codec.addDiscriminator(1, CountDecoded.class);
        codec.addDiscriminator(5, CountDecoded.class);
        EmbeddedChannel server = new EmbeddedChannel(new IncomingMessageGuard(Side.SERVER,
            new HashSet<>(Arrays.asList(5))), codec);
        try {
            assertFalse(server.writeInbound(new FMLProxyPacket(Unpooled.buffer().writeByte(1).writeInt(Integer.MAX_VALUE), "ysmu_network")));
            assertFalse(server.writeInbound(new FMLProxyPacket(Unpooled.buffer().writeByte(255), "ysmu_network")));
            assertEquals(0, CountDecoded.calls.get());
            assertTrue(server.writeInbound(new FMLProxyPacket(Unpooled.buffer().writeByte(5), "ysmu_network")));
            assertTrue(server.readInbound() instanceof CountDecoded);
            assertEquals(1, CountDecoded.calls.get());
        } finally {
            server.finish();
        }
    }

    @Test
    void clientGuardRejectsServerboundAndPreservesReaderIndex() {
        ByteBuf payload = Unpooled.buffer().writeByte(99).writeByte(1);
        payload.readByte();
        EmbeddedChannel client = new EmbeddedChannel(new IncomingMessageGuard(Side.CLIENT,
            new HashSet<>(Arrays.asList(1))));
        try {
            assertFalse(client.writeInbound(new FMLProxyPacket(Unpooled.buffer().writeByte(5), "ysmu_network")));
            FMLProxyPacket accepted = new FMLProxyPacket(payload, "ysmu_network");
            assertTrue(client.writeInbound(accepted));
            assertSame(accepted, client.readInbound());
            assertEquals(1, payload.readerIndex());
        } finally {
            client.finish();
        }
    }

    @Test
    void emptyAndOverTransportLimitPacketsAreRejected() {
        EmbeddedChannel server = new EmbeddedChannel(new IncomingMessageGuard(Side.SERVER,
            new HashSet<>(Arrays.asList(5))));
        try {
            assertFalse(server.writeInbound(new FMLProxyPacket(Unpooled.buffer(0), "ysmu_network")));
            assertFalse(server.writeInbound(new FMLProxyPacket(Unpooled.buffer().writeByte(5).writeZero(32766), "ysmu_network")));
        } finally {
            server.finish();
        }
    }
}
