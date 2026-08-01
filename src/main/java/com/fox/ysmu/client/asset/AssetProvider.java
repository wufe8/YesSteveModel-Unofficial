package com.fox.ysmu.client.asset;

/**
 * 一种资源类型的「如何加载 / 应用 / 释放」策略，由 {@link AssetCache} 调度驱动。
 *
 * <p>实现类只描述资源本身的读写方式（从哪读、写到哪、怎么释放），
 * 不关心线程调度、状态机与回收——这些都由缓存核心统一处理：
 * <ul>
 *   <li>{@link #load} 在后台线程池执行（重活：解密、JSON 解析）。</li>
 *   <li>{@link #apply} / {@link #release} 在主线程执行（GL 操作、全局缓存写入）。</li>
 * </ul>
 */
public interface AssetProvider<K, V> {

    /**
     * 后台线程：从持久源重建资源（解密客户端缓存 + 解析）。
     *
     * @return 就绪的资源对象；返回 {@code null} 表示失败（会进入失败冷却，稍后重试）。
     */
    V load(K key) throws Exception;

    /** 主线程：把后台产物应用到真实目标（写入 GeckoLibCache / 上传 GL 纹理）。 */
    void apply(K key, V value);

    /** 主线程：释放资源。 */
    void release(K key, V value, ReleaseMode mode);

    /** 该类资源默认的释放深度（GPU_ONLY 保留堆字节 / DROP_HEAP 连堆一起丢）。 */
    ReleaseMode defaultReleaseMode();

    /**
     * 估算资源体积（字节），供 {@link AssetCache#evict} 的容量上限使用。
     * 返回 0 表示不计入容量统计。
     */
    long weight(K key, V value);
}
