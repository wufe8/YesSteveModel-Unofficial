package com.fox.ysmu.model.format;

import static com.fox.ysmu.model.ServerModelManager.CACHE_NAME_INFO;
import static com.fox.ysmu.model.ServerModelManager.OPEN_YSM_SYNC_INFO;
import static com.fox.ysmu.model.ServerModelManager.RAW_MODEL_INFO;
import static com.fox.ysmu.model.ServerModelManager.removeExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.jetbrains.annotations.NotNull;

import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.YSMBinaryDeserializer;
import com.fox.ysmu.model.resource.YSMFolderDeserializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import rip.ysm.security.YsmCrypt;

public final class OpenYsmFormat {

    private static final byte[] OPEN_YSM_PREFIX = new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'Y', 'S', 'G', 'P' };

    private OpenYsmFormat() {}

    public static void cacheAllModels(Path rootPath) {
        if (rootPath == null || !Files.isDirectory(rootPath)) {
            return;
        }

        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                    if (dir.equals(rootPath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!Files.isRegularFile(dir.resolve("ysm.json")) || !YSMFolderDeserializer.isModelFolder(dir)) {
                        return FileVisitResult.CONTINUE;
                    }

                    String diskModelName = toModelName(rootPath, dir);
                    cacheFolderModel(dir, ModelIdUtil.getInternalModelId(diskModelName));
                    return FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                    File diskFile = file.toFile();
                    if (diskFile.isFile() && diskFile.getName().endsWith(".ysm")) {
                        cacheBinaryModel(rootPath, file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to scan OpenYSM models under {}", rootPath, e);
        }
    }

    private static void cacheFolderModel(Path dir, String modelId) {
        try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(dir)) {
            RawYsmModel raw = deserializer.deserialize();
            raw.modelId = modelId;
            RAW_MODEL_INFO.put(modelId, raw);
            if (!RawYsmModelAdapter.isBridgeable(raw)) {
                ysmu.LOG.warn("OpenYSM folder model {} parsed but cannot be bridged to legacy ModelData", dir);
                return;
            }
            ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, modelId);
            ServerModelInfo info = ModelCacheWriter.write(data);
            if (info != null) {
                CACHE_NAME_INFO.put(modelId, info);
            }
            OpenYsmSyncInfo syncInfo = ModelCacheWriter.writeOpenYsm(raw, modelId);
            OPEN_YSM_SYNC_INFO.put(modelId, syncInfo);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to load OpenYSM folder model {}", dir, e);
        }
    }

    private static void cacheBinaryModel(Path rootPath, Path file) {
        try {
            byte[] encrypted = Files.readAllBytes(file);
            if (!isOpenYsmBinary(encrypted)) {
                // 不以 YSGP 前缀开头 → 不是已知的 OpenYSM 二进制格式
                String hexPrefix = bytesToHex(encrypted, Math.min(encrypted.length, 24));
                ysmu.LOG.warn("OpenYSM binary model {} skipped: not a recognized YSGP format "
                    + "(size={}, firstBytes={})", file, encrypted.length, hexPrefix);
                return;
            }
            byte[] rawBytes = YsmCrypt.decryptYsmFile(encrypted);
            try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(rawBytes)) {
                RawYsmModel raw = deserializer.deserializeKeepOpen();
                deserializer.parseYSMFooter(raw);
                String modelId = ModelIdUtil.getInternalModelId(removeExtension(toModelName(rootPath, file)));
                raw.modelId = modelId;
                RAW_MODEL_INFO.put(modelId, raw);
                // 即使模型无法桥接 legacy 格式，仍写入 OpenYSM 同步缓存，
                // 保证客户端可通过 OpenYSM 同步协议加载模型。
                // 截断的 .ysm 文件核心数据（几何、动画、纹理）在
                // parseYSMJson/parseYSMFooter 之前已解析完成。
                boolean bridgeable = RawYsmModelAdapter.isBridgeable(raw);
                OpenYsmSyncInfo syncInfo = ModelCacheWriter.writeOpenYsm(raw, modelId);
                OPEN_YSM_SYNC_INFO.put(modelId, syncInfo);
                if (bridgeable) {
                    ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, modelId);
                    ServerModelInfo info = ModelCacheWriter.write(data);
                    if (info != null) {
                        CACHE_NAME_INFO.put(modelId, info);
                    }
                }
            }
        } catch (UnsupportedOperationException e) {
            ysmu.LOG.warn("Unsupported OpenYSM binary model {} (size={}): {}",
                file, file.toFile().length(), e.getMessage());
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to load OpenYSM binary model {} (size={}): {}: {}",
                file, file.toFile().length(), e.getClass().getSimpleName(), e.getMessage());
            if (ysmu.LOG.isDebugEnabled()) {
                ysmu.LOG.debug("OpenYSM binary model {} load failure detail", file, e);
            }
        }
    }

    private static boolean isOpenYsmBinary(byte[] data) {
        if (data == null || data.length < OPEN_YSM_PREFIX.length) {
            return false;
        }
        for (int i = 0; i < OPEN_YSM_PREFIX.length; i++) {
            if (data[i] != OPEN_YSM_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    private static String toModelName(Path rootPath, Path path) {
        return rootPath.relativize(path).toString().replace('\\', '/');
    }

    /** Helper: convert first {@code maxLen} bytes of a byte array to hex string. */
    private static String bytesToHex(byte[] data, int maxLen) {
        if (data == null || data.length == 0) return "(empty)";
        int len = Math.min(data.length, maxLen);
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        if (len < data.length) sb.append("...");
        return sb.toString();
    }
}
