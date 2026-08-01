package com.fox.ysmu.client.asset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;

import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;

/**
 * 通用「加载 → 释放 → 重加载」生命周期缓存核心。
 *
 * <p>每个 key 对应一个资源单元，内部状态机：
 * {@code ABSENT → LOADING → READY}，失败进入 {@code FAILED}（带重试冷却）；
 * 空闲超时（或超容量）由 {@link #evict} 释放回 {@code ABSENT}，之后任何 {@link #get}
 * 都会重新走后台加载——形成完整的释放后重加载闭环。
 *
 * <p>线程模型：
 * <ul>
 *   <li>{@link AssetProvider#load} 在后台线程池执行（重活：解密/解析，绝不阻塞主线程）；</li>
 *   <li>{@link AssetProvider#apply} / {@link AssetProvider#release} 通过
 *       {@code Minecraft.func_152344_a} 回到主线程（GL / 全局缓存写入）。</li>
 * </ul>
 *
 * <p>业务代码不直接碰状态机：读走 {@link #get}（未命中触发后台加载并返回 null），
 * 同步注册走 {@link #register}，定期回收由 {@link #evict} 驱动。
 */
public final class AssetCache<K, V> {

    /** 资源单元生命周期状态。 */
    private enum State { ABSENT, LOADING, READY, FAILED }

    private static final class Entry<V> {
        /** 状态用 AtomicReference 实现 CAS（volatile 枚举字段没有 compareAndSet）。 */
        private final java.util.concurrent.atomic.AtomicReference<State> state =
            new java.util.concurrent.atomic.AtomicReference<>(State.ABSENT);
        volatile V value;
        volatile long lastUsed;
        volatile long failedAt;

        State getState() {
            return state.get();
        }

        boolean cas(State expected, State next) {
            return state.compareAndSet(expected, next);
        }

        void setState(State next) {
            state.set(next);
        }
    }

    private final AssetProvider<K, V> provider;
    private final long failedRetryMs;
    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();

    public AssetCache(AssetProvider<K, V> provider, long failedRetryMs) {
        this.provider = provider;
        this.failedRetryMs = failedRetryMs;
    }

    /**
     * 读取资源。READY 时返回并刷新活跃时间；ABSENT/FAILED 时触发后台加载并返回 null；
     * LOADING（或失败冷却内）直接返回 null。
     */
    public V get(K key) {
        Entry<V> e = entries.computeIfAbsent(key, k -> new Entry<>());
        State s = e.getState();
        if (s == State.READY) {
            e.lastUsed = System.currentTimeMillis();
            return e.value;
        }
        if (s == State.LOADING) {
            return null;
        }
        if (s == State.FAILED && System.currentTimeMillis() - e.failedAt < failedRetryMs) {
            return null;
        }
        beginLoad(key, e);
        return null;
    }

    /**
     * 主线程登记一个已就绪的资源（如同步注册的模型/动画），跳过加载阶段。
     * 通常由首次同步流程调用，之后由本缓存统一管理生命周期。
     */
    public void register(K key, V value) {
        Entry<V> e = entries.computeIfAbsent(key, k -> new Entry<>());
        e.value = value;
        e.setState(State.READY);
        e.lastUsed = System.currentTimeMillis();
    }

    /** 标记最近使用，避免被空闲回收。 */
    public void touch(K key) {
        Entry<V> e = entries.get(key);
        if (e != null && e.getState() == State.READY) {
            e.lastUsed = System.currentTimeMillis();
        }
    }

    /**
     * 是否处于加载中 / 待加载（区别于「确定缺失」）。
     * 调用方可用它避免把「异步加载中」误判为「模型缺失」。
     */
    public boolean isPending(K key) {
        Entry<V> e = entries.get(key);
        if (e == null) {
            return true; // 尚未登记：视为待加载
        }
        State s = e.getState();
        return s == State.LOADING
            || s == State.ABSENT
            || (s == State.FAILED && System.currentTimeMillis() - e.failedAt < failedRetryMs);
    }

    /**
     * 主线程定期回收：先释放空闲超时的资源，再按需（超容量）释放最久未用的资源。
     *
     * @param idleMs    空闲多久（ms）后释放；{@code <= 0} 表示不按空闲回收。
     * @param maxWeight 总重量（{@link AssetProvider#weight} 累计）上限；{@code <= 0} 表示不启用容量上限。
     */
    public void evict(long now, long idleMs, long maxWeight) {
        long total = 0;
        List<Map.Entry<K, Entry<V>>> ready = new ArrayList<>();
        for (Map.Entry<K, Entry<V>> me : entries.entrySet()) {
            Entry<V> e = me.getValue();
            if (e.getState() != State.READY) {
                continue;
            }
            if (idleMs > 0 && now - e.lastUsed > idleMs) {
                release(me.getKey(), e);
            } else {
                total += provider.weight(me.getKey(), e.value);
                ready.add(me);
            }
        }
        if (maxWeight > 0 && total > maxWeight) {
            ready.sort(Comparator.comparingLong(me -> me.getValue().lastUsed));
            for (Map.Entry<K, Entry<V>> me : ready) {
                if (total <= maxWeight) {
                    break;
                }
                total -= provider.weight(me.getKey(), me.getValue().value);
                release(me.getKey(), me.getValue());
            }
        }
    }

    /** 释放所有 READY 资源并清空状态（断线 / /ysm reload 时调用）。 */
    public void clear() {
        for (Map.Entry<K, Entry<V>> me : entries.entrySet()) {
            Entry<V> e = me.getValue();
            if (e.getState() == State.READY) {
                try {
                    provider.release(me.getKey(), e.value, ReleaseMode.DROP_HEAP);
                } catch (Throwable t) {
                    ysmu.LOG.warn("Failed to release {} during clear: {}", me.getKey(), t.getMessage());
                }
            }
        }
        entries.clear();
    }

    /** 当前登记的条目数（诊断用）。 */
    public int size() {
        return entries.size();
    }

    /** 提交后台加载，并保证加载结果在主线程应用。 */
    private void beginLoad(K key, Entry<V> e) {
        State s = e.getState();
        if (s != State.ABSENT && s != State.FAILED) {
            return; // 已在加载中
        }
        if (!e.cas(s, State.LOADING)) {
            return; // 另一线程已抢先开始加载
        }
        ThreadTools.THREAD_POOL.submit(() -> {
            V loaded;
            try {
                loaded = provider.load(key);
            } catch (Throwable t) {
                ysmu.LOG.warn("Failed to load {}: {}", key, t.getMessage());
                loaded = null;
            }
            final V value = loaded;
            Minecraft.getMinecraft().func_152344_a(() -> {
                if (value != null) {
                    try {
                        provider.apply(key, value);
                        e.value = value;
                        e.setState(State.READY);
                        e.lastUsed = System.currentTimeMillis();
                    } catch (Throwable t) {
                        ysmu.LOG.warn("Failed to apply {}: {}", key, t.getMessage());
                        e.setState(State.FAILED);
                        e.failedAt = System.currentTimeMillis();
                    }
                } else {
                    e.setState(State.FAILED);
                    e.failedAt = System.currentTimeMillis();
                }
            });
        });
    }

    private void release(K key, Entry<V> e) {
        if (!e.cas(State.READY, State.ABSENT)) {
            return;
        }
        try {
            provider.release(key, e.value, provider.defaultReleaseMode());
        } catch (Throwable t) {
            ysmu.LOG.warn("Failed to release {}: {}", key, t.getMessage());
        } finally {
            e.value = null;
            entries.remove(key, e);
        }
    }
}
