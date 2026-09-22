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
    /** 每个 key 只报一次"加载成功"/"加载返回 null"（见 {@link #reportLoad}）。 */
    private final java.util.Set<K> loadedReported = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<K> nullReported = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 清空代次：{@link #clear()} 自增。后台加载在提交时记下代次，主线程应用结果前比对，
     *  不一致说明中途发生过 clear（断线/资源重载），必须丢弃结果而不是 apply。 */
    private final java.util.concurrent.atomic.AtomicLong generation =
        new java.util.concurrent.atomic.AtomicLong();

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
        register(key, value, System.currentTimeMillis());
    }

    /** @param now synthetic clock (tests); see {@link #evict}. */
    void register(K key, V value, long now) {
        Entry<V> e = entries.computeIfAbsent(key, k -> new Entry<>());
        e.value = value;
        e.setState(State.READY);
        e.lastUsed = now;
    }

    /** 标记最近使用，避免被空闲回收。 */
    public void touch(K key) {
        Entry<V> e = entries.get(key);
        if (e != null && e.getState() == State.READY) {
            e.lastUsed = System.currentTimeMillis();
        }
    }

    /**
     * 把所有当前 READY 且满足 {@code predicate} 的条目标记为最近使用。
     * <p>
     * 供"必须常驻"的资源使用：兜底模型 default 的几何按子 id 分开缓存
     * （{@code …/main}、{@code …/arm}），只 {@link #touch(Object) touch} 主几何会让手臂
     * 几何在闲置后被释放，而本地兜底模型没有加密客户端缓存可重载 —— 回切时缺 geo。
     */
    public void touchIf(java.util.function.Predicate<K> predicate) {
        touchIf(predicate, System.currentTimeMillis());
    }

    /** @param now synthetic clock (tests); see {@link #evict}. */
    void touchIf(java.util.function.Predicate<K> predicate, long now) {
        for (Map.Entry<K, Entry<V>> me : entries.entrySet()) {
            Entry<V> e = me.getValue();
            if (e.getState() == State.READY && predicate.test(me.getKey())) {
                e.lastUsed = now;
            }
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

    /** 释放所有 READY 资源并清空状态（断线 / /ysm reload 时调用）。
     *  <p>同时推进 {@link #generation}：此刻还在后台跑的加载结果回来时会被判为过期并丢弃，
     *  不会把刚被清掉的资源重新 {@code apply} 回 GeckoLib 缓存（旧实现会复活一个已经
     *  不属于任何登记项的资源，既泄漏又可能让"应该消失的模型"继续渲染）。 */
    public void clear() {
        generation.incrementAndGet();
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

    /** 清空代次（测试/诊断用）：{@link #clear()} 自增，后台加载据此判断结果是否已过期。 */
    long generation() {
        return generation.get();
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
        final long startedGeneration = generation.get();
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
                // 结果回来时可能已经过时：clear()（断线/资源重载）推进了 generation，或该条目
                // 已被 release/替换（entries 里不再是同一个 Entry）。此时绝不能 apply ——
                // provider.apply 会往 GeckoLib 全局缓存写一个已无人管理的资源。
                if (startedGeneration != generation.get() || entries.get(key) != e) {
                    return;
                }
                if (value != null) {
                    try {
                        provider.apply(key, value);
                        e.value = value;
                        e.setState(State.READY);
                        e.lastUsed = System.currentTimeMillis();
                        reportLoad(key, true);
                    } catch (Throwable t) {
                        ysmu.LOG.warn("Failed to apply {}: {}", key, t.getMessage());
                        e.setState(State.FAILED);
                        e.failedAt = System.currentTimeMillis();
                    }
                } else {
                    e.setState(State.FAILED);
                    e.failedAt = System.currentTimeMillis();
                    reportLoad(key, false);
                }
            });
        });
    }

    /**
     * 每次会话里每个 key 报一次加载结果（{@code DebugModelLoad} 门控）。
     *
     * <p>懒加载链条上"后台解密/解析拿不到资源"以前是完全静默的：条目只进 FAILED 并每
     * {@link #failedRetryMs} 重试一次，调用方看到的只是"资源一直不在"。而"资源不在"
     * 既可能是加载中、也可能是加载失败，两者的排查方向完全不同（前者等，后者修）。
     * 这条日志把两者分开，也让"模型渲染成绑定姿势"这类症状能直接定位到资源层。</p>
     */
    private void reportLoad(K key, boolean ok) {
        if (!com.fox.ysmu.Config.DEBUG_MODEL_LOAD) {
            return;
        }
        if (ok) {
            if (loadedReported.add(key)) {
                ysmu.LOG.info("[YSMU-ASSET] loaded and applied {}", key);
            }
        } else if (nullReported.add(key)) {
            ysmu.LOG.info("[YSMU-ASSET] {} load returned null (will retry every {} ms)", key, failedRetryMs);
        }
    }

    /** 立即释放指定资源（READY→ABSENT 并调用 provider.release；未加载时为 no-op）。
     *  <p>正在后台加载的条目也会被取消（LOADING→ABSENT 并移出登记表）：后台结果回来时
     *  会因 {@code entries.get(key) != e} 被丢弃，不会重新出现在缓存里。 */
    public void release(K key) {
        Entry<V> e = entries.get(key);
        if (e != null) {
            release(key, e);
        }
    }

    private void release(K key, Entry<V> e) {
        if (!e.cas(State.READY, State.ABSENT) && !e.cas(State.LOADING, State.ABSENT)) {
            return;
        }
        if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD) {
            // 释放是"资源消失"的另一个来源：只记"加载成功"看不出"刚加载完就被回收"这种
            // 释放/重载来回抖的循环，而那正是"模型时好时坏"的一类根因。
            ysmu.LOG.info("[YSMU-ASSET] released {} (was {})", key,
                e.value == null ? "loading" : "ready");
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
