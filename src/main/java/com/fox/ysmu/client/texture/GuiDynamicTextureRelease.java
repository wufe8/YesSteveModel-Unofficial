package com.fox.ysmu.client.texture;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.ysmu;

import cpw.mods.fml.relauncher.ReflectionHelper;

/** Main-thread teardown for dynamic textures owned by GUI buttons. */
public final class GuiDynamicTextureRelease {

    private static Field textureObjects;
    /** Field lookup is attempted once; a failure is reported once instead of per button. */
    private static boolean reportedFailure;

    private GuiDynamicTextureRelease() {}

    @SuppressWarnings("unchecked")
    public static void release(TextureManager manager, ResourceLocation id, DynamicTexture owned) {
        if (id == null || owned == null || manager == null) return;
        try {
            if (textureObjects == null) {
                textureObjects = ReflectionHelper.findField(TextureManager.class, "mapTextureObjects", "field_110585_a");
            }
            releaseOwned((Map<ResourceLocation, ITextureObject>) textureObjects.get(manager), id, owned,
                texture -> ((DynamicTexture) texture).deleteGlTexture());
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Teardown runs from initGui()/onGuiClosed(): throwing here would abort the screen
            // rebuild and leave it half-built, while a failed release only leaks one texture
            // (freed on the next resource reload / restart). Report once, then keep going.
            if (!reportedFailure) {
                reportedFailure = true;
                ysmu.LOG.warn("Cannot release owned YSM GUI texture {} — it stays allocated until restart", id, e);
            }
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
