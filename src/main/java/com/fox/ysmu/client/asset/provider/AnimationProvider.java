package com.fox.ysmu.client.asset.provider;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.asset.AssetProvider;
import com.fox.ysmu.client.asset.ReleaseMode;

import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * 动画资源策略：卸载后从加密客户端缓存重新解密+解析（后台），主线程写回 GeckoLibCache。
 *
 * <p>释放深度为 {@link ReleaseMode#DROP_HEAP}：动画的 GeckoLib KeyFrame 图是
 * 大型模型库堆内存的最大头（VisualVM 实测约占 78%），正是「释放 → 后台重载」
 * 闭环要管理的核心目标。
 */
public final class AnimationProvider implements AssetProvider<ResourceLocation, AnimationFile> {

    @Override
    public AnimationFile load(ResourceLocation mainId) {
        return ClientModelManager.parseAnimationFromCache(mainId);
    }

    @Override
    public void apply(ResourceLocation mainId, AnimationFile anim) {
        GeckoLibCache.getInstance().getAnimations().put(mainId, anim);
        // Virtual bones are injected from the animation when the geo is (re)built.
        // If the animation reloaded after the geo, the previous injection ran with a
        // null animation; forget the flag so it re-runs now that the anim is present.
        // injectVirtualBones is idempotent, so this is safe for already-injected models.
        com.fox.ysmu.client.model.CustomPlayerModel.clearInjectedCache(mainId);
    }

    @Override
    public void release(ResourceLocation mainId, AnimationFile anim, ReleaseMode mode) {
        if (mode != ReleaseMode.GPU_ONLY) {
            GeckoLibCache.getInstance().getAnimations().remove(mainId);
        }
    }

    @Override
    public ReleaseMode defaultReleaseMode() {
        return ReleaseMode.DROP_HEAP;
    }

    @Override
    public long weight(ResourceLocation mainId, AnimationFile anim) {
        if (anim == null || anim.animations == null) {
            return 0;
        }
        // 粗略按动画数量估算体积（容量上限统计用）。
        return anim.animations.size() * 4096L;
    }
}
