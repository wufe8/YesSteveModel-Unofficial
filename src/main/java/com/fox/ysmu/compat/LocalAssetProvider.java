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

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

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
    /** 与其他 YSMU chat 消息统一的前缀：金色 [ + 绿色 YSMU + 金色 ]。 */
    private static final String CHAT_PREFIX = "\u00a76[\u00a7aYSMU\u00a76]\u00a7r";
    /** 音效事件名 → OGG 虚拟路径列表（如 "item.trident.throw" → ["item/trident/throw1", ...]） */
    private static Map<String, List<String>> soundEventMap = null;
    /** 虚拟路径 → 资产 SHA-1 哈希（如 "minecraft/sounds/item/trident/throw1.ogg" → "abc123..."） */
    private static Map<String, AssetObject> assetIndex = null;
    private static Path objectsDir = null;
    private static boolean initialized = false;
    private static boolean initFailed = false;
    /** 是否已在 chat 提醒过玩家加载失败（会话内只提醒一次，reset/reinit 后重置）。 */
    private static boolean chatWarned = false;
    /** 最近一次初始化失败的具体原因（warnIfMisconfigured 进世界后补发 chat 时使用）。 */
    private static String lastFailReason = null;
    /** init 成功但粒子纹理不可用（asset index 无粒子纹理且版本 jar 未配置/未打开）时的降级提示。 */
    private static String particleFallbackNote = null;
    private static final Random RANDOM = new Random();
    /** 高版本游戏目录（init 时保存，供版本 jar 读取）。 */
    private static Path gameDir = null;
    /**
     * 版本 jar（新版 Minecraft 的 textures/particles 所在，如 versions/26.2/26.2.jar）。
     * 惰性打开；sounds 仍从 assets/objects 读取。
     */
    private static java.util.zip.ZipFile versionJar = null;

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
        } catch (java.nio.file.InvalidPathException e) {
            fail(t("message.yes_steve_model.asset.invalid_game_path", gamePath));
            return;
        }
        if (!Files.isDirectory(gameDir)) {
            fail(t("message.yes_steve_model.asset.not_a_directory", gamePath));
            return;
        }

        Path indexesDir = gameDir.resolve("assets").resolve("indexes");
        if (assetVer == null || assetVer.trim().isEmpty()) {
            fail(t("message.yes_steve_model.asset.asset_version_missing", listIndexFiles(indexesDir)));
            return;
        }
        Path indexFile = indexesDir.resolve(assetVer + ".json");
        if (!Files.isRegularFile(indexFile)) {
            fail(t("message.yes_steve_model.asset.index_not_found", listIndexFiles(indexesDir)));
            return;
        }

        objectsDir = gameDir.resolve("assets").resolve("objects");

        try {
            // 1. Load asset index
            byte[] indexBytes = Files.readAllBytes(indexFile);
            assetIndex = parseAssetIndex(new String(indexBytes, StandardCharsets.UTF_8));
            if (assetIndex == null || assetIndex.isEmpty()) {
                fail(t("message.yes_steve_model.asset.index_parse_failed", indexFile));
                return;
            }
            ysmu.LOG.info("[YSMU-ASSET] Loaded asset index with {} entries from {}", assetIndex.size(), indexFile);

            // 资产完整性诊断：统计纹理/粒子相关条目，帮助用户判断该高版本目录
            // 是否包含完整的游戏资源（某些精简/仅声音安装会缺少 textures/particle）。
            int particleTextures = 0;
            int particleDefs = 0;
            for (String key : assetIndex.keySet()) {
                if (key.startsWith("minecraft/textures/particle/")) {
                    particleTextures++;
                } else if (key.startsWith("minecraft/particles/")) {
                    particleDefs++;
                }
            }
            if (Config.DEBUG_PARTICLE || particleTextures == 0) {
                String note;
                if (particleTextures == 0) {
                    String jarVer = Config.HIGH_VERSION_JAR_VERSION;
                    if (jarVer != null && !jarVer.trim().isEmpty()) {
                        note = "(0 particle textures in assets/objects - textures are read from the "
                            + "version jar versions/" + jarVer + "/" + jarVer + ".jar instead; "
                            + "expected for 1.21.2+/26.x, custom high-version particles still work)";
                    } else {
                        note = "(0 particle textures in assets/objects and HighVersionJarVersion is empty - "
                            + "custom high-version particles unavailable; set HighVersionJarVersion to a "
                            + "version dir whose client jar holds textures/particles)";
                    }
                } else {
                    note = "";
                }
                ysmu.LOG.info("[YSMU-ASSET] asset index stats: {} particle textures, {} particle defs {}",
                    particleTextures, particleDefs, note);
            }

            // 2. Extract and parse sounds.json from asset objects
            AssetObject soundsJsonObj = assetIndex.get(SOUNDS_JSON_PATH);
            if (soundsJsonObj == null) {
                fail(t("message.yes_steve_model.asset.sounds_json_missing"));
                return;
            }

            Path soundsJsonFile = objectsDir.resolve(soundsJsonObj.hash.substring(0, 2))
                .resolve(soundsJsonObj.hash);
            if (!Files.isRegularFile(soundsJsonFile)) {
                fail(t("message.yes_steve_model.asset.sounds_obj_missing"));
                return;
            }

            byte[] soundsJsonBytes = Files.readAllBytes(soundsJsonFile);
            soundEventMap = parseSoundsJson(new String(soundsJsonBytes, StandardCharsets.UTF_8));
            if (soundEventMap == null || soundEventMap.isEmpty()) {
                fail(t("message.yes_steve_model.asset.sounds_parse_failed"));
                return;
            }
            ysmu.LOG.info("[YSMU-ASSET] Loaded {} sound events from sounds.json", soundEventMap.size());

            // 保存游戏目录并惰性打开版本 jar（新版纹理/粒子所在）。
            LocalAssetProvider.gameDir = gameDir;
            initVersionJar();

            // 粒子纹理可用性检测：asset index 无粒子纹理（精简/仅声音安装，如 1.21.2+/26.x）
            // 且版本 jar 未配置或打开失败 → 高版本粒子纹理不可用（非致命降级，进世界后温和提示一次）。
            if (particleTextures == 0 && versionJar == null) {
                String jarVer = Config.HIGH_VERSION_JAR_VERSION;
                if (jarVer == null || jarVer.trim().isEmpty()) {
                    particleFallbackNote = t("message.yes_steve_model.asset.fallback_no_jar");
                } else {
                    particleFallbackNote = t("message.yes_steve_model.asset.fallback_jar_not_open", jarVer);
                }
                // 顺带列出 versions/ 下可用的版本目录（含同名客户端 jar），方便用户改对配置。
                String versions = listVersionDirs(gameDir);
                if (!versions.isEmpty()) {
                    particleFallbackNote += "\n"
                        + t("message.yes_steve_model.asset.available_versions", versions);
                }
            }

            initialized = true;
            if (Config.DEBUG_SOUND) {
                ysmu.LOG.info("[YSMU-ASSET] LocalAssetProvider initialized: gamePath={}, version={}", gamePath, assetVer);
            }
        } catch (Exception e) {
            fail(t("message.yes_steve_model.asset.init_exception", e.getMessage()));
        }
    }

    /**
     * 重新初始化（路径或版本变更后调用）。
     */
    public static void reinit() {
        reset();
        init();
    }

    /** Reset cached state so the next access re-reads from config. */
    public static void reset() {
        if (versionJar != null) {
            try {
                versionJar.close();
            } catch (Exception ignored) {
            }
        }
        versionJar = null;
        gameDir = null;
        initialized = false;
        initFailed = false;
        chatWarned = false;
        lastFailReason = null;
        particleFallbackNote = null;
        soundEventMap = null;
        assetIndex = null;
        objectsDir = null;
        // 配置变更时清空粒子纹理缓存：否则旧的 GL 纹理/失败状态会残留，导致
        // 同进程内"同时出现高版本粒子与 fallback 粒子"，或配置修好后仍一直 fallback。
        com.fox.ysmu.client.particle.ParticleTextureManager.clearCache();
    }

    /**
     * 检查是否已成功初始化。
     */
    public static boolean isAvailable() {
        if (!initialized && !initFailed) init();
        return initialized;
    }

    /** 列出 assets/indexes/ 下可用的索引文件名（逗号分隔），用于失败提示。 */
    private static String listIndexFiles(Path indexesDir) {
        StringBuilder available = new StringBuilder();
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(indexesDir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> { if (available.length() > 0) available.append(", ");
                    available.append(p.getFileName()); });
        } catch (IOException ignored) {}
        return available.toString();
    }

    /**
     * 列出 versions/ 下包含同名客户端 jar 的版本目录名（逗号分隔），用于失败提示。
     * 只统计真正有客户端 jar 的版本（粒子纹理所在）；限制最多 8 个避免消息过长。
     */
    private static String listVersionDirs(Path gameDir) {
        Path versionsDir = gameDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) return "";
        java.util.List<String> dirs = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(versionsDir)) {
            files.filter(Files::isDirectory)
                .filter(p -> Files.isRegularFile(p.resolve(p.getFileName() + ".jar")))
                .map(p -> p.getFileName().toString())
                .sorted()
                .forEach(dirs::add);
        } catch (IOException ignored) {}
        if (dirs.isEmpty()) return "";
        StringBuilder available = new StringBuilder();
        int max = Math.min(dirs.size(), 8);
        for (int i = 0; i < max; i++) {
            if (i > 0) available.append(", ");
            available.append(dirs.get(i));
        }
        if (dirs.size() > max) available.append(", ...");
        return available.toString();
    }

    /**
     * 主动健康检查（玩家进世界后每 tick 调用，成本极低）：若配置了高版本游戏路径
     * 但加载失败，在 chat 输出汇总警告。幂等——会话内只提醒一次（reset/reinit 后重置），
     * 且未配置路径（功能未启用）时完全不打扰。
     */
    public static void warnIfMisconfigured() {
        String gamePath = Config.HIGH_VERSION_GAME_PATH;
        if (gamePath == null || gamePath.trim().isEmpty()) {
            return; // 未配置 = 功能未启用，不打扰
        }
        isAvailable(); // 确保 init 已执行（失败分支可能已在 chat 输出具体原因）
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        // 1) 初始化失败：补发具体原因（fail 时若在加载界面 player 为 null 只写了日志）。
        if (initFailed && !chatWarned) {
            chatWarned = true;
            String reason = lastFailReason != null ? lastFailReason
                : t("message.yes_steve_model.asset.load_failed");
            sendAssetChat("\u00a7c", reason);
            return;
        }
        // 2) init 成功但粒子纹理不可用（降级）：温和提示一次。
        if (particleFallbackNote != null && !chatWarned) {
            chatWarned = true;
            sendAssetChat("\u00a7e", particleFallbackNote);
        }
    }

    /** 初始化失败：记录具体原因、记录日志、在 chat 提醒玩家（若在游戏中）、标记失败。 */
    private static void fail(String message) {
        lastFailReason = message;
        warnChat(message);
        initFailed = true;
    }

    /** 记录日志并在 chat 提醒玩家（若在游戏中）。非致命问题（如版本 jar 缺失）也可用。 */
    private static void warnChat(String message) {
        ysmu.LOG.warn("[YSMU-ASSET] " + message);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            chatWarned = true;
            sendAssetChat("\u00a7c", message);
        }
    }

    /**
     * 本地化：按当前语言（en_US / zh_CN / 第三方语言）取翻译文本，支持 %s 占位符。
     * 所有用户可见消息都应走这里，避免硬编码；语言文件在
     * {@code assets/ysmu/lang/<语言>.lang}（第三方语言新增同名 .lang 文件即可）。
     * 注意：1.7.10 的 lang 解析（parseLangFile）不做 '\n' 转义，多行请用 {@code {nl}}
     * 占位符（此处替换为真实换行，配合 sendAssetChat 的多行拆分）。
     */
    private static String t(String key, Object... args) {
        return StatCollector.translateToLocalFormatted(key, args).replace("{nl}", "\n");
    }

    /**
     * 在 chat 发送高版本资源配置提示。消息可用 '\n' 拆分为多行，避免长消息在
     * 客户端自动折行时把括号/文件名字符拆到两行显示。第一行附加配置位置说明。
     */
    private static void sendAssetChat(String colorCode, String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        String[] lines = message.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == 0) {
                mc.thePlayer.addChatMessage(new ChatComponentText(
                    CHAT_PREFIX + " " + colorCode + line + "\u00a77"
                        + t("message.yes_steve_model.asset.config_hint")));
            } else {
                // 续行缩进，不带前缀（与用户期望的紧凑格式一致），灰色弱化。
                mc.thePlayer.addChatMessage(new ChatComponentText(
                    "  \u00a77" + line));
            }
        }
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
     * 通用资产读取：按虚拟路径（如 {@code minecraft/textures/particle/drip_water.png}）查资产索引，
     * 返回 {@code assets/objects/} 下的实际文件路径。与 {@link #resolveSound} 共用同一份
     * assetIndex / objectsDir，可读取任意高版本资源（音效、粒子纹理等）。
     * 路径须带命名空间前缀（与资产索引 key 格式一致，如 {@code minecraft/...}）。
     *
     * @param virtualPath 资产虚拟路径（如 {@code minecraft/textures/particle/drip_water.png}）
     * @return 本地文件绝对路径；未找到则返回 {@code null}
     */
    public static Path resolveAssetPath(String virtualPath) {
        if (!isAvailable()) return null;
        AssetObject obj = assetIndex.get(virtualPath);
        if (obj == null) {
            if (Config.DEBUG_PARTICLE) {
                ysmu.LOG.debug("[YSMU-ASSET] Asset index entry not found for '{}'", virtualPath);
            }
            return null;
        }
        Path file = objectsDir.resolve(obj.hash.substring(0, 2)).resolve(obj.hash);
        if (!Files.isRegularFile(file)) {
            if (Config.DEBUG_PARTICLE) {
                ysmu.LOG.debug("[YSMU-ASSET] Asset file not found at '{}' (hash {})", file, obj.hash);
            }
            return null;
        }
        return file;
    }

    /**
     * 惰性打开版本 jar（{@code <gameDir>/versions/<HighVersionJarVersion>/<...>.jar}），
     * 新版 Minecraft（1.21.2+ / 26.x）的 textures/particles 位于版本 jar 内。
     */
    private static void initVersionJar() {
        String jarVer = Config.HIGH_VERSION_JAR_VERSION;
        if (jarVer == null || jarVer.trim().isEmpty() || gameDir == null) {
            return;
        }
        Path jarFile = gameDir.resolve("versions").resolve(jarVer).resolve(jarVer + ".jar");
        if (!Files.isRegularFile(jarFile)) {
            // 非致命：assets/objects 仍可用（音效、部分粒子）；但高版本粒子纹理读不到
            warnChat(t("message.yes_steve_model.asset.jar_not_found"));
            return;
        }
        try {
            versionJar = new java.util.zip.ZipFile(jarFile.toFile());
            ysmu.LOG.info("[YSMU-ASSET] Opened version jar for textures/particles: {}", jarFile);
        } catch (Exception e) {
            warnChat(t("message.yes_steve_model.asset.jar_open_failed", jarFile));
        }
    }

    /**
     * 读取高版本资源字节。{@code relPath} 相对 minecraft（如
     * {@code textures/particle/drip_fall.png}）。
     * 优先从版本 jar（{@code assets/minecraft/<relPath>}，新版纹理/粒子所在）读取，
     * 其次从 {@code assets/objects}（资产索引 {@code minecraft/<relPath>}）读取。
     *
     * @param relPath 相对 minecraft 的虚拟路径（不带命名空间前缀）
     * @return 资源字节；未找到返回 {@code null}
     */
    public static byte[] readAssetBytes(String relPath) {
        if (!isAvailable()) return null;
        // 1) 版本 jar（新版 textures/particles 所在）
        java.util.zip.ZipFile jar = versionJar;
        if (jar != null) {
            java.util.zip.ZipEntry entry = jar.getEntry("assets/minecraft/" + relPath);
            if (entry != null) {
                try (InputStream in = jar.getInputStream(entry)) {
                    return IOUtils.toByteArray(in);
                } catch (Exception e) {
                    if (Config.DEBUG_PARTICLE) {
                        ysmu.LOG.debug("[YSMU-ASSET] failed to read jar entry {}: {}", relPath, e.toString());
                    }
                }
            }
        }
        // 2) assets/objects（资产索引）
        Path p = resolveAssetPath("minecraft/" + relPath);
        if (p != null) {
            try {
                return Files.readAllBytes(p);
            } catch (Exception e) {
                if (Config.DEBUG_PARTICLE) {
                    ysmu.LOG.debug("[YSMU-ASSET] failed to read object {}: {}", p, e.toString());
                }
            }
        }
        return null;
    }

    /**
     * 读取高版本粒子纹理字节。优先解析 {@code particles/<name>.json} 的纹理字段
     * （新版用 {@code textures} 数组，如 {@code "minecraft:drip_fall"}；旧版用
     * {@code texture} 字符串），再读 {@code textures/particle/<tex>.png}；JSON 缺失
     * 时回退 {@code textures/particle/<name>.png}。从版本 jar 或 assets/objects 读取。
     *
     * @param particleName 粒子名，可带命名空间（如 {@code minecraft:falling_dripstone_water}）
     * @return PNG 字节；未找到返回 {@code null}
     */
    public static byte[] readParticleTextureBytes(String particleName) {
        if (!isAvailable()) return null;
        String stripped = stripNamespace(particleName);
        if (stripped.isEmpty()) return null;
        // 1) particles/<name>.json → texture/textures 字段
        byte[] def = readAssetBytes("particles/" + stripped + ".json");
        if (def != null) {
            try {
                com.google.gson.JsonObject obj = new com.google.gson.JsonParser()
                    .parse(new String(def, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                String tex = null;
                if (obj.has("textures") && obj.get("textures").isJsonArray()
                    && obj.getAsJsonArray("textures").size() > 0) {
                    tex = obj.getAsJsonArray("textures").get(0).getAsString();
                } else if (obj.has("texture") && obj.get("texture").isJsonPrimitive()) {
                    tex = obj.get("texture").getAsString();
                }
                if (Config.DEBUG_PARTICLE) {
                    ysmu.LOG.info("[YSMU-ASSET] particles/{}.json -> texture='{}'", stripped, tex);
                }
                if (tex != null && !tex.isEmpty()) {
                    byte[] data = readAssetBytes("textures/particle/" + stripNamespace(tex) + ".png");
                    if (data != null) return data;
                    if (Config.DEBUG_PARTICLE) {
                        ysmu.LOG.info("[YSMU-ASSET] textures/particle/{}.png not found", stripNamespace(tex));
                    }
                }
            } catch (Exception e) {
                if (Config.DEBUG_PARTICLE) {
                    ysmu.LOG.info("[YSMU-ASSET] failed to parse particles/{}.json: {}", stripped, e.toString());
                }
            }
        }
        // 2) 回退：textures/particle/<name>.png
        return readAssetBytes("textures/particle/" + stripped + ".png");
    }

    /** 剥离资源名/纹理名的命名空间前缀（{@code minecraft:drip_fall} → {@code drip_fall}）。 */
    private static String stripNamespace(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
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
                if (c == '\\') { i += 2; continue; }
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
