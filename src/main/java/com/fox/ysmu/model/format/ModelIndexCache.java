package com.fox.ysmu.model.format;

import static com.fox.ysmu.model.ServerModelManager.CACHE_SERVER;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.ysmu;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import rip.ysm.security.YsmCrypt;

/**
 * 服务端模型索引侧车：把「源文件内容指纹 → 内容寻址缓存(id/hash)」的映射持久化到
 * {@code cache/server/model_index.json}，让缓存重建在<b>解密/反序列化之前</b>就能
 * 判定某模型是否已是最新。
 *
 * <ul>
 *   <li>指纹 = 源文件内容的强哈希：OpenYSM .ysm 用「长度+文件尾部 8 字节 CityHash」、
 *       裸 YSGP .ysm 用「长度+头部 body MD5」、文件夹用「文件内容 md5 聚合」。
 *       内容变化必然导致指纹变化 → 不会「文件不同却跳过」。</li>
 *   <li>命中（指纹一致 + ok=true + 缓存文件存在且 {@link #isCacheUsable} 通过）
 *       → 整个跳过解密/解析/序列化/加密，用侧车里的 hash 重建 {@link OpenYsmSyncInfo}。</li>
 *   <li>ok=false 表示上次构建失败：指纹没变就不重试（问题模型不再每次启动烧时间）。</li>
 * </ul>
 *
 * 线程安全：重建期间并行任务经 {@link #mark} 写入，重建结束后由调用方 {@link #commit()}
 * 统一清理（删除已消失的模型）+ 落盘。
 */
public final class ModelIndexCache {

    private static final int INDEX_VERSION = 1;
    private static final String INDEX_FILE = "model_index.json";

