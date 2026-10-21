package com.fox.ysmu.network.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.model.format.OpenYsmSyncInfo;

import io.netty.buffer.Unpooled;
import rip.ysm.security.YSMByteBuf;

class SyncRequestValidationTest {

    @Test
    void onlyAnExpectedExactConnectionCanReplyOnce() {
        SyncReplyGate<String> gate = new SyncReplyGate<>();
        UUID id = UUID.randomUUID();
        String oldConnection = new String("connection");
        String newConnection = new String("connection");
        assertFalse(gate.consume(id, oldConnection));
        gate.expect(id, oldConnection);
        gate.expect(id, newConnection);
        assertFalse(gate.consume(id, oldConnection), "equal values do not make old and new connections identical");
        assertTrue(gate.consume(id, newConnection));
        assertFalse(gate.consume(id, newConnection), "an unsolicited replay cannot restart the sync");
        gate.expect(id, newConnection);
        gate.clear(id);
        assertFalse(gate.consume(id, newConnection), "logout discards outstanding expectations");
    }

    @Test
    void recoveryRequiresCurrentConnectionAndSharesCooldownAcrossHandshakes() {
        ResyncRequestGate<Object> gate = new ResyncRequestGate<>();
        UUID id = UUID.randomUUID();
        Object oldConnection = new Object();
        Object currentConnection = new Object();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(OpenYsmModelSyncServer.RESYNC_FAILED,
            gate.request(id, oldConnection, 30, 1000L, requests::incrementAndGet));
        gate.track(id, oldConnection);
        assertEquals(OpenYsmModelSyncServer.RESYNC_OK,
            gate.request(id, oldConnection, 30, 1000L, requests::incrementAndGet));
        // Issuing the accepted recovery handshake tracks the same connection again.
        gate.track(id, oldConnection);
        assertEquals(OpenYsmModelSyncServer.RESYNC_COOLDOWN,
            gate.request(id, oldConnection, 30, 1001L, requests::incrementAndGet));
        assertEquals(30L, gate.remainingSeconds(id, 30, 1001L));
        assertEquals(OpenYsmModelSyncServer.RESYNC_OK,
            gate.request(id, oldConnection, 30, 31000L, requests::incrementAndGet));
        gate.track(id, currentConnection);
        assertEquals(OpenYsmModelSyncServer.RESYNC_FAILED,
            gate.request(id, oldConnection, 30, 31001L, requests::incrementAndGet));
        assertEquals(OpenYsmModelSyncServer.RESYNC_OK,
            gate.request(id, currentConnection, 30, 31001L, requests::incrementAndGet));
        gate.clear(id);
        assertEquals(OpenYsmModelSyncServer.RESYNC_FAILED,
            gate.request(id, currentConnection, 30, 62000L, requests::incrementAndGet));
        assertEquals(3, requests.get(), "denied recoveries must not issue a handshake");
    }

    @Test
    void recoveryHonorsDisabledAndUnlimitedSettings() {
        ResyncRequestGate<Object> gate = new ResyncRequestGate<>();
        UUID id = UUID.randomUUID();
        Object connection = new Object();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        gate.track(id, connection);
        assertEquals(OpenYsmModelSyncServer.RESYNC_DISABLED,
            gate.request(id, connection, -1, 1000L, requests::incrementAndGet));
        assertEquals(0, requests.get());
        assertEquals(OpenYsmModelSyncServer.RESYNC_OK,
            gate.request(id, connection, 0, 1000L, requests::incrementAndGet));
        assertEquals(OpenYsmModelSyncServer.RESYNC_OK,
            gate.request(id, connection, 0, 1000L, requests::incrementAndGet));
        assertEquals(2, requests.get(), "unlimited mode still permits legitimate empty-list recovery");
    }

    @Test
    void repeatedHashesAreSentOnceAndUnknownHashesCannotSelectFiles() {
        OpenYsmSyncInfo info = new OpenYsmSyncInfo("a", "cache-a", 1L, 2L, 32, false);
        Map<Long, Map<Long, OpenYsmSyncInfo>> allowed = new HashMap<>();
        Map<Long, OpenYsmSyncInfo> inner = new HashMap<>();
        inner.put(2L, info);
        allowed.put(1L, inner);
        try (YSMByteBuf buf = new YSMByteBuf(Unpooled.buffer())) {
            buf.writeVarInt(3);
            buf.writeVarLong(1); buf.writeVarLong(2);
            buf.writeVarLong(1); buf.writeVarLong(2);
            buf.writeVarLong(99); buf.writeVarLong(100);
            assertEquals(Arrays.asList(info), OpenYsmModelSyncServer.readRequestedModels(buf, allowed, 3));
        }
    }

    @Test
    void requestCountCannotExceedTheLibraryOrAvailableBytes() {
        Map<Long, Map<Long, OpenYsmSyncInfo>> allowed = new HashMap<>();
        try (YSMByteBuf buf = new YSMByteBuf(Unpooled.buffer())) {
            buf.writeVarInt(1000000);
            assertThrows(IllegalArgumentException.class,
                () -> OpenYsmModelSyncServer.readRequestedModels(buf, allowed, 3));
        }
        try (YSMByteBuf buf = new YSMByteBuf(Unpooled.buffer())) {
            buf.writeVarInt(1);
            assertThrows(IllegalArgumentException.class,
                () -> OpenYsmModelSyncServer.readRequestedModels(buf, allowed, 3));
        }
    }
}
