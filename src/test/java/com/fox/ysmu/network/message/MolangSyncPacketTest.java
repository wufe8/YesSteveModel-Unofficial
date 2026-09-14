package com.fox.ysmu.network.message;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import cpw.mods.fml.common.network.ByteBufUtils;
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

    /** 长度字节按无符号读：0xFF 不能变成 -1，截断后仍然只读 16 个参数。 */
    @Test
    void unsignedLengthByteCannotShrinkOrOverrunArguments() {
        ByteBuf buf = Unpooled.buffer();
        ByteBufUtils.writeUTF8String(buf, "ysmu:snake");
        buf.writeByte(0xFF);
        for (int i = 0; i < C2SMolangSync.MAX_ARGUMENTS; i++) {
            buf.writeInt(i);
        }

        C2SMolangSync decoded = new C2SMolangSync();
        decoded.fromBytes(buf);

        assertEquals(C2SMolangSync.MAX_ARGUMENTS, decoded.getArguments().length);
    }

    /** 下行包也按无符号读长度字节：0xFF 不能变成 -1 而少读参数。 */
    @Test
    void clientboundUnsignedLengthByteCannotShrinkArguments() {
        UUID sender = UUID.fromString("11111111-2222-3333-4444-555555555555");
        ByteBuf buf = Unpooled.buffer();
        buf.writeLong(sender.getMostSignificantBits());
        buf.writeLong(sender.getLeastSignificantBits());
        ByteBufUtils.writeUTF8String(buf, "ysmu:snake");
        buf.writeByte(0xFF);
        for (int i = 0; i < C2SMolangSync.MAX_ARGUMENTS; i++) {
            buf.writeInt(i);
        }

        S2CMolangSync decoded = new S2CMolangSync();
        decoded.fromBytes(buf);

        assertEquals(sender, decoded.getSenderId());
        assertEquals(C2SMolangSync.MAX_ARGUMENTS, decoded.getArguments().length);
        assertEquals(0, decoded.getArguments()[0]);
        assertEquals(C2SMolangSync.MAX_ARGUMENTS - 1, decoded.getArguments()[C2SMolangSync.MAX_ARGUMENTS - 1]);
    }

    /** 服务端限流：同一玩家约 1 秒一次，另一名玩家不受影响。 */
    @Test
    void serverThrottleAllowsOneBroadcastPerPlayerPerSecond() {
        C2SMolangSync.resetThrottle();
        UUID sender = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID other = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        assertTrue(C2SMolangSync.allowBroadcast(sender, 1_000L));
        assertFalse(C2SMolangSync.allowBroadcast(sender, 1_500L), "同一秒内的第二次应被限流");
        assertTrue(C2SMolangSync.allowBroadcast(sender, 2_000L), "满 1 秒后重新放行");
        assertTrue(C2SMolangSync.allowBroadcast(other, 1_500L), "另一名玩家不受影响");
        assertFalse(C2SMolangSync.allowBroadcast(null, 1_500L), "没有 UUID 一律不放行");
    }

    /**
     * 限流判定必须是原子的：同一 UUID 的多个并发请求落在同一个时间窗里时，
     * 只能有一个被放行（旧的 get+put 实现会让多个请求同时通过）。
     */
    @Test
    void concurrentThrottleAdmitsExactlyOneBroadcast() throws Exception {
        C2SMolangSync.resetThrottle();
        UUID sender = UUID.fromString("11111111-2222-3333-4444-555555555555");
        int workers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (C2SMolangSync.allowBroadcast(sender, 1_000L)) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, allowed.get(), "同一 UUID 的并发广播只能有一个通过");
    }
}
