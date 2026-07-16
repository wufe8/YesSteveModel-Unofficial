package com.fox.ysmu.model;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.minecraft.entity.player.EntityPlayer;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.data.EncryptTools;
import com.fox.ysmu.model.format.FolderFormat;
import com.fox.ysmu.model.format.OpenYsmFormat;
import com.fox.ysmu.model.format.OpenYsmSyncInfo;
import com.fox.ysmu.model.format.ServerModelInfo;
import com.fox.ysmu.model.format.YsmFormat;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.RequestSyncModel;
import com.fox.ysmu.network.message.S2CVersionCheck17;
import com.fox.ysmu.util.GetJarResources;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Maps;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

public final class ServerModelManager {

    /**
     * 配置相关文件夹
     */
    public static final Path FOLDER = Paths.get("config", ysmu.MODID);

    /**
     * 自定义模型所放置的文件夹
     */
    public static final Path BUILT = FOLDER.resolve("builtin");
    public static final Path CUSTOM = FOLDER.resolve("custom");
    public static final Path EXPORT = FOLDER.resolve("export");

    /**
     * 生成缓存文件的文件夹
     */
    public static final Path CACHE = FOLDER.resolve("cache");
    public static final Path CACHE_SERVER_INDEX_FILE = CACHE.resolve("server_index");
    public static final Path CACHE_SERVER = CACHE.resolve("server");
    /**
     * 存储密码的文件
     */
    public static final Path PASSWORD_FILE = CACHE_SERVER.resolve("PASSWORD");
    public static final Path CACHE_CLIENT = CACHE.resolve("client");
    /**
     * 模型内部 ID -> 模型额外信息缓存
     * 非安全磁盘名称会先编码为内部 ID，再写入此缓存。
     * 可以方便的通过此缓存，来判断客户端发来的 MD5 在不在服务端
     * 从而将服务器文件发送给玩家
     * 还可以获取其他服务端模型信息
     */
    public static final Map<String, ServerModelInfo> CACHE_NAME_INFO = Maps.newConcurrentMap();
    public static final Map<String, RawYsmModel> RAW_MODEL_INFO = Maps.newConcurrentMap();
    public static final Map<String, OpenYsmSyncInfo> OPEN_YSM_SYNC_INFO = Maps.newConcurrentMap();
    public static volatile byte[] OPEN_YSM_SERVER_KEY;

    /**
     * 模型包数据：pack文件夹路径（相对 custom/） → ServerPackData。
     */
    public static final Map<String, ServerPackData> PACKS = new Object2ObjectOpenHashMap<>();

    /**
     * 特定文件名
     */
    public static final String MAIN_MODEL_FILE_NAME = "main.json";
    public static final String ARM_MODEL_FILE_NAME = "arm.json";
    public static final String MAIN_ANIMATION_FILE_NAME = "main.animation.json";
    public static final String ARM_ANIMATION_FILE_NAME = "arm.animation.json";
    public static final String EXTRA_ANIMATION_FILE_NAME = "extra.animation.json";

    public static void sendRequestSyncModelMessage(List<EntityPlayer> playerList) {
        for (EntityPlayer player : playerList) {
            sendRequestSyncModelMessage(player);
        }
    }

    public static void sendRequestSyncModelMessage(EntityPlayer player) {
        if (Config.ENABLE_SYNC_PROTOCOL) {
            NetworkHandler.sendToClientPlayer(new S2CVersionCheck17(NetworkHandler.PROTOCOL_VERSION), player);
        }
        NetworkHandler.sendToClientPlayer(new RequestSyncModel(), player);
    }

