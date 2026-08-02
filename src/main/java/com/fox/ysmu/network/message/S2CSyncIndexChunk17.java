package com.fox.ysmu.network.message;

import com.fox.ysmu.client.sync.OpenYsmModelSyncClient;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Clientbound chunk of the (already encrypted) OpenYSM sync index.
 *
 * <p>On large model libraries the packet-03 sync index can exceed the 1.7.10
 * custom-payload size limit (~32 KB), which the transport splits into separate
 * messages and corrupts. The server splits the encrypted index into chunks well
 * under that limit and sends each as this message; the client reassembles them
 * (see {@link OpenYsmModelSyncClient#handleSyncIndexChunk}) and only then runs the
 * normal decrypt + packet-03 parse on the complete blob.
 */
public class S2CSyncIndexChunk17 implements IMessage {

    private static final int MAX_CHUNK_BYTES = 2 * 1024 * 1024;

    private int totalLength;
    private int offset;
    private byte[] chunk = new byte[0];
    private boolean last;

    public S2CSyncIndexChunk17() {}

    public S2CSyncIndexChunk17(int totalLength, int offset, byte[] chunk, boolean last) {
        this.totalLength = totalLength;
        this.offset = offset;
        this.chunk = chunk == null ? new byte[0] : chunk;
        this.last = last;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.totalLength = buf.readInt();
        this.offset = buf.readInt();
        int length = buf.readInt();
        if (length < 0 || length > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("Invalid sync index chunk length: " + length);
        }
        this.chunk = new byte[length];
        buf.readBytes(this.chunk);
        this.last = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.totalLength);
        buf.writeInt(this.offset);
        byte[] safeChunk = this.chunk == null ? new byte[0] : this.chunk;
        buf.writeInt(safeChunk.length);
        buf.writeBytes(safeChunk);
        buf.writeBoolean(this.last);
    }

    public static class Handler implements IMessageHandler<S2CSyncIndexChunk17, IMessage> {

        @Override
        public IMessage onMessage(S2CSyncIndexChunk17 message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT) {
                OpenYsmModelSyncClient.handleSyncIndexChunk(
                    message.totalLength, message.offset, message.chunk, message.last);
            }
            return null;
        }
    }
}
