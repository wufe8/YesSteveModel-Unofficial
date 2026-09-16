package com.fox.ysmu.client.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Idle-eviction bookkeeping of {@link AssetCache}, on a synthetic clock (no Minecraft,
 * no GL). {@code touchIf} exists so the fallback model's assets can be pinned as a
 * group: its geometry is cached per sub-id ({@code …/main}, {@code …/arm}), and the
 * local built-in fallback has no encrypted-cache entry to reload from, so releasing any
 * of them is unrecoverable.
 */
class AssetCacheTest {

    private static final class RecordingProvider implements AssetProvider<String, String> {

        final List<String> released = new ArrayList<>();

        @Override
        public String load(String key) {
            return key;
        }

        @Override
        public void apply(String key, String value) {
            // no-op
        }

        @Override
        public void release(String key, String value, ReleaseMode mode) {
            released.add(key);
        }

        @Override
        public ReleaseMode defaultReleaseMode() {
            return ReleaseMode.DROP_HEAP;
        }

        @Override
        public long weight(String key, String value) {
            return 1L;
        }
    }

    @Test
    void touchIfKeepsOnlyTheMatchingEntriesOutOfIdleEviction() {
        RecordingProvider provider = new RecordingProvider();
        AssetCache<String, String> cache = new AssetCache<>(provider, 0L);
        cache.register("ysmu:default/main", "geo", 0L);
        cache.register("ysmu:default/arm", "geo", 0L);
        cache.register("ysmu:other/main", "geo", 0L);

        // 100 clock units later only the fallback's assets are refreshed.
        cache.touchIf(key -> key.startsWith("ysmu:default/"), 100L);
        // At 120 the non-matching entry is 120 idle (> 30) while the refreshed fallback
        // entries are only 20 idle, so only the former may be released.
        cache.evict(120L, 30L, 0L);

        assertEquals(Collections.singletonList("ysmu:other/main"), provider.released);
        assertEquals(2, cache.size(), "the fallback's main and arm entries stay resident");
    }

    @Test
    void touchIfIgnoresEntriesThatAreNotReady() {
        RecordingProvider provider = new RecordingProvider();
        AssetCache<String, String> cache = new AssetCache<>(provider, 0L);
        cache.register("ysmu:default/main", "geo", 0L);
        // "ysmu:default/arm" was never registered → the predicate must not create it.
        cache.touchIf(key -> key.startsWith("ysmu:default/"), 100L);

        assertEquals(1, cache.size());
        assertEquals(Collections.emptyList(), provider.released);
    }

    /**
     * A background load started before a {@code clear()} must not apply afterwards.
     * {@code clear()} bumps the generation, which is what the main-thread apply callback
     * checks; the observable contract here is the bump plus the emptied table.
     */
    @Test
    void clearInvalidatesInFlightLoads() {
        RecordingProvider provider = new RecordingProvider();
        AssetCache<String, String> cache = new AssetCache<>(provider, 0L);
        cache.register("ysmu:a/main", "geo", 0L);

        long before = cache.generation();
        cache.clear();

        assertTrue(cache.generation() > before, "clear() must invalidate in-flight loads");
        assertEquals(0, cache.size());
        assertEquals(Collections.singletonList("ysmu:a/main"), provider.released);
    }

    /** release() drops the entry and releases the value; a second call is a no-op. */
    @Test
    void releaseRemovesTheEntryAndIsIdempotent() {
        RecordingProvider provider = new RecordingProvider();
        AssetCache<String, String> cache = new AssetCache<>(provider, 0L);
        cache.register("ysmu:a/main", "geo", 0L);

        cache.release("ysmu:a/main");
        cache.release("ysmu:a/main"); // 已不在表里 → no-op

        assertEquals(0, cache.size());
        assertEquals(Collections.singletonList("ysmu:a/main"), provider.released);
    }
}
