package com.fox.ysmu.model.format;

import static com.fox.ysmu.model.ServerModelManager.ARM_MODEL_FILE_NAME;
import static com.fox.ysmu.model.ServerModelManager.CACHE_NAME_INFO;
import static com.fox.ysmu.model.ServerModelManager.MAIN_MODEL_FILE_NAME;
import static com.fox.ysmu.model.ServerModelManager.OPEN_YSM_SYNC_INFO;
import static com.fox.ysmu.model.ServerModelManager.RAW_MODEL_INFO;
import static com.fox.ysmu.model.ServerModelManager.removeExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fox.ysmu.Config;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.YSMBinaryDeserializer;
import com.fox.ysmu.model.resource.YSMFolderDeserializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.util.YesModelUtils;
import com.fox.ysmu.ysmu;

import rip.ysm.security.YsmCrypt;

/**
 * 统一的服务端模型加载扫描器（modelLoad）。
 *
 * <p>负责把 <b>所有</b> 可加载的模型源统一转为 OpenYSM 二进制同步缓存并登记到
 * {@code OPEN_YSM_SYNC_INFO}：
 * <ul>
 *   <li>文件夹模型（有 {@code ysm.json} 的新格式，或无 {@code ysm.json} 的旧版
 *       main.json+arm.json 格式，如 builtin/misc 里的模型）——经 {@link YSMFolderDeserializer}；</li>
 *   <li>新版 BOM+YSGP 的 {@code .ysm}——经 {@link YSMBinaryDeserializer}；</li>
 *   <li>旧版裸 YSGP 的 {@code .ysm}——解包后经虚拟文件源 {@link YSMFolderDeserializer} 转换</li>
 * </ul>
 * 由此客户端只需一条同步协议（OpenYSM）即可加载全部模型，不再有新旧双路径。
 */
public final class OpenYsmFormat {

