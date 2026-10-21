package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.model.format.OpenYsmSyncInfo;
import com.fox.ysmu.network.sync.OpenYsmModelSyncServer;
import com.fox.ysmu.util.ModelIdUtil;

import net.minecraft.util.ResourceLocation;

/**
 * Regression tests for paths investigated alongside "model invisible" issues #3/#4.
 * These cases do not establish the root cause of either external report.
 *
 * <p><b>Default identity path</b>: the "is this the default model?" checks compared the <i>raw</i>
 * configured name against the <i>encoded</i> internal id, so as soon as the configured name
 * was not a plain lower-case name (a space, an upper-case letter, a non-ASCII character) the
 * comparison stopped matching. Two things then broke:
 * <ul>
 *   <li>the <b>builtin fallback</b> model ({@code builtin/default}) stopped being pinned
 *       against idle eviction — and it is the only model without an encrypted client-cache
 *       entry, so once evicted it could never be restored: the player stayed invisible
 *       forever;</li>
 *   <li>the configured default model was no longer sent first in the sync index.</li>
 * </ul>
 *
 * <p><b>Apply gate path</b>: a superseded prepare must not open the current gate;
 * a failed clear must not leave the current gate permanently closed. The scheduler and
 * full network handshake are not exercised by these unit tests.
 */
class DefaultModelIdentityTest {

    private static void withDefaultModelId(String id, Runnable body) {
        String previous = Config.DEFAULT_MODEL_ID;
        try {
            Config.DEFAULT_MODEL_ID = id;
            body.run();
        } finally {
            Config.DEFAULT_MODEL_ID = previous;
        }
    }

    @Test
    void safeNameKeepsItsPlainIdAndStillMatches() {
        withDefaultModelId("default", () -> {
            assertEquals("default", Config.getDefaultModelInternalId());
            assertTrue(ClientModelManager.isDefaultModelId(new ResourceLocation("ysmu", "default/main")));
            assertTrue(ClientModelManager.isDefaultModelId(new ResourceLocation("ysmu", "default/arm")));
            assertTrue(ClientModelManager.isModelPriority(new ResourceLocation("ysmu", "default/main")));
        });
    }

    @Test
    void builtinFallbackStaysPinnedEvenWhenTheConfiguredNameDiffers() {
        // The builtin fallback is loaded from disk and has NO encrypted client cache:
        // once it is evicted it can never be restored. It must stay pinned no matter how
        // the user names their default model (issue #4).
        withDefaultModelId("my model", () -> {
            assertTrue(
                ClientModelManager.isDefaultModelId(new ResourceLocation("ysmu", "default/main")),
                "the builtin fallback model must always be pinned against idle eviction");
            assertTrue(
                ClientModelManager.isDefaultModelId(new ResourceLocation("ysmu", "default/arm")));
        });
    }

    @Test
    void nameWithSpaceIsEncodedAndStillMatchesTheRegisteredId() {
        withDefaultModelId("my model", () -> {
            String internal = Config.getDefaultModelInternalId();
            // The configured name is not a safe id → the registry stores the encoded one.
            assertTrue(internal.startsWith("_name_"), internal);
            assertNotEquals("my model", internal);
            assertEquals(ModelIdUtil.getInternalModelId("my model"), internal);

            // The regression: every "is this the default model?" check must recognise it.
            assertTrue(ClientModelManager.isDefaultModelId(new ResourceLocation("ysmu", internal + "/main")),
                "the configured default model must stay pinned even when its name is encoded");
            assertTrue(ClientModelManager.isDefaultModelId(new ResourceLocation("ysmu", internal + "/arm")));
            assertTrue(ClientModelManager.isModelPriority(new ResourceLocation("ysmu", internal + "/main")),
                "the default model must stay first in the apply queue");

            // The server-side "send the default model first" check must agree.
            OpenYsmSyncInfo entry = new OpenYsmSyncInfo(internal, "cache", 1L, 2L, 32, false);
            assertTrue(OpenYsmModelSyncServer.isDefaultModelEntry(entry));
            OpenYsmSyncInfo other = new OpenYsmSyncInfo("wine_fox/15_kluonoa", "cache", 3L, 4L, 32, false);
            assertFalse(OpenYsmModelSyncServer.isDefaultModelEntry(other));
        });
    }