    private static final java.util.concurrent.ConcurrentHashMap<String, Entry> INDEX =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** 本轮重建见过的 modelId，用于 {@link #commit} 时清理已删除的模型。 */
    private static final java.util.Set<String> SEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile String serverKeyFp;

    public static final class Entry {

        /** "ysm" = 二进制 .ysm（BOM+YSGP 或裸 YSGP）；"folder" = 文件夹模型。 */
        public String kind;
        /** 源文件内容指纹（见类注释）。 */
        public String srcFp;
        public String hash1;
        public String hash2;
        public String cacheFile;
        /** false = 上次构建失败（文件未变则跳过重试）。 */
        public boolean ok;
        /** 构建完成时缓存文件的字节数（0 = 未知/旧侧车）。命中时用 stat 快速判定。 */
        public long fileSize;
    }

    private ModelIndexCache() {}

    /** 从磁盘加载侧车（每次重建前调用）。serverKey 变化或版本不符时整体失效。 */
    public static void init() {
        INDEX.clear();
        SEEN.clear();
        serverKeyFp = serverKeyFingerprint();
        if (serverKeyFp == null) {
            // server key 未初始化，无从校验 → 视为无缓存（全部重建）。
            return;
        }
        File indexFile = CACHE_SERVER.resolve(INDEX_FILE).toFile();
        if (!indexFile.isFile()) {
            return;
        }
        try {
            String text = FileUtils.readFileToString(indexFile, StandardCharsets.UTF_8);
            JsonObject root = new JsonParser().parse(text).getAsJsonObject();
            if (root.get("version").getAsInt() != INDEX_VERSION) {
                return;
            }
            if (!serverKeyFp.equals(root.get("serverKeyFp").getAsString())) {
                return;
            }
            JsonObject models = root.getAsJsonObject("models");
            for (Map.Entry<String, JsonElement> me : models.entrySet()) {
                JsonObject jo = me.getValue().getAsJsonObject();
                Entry e = new Entry();
                e.kind = jo.get("kind").getAsString();
                e.srcFp = jo.get("srcFp").getAsString();
                e.hash1 = jo.get("hash1").getAsString();
                e.hash2 = jo.get("hash2").getAsString();
                e.cacheFile = jo.get("cacheFile").getAsString();
                e.ok = jo.get("ok").getAsBoolean();
                e.fileSize = jo.has("fileSize") ? jo.get("fileSize").getAsLong() : 0L;
                INDEX.put(me.getKey(), e);
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to load model index cache (falling back to full rebuild): {}", e.getMessage());
            INDEX.clear();
        }
    }

    public static Entry get(String modelId) {
        return modelId == null ? null : INDEX.get(modelId);
    }

    /** 记录本轮处理结果（成功或失败都写），并标记该 modelId 本轮已见。 */
    public static void mark(String modelId, Entry entry) {
        if (modelId == null) {
            return;
        }
        SEEN.add(modelId);
        if (entry == null) {
            INDEX.remove(modelId);
        } else {
            INDEX.put(modelId, entry);
        }
    }

    /** 清理本轮未出现的模型并落盘。重建并行阶段结束后调用。 */
    public static void commit() {
        try {
            if (serverKeyFp == null) {
                return;
            }
            INDEX.keySet().removeIf(id -> !SEEN.contains(id));
            JsonObject root = new JsonObject();
            root.addProperty("version", INDEX_VERSION);
            root.addProperty("serverKeyFp", serverKeyFp);
            JsonObject models = new JsonObject();
            // TreeMap 保证输出稳定可读。
            TreeMap<String, Entry> sorted = new TreeMap<>(INDEX);
            for (Map.Entry<String, Entry> me : sorted.entrySet()) {
                Entry e = me.getValue();
                JsonObject jo = new JsonObject();
                jo.addProperty("kind", e.kind);
                jo.addProperty("srcFp", e.srcFp);
                jo.addProperty("hash1", e.hash1 == null ? "" : e.hash1);
                jo.addProperty("hash2", e.hash2 == null ? "" : e.hash2);
                jo.addProperty("cacheFile", e.cacheFile == null ? "" : e.cacheFile);
                jo.addProperty("ok", e.ok);
                jo.addProperty("fileSize", e.fileSize);
                models.add(me.getKey(), jo);
            }
            root.add("models", models);
            FileUtils.writeStringToFile(
                CACHE_SERVER.resolve(INDEX_FILE).toFile(),
                root.toString(),
                StandardCharsets.UTF_8);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to save model index cache: {}", e.getMessage());
        } finally {
            SEEN.clear();
        }
    }

    /**
     * 缓存文件是否可用。侧车记录了写入时的文件大小：stat 一致即视为可用（缓存文件由
     * 本进程原子写入、内容不会被外部改动，跳过整文件哈希校验把二次启动重建从全读降到
     * stat）；大小缺失（旧侧车）或不符时才退回完整 verifyServerCache 校验。
     */
    public static boolean isCacheUsable(Entry e) {
        if (e == null || e.cacheFile == null || e.cacheFile.isEmpty()
            || e.hash1 == null || e.hash2 == null || e.hash1.isEmpty() || e.hash2.isEmpty()) {
            return false;
        }
        File f = CACHE_SERVER.resolve(e.cacheFile).toFile();
        if (!f.isFile() || f.length() == 0) {
            return false;
        }
        if (e.fileSize > 0L) {
            return f.length() == e.fileSize;
        }
        try {
            long h1 = Long.parseUnsignedLong(e.hash1, 16);
            long h2 = Long.parseUnsignedLong(e.hash2, 16);
            return YsmCrypt.verifyServerCache(FileUtils.readFileToByteArray(f), h1, h2);
        } catch (Exception ex) {
            return false;
        }
    }

    private static String serverKeyFingerprint() {
        byte[] key = ServerModelManager.OPEN_YSM_SERVER_KEY;
        if (key == null || key.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, key.length); i++) {
            sb.append(String.format("%02X", key[i] & 0xFF));
        }
        return sb.toString();
    }
}