    public static void reloadPacks() {
        if (Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.info("[YSMU-MODEL] ===== Starting model reload =====");
            ysmu.LOG.info("[YSMU-MODEL] CUSTOM dir: {} (exists={})", CUSTOM, Files.isDirectory(CUSTOM));
            ysmu.LOG.info("[YSMU-MODEL] BUILT dir: {} (exists={})", BUILT, Files.isDirectory(BUILT));
        }

        clearModelCaches();
        createConfigDirectories();
        extractBuiltinModels();
        // copyBuiltInModels();  // 已迁移到 builtin/，不再需要 custom/ 下硬拷贝
        initPassword();
        initOpenYsmServerIndex();
        initBlacklistFile();
        scanDirectoryPacks(CUSTOM);
        scanDirectoryPacks(BUILT);
        rebuildModelCaches();

        if (Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.info("[YSMU-MODEL] ===== Model reload complete =====");
            ysmu.LOG.info("[YSMU-MODEL] CACHE_NAME_INFO size: {}", CACHE_NAME_INFO.size());
            ysmu.LOG.info("[YSMU-MODEL] RAW_MODEL_INFO size: {}", RAW_MODEL_INFO.size());
            ysmu.LOG.info("[YSMU-MODEL] OPEN_YSM_SYNC_INFO size: {}", OPEN_YSM_SYNC_INFO.size());
            ysmu.LOG.info("[YSMU-MODEL] PACKS size: {}", PACKS.size());
            if (!CACHE_NAME_INFO.isEmpty()) {
                ysmu.LOG.info("[YSMU-MODEL] Cached model IDs: {}", CACHE_NAME_INFO.keySet());
            }
            if (!RAW_MODEL_INFO.isEmpty()) {
                ysmu.LOG.info("[YSMU-MODEL] Raw model IDs: {}", RAW_MODEL_INFO.keySet());
            }
        }
    }

    private static void clearModelCaches() {
        CACHE_NAME_INFO.clear();
        RAW_MODEL_INFO.clear();
        OPEN_YSM_SYNC_INFO.clear();
        PACKS.clear();
    }

    private static void createConfigDirectories() {
        createFolder(FOLDER);
        createFolder(BUILT);
        createFolder(CUSTOM);
        createFolder(EXPORT);

        createFolder(CACHE);
        createFolder(CACHE_SERVER);
        createFolder(CACHE_CLIENT);
    }

    private static void rebuildModelCaches() {
        // 收集所有模型处理任务
        List<Runnable> tasks = new ArrayList<>();
        OpenYsmFormat.collectTasks(BUILT, tasks);
        OpenYsmFormat.collectTasks(CUSTOM, tasks);
        collectLegacyTasks(BUILT, tasks);
        collectLegacyTasks(CUSTOM, tasks);

        if (tasks.isEmpty()) return;

        if (Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.info("[YSMU-MODEL] Submitting {} model tasks to thread pool (max {} threads)",
                tasks.size(), 10);
        }

        // 并行执行所有任务，等待全部完成
        CompletableFuture.allOf(
            tasks.stream()
                .map(t -> CompletableFuture.runAsync(t, ThreadTools.THREAD_POOL))
                .toArray(CompletableFuture[]::new))
            .join();

        if (Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.info("[YSMU-MODEL] All {} model tasks completed. "
                + "CACHE_NAME_INFO={}, RAW_MODEL_INFO={}, OPEN_YSM_SYNC_INFO={}",
                tasks.size(), CACHE_NAME_INFO.size(), RAW_MODEL_INFO.size(), OPEN_YSM_SYNC_INFO.size());
        }
    }

    private static void collectLegacyTasks(Path rootPath, List<Runnable> tasks) {
        YsmFormat.collectTasks(rootPath, tasks);
        FolderFormat.collectTasks(rootPath, tasks);
    }