    @Test
    void nonAsciiNameIsEncodedAndStillMatches() {
        withDefaultModelId("我的模型", () -> {
            String internal = Config.getDefaultModelInternalId();
            assertNotEquals("我的模型", internal);
            assertTrue(ClientModelManager.isDefaultModelId(new ResourceLocation("ysmu", internal + "/main")));
        });
    }

    @Test
    void rendererFallbackPointsAtTheBuiltinFallbackModel() {
        // The render fallback is the *builtin* model (loaded from disk, always present),
        // not the configured default — those are two different roles.
        withDefaultModelId("my model", () -> {
            assertEquals(
                ModelIdUtil.getMainId(new ResourceLocation("ysmu", "default")),
                CustomPlayerModel.getDefaultMainModel());
            assertEquals(
                new ResourceLocation("ysmu", "default/default.png"),
                CustomPlayerModel.getDefaultTexture());
        });
        withDefaultModelId("default", () -> {
            assertEquals(
                ModelIdUtil.getMainId(new ResourceLocation("ysmu", "default")),
                CustomPlayerModel.getDefaultMainModel());
        });
    }

    @Test
    void builtinFallbackTextureDoesNotUseConfiguredPlayerTexture() {
        String previousTexture = Config.DEFAULT_MODEL_TEXTURE;
        try {
            Config.DEFAULT_MODEL_TEXTURE = "skin.png";
            withDefaultModelId("my model", () -> {
                ResourceLocation expected = new ResourceLocation("ysmu", "default/default.png");
                assertEquals(expected, CustomPlayerModel.getDefaultTexture());
                assertEquals(
                    ModelIdUtil.getSubModelId(
                        new ResourceLocation("ysmu", Config.getDefaultModelInternalId()), "skin.png"),
                    new ExtendedModelInfo(null).getSelectTexture(),
                    "the configured player model must still use its configured texture");
                com.fox.ysmu.client.entity.CustomPlayerEntity entity =
                    new com.fox.ysmu.client.entity.CustomPlayerEntity();
                assertEquals(expected, entity.getTexture());
                entity.setTexture(new ResourceLocation("ysmu", "custom/skin.png"));
                assertEquals(new ResourceLocation("ysmu", "custom/skin.png"), entity.getTexture());
                entity.setTexture(null);
                assertEquals(expected, entity.getTexture());
            });
        } finally {
            Config.DEFAULT_MODEL_TEXTURE = previousTexture;
        }
    }

    @Test
    void playerStateStartsOnTheSameIdTheRegistryUses() {
        withDefaultModelId("my model", () -> {
            String internal = Config.getDefaultModelInternalId();
            // The player's default model id and the registry's default model id must be the
            // same string, otherwise the fallback never finds the model it points at.
            assertEquals(internal, ModelIdUtil.getModelIdFromSubId(
                new ExtendedModelInfo(null).getModelId()).getResourcePath());
        });
    }

    @Test
    void supersededPrepareDoesNotOpenTheGateForTheWrongGeneration() throws Exception {
        AtomicLong generation = generationField();
        Field cleared = declaredField("clearedApplyGeneration");
        long previousGeneration = generation.get();
        long previousCleared = cleared.getLong(null);
        try {
            generation.set(500);
            cleared.setLong(null, 499);
            // A stale generation must not touch the gate — the newer one owns the caches.
            assertFalse(ClientModelManager.runPrepareClear(499));
            assertEquals(499L, cleared.getLong(null));

            // The current generation clears and opens the gate, so the apply pipeline can
            // register models again (before the fix an exception here left the gate closed
            // forever → zero models, no log).
            assertTrue(ClientModelManager.runPrepareClear(500));
            assertEquals(500L, cleared.getLong(null));
            assertTrue(ClientModelManager.isApplyGenerationReady(500));
        } finally {
            generation.set(previousGeneration);
            cleared.setLong(null, previousCleared);
        }
    }

    private static AtomicLong generationField() throws Exception {
        Field f = declaredField("APPLY_GENERATION");
        return (AtomicLong) f.get(null);
    }

    private static Field declaredField(String name) throws Exception {
        Field f = ClientModelManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
