package com.fox.ysmu.client.texture;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

class GuiDynamicTextureReleaseTest {

    private static final ResourceLocation ICON = new ResourceLocation("dynamic/ysmu_pack_test_1");

    private static ITextureObject texture() {
        return new ITextureObject() {
            @Override
            public void loadTexture(IResourceManager resources) {}

            @Override
            public int getGlTextureId() {
                throw new AssertionError("map teardown must not allocate or inspect a GL texture");
            }
        };
    }

    @Test
    void releasesOwnedMappingAndIsIdempotent() {
        Map<ResourceLocation, ITextureObject> textures = new HashMap<>();
        ITextureObject owned = texture();
        textures.put(ICON, owned);
        AtomicInteger deleted = new AtomicInteger();
        assertTrue(GuiDynamicTextureRelease.releaseOwned(textures, ICON, owned, t -> {
            assertSame(owned, t);
            deleted.incrementAndGet();
        }));
        assertFalse(textures.containsKey(ICON), "pixel array must no longer be rooted in TextureManager");
        assertFalse(GuiDynamicTextureRelease.releaseOwned(textures, ICON, owned, t -> deleted.incrementAndGet()));
        assertEquals(1, deleted.get());
    }

    @Test
    void staleOwnerCannotDeleteReplacementOrSharedTexture() {
        Map<ResourceLocation, ITextureObject> textures = new HashMap<>();
        ITextureObject owned = texture();
        ITextureObject replacement = texture();
        textures.put(ICON, replacement);
        assertFalse(GuiDynamicTextureRelease.releaseOwned(textures, ICON, owned, t -> fail("not owned")));
        assertSame(replacement, textures.get(ICON));
        assertFalse(GuiDynamicTextureRelease.releaseOwned(textures, ICON, null, t -> fail("shared icon")));
        assertSame(replacement, textures.get(ICON));
    }

    @Test
    void repeatedPageIconsDoNotAccumulateMappings() {
        Map<ResourceLocation, ITextureObject> textures = new HashMap<>();
        ResourceLocation sharedId = new ResourceLocation("ysmu", "texture/default_pack_icon.png");
        ITextureObject shared = texture();
        textures.put(sharedId, shared);
        for (int page = 0; page < 100; page++) {
            ResourceLocation id = new ResourceLocation("dynamic/ysmu_pack_test_" + page);
            ITextureObject owned = texture();
            textures.put(id, owned);
            assertTrue(GuiDynamicTextureRelease.releaseOwned(textures, id, owned, t -> {}));
        }
        assertEquals(1, textures.size());
        assertSame(shared, textures.get(sharedId));
    }
}
