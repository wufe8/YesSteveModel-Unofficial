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

import com.fox.ysmu.Config;
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

        if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
            ysmu.LOG.info("[YSMU-MODEL] Scanning OpenYSM models under {}", rootPath);
        }

        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                    if (dir.equals(rootPath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    // 同时支持新格式（有 ysm.json）和旧格式（无 ysm.json，但有 main.json+arm.json）。
                    // 使用 && 而非 ||：只有两种格式都不匹配时才跳过。
                    if (!Files.isRegularFile(dir.resolve("ysm.json")) && !YSMFolderDeserializer.isModelFolder(dir)) {
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

            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                int texCount = raw.mainEntity != null ? raw.mainEntity.textures.size() : 0;
                ysmu.LOG.info("[YSMU-MODEL] Folder model {} ({}): textures={}", dir.getFileName(), modelId, texCount);
            }

            RAW_MODEL_INFO.put(modelId, raw);
            if (!RawYsmModelAdapter.isBridgeable(raw)) {
                ysmu.LOG.warn("OpenYSM folder model {} parsed but cannot be bridged to legacy ModelData", dir);
                RAW_MODEL_INFO.remove(modelId);
                return;
            }
            ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, modelId);
            ServerModelInfo info = ModelCacheWriter.write(data);
            if (info != null) {
                CACHE_NAME_INFO.put(modelId, info);
                if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                    ysmu.LOG.info("[YSMU-MODEL] Folder model {} cached to CACHE_NAME_INFO", modelId);
                }
            }
            OpenYsmSyncInfo syncInfo = ModelCacheWriter.writeOpenYsm(raw, modelId);
            OPEN_YSM_SYNC_INFO.put(modelId, syncInfo);
            // RawYsmModel 在缓存构建完成后不再需要，释放内存
            RAW_MODEL_INFO.remove(modelId);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to load OpenYSM folder model {}", dir, e);
        }
    }

    private static void cacheBinaryModel(Path rootPath, Path file) {
        try {
            byte[] encrypted = Files.readAllBytes(file);
            String fileName = file.getFileName().toString();
            long fileSize = encrypted.length;

            if (!isOpenYsmBinary(encrypted)) {
                // 不以 YSGP 前缀开头 → 不是已知的 OpenYSM 二进制格式
                String hexPrefix = bytesToHex(encrypted, Math.min(encrypted.length, 24));
                ysmu.LOG.warn("OpenYSM binary model {} skipped: not a recognized YSGP format "
                    + "(size={}, firstBytes={})", file, fileSize, hexPrefix);
                return;
            }

            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                ysmu.LOG.info("[YSMU-MODEL] Found OpenYSM .ysm file: {} (size={})", fileName, fileSize);
            }

            byte[] rawBytes = YsmCrypt.decryptYsmFile(encrypted);

            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                ysmu.LOG.info("[YSMU-MODEL] Decrypted .ysm file {}: decompressed size={}", fileName, rawBytes.length);
            }

            try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(rawBytes)) {
                RawYsmModel raw = deserializer.deserializeKeepOpen();
                deserializer.parseYSMFooter(raw);
                String modelId = ModelIdUtil.getInternalModelId(removeExtension(toModelName(rootPath, file)));
                raw.modelId = modelId;

                if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                    int texCount = raw.mainEntity != null ? raw.mainEntity.textures.size() : 0;
                    int animCount = raw.mainEntity != null ? raw.mainEntity.animationFiles.size() : 0;
                    boolean hasMainModel = raw.mainEntity != null && raw.mainEntity.mainModel != null;
                    boolean hasArmModel = raw.mainEntity != null && raw.mainEntity.armModel != null;
                    ysmu.LOG.info("[YSMU-MODEL] Parsed .ysm {} -> modelId={}, formatVersion={}, "
                        + "mainModel={}, armModel={}, textures={}, animations={}, footer.version={}",
                        fileName, modelId, raw.formatVersion,
                        hasMainModel, hasArmModel, texCount, animCount, raw.footer.version);
                    if (raw.mainEntity != null) {
                        for (RawYsmModel.RawTexture tex : raw.mainEntity.textures.values()) {
                            ysmu.LOG.info("[YSMU-MODEL]   texture: name={}, format={}, w={}, h={}, dataLen={}",
                                tex.name, tex.imageFormat, tex.width, tex.height,
                                tex.data == null ? 0 : tex.data.length);
                        }
                    }
                }

                RAW_MODEL_INFO.put(modelId, raw);
                // 即使模型无法桥接 legacy 格式，仍写入 OpenYSM 同步缓存，
                // 保证客户端可通过 OpenYSM 同步协议加载模型。
                // 截断的 .ysm 文件核心数据（几何、动画、纹理）在
                // parseYSMJson/parseYSMFooter 之前已解析完成。
                boolean bridgeable = RawYsmModelAdapter.isBridgeable(raw);

                if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                    ysmu.LOG.info("[YSMU-MODEL] isBridgeable({}) = {}", modelId, bridgeable);
                }

                OpenYsmSyncInfo syncInfo = ModelCacheWriter.writeOpenYsm(raw, modelId);
                OPEN_YSM_SYNC_INFO.put(modelId, syncInfo);
                if (bridgeable) {
                    ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, modelId);
                    ServerModelInfo info = ModelCacheWriter.write(data);
                    if (info != null) {
                        CACHE_NAME_INFO.put(modelId, info);
                        if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                            ysmu.LOG.info("[YSMU-MODEL] Successfully cached model {} to CACHE_NAME_INFO", modelId);
                        }
                    }
                } else if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                    ysmu.LOG.info("[YSMU-MODEL] Model {} not bridgeable, skipped legacy cache but OpenYSM sync info written", modelId);
                }
                // RawYsmModel 在缓存构建完成后不再需要，释放内存
                RAW_MODEL_INFO.remove(modelId);
            }
        } catch (UnsupportedOperationException e) {
            ysmu.LOG.warn("Unsupported OpenYSM binary model {} (size={}): {}",
                file, file.toFile().length(), e.getMessage());
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to load OpenYSM binary model {} (size={}): {}: {}",
                file, file.toFile().length(), e.getClass().getSimpleName(), e.getMessage());
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN || ysmu.LOG.isDebugEnabled()) {
                ysmu.LOG.warn("[YSMU-MODEL] OpenYSM binary model {} load failure detail", file, e);
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

    /**
     * Collect model-processing Runnable tasks for parallel execution.
     */
    public static void collectTasks(Path rootPath, java.util.List<java.lang.Runnable> tasks) {
        if (rootPath == null || !Files.isDirectory(rootPath)) return;
        try {
            Files.walkFileTree(rootPath, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (dir.equals(rootPath)) return java.nio.file.FileVisitResult.CONTINUE;
                    if (!Files.isRegularFile(dir.resolve("ysm.json")) && !YSMFolderDeserializer.isModelFolder(dir))
                        return java.nio.file.FileVisitResult.CONTINUE;
                    String diskModelName = toModelName(rootPath, dir);
                    String modelId = ModelIdUtil.getInternalModelId(diskModelName);
                    Path dirCopy = dir;
                    tasks.add(() -> cacheFolderModel(dirCopy, modelId));
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (file.toFile().isFile() && file.toFile().getName().endsWith(".ysm")) {
                        Path rootCopy = rootPath;
                        Path fileCopy = file;
                        tasks.add(() -> cacheBinaryModel(rootCopy, fileCopy));
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to collect OpenYSM model tasks under {}", rootPath, e);
        }
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
