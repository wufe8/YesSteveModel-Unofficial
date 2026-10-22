package com.fox.ysmu.network.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;

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

    /** Only identity fields are needed; no world, socket, or packet send is constructed. */
    private static TestPlayer player(UUID id, NetHandlerPlayServer handler) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        TestPlayer player = (TestPlayer) unsafe.allocateInstance(TestPlayer.class);
        Field uuid = Entity.class.getDeclaredField("entityUniqueID");
        uuid.setAccessible(true);
        uuid.set(player, id);
        player.playerNetServerHandler = handler;
        return player;
    }

    private static NetHandlerPlayServer connection() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (NetHandlerPlayServer) ((sun.misc.Unsafe) field.get(null)).allocateInstance(NetHandlerPlayServer.class);
    }

    private static final class TestPlayer extends EntityPlayerMP {
        private TestPlayer() { super(null, null, null, null); }

        @Override
        public String getCommandSenderName() { return "sync-test"; }
    }

    @Test
    @SuppressWarnings("unchecked")
    void respawnKeepsReplyAuthorizationAndRecoveryCooldownButReconnectDoesNot() throws Exception {
        UUID id = UUID.randomUUID();
        NetHandlerPlayServer original = connection();
        TestPlayer before = player(id, original);
        TestPlayer respawned = player(id, original);
        TestPlayer reconnected = player(id, connection());
        Field requests = OpenYsmModelSyncServer.class.getDeclaredField("RESYNC_REQUESTS");
        requests.setAccessible(true);
        ResyncRequestGate<NetHandlerPlayServer> gate = (ResyncRequestGate<NetHandlerPlayServer>) requests.get(null);
        try {
            OpenYsmModelSyncServer.expectVersionReply(before);
            assertEquals(OpenYsmModelSyncServer.RESYNC_OK, gate.reserve(id, original, 30, 1000L));
            assertTrue(OpenYsmModelSyncServer.consumeVersionReply(respawned), "respawn is the same connection");
            OpenYsmModelSyncServer.expectVersionReply(respawned);
            assertEquals(OpenYsmModelSyncServer.RESYNC_COOLDOWN,
                gate.reserve(id, original, 30, 1001L), "respawn must not reset recovery cooldown");
            assertEquals(OpenYsmModelSyncServer.RESYNC_OK, gate.reserve(id, original, 30, 31000L));

            OpenYsmModelSyncServer.LegacyTransfer transfer = OpenYsmModelSyncServer.expectLegacyReply(before);
            assertSame(transfer, OpenYsmModelSyncServer.claimLegacyReply(respawned));
            assertNull(OpenYsmModelSyncServer.claimLegacyReply(respawned), "legacy reply can only be claimed once");
            assertTrue(OpenYsmModelSyncServer.isLegacyActive(transfer));

            OpenYsmModelSyncServer.expectVersionReply(reconnected);
            assertFalse(OpenYsmModelSyncServer.consumeVersionReply(before));
            assertTrue(OpenYsmModelSyncServer.consumeVersionReply(reconnected));
            assertEquals(OpenYsmModelSyncServer.RESYNC_FAILED, gate.reserve(id, original, 30, 31001L));
            assertEquals(OpenYsmModelSyncServer.RESYNC_OK,
                gate.reserve(id, reconnected.playerNetServerHandler, 30, 31001L));
            assertFalse(OpenYsmModelSyncServer.isLegacyActive(transfer));
        } finally {
            OpenYsmModelSyncServer.clear(id);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void unifiedPayloadAndCompletionUseTheHandlerRatherThanTheRespawnedEntity() throws Exception {
        UUID id = UUID.randomUUID();
        NetHandlerPlayServer handler = connection();
        TestPlayer before = player(id, handler);
        TestPlayer respawned = player(id, handler);
        TestPlayer unrelated = player(id, connection());
        Class<?> stateType = Class.forName(OpenYsmModelSyncServer.class.getName() + "$PlayerSyncState");
        java.lang.reflect.Constructor<?> constructor = stateType.getDeclaredConstructor(EntityPlayerMP.class, byte[].class);
        constructor.setAccessible(true);
        Object state = constructor.newInstance(before, new byte[56]);
        Field statesField = OpenYsmModelSyncServer.class.getDeclaredField("SYNC_STATES");
        statesField.setAccessible(true);
        Map<UUID, Object> states = (Map<UUID, Object>) statesField.get(null);
        Field pending = stateType.getDeclaredField("payloadPending");
        pending.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) pending.get(state)).set(true); // keep this test offline
        Field payloads = stateType.getDeclaredField("payloads");
        payloads.setAccessible(true);
        java.util.Queue<byte[]> queue = (java.util.Queue<byte[]>) payloads.get(state);
        states.put(id, state);
        try {
            OpenYsmModelSyncServer.handlePayload(unrelated, new byte[]{1});
            assertTrue(queue.isEmpty(), "old/other connection cannot queue a payload");
            OpenYsmModelSyncServer.handlePayload(respawned, new byte[]{2});
            assertEquals(1, queue.size(), "same handler accepts payload after entity replacement");
            OpenYsmModelSyncServer.complete(unrelated, 0, 0, 0, 0, "");
            assertSame(state, states.get(id));
            OpenYsmModelSyncServer.complete(respawned, 0, 0, 0, 0, "");
            assertFalse(states.containsKey(id));
        } finally {
            OpenYsmModelSyncServer.clear(id);
        }
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
