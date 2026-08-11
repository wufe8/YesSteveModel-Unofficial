package com.fox.ysmu.model.format;

import static com.fox.ysmu.model.ServerModelManager.ARM_MODEL_FILE_NAME;
import static com.fox.ysmu.model.ServerModelManager.CACHE_NAME_INFO;
import static com.fox.ysmu.model.ServerModelManager.CACHE_SERVER;
import static com.fox.ysmu.model.ServerModelManager.MAIN_MODEL_FILE_NAME;
import static com.fox.ysmu.model.ServerModelManager.OPEN_YSM_SERVER_KEY;
import static com.fox.ysmu.model.ServerModelManager.OPEN_YSM_SYNC_INFO;
import static com.fox.ysmu.model.ServerModelManager.RAW_MODEL_INFO;
import static com.fox.ysmu.model.ServerModelManager.removeExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fox.ysmu.Config;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.YSMBinaryDeserializer;
import com.fox.ysmu.model.resource.YSMFolderDeserializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.util.ThreadTools;
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
 *
 * <p>legacy 缓存（CACHE_NAME_INFO + md5 加密文件）不再在此构建：协议开启时只写
 * OpenYSM 缓存（省一半 cache 空间与首建耗时），需要 legacy 同步（版本不匹配/协议
 * 关闭）时由 {@link #ensureLegacyCacheBuilt()} 从 OpenYSM 缓存反解析按需补建。
 */
public final class OpenYsmFormat {

    private static final byte[] OPEN_YSM_PREFIX = new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'Y', 'S', 'G', 'P' };
    /** OpenYSM 同步缓存二进制格式号。唯一来源，客户端反序列化（OpenYsmModelSyncClient /
     *  ClientModelManager 懒加载）与 ModelCacheWriter 序列化共用此值。 */
    public static final int OPEN_YSM_SYNC_FORMAT = 32;

    private OpenYsmFormat() {}

    private static void cacheFolderModel(Path dir, String modelId) {
        String fp = null;
        try {
            // 先查侧车：仅当存在条目时才计算文件夹指纹（内容 md5 聚合，避免首启白算）。
            ModelIndexCache.Entry cached = ModelIndexCache.get(modelId);
            if (cached != null) {
                fp = YSMFolderDeserializer.computeFolderHash(dir);
                if (trySidecarHit(modelId, fp, "Folder model " + modelId)) {
                    return;
                }
            }

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
                // 仍可注册 extra wheel / projectile 子实体。legacy 缓存（CACHE_NAME_INFO）
                // 不再在此构建——按需懒构建（ensureLegacyCacheBuilt）从 OpenYSM 缓存
                // 反解析生成，避免 cache 目录双份存储与首建双倍耗时。
                OpenYsmSyncInfo syncInfo = ModelCacheWriter.writeOpenYsm(raw, modelId);
                OPEN_YSM_SYNC_INFO.put(modelId, syncInfo);
                if (fp == null) {
                    fp = YSMFolderDeserializer.computeFolderHash(dir);
                }
                ModelIndexCache.mark(modelId, entryOf("folder", fp, syncInfo, true));
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to load OpenYSM folder model {}", dir, e);
            if (fp == null) {
                try {
                    fp = YSMFolderDeserializer.computeFolderHash(dir);
                } catch (Exception ignore) {
                    fp = "";
                }
            }
            ModelIndexCache.mark(modelId, entryOf("folder", fp, null, false));
        } finally {
            // 无论成功/失败都释放 raw：原先异常路径会把 RawYsmModel（含全部纹理
            // 字节）泄漏在 RAW_MODEL_INFO，多个失败模型累积会显著抬高预初始化峰值。
            RAW_MODEL_INFO.remove(modelId);
        }
    }

    private static void cacheBinaryModel(Path rootPath, Path file) {
        byte[] encrypted;
        try {
            encrypted = Files.readAllBytes(file);
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to read YSM binary model {}", file, e);
            return;
        }
        String modelId = ModelIdUtil.getInternalModelId(removeExtension(toModelName(rootPath, file)));
        String fileName = file.getFileName().toString();
        long fileSize = encrypted.length;

        if (!isOpenYsmBinary(encrypted)) {
            // 旧版裸 YSGP（无 BOM 前缀）→ 解包后统一转成 OpenYSM 同步缓存。
            // 合并旧/新两条加载路径的核心：所有 .ysm（无论版本）都进入同一个
            // OPEN_YSM_SYNC_INFO 索引，客户端只用一条协议加载。
            cacheLegacyBinaryModel(rootPath, file, modelId, encrypted);
            return;
        }

        // ── 新版 BOM+YSGP：先用「长度 + 文件尾部 8 字节 CityHash」指纹查侧车 ──
        // 尾部哈希 = 整个加密文件内容的 CityHash（decryptYsmFile 也用同一校验），
        // 内容变化必然导致指纹变化 → 命中时无需解密/反序列化即可复用缓存。
        String fp = ysmFileFingerprint(encrypted);
        if (trySidecarHit(modelId, fp, ".ysm " + modelId)) {
            return;
        }

        try {
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
                ModelIndexCache.mark(modelId, entryOf("ysm", fp, syncInfo, true));
            }
        } catch (UnsupportedOperationException e) {
            ysmu.LOG.warn("Unsupported OpenYSM binary model {} (size={}): {}",
                file, file.toFile().length(), e.getMessage());
            ModelIndexCache.mark(modelId, entryOf("ysm", fp, null, false));
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to load OpenYSM binary model {} (size={}): {}: {}",
                file, file.toFile().length(), e.getClass().getSimpleName(), e.getMessage());
            // 细节堆栈：需开 DebugModelLoad + (DebugModelScan 或 logger DEBUG 级) 才打印，
            // 括号修正 &&/|| 优先级（原表达式在 logger DEBUG 级时无条件打印）。
            if (Config.DEBUG_MODEL_LOAD && (Config.DEBUG_MODEL_SCAN || ysmu.LOG.isDebugEnabled())) {
                ysmu.LOG.warn("[YSMU-MODEL] OpenYSM binary model {} load failure detail", file, e);
            }
            ModelIndexCache.mark(modelId, entryOf("ysm", fp, null, false));
        } finally {
            // 无论成功/失败都释放 raw，避免异常路径把 RawYsmModel（含全部纹理字节）
            // 泄漏在 RAW_MODEL_INFO，多个失败模型累积会抬高预初始化内存峰值。
            RAW_MODEL_INFO.remove(modelId);
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
    private static void cacheLegacyBinaryModel(Path rootPath, Path file, String modelId, byte[] encrypted) {
        if (encrypted == null || encrypted.length < 24) {
            String hexPrefix = encrypted == null ? "(empty)"
                : bytesToHex(encrypted, Math.min(encrypted.length, 24));
            ysmu.LOG.warn("YSM binary model {} skipped: not a recognized YSGP format "
                + "(size={}, firstBytes={})", file, encrypted == null ? 0 : encrypted.length, hexPrefix);
            return;
        }
        // 便宜指纹：长度 + 头部 body MD5（offset 8..24，整个 body 的内容哈希）。
        String fp = encrypted.length + ":" + toHexCompact(Arrays.copyOfRange(encrypted, 8, 24));
        if (trySidecarHit(modelId, fp, "Legacy .ysm " + modelId)) {
            return;
        }

        try {
            Map<String, byte[]> files = YesModelUtils.input(file.toFile());
            if (files.isEmpty()) {
                String hexPrefix = bytesToHex(encrypted, Math.min(encrypted.length, 24));
                ysmu.LOG.warn("YSM binary model {} skipped: not a recognized YSGP format "
                    + "(size={}, firstBytes={})", file, encrypted.length, hexPrefix);
                ModelIndexCache.mark(modelId, entryOf("ysm", fp, null, false));
                return;
            }
            if (!files.containsKey(MAIN_MODEL_FILE_NAME) || !files.containsKey(ARM_MODEL_FILE_NAME)) {
                ysmu.LOG.warn("YSM binary model {} skipped: legacy format missing main.json/arm.json", file);
                ModelIndexCache.mark(modelId, entryOf("ysm", fp, null, false));
                return;
            }
            if (files.keySet().stream().noneMatch(name -> name.endsWith(".png"))) {
                ysmu.LOG.warn("YSM binary model {} skipped: no .png texture found", file);
                ModelIndexCache.mark(modelId, entryOf("ysm", fp, null, false));
                return;
            }

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
                ModelIndexCache.mark(modelId, entryOf("ysm", fp, syncInfo, true));
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to convert legacy YSM binary model {} (size={}): {}: {}",
                file, encrypted.length, e.getClass().getSimpleName(), e.getMessage());
            ModelIndexCache.mark(modelId, entryOf("ysm", fp, null, false));
        }
    }

    /** 紧凑 hex（无空格）。 */
    private static String toHexCompact(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /** OpenYSM .ysm 的内容指纹：长度 + 文件尾部 8 字节 CityHash（覆盖整个文件内容）。 */
    private static String ysmFileFingerprint(byte[] encrypted) {
        if (encrypted == null || encrypted.length < 8) {
            return "0:";
        }
        return encrypted.length + ":" + toHexCompact(
            Arrays.copyOfRange(encrypted, encrypted.length - 8, encrypted.length));
    }

    /** 用侧车里的内容寻址信息重建 OpenYsmSyncInfo（命中时不再反序列化）。 */
    private static OpenYsmSyncInfo toSyncInfo(String modelId, ModelIndexCache.Entry e) {
        return new OpenYsmSyncInfo(modelId, e.cacheFile,
            Long.parseUnsignedLong(e.hash1, 16), Long.parseUnsignedLong(e.hash2, 16),
            OPEN_YSM_SYNC_FORMAT, false);
    }

    /**
     * 尝试用侧车命中跳过重建（folder/ysm/legacy 三条路径共用）。
     * 返回 true 表示「已由侧车处理」（命中或上次失败跳过），调用方应直接 return；
     * 返回 false 表示未命中，需要按正常流程重建。
     *
     * @param label debug 日志用的模型类型标签（含 modelId，如 "Folder model xxx"）
     */
    private static boolean trySidecarHit(String modelId, String fp, String label) {
        ModelIndexCache.Entry cached = ModelIndexCache.get(modelId);
        if (cached == null || !fp.equals(cached.srcFp)) {
            return false;
        }
        if (!cached.ok) {
            // 上次构建失败且文件未变：跳过重试，避免问题模型每次启动烧时间。
            ModelIndexCache.mark(modelId, cached);
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                ysmu.LOG.info("[YSMU-MODEL] {} previously failed, skipped rebuild", label);
            }
            return true;
        }
        if (ModelIndexCache.isCacheUsable(cached)) {
            OPEN_YSM_SYNC_INFO.put(modelId, toSyncInfo(modelId, cached));
            ModelIndexCache.mark(modelId, cached);
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
                ysmu.LOG.info("[YSMU-MODEL] {} cache hit, skipped rebuild", label);
            }
            return true;
        }
        return false;
    }

    /** 构造侧车条目。 */
    private static ModelIndexCache.Entry entryOf(String kind, String fp, OpenYsmSyncInfo syncInfo, boolean ok) {
        ModelIndexCache.Entry e = new ModelIndexCache.Entry();
        e.kind = kind;
        e.srcFp = fp == null ? "" : fp;
        e.ok = ok;
        if (syncInfo != null) {
            e.hash1 = String.format("%016x", syncInfo.getHash1());
            e.hash2 = String.format("%016x", syncInfo.getHash2());
            e.cacheFile = syncInfo.getCacheFileName();
            // 记录缓存文件大小，让侧车命中时用 stat 替代整文件哈希校验（二次启动提速）。
            try {
                e.fileSize = Files.size(CACHE_SERVER.resolve(e.cacheFile));
            } catch (IOException ignore) {
                e.fileSize = 0L;
            }
        }
        return e;
    }

    // ── legacy 缓存懒构建 ──────────────────────────────────────────────────
    // legacy（CACHE_NAME_INFO + md5 加密文件）只在真的需要 legacy 同步时才构建：
    // 构建源是已写好的 OpenYSM 同步缓存（格式 32），反解析回 RawYsmModel 再桥接成
    // legacy ModelData。协议开启时 cache 目录只有 OpenYSM 文件（省一半空间、省首建
    // 一半耗时）；版本不匹配/协议关闭时才按需补建 legacy。

    private static volatile CompletableFuture<Void> legacyCacheBuildFuture;

    /** 重建/重载后重置懒构建状态，强制下一次 legacy 同步按新索引重建。 */
    public static synchronized void resetLegacyCacheState() {
        legacyCacheBuildFuture = null;
    }

    /** 确保 legacy 缓存已构建（懒构建；并发调用共享同一个任务）。返回完成 future。 */
    public static synchronized CompletableFuture<Void> ensureLegacyCacheBuilt() {
        if (legacyCacheBuildFuture != null) {
            return legacyCacheBuildFuture;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        legacyCacheBuildFuture = future;
        ThreadTools.THREAD_POOL.submit(() -> {
            try {
                buildLegacyCacheFromOpenYsm();
                future.complete(null);
            } catch (Throwable t) {
                ysmu.LOG.warn("Failed to build legacy model cache from OpenYSM cache", t);
                synchronized (OpenYsmFormat.class) {
                    if (legacyCacheBuildFuture == future) {
                        legacyCacheBuildFuture = null; // 失败允许后续重试
                    }
                }
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private static void buildLegacyCacheFromOpenYsm() {
        List<OpenYsmSyncInfo> infos = new ArrayList<>(OPEN_YSM_SYNC_INFO.values());
        if (infos.isEmpty()) {
            return;
        }
        CompletableFuture.allOf(infos.stream()
            .map(info -> CompletableFuture.runAsync(() -> buildOneLegacyFromOpenYsm(info), ThreadTools.THREAD_POOL))
            .toArray(CompletableFuture[]::new))
            .join();
        if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SCAN) {
            ysmu.LOG.info("[YSMU-MODEL] Legacy model cache built from OpenYSM cache: {} models",
                CACHE_NAME_INFO.size());
        }
    }

    private static void buildOneLegacyFromOpenYsm(OpenYsmSyncInfo info) {
        try {
            byte[] cacheBytes = Files.readAllBytes(CACHE_SERVER.resolve(info.getCacheFileName()));
            byte[] clearBytes = YsmCrypt.read(cacheBytes, OPEN_YSM_SERVER_KEY);
            RawYsmModel raw;
            try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(clearBytes, OPEN_YSM_SYNC_FORMAT)) {
                raw = deserializer.deserializeKeepOpen();
                deserializer.parseYSMFooter(raw);
            }
            raw.modelId = info.getModelId();
            if (!RawYsmModelAdapter.isBridgeable(raw)) {
                return;
            }
            ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, info.getModelId());
            ServerModelInfo modelInfo = ModelCacheWriter.write(data);
            if (modelInfo != null) {
                CACHE_NAME_INFO.put(info.getModelId(), modelInfo);
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to build legacy cache for {}: {}: {}",
                info.getModelId(), e.getClass().getSimpleName(), e.getMessage());
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
