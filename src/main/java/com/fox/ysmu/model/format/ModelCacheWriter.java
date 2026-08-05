package com.fox.ysmu.model.format;

import static com.fox.ysmu.model.ServerModelManager.CACHE_SERVER;
import static com.fox.ysmu.model.ServerModelManager.OPEN_YSM_SERVER_KEY;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;

import com.fox.ysmu.Config;
import com.fox.ysmu.data.EncryptTools;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.resource.YSMBinarySerializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.util.Md5Utils;

import rip.ysm.security.YSMByteBuf;
import rip.ysm.security.YsmCrypt;

final class ModelCacheWriter {

    private ModelCacheWriter() {}

    static ServerModelInfo write(ModelData data) throws Exception {
        byte[] dataBytes = EncryptTools.assembleEncryptModels(data);
        data.setMd5(Md5Utils.md5Hex(dataBytes).toUpperCase(Locale.US));
        atomicWrite(CACHE_SERVER.resolve(data.getInfo().getMd5()), dataBytes);
        return data.getInfo();
    }

    static OpenYsmSyncInfo writeOpenYsm(RawYsmModel raw, String modelId) throws Exception {
        if (OPEN_YSM_SERVER_KEY == null || OPEN_YSM_SERVER_KEY.length != 56) {
            throw new IllegalStateException("OpenYSM server key is not initialized");
        }

        String hashSource = getHashSource(raw, modelId);
        long[] hashes = YsmCrypt.calculateModelHashes(hashSource, OPEN_YSM_SERVER_KEY);
        String cacheFileName = String.format(Locale.US, "%016x%016x", hashes[0], hashes[1]);

        byte[] clearBytes = serializeForOpenYsmSync(raw);

        byte[] cacheBytes = YsmCrypt.encryptServerCache(clearBytes, OPEN_YSM_SERVER_KEY, hashes[0], hashes[1]);
        atomicWrite(CACHE_SERVER.resolve(cacheFileName), cacheBytes);
        return new OpenYsmSyncInfo(modelId, cacheFileName, hashes[0], hashes[1], OpenYsmFormat.OPEN_YSM_SYNC_FORMAT, false);
    }

    /**
     * 原子写：先写唯一临时文件再原子移动。库中可能存在内容相同的重复 .ysm，并行
     * 构建时多个任务会写同一个缓存文件；encryptServerCache 带随机 padding，普通覆盖
     * 写会撕裂文件，导致客户端读不回。临时文件唯一 + 原子移动保证最终文件必为某次
     * 完整写入的结果。
     */
    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(
            target.getFileName() + "." + Long.toHexString(System.nanoTime()) + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static byte[] serializeForOpenYsmSync(RawYsmModel raw) {
        Map<String, RawYsmModel.RawDataFile> originalSoundFiles = raw.soundFiles;
        if (!Config.ACCEPT_SOUND_FX && raw.soundFiles != null && !raw.soundFiles.isEmpty()) {
            raw.soundFiles = new java.util.LinkedHashMap<>();
        }
        try (YSMByteBuf serialized = YSMBinarySerializer.serialize(raw, OpenYsmFormat.OPEN_YSM_SYNC_FORMAT, true)) {
            return serialized.toArray();
        } finally {
            raw.soundFiles = originalSoundFiles;
        }
    }

    private static String getHashSource(RawYsmModel raw, String modelId) {
        if (raw != null && raw.properties != null && raw.properties.sha256 != null
            && !raw.properties.sha256.isEmpty()) {
            return raw.properties.sha256;
        }
        if (raw != null && raw.modelId != null && !raw.modelId.isEmpty()) {
            return raw.modelId;
        }
        return modelId;
    }
}
