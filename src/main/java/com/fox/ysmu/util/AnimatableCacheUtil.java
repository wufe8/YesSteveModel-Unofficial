package com.fox.ysmu.util;

import java.util.concurrent.TimeUnit;

import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import software.bernie.geckolib3.core.IAnimatable;

@SideOnly(Side.CLIENT)
public final class AnimatableCacheUtil {

    public static final Cache<ResourceLocation, IAnimatable> ANIMATABLE_CACHE = CacheBuilder.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .<ResourceLocation, IAnimatable>removalListener(notification -> {
            IAnimatable animatable = notification.getValue();
            if (animatable != null) animatable.getFactory().dispose();
        })
        .build();
    public static final Cache<ResourceLocation, IAnimatable> TEXTURE_GUI_CACHE = CacheBuilder.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .<ResourceLocation, IAnimatable>removalListener(notification -> {
            IAnimatable animatable = notification.getValue();
            if (animatable != null) animatable.getFactory().dispose();
        })
        .build();
    public static final Cache<ResourceLocation, Entity> ENTITIES_CACHE = CacheBuilder.newBuilder()
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .build();
    public static void clear() {
        ANIMATABLE_CACHE.invalidateAll();
        TEXTURE_GUI_CACHE.invalidateAll();
        ENTITIES_CACHE.invalidateAll();
        ANIMATABLE_CACHE.cleanUp();
        TEXTURE_GUI_CACHE.cleanUp();
    }

}
