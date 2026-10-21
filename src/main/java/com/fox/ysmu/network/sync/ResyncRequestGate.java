package com.fox.ysmu.network.sync;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Atomically rate-limit recovery requests from the current server-issued connection. */
final class ResyncRequestGate<T> {

    private final Map<UUID, Entry<T>> connections = new HashMap<>();

    synchronized void track(UUID id, T connection) {
        Entry<T> entry = connections.get(id);
        if (entry == null || entry.connection != connection) {
            connections.put(id, new Entry<>(connection));
        }
    }

    synchronized int reserve(UUID id, T connection, int cooldownSeconds, long now) {
        Entry<T> entry = connections.get(id);
        if (entry == null || entry.connection != connection) return OpenYsmModelSyncServer.RESYNC_FAILED;
        if (cooldownSeconds < 0) return OpenYsmModelSyncServer.RESYNC_DISABLED;
        if (cooldownSeconds > 0 && entry.lastRequest != null
            && now - entry.lastRequest < cooldownSeconds * 1000L) {
            return OpenYsmModelSyncServer.RESYNC_COOLDOWN;
        }
        entry.lastRequest = now;
        return OpenYsmModelSyncServer.RESYNC_OK;
    }

    synchronized int request(UUID id, T connection, int cooldownSeconds, long now, Runnable accepted) {
        int result = reserve(id, connection, cooldownSeconds, now);
        if (result == OpenYsmModelSyncServer.RESYNC_OK) accepted.run();
        return result;
    }

    synchronized long remainingSeconds(UUID id, int cooldownSeconds, long now) {
        Entry<T> entry = connections.get(id);
        if (cooldownSeconds <= 0 || entry == null || entry.lastRequest == null) return 0L;
        return Math.max(0L, cooldownSeconds - (now - entry.lastRequest) / 1000L);
    }

    synchronized void clear(UUID id) {
        connections.remove(id);
    }

    private static final class Entry<T> {
        private final T connection;
        private Long lastRequest;

        private Entry(T connection) {
            this.connection = connection;
        }
    }
}
