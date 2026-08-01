package com.fox.ysmu.client.asset;

/**
 * 业务侧资源句柄：屏蔽缓存内部的状态机与线程调度。
 *
 * <p>业务代码只需要：
 * <ul>
 *   <li>{@link #get} 读资源（未命中时自动触发后台加载并返回 null，调用方应容忍 null）；</li>
 *   <li>{@link #touch} 标记最近使用，避免被空闲回收；</li>
 *   <li>{@link #isPending} 区分「正在加载」与「确定缺失」。</li>
 * </ul>
 */
public final class AssetHandle<K, V> {

    private final AssetCache<K, V> cache;
    private final K key;

    AssetHandle(AssetCache<K, V> cache, K key) {
        this.cache = cache;
        this.key = key;
    }

    /** 返回就绪资源；未命中时触发后台加载并返回 null。 */
    public V get() {
        return cache.get(key);
    }

    /** 标记最近使用，避免被空闲回收。 */
    public void touch() {
        cache.touch(key);
    }

    /** 资源是否正在加载 / 待加载（区别于「确定缺失」）。 */
    public boolean isPending() {
        return cache.isPending(key);
    }

    /** 底层缓存 key（诊断用）。 */
    public K key() {
        return key;
    }
}
