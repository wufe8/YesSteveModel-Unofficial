package com.fox.ysmu.compat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.util.ResourceLocation;

import org.apache.commons.io.IOUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 本地高版本游戏资产提供者。
 *
 * <p>通过读取玩家指定的高版本 Minecraft 游戏目录，直接获取 OGG 音效文件，
 * 支持所有高版本音效事件（包括 Et-Futurum 未下载的三叉戟等音效）。</p>
 *
 * <p>工作原理：</p>
 * <ol>
 *   <li>读取 {@code assets/indexes/<version>.json}（资产索引）</li>
 *   <li>从资产索引中提取 {@code minecraft/sounds.json} 并解析音效事件映射</li>
 *   <li>运行时按音效事件名查找对应的 OGG 文件，从 {@code assets/objects/} 读取</li>
 * </ol>
 *
 * <p>仅通过 paulscode SoundSystem 直接播放，不注册到 Minecraft 的 SoundHandler，
 * 不会与 1.7.10 原版或其他模组的音效产生冲突。</p>
 */
@SideOnly(Side.CLIENT)
public final class LocalAssetProvider {

    private static final String SOUNDS_JSON_PATH = "minecraft/sounds.json";
    /** 音效事件名 → OGG 虚拟路径列表（如 "item.trident.throw" → ["item/trident/throw1", ...]） */
    private static Map<String, List<String>> soundEventMap = null;
    /** 虚拟路径 → 资产 SHA-1 哈希（如 "minecraft/sounds/item/trident/throw1.ogg" → "abc123..."） */
    private static Map<String, AssetObject> assetIndex = null;
    private static Path objectsDir = null;
    private static boolean initialized = false;
    private static boolean initFailed = false;
    private static final Random RANDOM = new Random();

    private LocalAssetProvider() {}

