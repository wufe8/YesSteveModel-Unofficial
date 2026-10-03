package com.fox.ysmu.network;

import java.nio.charset.StandardCharsets;

import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;

/** Size checks must precede allocations, including for truncated network packets. */
public final class PacketReadUtil {

    public static final int MAX_ID_BYTES = 4096;
    public static final int MAX_ANIMATION_BYTES = 1024;

    private PacketReadUtil() {}

    public static String readString(ByteBuf buf, int maxBytes) {
        int length = ByteBufUtils.readVarInt(buf, 2);
        requireLength(buf, length, maxBytes);
        String text = buf.toString(buf.readerIndex(), length, StandardCharsets.UTF_8);
        buf.skipBytes(length);
        return text;
    }

    public static void requireLength(ByteBuf buf, int length, int maxBytes) {
        if (length < 0 || length > maxBytes || length > buf.readableBytes()) {
            throw new IllegalArgumentException("Invalid YSM packet field length: " + length);
        }
    }

    public static int readCount(ByteBuf buf, int maxCount, int minEntryBytes) {
        int count = buf.readInt();
        if (count < 0 || count > maxCount || count > buf.readableBytes() / minEntryBytes) {
            throw new IllegalArgumentException("Invalid YSM packet entry count: " + count);
        }
        return count;
    }
}
