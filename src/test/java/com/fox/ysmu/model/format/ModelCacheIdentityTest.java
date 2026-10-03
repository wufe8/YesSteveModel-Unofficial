package com.fox.ysmu.model.format;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fox.ysmu.Config;
import com.fox.ysmu.model.resource.YSMBinaryDeserializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;

import rip.ysm.security.YsmCrypt;

class ModelCacheIdentityTest {

    @TempDir
    Path cacheDir;

    @Test
    void soundSettingChangesCacheIdentityAndSerializedSounds() throws Exception {
        boolean saved = Config.ACCEPT_SOUND_FX;
        byte[] key = new byte[56];
        RawYsmModel raw = new RawYsmModel();
        raw.properties.sha256 = "unchanged-source";
        raw.soundFiles.put("sample", new RawYsmModel.RawDataFile("sound-hash", new byte[] { 1, 2, 3 }));
        try {
            Config.ACCEPT_SOUND_FX = false;
            OpenYsmSyncInfo muted = ModelCacheWriter.writeOpenYsm(raw, "sample", cacheDir, key);
            assertTrue(readModel(muted, key).soundFiles.isEmpty());
            assertEquals(1, raw.soundFiles.size(), "serialization must restore the original model");

            Config.ACCEPT_SOUND_FX = true;
            OpenYsmSyncInfo enabled = ModelCacheWriter.writeOpenYsm(raw, "sample", cacheDir, key);
            assertNotEquals(muted.getCacheFileName(), enabled.getCacheFileName(),
                "a changed sync payload must miss an existing client's cache");
            assertArrayEquals(new byte[] { 1, 2, 3 }, readModel(enabled, key).soundFiles.get("sample").data);
            assertEquals(enabled.getCacheFileName(),
                ModelCacheWriter.writeOpenYsm(raw, "sample", cacheDir, key).getCacheFileName(),
                "random encryption padding must not change the cache identity");
        } finally {
            Config.ACCEPT_SOUND_FX = saved;
        }
    }

    @Test
    void sidecarProfileInvalidatesBothSuccessAndFailureEntries() throws Exception {
        boolean saved = Config.ACCEPT_SOUND_FX;
        byte[] key = new byte[56];
        try {
            Config.ACCEPT_SOUND_FX = false;
            ModelIndexCache.init(cacheDir, key);
            ModelIndexCache.mark("success", entry(true));
            ModelIndexCache.mark("failure", entry(false));
            ModelIndexCache.commit(cacheDir);
            ModelIndexCache.init(cacheDir, key);
            assertNotNull(ModelIndexCache.get("success"));
            assertNotNull(ModelIndexCache.get("failure"));

            Config.ACCEPT_SOUND_FX = true;
            ModelIndexCache.init(cacheDir, key);
            assertNull(ModelIndexCache.get("success"));
            assertNull(ModelIndexCache.get("failure"), "a conversion change must retry old failures too");
        } finally {
            Config.ACCEPT_SOUND_FX = saved;
            ModelIndexCache.init(cacheDir, key);
        }
    }

    @Test
    void oldSidecarWithoutProfileIsRebuilt() throws Exception {
        byte[] key = new byte[56];
        String old = "{\"version\":1,\"serverKeyFp\":\"0000000000000000\",\"models\":{"
            + "\"old\":{\"kind\":\"folder\",\"srcFp\":\"source\",\"hash1\":\"1\","
            + "\"hash2\":\"2\",\"cacheFile\":\"old-file\",\"ok\":true}}}";
        Files.write(cacheDir.resolve("model_index.json"), old.getBytes(StandardCharsets.UTF_8));
        ModelIndexCache.init(cacheDir, key);
        assertNull(ModelIndexCache.get("old"));
    }

    private RawYsmModel readModel(OpenYsmSyncInfo info, byte[] key) throws Exception {
        byte[] bytes = YsmCrypt.read(Files.readAllBytes(cacheDir.resolve(info.getCacheFileName())), key);
        try (YSMBinaryDeserializer in = new YSMBinaryDeserializer(bytes, OpenYsmFormat.OPEN_YSM_SYNC_FORMAT)) {
            return in.deserialize();
        }
    }

    private static ModelIndexCache.Entry entry(boolean ok) {
        ModelIndexCache.Entry entry = new ModelIndexCache.Entry();
        entry.kind = "folder";
        entry.srcFp = "source";
        entry.hash1 = "1";
        entry.hash2 = "2";
        entry.cacheFile = "test-cache";
        entry.ok = ok;
        return entry;
    }
}
