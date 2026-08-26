package com.fox.ysmu.client.asset;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.asset.provider.AnimationProvider;
import com.fox.ysmu.client.asset.provider.GeoModelProvider;

import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * 模型资源生命周期门面：统一管理几何/动画的「加载 → 释放 → 重加载」。
 *
 * <p>业务代码只通过 {@link #geo}/{@link #anim} 拿到 {@link AssetHandle} 访问资源，
 * 不感知内部状态机与线程调度。生命周期编排：
 * <ul>
 *   <li>首次同步注册：{@code applyPreParsed} 调 {@link #registerGeo}/{@link #registerAnim}
 *       登记为 READY（跳过加载阶段）；</li>
 *   <li>闲置回收：主线程 {@link #tick}（空闲超时）释放 READY 资源回 ABSENT；</li>
 *   <li>重加载：之后任何 {@code AssetHandle.get()} 命中 ABSENT 时，自动在后台
 *       重新解密+解析（{@code ThreadTools.THREAD_POOL}），主线程写回 GeckoLibCache——
 *       不再阻塞渲染线程，解决大型模型库的重载卡顿。</li>
 * </ul>
 *
 * <p>纹理（字节常驻 + GPU 懒上传 + GPU 释放）生命周期极简，不纳入本状态机；
 * 其空闲 GPU 释放由 {@link #tick} 一并驱动（见
 * {@link ClientModelManager#unloadIdleTextures(long)}）。
 */
public final class AssetManager {

    /** 失败重试冷却：损坏/缺失资源不每帧重试。 */
    private static final long FAILED_RETRY_MS = 2000L;
    /** 几何/动画空闲多久（ms）后释放。与旧纹理/动画卸载窗口一致。 */
    private static final long IDLE_UNLOAD_MS = 30_000L;
    /** 可选容量上限（字节），0 = 不启用（只按空闲超时回收）。 */
    private static final long MAX_WEIGHT = 0L;

    /** 几何缓存：key 为 sub-model id（如 "ysmu:model_id/main"、"ysmu:model_id/arm"）。 */
    private static final AssetCache<ResourceLocation, GeoModel> GEO =
        new AssetCache<>(new GeoModelProvider(), FAILED_RETRY_MS);
    /** 动画缓存：key 为 mainId（如 "ysmu:model_id/main"）。 */
    private static final AssetCache<ResourceLocation, AnimationFile> ANIM =
        new AssetCache<>(new AnimationProvider(), FAILED_RETRY_MS);

    private AssetManager() {}

    /** 获取几何资源句柄（key 为 sub-model id）。 */
    public static AssetHandle<ResourceLocation, GeoModel> geo(ResourceLocation geoId) {
        return new AssetHandle<>(GEO, geoId);
    }

    /** 获取动画资源句柄（key 为 mainId）。 */
    public static AssetHandle<ResourceLocation, AnimationFile> anim(ResourceLocation mainId) {
        return new AssetHandle<>(ANIM, mainId);
    }

    /** 主线程登记已就绪的几何（首次同步注册时调用）。 */
    public static void registerGeo(ResourceLocation geoId, GeoModel geo) {
        GEO.register(geoId, geo);
    }

    /** 主线程登记已就绪的动画（首次同步注册时调用）。 */
    public static void registerAnim(ResourceLocation mainId, AnimationFile anim) {
        ANIM.register(mainId, anim);
    }

    /**
     * 主线程周期回收：空闲几何/动画（框架核心）+ 空闲 GPU 纹理。
     * 由 {@link ClientModelManager#unloadUnusedCaches()} 触发（客户端 tick，每 60s）。
     * 当模型预览 GUI 打开或刚关闭（宽限期内），跳过所有资源回收。
     */
    public static void tick() {
        // 内置兜底模型（default）必须常驻：若被闲置释放，GUI 预览 fallback 到它时
        // GeckoLib 的 getModel() 会抛 GeoModelException "Could not find model" 崩溃。
        // 每次回收前 touch 刷新其活跃时间，evict 便不会释放它。
        GEO.touch(com.fox.ysmu.client.model.CustomPlayerModel.DEFAULT_MAIN_MODEL);
        ANIM.touch(com.fox.ysmu.client.model.CustomPlayerModel.DEFAULT_MAIN_MODEL);

        // Suppress all resource eviction while the preview GUI is open or within
        // the post-close grace period.
        if (com.fox.ysmu.client.ClientModelManager.isIdleEvictionSuppressed()) {
            return;
        }

        long now = System.currentTimeMillis();
        GEO.evict(now, IDLE_UNLOAD_MS, MAX_WEIGHT);
        ANIM.evict(now, IDLE_UNLOAD_MS, MAX_WEIGHT);
        ClientModelManager.unloadIdleTextures(now);
    }

    /** 释放全部资源并清空状态（断线 / /ysm reload 时调用）。 */
    public static void clearAll() {
        GEO.clear();
        ANIM.clear();
    }

    /** 立即释放主模型（main 几何 + main 动画）的懒资源（模型切换后快速卸载用，主线程调用）。
     *  未加载/加载中为 no-op；arm 几何不在主模型 key 下，仍由闲置扫描回收。 */
    public static void release(ResourceLocation mainId) {
        GEO.release(mainId);
        ANIM.release(mainId);
    }

    /** 当前登记的几何条目数（诊断用）。 */
    public static int geoSize() {
        return GEO.size();
    }

    /** 当前登记的动画条目数（诊断用）。 */
    public static int animSize() {
        return ANIM.size();
    }
}
