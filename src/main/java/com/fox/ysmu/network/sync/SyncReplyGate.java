package com.fox.ysmu.network.sync;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** One server-issued reply per exact connection object, even when a UUID reconnects. */
final class SyncReplyGate<T> {

    private final ConcurrentMap<UUID, Ticket<T>> expected = new ConcurrentHashMap<>();

    void expect(UUID playerId, T connection) {
        expected.put(playerId, new Ticket<>(connection));
    }

    boolean consume(UUID playerId, T connection) {
        Ticket<T> ticket = expected.get(playerId);
        return ticket != null && ticket.connection == connection && expected.remove(playerId, ticket);
    }

    void clear(UUID playerId) {
        expected.remove(playerId);
    }

    private static final class Ticket<T> {
        private final T connection;
        private Ticket(T connection) { this.connection = connection; }
    }
}
