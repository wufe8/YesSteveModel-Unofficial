package com.fox.ysmu.client.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.audio.SoundManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.compat.LocalAssetProvider;
import com.fox.ysmu.compat.SoundNamespaceCompat;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.ysmu;

/**
 * YSM 模型自定义音效管理器。
 * 通过反射直接操作 paulscode SoundSystem 播放缓存的 OGG 文件，
 * 支持音源追踪和自动清理。
 */
public final class YSMSoundManager {

    private static final Path SOUND_CACHE = ServerModelManager.CACHE.resolve("sounds");
    /** 模型自定义音效注册表：modelKey::name → 主模型 id（按需解密的来源）。 */
    private static final Map<String, ResourceLocation> SOUND_SOURCES = new ConcurrentHashMap<>();
    /** 已按需解密的模型音效字节：modelKey::name → OGG bytes（内存驻留，不落盘明文）。 */
    private static final Map<String, byte[]> SOUND_FILES = new ConcurrentHashMap<>();
    /** soundName → SoundSystem source name */
    private static final Map<String, String> ACTIVE_SOURCES = new ConcurrentHashMap<>();
    /** GeckoLib controller name → last sound name triggered by its keyframe */
    private static final Map<String, String> CONTROLLER_SOUNDS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger sourceCounter = new java.util.concurrent.atomic.AtomicInteger(0);
    /** Lazily cached SoundSystem reflection handle */
    private static Object sndSystem = null;
    private static boolean sndSystemSearched = false;
    /** Lazily cached setPosition reflection handle (per-tick position updates). */
    private static java.lang.reflect.Method sndSetPosition = null;
    /**
     * 防抖：同一 controller+sound 在短时间内的重复触发将被忽略。
     * 当动画子条件变化（如站立→奔跑导致 sword_attack_01 切换到 sword_attack_run1）
     * 时，GeckoLib 的 resetEventKeyFrames() 会清除已执行的关键帧记录，
     * 导致声音关键帧在动画重设后再次触发。此防抖防止同一声音在短时间内重复播放。
     */
    private static final Map<String, Long> SOUND_KEYFRAME_LAST_TIME = new ConcurrentHashMap<>();
    private static final long SOUND_KEYFRAME_COOLDOWN_MS = 100L;

    /**
     * When true, onSoundKeyframe / playSound will skip actual playback.
     * Set by RenderUtil before rendering GUI previews (ModelButton, TextureButton)
     * to prevent GeckoLib animation keyframes from playing sounds during off-screen
     * model preview rendering.
     */
    private static boolean previewRendering = false;

    public static void setPreviewRendering(boolean value) {
        previewRendering = value;
    }

    private YSMSoundManager() {}

    // ── Public API ────────────────────────────────────────

    public static void registerModelSounds(ResourceLocation modelId, RawYsmModel raw) {
        if (raw.soundFiles == null || raw.soundFiles.isEmpty()) return;
        String modelKey = modelId.toString(); // modelId 为 main id
        for (Map.Entry<String, RawYsmModel.RawDataFile> e : raw.soundFiles.entrySet()) {
            String name = e.getKey();
            RawYsmModel.RawDataFile sf = e.getValue();
            if (sf == null || sf.data == null || sf.data.length == 0) continue;
            // 不再写明文 .ogg 到磁盘（防私有模型音效泄漏）：只记录「音效名 → 主模型」，
            // 首次播放时从加密客户端缓存按需解密（getSoundBytes → loadRawModelFromCache），
            // 之后字节驻留内存。磁盘上只保留加密缓存。
            SOUND_SOURCES.put(modelKey + "::" + name, modelId);
            if (Config.DEBUG_SOUND) {
                ysmu.LOG.info("[YSMU-SOUND] registered '{}'::'{}' (lazy, {} bytes)", modelKey, name, sf.data.length);
            }
        }
    }