    private static final byte[] OPEN_YSM_PREFIX = new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'Y', 'S', 'G', 'P' };

    private OpenYsmFormat() {}

    private static void cacheFolderModel(Path dir, String modelId) {
        try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(dir)) {
            RawYsmModel raw = deserializer.deserialize();
            raw.modelId = modelId;

            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                int texCount = raw.mainEntity != null ? raw.mainEntity.textures.size() : 0;
                ysmu.LOG.info("[YSMU-MODEL] Folder model {} ({}): textures={}", dir.getFileName(), modelId, texCount);
            }

            RAW_MODEL_INFO.put(modelId, raw);
            boolean bridgeable = RawYsmModelAdapter.isBridgeable(raw);
            if (!bridgeable && Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                ysmu.LOG.warn("OpenYSM folder model {} parsed but cannot be bridged to legacy ModelData", dir);
            }
            // 无论是否可桥接都写入 OpenYSM 同步缓存（与 .ysm 一致）：非桥接模型
            // 仍可注册 extra wheel / projectile 子实体。桥接模型同时写 legacy 缓存，
            // 保证版本不匹配/关闭新协议时 legacy 同步仍可用。
            OpenYsmSyncInfo syncInfo = ModelCacheWriter.writeOpenYsm(raw, modelId);
            OPEN_YSM_SYNC_INFO.put(modelId, syncInfo);
            if (bridgeable) {
                ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, modelId);
                ServerModelInfo info = ModelCacheWriter.write(data);
                if (info != null) {
                    CACHE_NAME_INFO.put(modelId, info);
                    if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                        ysmu.LOG.info("[YSMU-MODEL] Folder model {} cached to CACHE_NAME_INFO", modelId);
                    }
                }
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to load OpenYSM folder model {}", dir, e);
        } finally {
            // 无论成功/失败都释放 raw：原先异常路径会把 RawYsmModel（含全部纹理
            // 字节）泄漏在 RAW_MODEL_INFO，多个失败模型累积会显著抬高预初始化峰值。
            RAW_MODEL_INFO.remove(modelId);
        }
    }

    private static void cacheBinaryModel(Path rootPath, Path file) {
        String modelId = null;
        try {
            byte[] encrypted = Files.readAllBytes(file);
            String fileName = file.getFileName().toString();
            long fileSize = encrypted.length;

            if (!isOpenYsmBinary(encrypted)) {
                // 旧版裸 YSGP（无 BOM 前缀）→ 解包后统一转成 OpenYSM 同步缓存。
                // 合并旧/新两条加载路径的核心：所有 .ysm（无论版本）都进入同一个
                // OPEN_YSM_SYNC_INFO 索引，客户端只用一条协议加载。
                cacheLegacyBinaryModel(rootPath, file);
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
                modelId = ModelIdUtil.getInternalModelId(removeExtension(toModelName(rootPath, file)));
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
        } finally {
            // 无论成功/失败都释放 raw，避免异常路径把 RawYsmModel（含全部纹理字节）
            // 泄漏在 RAW_MODEL_INFO，多个失败模型累积会抬高预初始化内存峰值。
            if (modelId != null) {
                RAW_MODEL_INFO.remove(modelId);
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
     * 把旧版裸 YSGP 的 .ysm（无 BOM 前缀，YesModelUtils.input 可解包）转成
     * OpenYSM 同步缓存并加入 OPEN_YSM_SYNC_INFO。解包结果是一组文件
     * （main.json/arm.json/动画/贴图），形状与无 ysm.json 的文件夹一致，因此
     * 复用 {@link YSMFolderDeserializer} 的虚拟文件源解析为 RawYsmModel，再经
     * {@link ModelCacheWriter#writeOpenYsm} 序列化为 OpenYSM 二进制缓存。
     */
    private static void cacheLegacyBinaryModel(Path rootPath, Path file) {
        byte[] encrypted;
        try {
            encrypted = Files.readAllBytes(file);
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to read legacy YSM binary model {}", file, e);
            return;
        }
        try {
            Map<String, byte[]> files = YesModelUtils.input(file.toFile());
            if (files.isEmpty()) {
                String hexPrefix = bytesToHex(encrypted, Math.min(encrypted.length, 24));
                ysmu.LOG.warn("YSM binary model {} skipped: not a recognized YSGP format "
                    + "(size={}, firstBytes={})", file, encrypted.length, hexPrefix);
                return;
            }
            if (!files.containsKey(MAIN_MODEL_FILE_NAME) || !files.containsKey(ARM_MODEL_FILE_NAME)) {
                ysmu.LOG.warn("YSM binary model {} skipped: legacy format missing main.json/arm.json", file);
                return;
            }
            if (files.keySet().stream().noneMatch(name -> name.endsWith(".png"))) {
                ysmu.LOG.warn("YSM binary model {} skipped: no .png texture found", file);
                return;
            }

            String modelId = ModelIdUtil.getInternalModelId(removeExtension(toModelName(rootPath, file)));
            try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(files, modelId)) {
                RawYsmModel raw = deserializer.deserialize();
                raw.modelId = modelId;

                if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                    int texCount = raw.mainEntity != null ? raw.mainEntity.textures.size() : 0;
                    ysmu.LOG.info("[YSMU-MODEL] Parsed legacy .ysm {} -> modelId={}, mainModel={}, armModel={}, textures={}",
                        file.getFileName(), modelId,
                        raw.mainEntity != null && raw.mainEntity.mainModel != null,
                        raw.mainEntity != null && raw.mainEntity.armModel != null,
                        texCount);
                }

                OpenYsmSyncInfo syncInfo = ModelCacheWriter.writeOpenYsm(raw, modelId);
                OPEN_YSM_SYNC_INFO.put(modelId, syncInfo);
                // 桥接模型同时写 legacy 缓存，保证版本不匹配/关闭新协议时 legacy 同步仍可用。
                if (RawYsmModelAdapter.isBridgeable(raw)) {
                    ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, modelId);
                    ServerModelInfo info = ModelCacheWriter.write(data);
                    if (info != null) {
                        CACHE_NAME_INFO.put(modelId, info);
                    }
                }
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to convert legacy YSM binary model {} (size={}): {}: {}",
                file, encrypted.length, e.getClass().getSimpleName(), e.getMessage());
        }
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