    /*====== 以下方法已废弃：模型已迁移到 builtin/ ======
    private static void copyBuiltInModels() {
        copyDefaultModel();
        copyWineFoxModel();
        copyVanillaModel();
    }
    private static void copyDefaultModel() {
        Path defaultPath = CUSTOM.resolve("default");
        createFolder(defaultPath);

        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/default/main.json"), defaultPath, MAIN_MODEL_FILE_NAME);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/default/arm.json"), defaultPath, ARM_MODEL_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/default/default.png"), defaultPath, "default.png");
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/default/blue.png"), defaultPath, "blue.png");
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/default/main.animation.json"),
            defaultPath,
            MAIN_ANIMATION_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/default/arm.animation.json"),
            defaultPath,
            ARM_ANIMATION_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/default/extra.animation.json"),
            defaultPath,
            EXTRA_ANIMATION_FILE_NAME);

        Path defaultBoyPath = CUSTOM.resolve("default_boy");
        createFolder(defaultBoyPath);

        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/default_boy/main.json"),
            defaultBoyPath,
            MAIN_MODEL_FILE_NAME);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/default_boy/arm.json"), defaultBoyPath, ARM_MODEL_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/default_boy/red.png"), defaultBoyPath, "red.png");
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/default_boy/blue.png"), defaultBoyPath, "blue.png");
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/default_boy/main.animation.json"),
            defaultBoyPath,
            MAIN_ANIMATION_FILE_NAME);
    }

    private static void copyVanillaModel() {
        Path stevePath = CUSTOM.resolve("steve");
        createFolder(stevePath);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/steve/main.json"), stevePath, MAIN_MODEL_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/steve/arm.json"), stevePath, ARM_MODEL_FILE_NAME);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/steve/tartaric_acid.png"), stevePath, "tartaric_acid.png");
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/steve/main.animation.json"),
            stevePath,
            MAIN_ANIMATION_FILE_NAME);

        Path alexPath = CUSTOM.resolve("alex");
        createFolder(alexPath);
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/alex/main.json"), alexPath, MAIN_MODEL_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/alex/arm.json"), alexPath, ARM_MODEL_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/alex/gsl.png"), alexPath, "gsl.png");
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/alex/main.animation.json"),
            alexPath,
            MAIN_ANIMATION_FILE_NAME);

        Path qinglukaPath = CUSTOM.resolve("qingluka");
        createFolder(qinglukaPath);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/qingluka/main.json"), qinglukaPath, MAIN_MODEL_FILE_NAME);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/qingluka/arm.json"), qinglukaPath, ARM_MODEL_FILE_NAME);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/qingluka/texture.png"), qinglukaPath, "texture.png");
    }

    private static void copyWineFoxModel() {
        Path wineFoxPath = CUSTOM.resolve("wine_fox");
        createFolder(wineFoxPath);

        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/wine_fox/main.json"), wineFoxPath, MAIN_MODEL_FILE_NAME);
        GetJarResources
            .copyYesSteveModelFile(getCustomFiles("custom/wine_fox/arm.json"), wineFoxPath, ARM_MODEL_FILE_NAME);
        GetJarResources.copyYesSteveModelFile(getCustomFiles("custom/wine_fox/skin.png"), wineFoxPath, "skin.png");
        GetJarResources.copyYesSteveModelFile(
            getCustomFiles("custom/wine_fox/main.animation.json"),
            wineFoxPath,
            MAIN_ANIMATION_FILE_NAME);
    }
    ====== end ======*/

    private static void cacheAllModels(Path rootPath) {
        YsmFormat.cacheAllModels(rootPath);
        FolderFormat.cacheAllModels(rootPath);
    }

