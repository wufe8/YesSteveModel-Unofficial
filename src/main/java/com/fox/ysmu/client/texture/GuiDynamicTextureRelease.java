package com.fox.ysmu.client.texture;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** Main-thread teardown for dynamic textures owned by GUI buttons. */
public final class GuiDynamicTextureRelease {

    private static Field textureObjects;

    private GuiDynamicTextureRelease() {}

    @SuppressWarnings("unchecked")
    public static void release(TextureManager manager, ResourceLocation id, DynamicTexture owned) {
        if (id == null || owned == null) return;
        try {
            if (textureObjects == null) {
                textureObjects = ReflectionHelper.findField(TextureManager.class, "mapTextureObjects", "field_110585_a");
            }
            releaseOwned((Map<ResourceLocation, ITextureObject>) textureObjects.get(manager), id, owned,
                texture -> ((DynamicTexture) texture).deleteGlTexture());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot release owned GUI texture " + id, e);
        }
    }

    /** Identity check prevents a stale button from deleting a replacement or shared texture. */
    static boolean releaseOwned(Map<ResourceLocation, ITextureObject> textures, ResourceLocation id,
        ITextureObject owned, Consumer<ITextureObject> delete) {
        if (owned == null || textures.get(id) != owned) return false;
        delete.accept(owned);
        textures.remove(id, owned);
        return true;
    }
}
