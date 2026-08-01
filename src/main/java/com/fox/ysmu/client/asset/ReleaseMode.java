package com.fox.ysmu.client.asset;

/**
 * 资源释放深度。
 *
 * <p>由 {@link AssetProvider#defaultReleaseMode} 决定某类资源默认释放到什么程度：
 * 纹理通常只释放 GPU 副本（字节留在堆里以便快速重传），几何/动画则连堆一起释放
 * （重加载时从持久源重新解析，是容量优化的主要目标）。
 */
public enum ReleaseMode {

    /** 只释放 GPU 副本（如纹理对象），堆内存字节保留。 */
    GPU_ONLY,

    /** 连堆内存一起释放（如 GeckoLib KeyFrame 图 / GeoBone 层级），重加载时重建。 */
    DROP_HEAP
}