    /**
     * 播放模型音效。如果同名音效已在播放，先停止旧的再播新的。
     * 会先停止所有活跃的 YSM 音源（防止音源泛滥）。
     */
    /** 检查 soundId 是否已在 1.7.10 的 SoundHandler 中注册。 */
    private static boolean soundExistsInHandler(String soundId) {
        if (soundId == null) return false;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return false;
            SoundHandler handler = mc.getSoundHandler();
            // Find SoundManager field by type (works with MCP names and obfuscated names)
            java.lang.reflect.Field sndMgrFd = null;
            for (java.lang.reflect.Field f : SoundHandler.class.getDeclaredFields()) {
                if (SoundManager.class.isAssignableFrom(f.getType())) {
                    sndMgrFd = f;
                    break;
                }
            }
            if (sndMgrFd == null) return true;
            sndMgrFd.setAccessible(true);
            Object sndMgr = sndMgrFd.get(handler);
            java.lang.reflect.Field regFd = sndMgr.getClass().getDeclaredField("soundRegistry");
            regFd.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, ?> reg = (java.util.Map<String, ?>) regFd.get(sndMgr);
            return reg.containsKey(soundId);
        } catch (Exception e) {
            return true; // On error, behave conservatively — let SoundHandler decide
        }
    }

    /**
     * 播放音效（带模型上下文）。查找顺序：
     * 1. 模型自定义音效（SOUND_FILES，按 modelId::name 查找）
     * 2. 1.7.10 SoundHandler（仅在音效已注册时调用，避免内部 WARN）
     * 3. 命名空间翻译后的 SoundHandler
     * 4. LocalAssetProvider（高版本游戏本地资产）
     * 5. 全部失败 → 输出一条最终 WARN
     */
    public static void playSound(EntityPlayer player, String soundName, ResourceLocation modelId, float volume, float pitch) {
        if (soundName == null || soundName.isEmpty()) return;

        if (Config.DEBUG_SOUND) {
            ysmu.LOG.info("[YSMU-SOUND] playSound: '{}' vol={} pitch={} model={}", soundName, volume, pitch, modelId);
        }

        // Step 1 — 模型自定义音效（按 modelId::name 隔离，避免跨模型同名冲突；
        // 首次播放时从加密客户端缓存按需解密）
        String modelKey = modelId != null ? modelId.toString() : null;
        byte[] sound = null;
        if (modelKey != null) {
            sound = getSoundBytes(modelKey + "::" + soundName);
            if (sound == null) {
                for (Map.Entry<String, ResourceLocation> e : SOUND_SOURCES.entrySet()) {
                    String key = e.getKey();
                    // Only match sounds belonging to this model
                    if (key.startsWith(modelKey + "::")) {
                        String snd = namePartOf(key);
                        if (snd.equals(soundName) || snd.equalsIgnoreCase(soundName)) {
                            sound = getSoundBytes(key);
                            break;
                        }
                    }
                }
            }
        } else {
            // Legacy path (no modelId, e.g. debug command)
            sound = getSoundBytes(soundName);
            if (sound == null) {
                for (String key : SOUND_SOURCES.keySet()) {
                    String snd = namePartOf(key);
                    if (snd.equals(soundName) || snd.equalsIgnoreCase(soundName)) {
                        sound = getSoundBytes(key);
                        break;
                    }
                }
            }
        }
        if (sound != null) {
            stopSound(soundName);
            playOggDirect(sound, soundName, volume, pitch);
            return;
        }

        // Step 2~4 — 命名空间音效（SoundHandler + LocalAssetProvider）
        if (!soundName.contains(":")) return;

        Minecraft mc = Minecraft.getMinecraft();
        SoundHandler handler = mc.getSoundHandler();
        boolean foundAny = false;

        // Step 2 — 原始音效名（如 minecraft:entity.arrow.shoot，1.7.10 原生）
        if (soundExistsInHandler(soundName)) {
            handler.playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation(soundName), volume));
            foundAny = true;
        }

        // Step 3 — 命名空间翻译（如 minecraft_1.21:item.trident.throw）
        ResourceLocation translated = SoundNamespaceCompat.resolve(soundName);
        if (translated != null && soundExistsInHandler(translated.toString())) {
            if (Config.DEBUG_SOUND) {
                ysmu.LOG.info("[YSMU-SOUND] namespace translation: '{}' → '{}'", soundName, translated);
            }
            handler.playSound(PositionedSoundRecord.func_147674_a(translated, volume));
            foundAny = true;
        }

        // Step 4 — 本地高版本游戏资产（绕过 SoundHandler）
        Path localOgg = LocalAssetProvider.resolveSound(soundName);
        if (localOgg != null) {
            if (Config.DEBUG_SOUND) {
                ysmu.LOG.info("[YSMU-SOUND] local asset: '{}' → {}", soundName, localOgg);
            }
            playOggDirect(localOgg, soundName, volume, pitch);
            return;
        }

        // Step 5 — 所有 fallback 均失败，输出最终警告
        if (!foundAny) {
            ysmu.LOG.warn("[YSMU-SOUND] Unable to play unknown soundEvent: {} (not found in any provider)", soundName);
        }
    }

    public static void playSound(EntityPlayer player, String soundName, float volume, float pitch) {
        playSound(player, soundName, null, volume, pitch);
    }

    public static void playSoundAtPlayer(String soundName) {
        playSoundAtPlayer(soundName, null);
    }

    public static void playSoundAtPlayer(String soundName, ResourceLocation modelId) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) playSound(mc.thePlayer, soundName, modelId, 1.0f, 1.0f);
    }

    /**
     * 由关键帧音效监听器调用。记录该音效来自哪个 GeckoLib 控制器，
     * 以便当该控制器/动画停止时能清理对应音效。
     */
    public static void onSoundKeyframe(String controllerName, String soundName) {
        onSoundKeyframe(controllerName, soundName, null);
    }

    /**
     * 由关键帧音效监听器调用。记录该音效来自哪个 GeckoLib 控制器和模型，
     * 以便当该控制器/动画停止时能清理对应音效，并使用正确的模型上下文查找音效文件。
     *
     * @param controllerName GeckoLib 控制器名称
     * @param soundName 音效名称（动画 keyframe 中定义的名称）
     * @param modelId 当前模型的 ResourceLocation，用于隔离同名音效
     */
    public static void onSoundKeyframe(String controllerName, String soundName, ResourceLocation modelId) {
        if (controllerName == null || soundName == null) return;
        if (previewRendering) {
            // During GUI preview rendering, suppress sounds from parallel controllers
            // (which would otherwise spam keyframe sounds every animation loop cycle).
            // Cap controller (focus/hover) and main controller (idle/preview) sounds
            // are still allowed — users expect to hear focus animation sound effects
            // when clicking a model button.
            if (controllerName.startsWith("parallel_") || controllerName.startsWith("pre_parallel_")) {
                return;
            }
        }
        if (Config.DEBUG_SOUND) {
            ysmu.LOG.info("[YSMU-SOUND] onSoundKeyframe: ctrl='{}' sound='{}' model={}", controllerName, soundName, modelId);
        }
        // 防抖：同一 controller+sound 在短时间内重复触发则忽略。
        // 这解决了动画子条件变化（如站立攻击→奔跑攻击）时
        // GeckoLib 重置关键帧导致声音重复播放的问题。
        String debounceKey = controllerName + "::" + soundName;
        if (modelId != null) debounceKey = modelId + "::" + debounceKey;
        long now = System.currentTimeMillis();
        Long last = SOUND_KEYFRAME_LAST_TIME.get(debounceKey);
        if (last != null && now - last < SOUND_KEYFRAME_COOLDOWN_MS) {
            if (Config.DEBUG_SOUND) {
                ysmu.LOG.info("[YSMU-SOUND] debounced: ctrl='{}' sound='{}' ({}ms since last)",
                    controllerName, soundName, now - last);
            }
            return;
        }
        SOUND_KEYFRAME_LAST_TIME.put(debounceKey, now);
        // If this controller was playing a different sound, stop the old one
        String oldSound = CONTROLLER_SOUNDS.get(controllerName);
        if (oldSound != null && !oldSound.equals(soundName)) {
            stopSound(oldSound);
        }
        CONTROLLER_SOUNDS.put(controllerName, soundName);
        playSoundAtPlayer(soundName, modelId);
    }

    /** 停止指定控制器触发的音效（动画停止时调用） */
    public static void stopController(String controllerName) {
        String soundName = CONTROLLER_SOUNDS.remove(controllerName);
        if (soundName != null) stopSound(soundName);
        // 清除该控制器的防抖记录，确保下次重新触发时能正常播放
        SOUND_KEYFRAME_LAST_TIME.keySet().removeIf(k -> k.startsWith(controllerName + "::"));
    }

    /** 停止指定名称的音效 */
    public static void stopSound(String soundName) {
        String src = ACTIVE_SOURCES.remove(soundName);
        if (src != null) stopSource(src);
    }

    /** 停止所有 YSM 发出的音效 */
    public static void stopAll() {
        for (String src : new HashSet<>(ACTIVE_SOURCES.values())) {
            stopSource(src);
        }
        ACTIVE_SOURCES.clear();
        CONTROLLER_SOUNDS.clear();
    }

    /**
     * 把活跃音源的位置更新到玩家当前坐标（每 tick 由 ClientEventHandler 调用）。
     * OpenAL 的 panning 基于音源相对听者的方位：若音源固定在播放时刻的位置而听者
     * 移动，左右平移会立即造成左右声道跳变/混叠。把音源绑定在玩家身上后相对方位
     * 恒定在正前方，panning 保持中央（第一人称自身音效语义上也正确）。
     * 成本：活跃源数 × 1 次反射 setPosition，活跃源通常只有几个，可忽略。
     */
    public static void updateSourcePositions() {
        if (ACTIVE_SOURCES.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        Object ss = resolveSndSystem();
        if (ss == null) return;
        if (sndSetPosition == null) {
            try {
                sndSetPosition = ss.getClass().getMethod(
                    "setPosition", String.class, float.class, float.class, float.class);
            } catch (NoSuchMethodException e) {
                ysmu.LOG.warn("[YSMU-SOUND] setPosition not available");
                return;
            }
        }
        float px = (float) mc.thePlayer.posX;
        float py = (float) mc.thePlayer.posY;
        float pz = (float) mc.thePlayer.posZ;
        for (String src : ACTIVE_SOURCES.values()) {
            try {
                sndSetPosition.invoke(ss, src, px, py, pz);
            } catch (Exception ignored) {}
        }
    }

    /** Returns an unmodifiable view of all registered sounds (name → in-memory OGG bytes). */
    public static Map<String, byte[]> getSoundFiles() {
        return java.util.Collections.unmodifiableMap(SOUND_FILES);
    }

    /** 从 "modelKey::name" 键中取音效名（无 "::" 时返回整串）。 */
    private static String namePartOf(String key) {
        int idx = key.indexOf("::");
        return idx >= 0 ? key.substring(idx + 2) : key;
    }

    /**
     * 后台线程预暖（geo/anim 懒加载同源解密时调用）：把从加密客户端缓存解出的
     * 模型音效字节填入内存缓存，首次播放不再走主线程解密（消除 ~0.5s 首播卡顿）。
     */
    public static void cacheModelSounds(ResourceLocation mainId, RawYsmModel raw) {
        if (raw == null || raw.soundFiles == null || raw.soundFiles.isEmpty()) return;
        String modelKey = mainId.toString();
        for (Map.Entry<String, RawYsmModel.RawDataFile> e : raw.soundFiles.entrySet()) {
            RawYsmModel.RawDataFile sf = e.getValue();
            if (sf == null || sf.data == null || sf.data.length == 0) continue;
            String key = modelKey + "::" + e.getKey();
            SOUND_FILES.put(key, sf.data);
            SOUND_SOURCES.put(key, mainId);
        }
    }

    /**
     * 取音效字节：先查内存缓存；未加载则从加密客户端缓存按需解密该模型并提取
     * 全部音效（一次解密，多音效共用）。正常路径下该模型首次使用时 geo/anim 懒加载
     * 已预暖（cacheModelSounds），此处仅在边缘场景（如 /ysm playsound 直接播放
     * 从未使用过的模型）触发。
     */
    private static byte[] getSoundBytes(String key) {
        byte[] bytes = SOUND_FILES.get(key);
        if (bytes != null) return bytes;
        ResourceLocation mainId = SOUND_SOURCES.get(key);
        if (mainId == null) return null;
        RawYsmModel raw = com.fox.ysmu.client.ClientModelManager.loadRawModelFromCache(mainId);
        if (raw == null || raw.soundFiles == null || raw.soundFiles.isEmpty()) return null;
        String prefix = key.contains("::") ? key.substring(0, key.indexOf("::") + 2) : key;
        for (Map.Entry<String, RawYsmModel.RawDataFile> e : raw.soundFiles.entrySet()) {
            RawYsmModel.RawDataFile sf = e.getValue();
            if (sf == null || sf.data == null || sf.data.length == 0) continue;
            SOUND_FILES.put(prefix + e.getKey(), sf.data);
        }
        return SOUND_FILES.get(key);
    }

    /** 清理注册的音效并停止播放 */
    public static void clear() {
        stopAll();
        SOUND_SOURCES.clear();
        SOUND_FILES.clear();
        sndSystem = null;
        sndSystemSearched = false;
        sndSetPosition = null;
    }

    // ── Internal ──────────────────────────────────────────

    private static String sanitize(String n) {
        return n.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase(java.util.Locale.ROOT);
    }

    private static void stopSource(String srcName) {
        if (srcName == null) return;
        try {
            Object ss = resolveSndSystem();
            if (ss == null) return;
            try {
                ss.getClass().getMethod("stop", String.class).invoke(ss, srcName);
            } catch (NoSuchMethodException ignored) {}
            try {
                ss.getClass().getMethod("removeSource", String.class).invoke(ss, srcName);
            } catch (NoSuchMethodException ignored) {}
        } catch (Exception e) {
            ysmu.LOG.warn("[YSMU-SOUND] Failed to stop source '{}': {}", srcName, e.getMessage());
        }
    }

    /** 查找并缓存 SoundSystem 反射句柄 */
    private static Object resolveSndSystem() {
        if (sndSystem != null) return sndSystem;
        if (sndSystemSearched) return null;
        sndSystemSearched = true;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            SoundHandler sh = mc.getSoundHandler();
            java.lang.reflect.Field sndF = null;
            for (java.lang.reflect.Field f : SoundHandler.class.getDeclaredFields()) {
                if (SoundManager.class.isAssignableFrom(f.getType())) { sndF = f; break; }
            }
            if (sndF == null) return null;
            sndF.setAccessible(true);
            SoundManager sndMgr = (SoundManager) sndF.get(sh);

            // Try known field names
            String[] names = {"field_148620_e", "sndSystem", "field_148617_c", "field_148614_b", "sndManager"};
            for (String name : names) {
                try {
                    java.lang.reflect.Field f = SoundManager.class.getDeclaredField(name);
                    f.setAccessible(true);
                    Object val = f.get(sndMgr);
                    if (val != null) { sndSystem = val; return sndSystem; }
                } catch (NoSuchFieldException ignored) {}
            }
            // Fallback: type search
            for (java.lang.reflect.Field f : SoundManager.class.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(sndMgr);
                if (val != null && val.getClass().getName().contains("SoundSystem")) {
                    sndSystem = val;
                    return sndSystem;
                }
            }
        } catch (Exception e) {
            ysmu.LOG.warn("[YSMU-SOUND] Failed to resolve SoundSystem: {}", e.getMessage());
        }
        return null;
    }

    /** Check that the file is an OGG container with Vorbis audio.
     *  Reads the OGG page header + segment table, then looks for "vorbis"
     *  at the start of the first packet. Files that pass OggS check but lack
     *  Vorbis data (e.g. OGG FLAC/Opus) will crash CodecJOrbis. */
    private static boolean isValidOgg(Path path) {
        try (java.io.InputStream is = java.nio.file.Files.newInputStream(path)) {
            return isValidOggStream(is);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isValidOgg(byte[] data) {
        try (java.io.InputStream is = new java.io.ByteArrayInputStream(data)) {
            return isValidOggStream(is);
        } catch (IOException e) {
            return false;
        }
    }

    /** Check that the stream is an OGG container with Vorbis audio.
     *  Reads the OGG page header + segment table, then looks for "vorbis"
     *  at the start of the first packet. Files that pass OggS check but lack
     *  Vorbis data (e.g. OGG FLAC/Opus) will crash CodecJOrbis. */
    private static boolean isValidOggStream(java.io.InputStream is) throws IOException {
        byte[] hdr = new byte[4];
        if (is.read(hdr) != 4 || hdr[0] != 'O' || hdr[1] != 'g' || hdr[2] != 'g' || hdr[3] != 'S')
            return false;
        // Skip stream_structure_version (1), header_type_flag (1), granule_position (8),
        // bitstream_serial_number (4), page_sequence_number (4), page_checksum (4) = 22 bytes
        long skipped = is.skip(22);
        if (skipped < 22) return false;
        int pageSegments = is.read();
        if (pageSegments < 0) return false;
        // Skip segment table (pageSegments bytes)
        skipped = is.skip(pageSegments);
        if (skipped < pageSegments) return false;
        // First packet should be Vorbis identification header: packet_type=1, "vorbis"
        int packetType = is.read();
        if (packetType != 1) return false;
        byte[] vorbis = new byte[6];
        return is.read(vorbis) == 6
            && vorbis[0] == 'v' && vorbis[1] == 'o' && vorbis[2] == 'r'
            && vorbis[3] == 'b' && vorbis[4] == 'i' && vorbis[5] == 's';
    }

    /** 通过 SoundSystem 直接播放 OGG（本地高版本游戏资产路径）。
     *  与内存字节版（playOggDirect(byte[],...)）一致：播放前停止旧同名源，
     *  播放后把 soundName→srcName 记入 ACTIVE_SOURCES，使 stopSound/
     *  stopController/stopAll 能追踪并停止这些音效，避免每次播放泄漏音源。 */
    private static void playOggDirect(Path oggPath, String soundName, float volume, float pitch) {
        Object ss = resolveSndSystem();
        if (ss == null) return;
        // Skip invalid OGG files – passing them to CodecJOrbis can freeze the
        // SoundSystem background thread.
        if (!java.nio.file.Files.exists(oggPath) || !isValidOgg(oggPath)) {
            ysmu.LOG.warn("[YSMU-SOUND] skipping invalid OGG: {}", oggPath);
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return; // world not fully loaded yet
        // 停止旧同名源（对齐内存字节路径：同名音效重播前先停旧的）。
        if (soundName != null) stopSound(soundName);
        try {
            String srcName = "ysm_" + sourceCounter.incrementAndGet();
            float px = (float) mc.thePlayer.posX;
            float py = (float) mc.thePlayer.posY;
            float pz = (float) mc.thePlayer.posZ;
            java.net.URL url = oggPath.toUri().toURL();

            // paulscode SoundSystem selects the codec based on file extension.
            // Assets from a game directory have no extension (SHA-1 hash names),
            // so we need a .ogg filename for CodecJOrbis to detect the Vorbis
            // codec.  Create a zero-copy hard link in the cache dir instead of
            // duplicating the bytes – same disk blocks, just an extra name.
            Path playPath = oggPath;
            String fileName = oggPath.getFileName().toString();
            if (!fileName.endsWith(".ogg") && !fileName.endsWith(".OGG")) {
                Path cached = SOUND_CACHE.resolve(fileName + ".ogg");
                if (!java.nio.file.Files.exists(cached)) {
                    try {
                        java.nio.file.Files.createDirectories(SOUND_CACHE);
                        // Try hard link first (zero-copy, same inode/blocks)
                        try {
                            java.nio.file.Files.createLink(cached, oggPath);
                        } catch (java.io.IOException | UnsupportedOperationException e) {
                            // Hard link not available (e.g. different volume, FAT32) –
                            // fall back to copy.
                            java.nio.file.Files.copy(oggPath, cached,
                                java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                        }
                    } catch (java.io.IOException e) {
                        ysmu.LOG.warn("[YSMU-SOUND] failed to prepare {}: {}", cached, e.getMessage());
                    }
                }
                if (java.nio.file.Files.exists(cached)) {
                    playPath = cached;
                }
            }

            // Use the URL overload with streaming=true to bypass
            // LibraryLWJGLOpenAL.loadSound() (which uses Java AudioSystem and
            // doesn't support OGG).  Streaming sources go through CodecJOrbis
            // directly, which understands Vorbis.
            java.net.URL absUrl = playPath.toAbsolutePath().toUri().toURL();
            try {
                // boolean参数: priority=false, toLoop=false → 不循环播放
                ss.getClass().getMethod("newSource", boolean.class, String.class,
                    java.net.URL.class, String.class, boolean.class, float.class,
                    float.class, float.class, int.class, float.class)
                    .invoke(ss, false, srcName, absUrl, absUrl.toString(),
                        false, px, py, pz, 0, 16f);
            } catch (NoSuchMethodException e) {
                ysmu.LOG.warn("[YSMU-SOUND] newSource(URL) not available");
                return;
            }
            // Set pitch/volume before play (Minecraft's order)
            try { ss.getClass().getMethod("setPitch", String.class, float.class).invoke(ss, srcName, pitch); } catch (NoSuchMethodException ignored) {}
            try { ss.getClass().getMethod("setVolume", String.class, float.class).invoke(ss, srcName, volume); } catch (NoSuchMethodException ignored) {}
            ss.getClass().getMethod("play", String.class).invoke(ss, srcName);
            // 追踪音源（对齐内存字节版），使停止路径能覆盖本地资产音效。
            if (soundName != null) ACTIVE_SOURCES.put(soundName, srcName);
            if (Config.DEBUG_SOUND) ysmu.LOG.info("[YSMU-SOUND] playing '{}' as {}", oggPath.getFileName(), srcName);
        } catch (Exception e) {
            ysmu.LOG.warn("[YSMU-SOUND] Failed to play: {}", e.getMessage());
        }
    }

    /**
     * 通过 SoundSystem 播放内存中的 OGG 字节（模型音效专用，不落盘明文）。
     * 用自定义 URLStreamHandler 构造一个带 ".ogg" 路径的 "file:" URL，
     * openStream() 返回内存字节流——下游（CodecJOrbis 按扩展名选择、OGG 头校验）
     * 与文件播放路径完全一致。
     */
    private static void playOggDirect(byte[] data, String soundName, float volume, float pitch) {
        if (data == null || data.length == 0) return;
        Object ss = resolveSndSystem();
        if (ss == null) return;
        // Skip invalid OGG data – passing them to CodecJOrbis can freeze the
        // SoundSystem background thread.
        if (!isValidOgg(data)) {
            ysmu.LOG.warn("[YSMU-SOUND] skipping invalid in-memory OGG '{}' ({} bytes)", soundName, data.length);
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return; // world not fully loaded yet
        try {
            String srcName = "ysm_" + sourceCounter.incrementAndGet();
            float px = (float) mc.thePlayer.posX;
            float py = (float) mc.thePlayer.posY;
            float pz = (float) mc.thePlayer.posZ;
            final byte[] payload = data;
            java.net.URL url = new java.net.URL("file", "", -1,
                "/ysmu_sounds/" + sanitize(soundName) + "_" + Integer.toHexString(data.length) + ".ogg",
                new java.net.URLStreamHandler() {
                    @Override
                    protected java.net.URLConnection openConnection(java.net.URL u) {
                        return new java.net.URLConnection(u) {
                            @Override public void connect() {}
                            @Override public int getContentLength() { return payload.length; }
                            @Override public java.io.InputStream getInputStream() {
                                return new java.io.ByteArrayInputStream(payload);
                            }
                        };
                    }
                });
            try {
                // boolean参数: priority=false, toLoop=false → 不循环播放
                ss.getClass().getMethod("newSource", boolean.class, String.class,
                    java.net.URL.class, String.class, boolean.class, float.class,
                    float.class, float.class, int.class, float.class)
                    .invoke(ss, false, srcName, url, url.toString(), false, px, py, pz, 0, 16f);
            } catch (NoSuchMethodException e) {
                ysmu.LOG.warn("[YSMU-SOUND] newSource(URL) not available");
                return;
            }
            // Set pitch/volume before play (Minecraft's order)
            try { ss.getClass().getMethod("setPitch", String.class, float.class).invoke(ss, srcName, pitch); } catch (NoSuchMethodException ignored) {}
            try { ss.getClass().getMethod("setVolume", String.class, float.class).invoke(ss, srcName, volume); } catch (NoSuchMethodException ignored) {}
            ss.getClass().getMethod("play", String.class).invoke(ss, srcName);
            ACTIVE_SOURCES.put(soundName, srcName);
            if (Config.DEBUG_SOUND) ysmu.LOG.info("[YSMU-SOUND] playing in-memory '{}' as {}", soundName, srcName);
        } catch (Exception e) {
            ysmu.LOG.warn("[YSMU-SOUND] Failed to play: {}", e.getMessage());
        }
    }
}
