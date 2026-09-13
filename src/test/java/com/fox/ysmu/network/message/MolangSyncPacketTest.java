package com.fox.ysmu.network.message;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.UUID;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * {@code ysm.sync(...)} 的包体（YSM-wiki: molang/script「主动同步」）。
 *
 * <p>wiki 的硬限制是最多 16 个数值参数。上下行包都按"字节里读出的长度"为准，
 * 所以这里锁住往返一致、超长截断、空参数不读崩。</p>
 */
class MolangSyncPacketTest {

    private static ResourceLocation model(String path) {
        return new ResourceLocation("ysmu", path);
    }

    @Test
    void serverboundArgumentsSurviveRoundTrip() {
        int[] arguments = { 1234, -7, 0, 65536 };
        C2SMolangSync message = new C2SMolangSync(model("snake"), arguments);

        ByteBuf buf = Unpooled.buffer();
        message.toBytes(buf);
        C2SMolangSync decoded = new C2SMolangSync();
        decoded.fromBytes(buf);

        assertEquals("ysmu:snake", decoded.getModelId());
        assertArrayEquals(arguments, decoded.getArguments());
    }

    @Test
    void clientboundArgumentsSurviveRoundTrip() {
        UUID sender = UUID.fromString("11111111-2222-3333-4444-555555555555");
        S2CMolangSync message = new S2CMolangSync(sender, model("snake"), new int[] { 1, 2, 3 });

        ByteBuf buf = Unpooled.buffer();
        message.toBytes(buf);
        S2CMolangSync decoded = new S2CMolangSync();
        decoded.fromBytes(buf);

        assertEquals(sender, decoded.getSenderId());
        assertEquals("ysmu:snake", decoded.getModelId());
        assertArrayEquals(new int[] { 1, 2, 3 }, decoded.getArguments());
    }

    /** wiki：最多 16 个参数。多出来的必须被截断，避免把包撑大。 */
    @Test
    void moreThanSixteenArgumentsAreTruncated() {
        int[] tooMany = new int[40];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = i;
        }

        C2SMolangSync message = new C2SMolangSync(model("snake"), tooMany);

        assertEquals(C2SMolangSync.MAX_ARGUMENTS, message.getArguments().length);
        assertArrayEquals(Arrays.copyOf(tooMany, C2SMolangSync.MAX_ARGUMENTS), message.getArguments());
    }

    /** 空参数列表不能把 fromBytes 读崩。 */
    @Test
    void emptyArgumentsRoundTrip() {
        C2SMolangSync message = new C2SMolangSync(model("snake"), new int[0]);

        ByteBuf buf = Unpooled.buffer();
        message.toBytes(buf);
        C2SMolangSync decoded = new C2SMolangSync();
        decoded.fromBytes(buf);

        assertEquals(0, decoded.getArguments().length);
    }

    /** 模型 id 缺失时上行包不能变成空指针（服务端会据此拒收）。 */
    @Test
    void missingModelIdIsEncodedAsEmptyString() {
        C2SMolangSync message = new C2SMolangSync(null, new int[] { 1 });
        ByteBuf buf = Unpooled.buffer();
        message.toBytes(buf);
        C2SMolangSync decoded = new C2SMolangSync();
        decoded.fromBytes(buf);

        assertEquals("", decoded.getModelId());
        assertArrayEquals(new int[] { 1 }, decoded.getArguments());
    }
}
