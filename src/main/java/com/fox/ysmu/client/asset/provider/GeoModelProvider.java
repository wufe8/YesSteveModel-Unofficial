package com.fox.ysmu.client.asset.provider;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.asset.AssetProvider;
import com.fox.ysmu.client.asset.ReleaseMode;

import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * 几何资源策略：卸载后从加密客户端缓存重新解密+解析（后台），主线程写回 GeckoLibCache。
 *
 * <p>释放深度为 {@link ReleaseMode#DROP_HEAP}：几何 JSON 体积小、重解析快，
 * 卸载后通过本缓存走「释放 → 后台重载」闭环。
 */
public final class GeoModelProvider implements AssetProvider<ResourceLocation, GeoModel> {

    @Override
    public GeoModel load(ResourceLocation geoId) {
        return ClientModelManager.parseSingleGeoFromCache(geoId);
    }

    @Override
    public void apply(ResourceLocation geoId, GeoModel geo) {
        GeckoLibCache.getInstance().getGeoModels().put(geoId, geo);
    }

    @Override
    public void release(ResourceLocation geoId, GeoModel geo, ReleaseMode mode) {
        if (mode != ReleaseMode.GPU_ONLY) {
            GeckoLibCache.getInstance().getGeoModels().remove(geoId);
            // The next getModel() builds a fresh AnimationProcessor bone list from the
            // reloaded GeoModel (dropping previously injected VirtualBones); forget the
            // injection flag so they are re-injected from the (reloaded) animation.
            com.fox.ysmu.client.model.CustomPlayerModel.clearInjectedCache(geoId);
        }
    }

    @Override
    public ReleaseMode defaultReleaseMode() {
        return ReleaseMode.DROP_HEAP;
    }

    @Override
    public long weight(ResourceLocation geoId, GeoModel geo) {
        if (geo == null || geo.topLevelBones == null) {
            return 0;
        }
        // 粗略按顶层骨骼数估算体积（容量上限统计用）。
        return geo.topLevelBones.size() * 1024L;
    }
}
