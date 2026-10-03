package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.model.PreParsedModelBundle;
import com.fox.ysmu.util.FrameApplyQueue;
import com.fox.ysmu.util.ModelIdUtil;

import net.minecraft.util.ResourceLocation;

class ClientModelApplyLifecycleTest {

    private static final ResourceLocation MODEL = new ResourceLocation("ysmu", "cache_apply_test");
    private static final ResourceLocation MAIN = ModelIdUtil.getMainId(MODEL);

    @Test
    void cachePathIsPublishedWithTheModelRatherThanByTheProducer() throws Exception {
        AtomicLong generation = field("APPLY_GENERATION");
        Field cleared = declared("clearedApplyGeneration");
        long previous = generation.get();
        long previousCleared = cleared.getLong(null);
        try {
            generation.set(100);
            cleared.setLong(null, 100);
            PreParsedModelBundle bundle = bundle(100);
            ClientModelManager.scheduleApply(bundle);
            assertFalse(cachePaths().containsKey(MAIN), "producer must not publish a lazy cache mapping");
            assertFalse(ClientModelManager.MODELS.containsKey(MODEL));

            ClientModelManager.processPendingAppliesForFrame();
            assertEquals("0/current-cache", cachePaths().get(MAIN));
            assertTrue(ClientModelManager.MODELS.containsKey(MODEL));
            Set<ResourceLocation> formats = field("OPENYSM_CACHE_FORMAT");
            assertTrue(formats.contains(MAIN));
        } finally {
            cleanup();
            generation.set(previous);
            cleared.setLong(null, previousCleared);
        }
    }

    @Test
    void applyWaitsForClearAndRejectsWorkSupersededWhileWaiting() throws Exception {
        AtomicLong generation = field("APPLY_GENERATION");
        Field cleared = declared("clearedApplyGeneration");
        long previous = generation.get();
        long previousCleared = cleared.getLong(null);
        try {
            generation.set(101);
            cleared.setLong(null, 100);
            ClientModelManager.scheduleApply(bundle(101));
            ClientModelManager.processPendingAppliesForFrame();
            assertFalse(ClientModelManager.isApplyPipelineDrained(), "pending clear must hold new apply work");
            assertFalse(cachePaths().containsKey(MAIN));
            assertFalse(ClientModelManager.MODELS.containsKey(MODEL));

            generation.set(102);
            cleared.setLong(null, 102);
            ClientModelManager.processPendingAppliesForFrame();
            assertTrue(ClientModelManager.isApplyPipelineDrained());
            assertFalse(cachePaths().containsKey(MAIN), "old work must not leave a lazy path behind");
            assertFalse(ClientModelManager.MODELS.containsKey(MODEL), "old work must not revive a deleted model");
        } finally {
            cleanup();
            generation.set(previous);
            cleared.setLong(null, previousCleared);
        }
    }

    @Test
    void directApplyAlsoRejectsOldGenerationBeforePublishing() throws Exception {
        long generation = ClientModelManager.currentApplyGeneration();
        ClientModelManager.applyPreParsed(bundle(generation - 1));
        assertFalse(cachePaths().containsKey(MAIN));
        assertFalse(ClientModelManager.MODELS.containsKey(MODEL));
    }

    private static PreParsedModelBundle bundle(long generation) {
        PreParsedModelBundle bundle = new PreParsedModelBundle(MODEL);
        bundle.applyGeneration = generation;
        bundle.cachePath = "0/current-cache";
        bundle.openYsmCache = true;
        bundle.localRegistration = true;
        return bundle;
    }

    private static Map<ResourceLocation, String> cachePaths() throws Exception {
        return field("CACHED_MODEL_MD5");
    }

    private static void cleanup() throws Exception {
        FrameApplyQueue<PreParsedModelBundle> queue = field("APPLY_QUEUE");
        queue.clear();
        cachePaths().remove(MAIN);
        Set<ResourceLocation> formats = field("OPENYSM_CACHE_FORMAT");
        formats.remove(MAIN);
        ClientModelManager.MODELS.remove(MODEL);
        ClientModelManager.MODEL_STATS.remove(MAIN);
        ClientModelManager.MODEL_APPLY_TIME.remove(MAIN);
    }

    private static Field declared(String name) throws Exception {
        Field field = ClientModelManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(String name) throws Exception {
        return (T) declared(name).get(null);
    }
}
