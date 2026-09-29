package com.fox.ysmu.client.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
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
        /** weight() 的调用次数：容量统计是否被跳过就看它。 */
        int weightCalls;
        long weightPerEntry = 1L;

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
            weightCalls++;
            return weightPerEntry;
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
     * 容量上限关闭时（当前 {@code AssetManager.MAX_WEIGHT = 0}）不得再做容量簿记：
     * {@code weight()} 对几何/动画是沿对象图累加，每轮为每个常驻条目白算一遍是纯浪费。
     * 同时钉住"关闭时行为不变"：只按空闲超时回收，不因容量释放任何东西。
     */
    @Test
    void disabledBudgetSkipsWeightBookkeepingEntirely() {
        RecordingProvider provider = new RecordingProvider();
        AssetCache<String, String> cache = new AssetCache<>(provider, 0L);
        cache.register("ysmu:a/main", "geo", 0L);
        cache.register("ysmu:b/main", "geo", 0L);

        // maxWeight = 0 → 不启用容量上限：一个条目都不许被容量逻辑释放，weight() 一次都不该调。
        cache.evict(10L, 30L, 0L);
        assertEquals(Collections.emptyList(), provider.released);
        assertEquals(2, cache.size());
        assertEquals(0, provider.weightCalls, "weight() must not be called when the budget is disabled");

        // 空闲超时仍然生效（关闭预算只影响容量那一条路径）。
        cache.evict(100L, 30L, 0L);
        assertEquals(2, provider.released.size(), "idle eviction must still work without a budget");
        assertEquals(0, provider.weightCalls);
    }

    /**
     * 容量上限启用时，容量簿记与 LRU 回收必须照旧工作 —— 上面那个跳过不能把功能改坏。
     */
    @Test
    void enabledBudgetStillEvictsLeastRecentlyUsedUntilUnderTheLimit() {
        RecordingProvider provider = new RecordingProvider();
        provider.weightPerEntry = 10L;
        AssetCache<String, String> cache = new AssetCache<>(provider, 0L);
        cache.register("ysmu:a/main", "geo", 0L);
        cache.register("ysmu:b/main", "geo", 100L);
        cache.register("ysmu:c/main", "geo", 200L);
        cache.touchIf(key -> "ysmu:a/main".equals(key), 300L); // a 变成最近使用

        // 30 单位总量、上限 10 → 必须回收到只剩最近使用的那个：b（100）比 c（200）更久未用，
        // 所以先释放 b；释放到只剩 a 时总量 10 <= 上限，停止。只有三个 key，因此
        // "释放了 b 与 c" 就等价于"a 是幸存者"。
        cache.evict(300L, 0L, 10L);

        assertEquals(1, cache.size());
        assertEquals(Arrays.asList("ysmu:b/main", "ysmu:c/main"), provider.released);
        assertTrue(provider.weightCalls > 0, "the budget path must still measure");
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