    private static void initPassword() {
        try {
            EncryptTools.createRandomPassword();
            File passwordFile = PASSWORD_FILE.toFile();
            if (passwordFile.isFile()) {
                boolean validPassword = EncryptTools.readPassword(FileUtils.readFileToByteArray(passwordFile));
                if (!validPassword) {
                    FileUtils.writeByteArrayToFile(passwordFile, EncryptTools.writePassword());
                }
            } else {
                FileUtils.writeByteArrayToFile(passwordFile, EncryptTools.writePassword());
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to initialize legacy model password", e);
        }
    }

    private static void initOpenYsmServerIndex() {
        try {
            byte[] serverKey = readOpenYsmServerKey();
            if (serverKey == null) {
                serverKey = new byte[56];
                new SecureRandom().nextBytes(serverKey);
                JsonObject root = new JsonObject();
                root.addProperty("server_key", Base64.getEncoder().encodeToString(serverKey));
                FileUtils.writeStringToFile(CACHE_SERVER_INDEX_FILE.toFile(), ysmu.GSON.toJson(root), StandardCharsets.UTF_8);
            }
            OPEN_YSM_SERVER_KEY = serverKey;
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to initialize OpenYSM server_index", e);
            byte[] fallbackKey = new byte[56];
            new SecureRandom().nextBytes(fallbackKey);
            OPEN_YSM_SERVER_KEY = fallbackKey;
        }
    }

    private static byte[] readOpenYsmServerKey() {
        try {
            File serverIndexFile = CACHE_SERVER_INDEX_FILE.toFile();
            if (!serverIndexFile.isFile()) {
                return null;
            }
            String json = FileUtils.readFileToString(serverIndexFile, StandardCharsets.UTF_8);
            JsonObject root = new JsonParser().parse(json).getAsJsonObject();
            JsonElement serverKeyElement = root.get("server_key");
            if (serverKeyElement == null || !serverKeyElement.isJsonPrimitive()) {
                return null;
            }
            byte[] decoded = Base64.getDecoder().decode(serverKeyElement.getAsString());
            return decoded.length == 56 ? decoded : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void initBlacklistFile() {
        Path blacklistFile = FOLDER.resolve("blacklist.txt");
        if (Files.isRegularFile(blacklistFile)) {
            return;
        }
        try {
            String content = "# Yes Steve Model built-in model blacklist\n"
                + "# One Java regular expression per line. Lines starting with # are comments.\n"
                + "# The default legacy models copied into config/ysmu/custom are not controlled by this file yet.\n";
            FileUtils.writeStringToFile(blacklistFile.toFile(), content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to create OpenYSM blacklist file", e);
        }
    }

    /**
     * Extracts built-in models from the JAR's assets/ysmu/builtin/ directory
     * into the runtime BUILT directory, matching OpenYSM's approach.
     * The directory is cleared and re-extracted every time the game starts.
     */
    private static void extractBuiltinModels() {
        // Clear existing builtin directory contents (but keep the dir itself)
        if (Files.isDirectory(BUILT)) {
            try {
                FileUtils.cleanDirectory(BUILT.toFile());
            } catch (IOException e) {
                ysmu.LOG.warn("Failed to clean builtin directory", e);
            }
        }
        createFolder(BUILT);

        // Write notice.txt matching OpenYSM
        try {
            String notice = "This directory is cleared every time the game starts!\n"
                + "\u8BE5\u76EE\u5F55\u4F1A\u5728\u6BCF\u6B21\u6E38\u620F\u542F\u52A8\u65F6\u6E05\u7A7A\uFF01\n";
            Files.write(BUILT.resolve("notice.txt"), notice.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to write builtin notice.txt", e);
        }

        // Extract from JAR: assets/ysmu/builtin/ → config/ysmu/builtin/
        String prefix = "/assets/" + ysmu.MODID + "/builtin/";
        URL url = ysmu.class.getResource(prefix);
        if (url == null) {
            ysmu.LOG.info("No builtin models to extract ({} not found in JAR)", prefix);
            return;
        }

        try {
            if ("jar".equals(url.getProtocol())) {
                // Production mode: read from JAR via ZipFile
                String path = url.getPath();
                int bang = path.indexOf('!');
                if (bang < 0) return;
                String jarPath = path.substring(5, bang); // strip "file:"
                try (ZipFile zip = new ZipFile(jarPath)) {
                    String entryPrefix = "assets/" + ysmu.MODID + "/builtin/";
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (!name.startsWith(entryPrefix) || entry.isDirectory()) continue;
                        String relative = name.substring(entryPrefix.length());
                        Path dest = BUILT.resolve(relative);
                        Files.createDirectories(dest.getParent());
                        try (InputStream in = zip.getInputStream(entry)) {
                            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            } else if ("file".equals(url.getProtocol())) {
                // Dev mode: resources are on the filesystem
                Path srcPath = new File(url.toURI()).toPath();
                if (Files.isDirectory(srcPath)) {
                    Files.walk(srcPath).forEach(src -> {
                        try {
                            Path relative = srcPath.relativize(src);
                            Path dest = BUILT.resolve(relative.toString());
                            if (Files.isDirectory(src)) {
                                Files.createDirectories(dest);
                            } else {
                                Files.createDirectories(dest.getParent());
                                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            ysmu.LOG.warn("Failed to copy builtin file: {}", src, e);
                        }
                    });
                }
            }
            ysmu.LOG.info("YSM extracted builtin models from {} to {}", prefix, BUILT);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to extract builtin models from {}", prefix, e);
        }
    }

    private static String getCustomFiles(String path) {
        return String.format("/assets/%s/%s", ysmu.MODID, path);
    }

    private static void createFolder(Path path) {
        File folder = path.toFile();
        if (!folder.isDirectory()) {
            try {
                Files.createDirectories(folder.toPath());
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to create directory {}", path, e);
            }
        }
    }

    public static String removeExtension(String fileName) {
        int lastIndex = fileName.lastIndexOf('.');
        if (lastIndex != -1) {
            fileName = fileName.substring(0, lastIndex);
        }
        return fileName;
    }

    // ── Pack scanning ────────────────────────────────────────────

    /** Scans a root directory for pack folders containing ysm-pack.json. */
    public static void scanDirectoryPacks(Path rootPath) {
        if (!Files.isDirectory(rootPath)) return;
        try {
            Files.walkFileTree(rootPath, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                    java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (dir.equals(rootPath)) return java.nio.file.FileVisitResult.CONTINUE;
                    Path packJson = dir.resolve("ysm-pack.json");
                    if (!Files.isRegularFile(packJson)) {
                        // Check if this dir contains model sub-dirs (implicit pack)
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    // Found a pack folder
                    String folderPath = rootPath.relativize(dir).toString().replace('\\', '/');
                    try {
                        scanSinglePack(dir, folderPath);
                    } catch (Exception e) {
                        ysmu.LOG.warn("Failed to scan pack {}", dir, e);
                    }
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
            });
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to scan packs under {}", rootPath, e);
        }
    }

    private static void scanSinglePack(Path dir, String folderPath) throws Exception {
        Path packJson = dir.resolve("ysm-pack.json");
        if (!Files.isRegularFile(packJson)) return;

        JsonObject json = new JsonParser().parse(
            new String(Files.readAllBytes(packJson), StandardCharsets.UTF_8)).getAsJsonObject();

        String name = getOptString(json, "name", folderPath);
        String description = getOptString(json, "description", "");

        // Language translations
        Map<String, Map<String, String>> lang = new java.util.LinkedHashMap<>();
        if (json.has("lang") && json.get("lang").isJsonObject()) {
            JsonObject langObj = json.getAsJsonObject("lang");
            for (Map.Entry<String, JsonElement> entry : langObj.entrySet()) {
                String langCode = entry.getKey();
                JsonObject trans = entry.getValue().getAsJsonObject();
                Map<String, String> map = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> te : trans.entrySet()) {
                    map.put(te.getKey(), te.getValue().getAsString());
                }
                lang.put(langCode, map);
            }
        }

        // Optional icon
        byte[] iconData = null;
        int iconW = 0, iconH = 0, iconFmt = 0;
        Path iconPng = dir.resolve("ysm-pack.png");
        if (Files.isRegularFile(iconPng)) {
            iconData = Files.readAllBytes(iconPng);
            // Simple PNG dimension detection from header
            iconW = readPngWidth(iconData);
            iconH = readPngHeight(iconData);
            iconFmt = 1; // PNG format
        }

        PACKS.put(folderPath, new ServerPackData(folderPath, name, description, iconData, iconW, iconH, iconFmt, lang));
        ysmu.LOG.info("YSM server registered pack '{}' at {}", name, folderPath);
    }

    private static String getOptString(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return fallback;
    }

    private static int readPngWidth(byte[] data) {
        if (data == null || data.length < 24) return 0;
        // PNG: 8-byte signature, then IHDR chunk: 4 len, 4 type, 4 width
        return ((data[16] & 0xFF) << 24) | ((data[17] & 0xFF) << 16)
             | ((data[18] & 0xFF) << 8)  | (data[19] & 0xFF);
    }

    private static int readPngHeight(byte[] data) {
        if (data == null || data.length < 24) return 0;
        return ((data[20] & 0xFF) << 24) | ((data[21] & 0xFF) << 16)
             | ((data[22] & 0xFF) << 8)  | (data[23] & 0xFF);
    }

    /** Model pack metadata from ysm-pack.json, sent to clients during sync. */
    public static final class ServerPackData {
        public final String folderPath;
        public final String name;
        public final String description;
        public final byte[] iconData;
        public final int iconWidth;
        public final int iconHeight;
        public final int iconFormat;
        public final Map<String, Map<String, String>> lang;

        public ServerPackData(String folderPath, String name, String description,
            byte[] iconData, int iconWidth, int iconHeight, int iconFormat,
            Map<String, Map<String, String>> lang) {
            this.folderPath = folderPath;
            this.name = name;
            this.description = description;
            this.iconData = iconData;
            this.iconWidth = iconWidth;
            this.iconHeight = iconHeight;
            this.iconFormat = iconFormat;
            this.lang = lang;
        }
    }
}