    /**
     * 初始化资产提供者。读取配置中指定的游戏路径和版本，加载资产索引和 sounds.json。
     * 安全地重复调用——只会执行一次实际加载。
     */
    public static void init() {
        if (initialized || initFailed) return;

        String gamePath = Config.HIGH_VERSION_GAME_PATH;
        String assetVer = Config.HIGH_VERSION_ASSET_VERSION;

        if (gamePath == null || gamePath.isEmpty()) {
            initFailed = true;
            return;
        }

        // Normalise paths for cross-platform compatibility.
        // On Windows, Paths.get handles both \ and /.
        // On Unix, \ is a valid filename character so we strip it if it looks
        // like a Windows path was accidentally given; expand ~ to user home.
        // Split quotes that may have been stored in the config or passed via
        // command (e.g. "/ysm setgamepath \"C:\\path\\to\\.minecraft\"").
        // Also trim whitespace and normalise separators.
        String normalised = gamePath.trim();
        if (normalised.startsWith("\"") && normalised.endsWith("\"")) {
            normalised = normalised.substring(1, normalised.length() - 1).trim();
        }
        if (org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS) {
            normalised = normalised.replace('/', '\\');
        } else {
            normalised = normalised.replace('\\', '/');
            // Expand ~ to user home directory
            if (normalised.startsWith("~")) {
                String home = System.getProperty("user.home");
                if (home != null) {
                    normalised = home + normalised.substring(1);
                }
            }
        }

        Path gameDir;
        try {
            gameDir = Paths.get(normalised);
        } catch (Exception e) {
            ysmu.LOG.warn("[YSMU-ASSET] Invalid game path '{}': {}", gamePath, e.getMessage());
            initFailed = true;
            return;
        }
        if (!Files.isDirectory(gameDir)) {
            ysmu.LOG.warn("[YSMU-ASSET] Game path '{}' is not a valid directory", gamePath);
            initFailed = true;
            return;
        }

        Path indexesDir = gameDir.resolve("assets").resolve("indexes");
        Path indexFile = indexesDir.resolve(assetVer + ".json");
        if (!Files.isRegularFile(indexFile)) {
            // Log available index files to help the user find the right version
            StringBuilder available = new StringBuilder();
            try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(indexesDir)) {
                files.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> { if (available.length() > 0) available.append(", ");
                        available.append(p.getFileName()); });
            } catch (IOException ignored) {}
            ysmu.LOG.warn("[YSMU-ASSET] Asset index not found: {} (check game path and version). Available: [{}]",
                indexFile, available);
            initFailed = true;
            return;
        }

        objectsDir = gameDir.resolve("assets").resolve("objects");

        try {
            // 1. Load asset index
            byte[] indexBytes = Files.readAllBytes(indexFile);
            assetIndex = parseAssetIndex(new String(indexBytes, StandardCharsets.UTF_8));
            if (assetIndex == null || assetIndex.isEmpty()) {
                ysmu.LOG.warn("[YSMU-ASSET] Failed to parse asset index, or index is empty");
                initFailed = true;
                return;
            }
            ysmu.LOG.info("[YSMU-ASSET] Loaded asset index with {} entries from {}", assetIndex.size(), indexFile);

            // 2. Extract and parse sounds.json from asset objects
            AssetObject soundsJsonObj = assetIndex.get(SOUNDS_JSON_PATH);
            if (soundsJsonObj == null) {
                ysmu.LOG.warn("[YSMU-ASSET] sounds.json not found in asset index");
                initFailed = true;
                return;
            }

            Path soundsJsonFile = objectsDir.resolve(soundsJsonObj.hash.substring(0, 2))
                .resolve(soundsJsonObj.hash);
            if (!Files.isRegularFile(soundsJsonFile)) {
                ysmu.LOG.warn("[YSMU-ASSET] sounds.json object file not found at {}", soundsJsonFile);
                initFailed = true;
                return;
            }

            byte[] soundsJsonBytes = Files.readAllBytes(soundsJsonFile);
            soundEventMap = parseSoundsJson(new String(soundsJsonBytes, StandardCharsets.UTF_8));
            if (soundEventMap == null || soundEventMap.isEmpty()) {
                ysmu.LOG.warn("[YSMU-ASSET] Failed to parse sounds.json, or no sound events found");
                initFailed = true;
                return;
            }
            ysmu.LOG.info("[YSMU-ASSET] Loaded {} sound events from sounds.json", soundEventMap.size());

            initialized = true;
            if (Config.DEBUG_SOUND) {
                ysmu.LOG.info("[YSMU-ASSET] LocalAssetProvider initialized: gamePath={}, version={}", gamePath, assetVer);
            }
        } catch (Exception e) {
            ysmu.LOG.warn("[YSMU-ASSET] Failed to initialize: {}", e.getMessage());
            initFailed = true;
        }
    }

    /**
     * 重新初始化（路径或版本变更后调用）。
     */
    public static void reinit() {
        initialized = false;
        initFailed = false;
        soundEventMap = null;
        assetIndex = null;
        objectsDir = null;
        init();
    }

    /**
     * 重置状态（用于配置变更时重新加载）。
     */
    public static void reset() {
        initialized = false;
        initFailed = false;
        soundEventMap = null;
        assetIndex = null;
        objectsDir = null;
    }

    /**
     * 检查是否已成功初始化。
     */
    public static boolean isAvailable() {
        if (!initialized && !initFailed) init();
        return initialized;
    }

    /**
     * 根据音效事件名查找 OGG 文件路径。
     *
     * @param soundName 音效事件名，如 "item.trident.throw" 或 "minecraft:item.trident.throw"
     * @return 本地 OGG 文件的绝对路径，如果找不到则返回 null
     */
    public static Path resolveSound(String soundName) {
        if (!isAvailable()) return null;

        // Strip namespace if present (e.g. "minecraft:item.trident.throw" → "item.trident.throw")
        String eventName = soundName;
        int colon = soundName.indexOf(':');
        if (colon >= 0) {
            eventName = soundName.substring(colon + 1);
        }

        // Look up the event in sounds.json
        List<String> soundFiles = soundEventMap.get(eventName);
        if (soundFiles == null || soundFiles.isEmpty()) {
            if (Config.DEBUG_SOUND) {
                ysmu.LOG.debug("[YSMU-ASSET] No sounds.json entry for '{}'", eventName);
            }
            return null;
        }

        // Pick a random variant (like Minecraft does)
        String selectedFile = soundFiles.get(RANDOM.nextInt(soundFiles.size()));

        // Look up the OGG file in the asset index
        String assetPath = "minecraft/sounds/" + selectedFile + ".ogg";
        AssetObject obj = assetIndex.get(assetPath);
        if (obj == null) {
            if (Config.DEBUG_SOUND) {
                ysmu.LOG.debug("[YSMU-ASSET] Asset index entry not found for '{}'", assetPath);
            }
            return null;
        }

        // Build the file path: objects/<hash[:2]>/<hash>
        Path oggFile = objectsDir.resolve(obj.hash.substring(0, 2)).resolve(obj.hash);
        if (!Files.isRegularFile(oggFile)) {
            if (Config.DEBUG_SOUND) {
                ysmu.LOG.debug("[YSMU-ASSET] OGG file not found at '{}' (hash {})", oggFile, obj.hash);
            }
            return null;
        }

        return oggFile;
    }

    /**
     * 获取当前配置的游戏路径（用于调试/显示）。
     */
    public static String getGamePath() {
        return Config.HIGH_VERSION_GAME_PATH;
    }

    /**
     * 获取当前配置的资产版本（用于调试/显示）。
     */
    public static String getAssetVersion() {
        return Config.HIGH_VERSION_ASSET_VERSION;
    }

    // ── JSON Parsing (minimal, no Gson dependency needed) ──

    /**
     * 解析资产索引 JSON。
     * 格式: {"objects": {"minecraft/sounds/item/trident/throw1.ogg": {"hash": "abc...", "size": 123}}}
     */
    private static Map<String, AssetObject> parseAssetIndex(String json) {
        Map<String, AssetObject> result = new LinkedHashMap<>();
        try {
            // Find the "objects" object
            int objStart = json.indexOf("\"objects\"");
            if (objStart < 0) return result;
            objStart = json.indexOf('{', objStart);
            if (objStart < 0) return result;
            int objEnd = findMatchingBrace(json, objStart);
            if (objEnd < 0) return result;

            String objectsSection = json.substring(objStart + 1, objEnd);
            // Parse each entry: "path": {"hash": "abc...", "size": 123}
            int pos = 0;
            while (pos < objectsSection.length()) {
                // Skip whitespace and commas
                while (pos < objectsSection.length() && (objectsSection.charAt(pos) <= ' ' || objectsSection.charAt(pos) == ','))
                    pos++;
                if (pos >= objectsSection.length() || objectsSection.charAt(pos) != '"')
                    break;

                // Read key (path)
                pos++; // skip opening quote
                int keyEnd = objectsSection.indexOf('"', pos);
                if (keyEnd < 0) break;
                String path = objectsSection.substring(pos, keyEnd);
                pos = keyEnd + 1;

                // Skip ":"
                while (pos < objectsSection.length() && objectsSection.charAt(pos) <= ' ')
                    pos++;
                if (pos >= objectsSection.length() || objectsSection.charAt(pos) != ':')
                    break;
                pos++;
                while (pos < objectsSection.length() && objectsSection.charAt(pos) <= ' ')
                    pos++;

                // Read value object
                if (pos >= objectsSection.length() || objectsSection.charAt(pos) != '{')
                    break;
                int valEnd = findMatchingBrace(objectsSection, pos);
                if (valEnd < 0) break;
                String valSection = objectsSection.substring(pos + 1, valEnd);
                pos = valEnd + 1;

                String hash = extractStringValue(valSection, "hash");
                long size = extractLongValue(valSection, "size");
                if (hash != null && !hash.isEmpty()) {
                    result.put(path, new AssetObject(hash, size));
                }
            }
        } catch (Exception e) {
            ysmu.LOG.warn("[YSMU-ASSET] Error parsing asset index: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 解析 sounds.json。
     * 格式: {"event_name": {"sounds": ["file1", {"name": "file2", ...}]}}
     * 只提取 .ogg 文件名（不含扩展名）。
     */
    private static Map<String, List<String>> parseSoundsJson(String json) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try {
            // Parse top-level object
            int braceStart = json.indexOf('{');
            if (braceStart < 0) return result;
            int braceEnd = findMatchingBrace(json, braceStart);
            if (braceEnd < 0) return result;

            String topSection = json.substring(braceStart + 1, braceEnd);
            int pos = 0;
            while (pos < topSection.length()) {
                // Skip whitespace, commas, and closing braces
                while (pos < topSection.length() && (topSection.charAt(pos) <= ' ' || topSection.charAt(pos) == ',' || topSection.charAt(pos) == '}'))
                    pos++;
                if (pos >= topSection.length() || topSection.charAt(pos) != '"')
                    break;

                // Read event name
                pos++; // skip opening quote
                int keyEnd = topSection.indexOf('"', pos);
                if (keyEnd < 0) break;
                String eventName = topSection.substring(pos, keyEnd);
                pos = keyEnd + 1;

                // Skip ":"
                while (pos < topSection.length() && topSection.charAt(pos) <= ' ')
                    pos++;
                if (pos >= topSection.length() || topSection.charAt(pos) != ':')
                    break;
                pos++;
                while (pos < topSection.length() && topSection.charAt(pos) <= ' ')
                    pos++;

                // Read value (event definition object)
                if (pos >= topSection.length() || topSection.charAt(pos) != '{')
                    break;
                int eventEnd = findMatchingBrace(topSection, pos);
                if (eventEnd < 0) break;
                String eventSection = topSection.substring(pos + 1, eventEnd);
                pos = eventEnd + 1;

                // Extract "sounds" array
                List<String> sounds = extractSoundsArray(eventSection);
                if (!sounds.isEmpty()) {
                    result.put(eventName, sounds);
                }
            }
        } catch (Exception e) {
            ysmu.LOG.warn("[YSMU-ASSET] Error parsing sounds.json: {}", e.getMessage());
        }
        return result;
    }

    /** Extract a string value by key from a JSON object section. */
    private static String extractStringValue(String section, String key) {
        int idx = section.indexOf('"' + key + '"');
        if (idx < 0) return null;
        idx = section.indexOf(':', idx);
        if (idx < 0) return null;
        idx++;
        while (idx < section.length() && section.charAt(idx) <= ' ') idx++;
        if (idx >= section.length() || section.charAt(idx) != '"') return null;
        idx++;
        int end = section.indexOf('"', idx);
        return end < 0 ? null : section.substring(idx, end);
    }

    /** Extract a long value by key from a JSON object section. */
    private static long extractLongValue(String section, String key) {
        int idx = section.indexOf('"' + key + '"');
        if (idx < 0) return 0;
        idx = section.indexOf(':', idx);
        if (idx < 0) return 0;
        idx++;
        while (idx < section.length() && section.charAt(idx) <= ' ') idx++;
        int end = idx;
        while (end < section.length() && (Character.isDigit(section.charAt(end)) || section.charAt(end) == '-'))
            end++;
        try {
            return Long.parseLong(section.substring(idx, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Extract the "sounds" array from a JSON object section. */
    private static List<String> extractSoundsArray(String section) {
        List<String> result = new ArrayList<>();
        int arrStart = section.indexOf("\"sounds\"");
        if (arrStart < 0) return result;
        arrStart = section.indexOf('[', arrStart);
        if (arrStart < 0) return result;
        int arrEnd = findMatchingBracket(section, arrStart);
        if (arrEnd < 0) return result;

        String arrContent = section.substring(arrStart + 1, arrEnd);
        int pos = 0;
        while (pos < arrContent.length()) {
            // Skip whitespace, commas
            while (pos < arrContent.length() && (arrContent.charAt(pos) <= ' ' || arrContent.charAt(pos) == ','))
                pos++;
            if (pos >= arrContent.length()) break;

            if (arrContent.charAt(pos) == '"') {
                // Plain string: "filename"
                pos++;
                int end = arrContent.indexOf('"', pos);
                if (end < 0) break;
                result.add(arrContent.substring(pos, end));
                pos = end + 1;
            } else if (arrContent.charAt(pos) == '{') {
                // Object: {"name": "filename", ...}
                int objEnd = findMatchingBrace(arrContent, pos);
                if (objEnd < 0) break;
                String objSection = arrContent.substring(pos + 1, objEnd);
                pos = objEnd + 1;
                String name = extractStringValue(objSection, "name");
                if (name != null) {
                    result.add(name);
                }
            } else {
                break;
            }
        }
        return result;
    }

    /** Find the matching closing brace for a JSON object starting at pos. */
    private static int findMatchingBrace(String s, int pos) {
        if (pos >= s.length() || s.charAt(pos) != '{') return -1;
        int depth = 1;
        boolean inString = false;
        for (int i = pos + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    /** Find the matching closing bracket for a JSON array starting at pos. */
    private static int findMatchingBracket(String s, int pos) {
        if (pos >= s.length() || s.charAt(pos) != '[') return -1;
        int depth = 1;
        boolean inString = false;
        for (int i = pos + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    // ── Data classes ──────────────────────────────────────

    static final class AssetObject {
        final String hash;
        @SuppressWarnings("unused")
        final long size;

        AssetObject(String hash, long size) {
            this.hash = hash;
            this.size = size;
        }
    }
}
