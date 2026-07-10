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
    private static final Map<String, Path> SOUND_FILES = new ConcurrentHashMap<>();
    /** soundName → SoundSystem source name */
    private static final Map<String, String> ACTIVE_SOURCES = new ConcurrentHashMap<>();
    /** GeckoLib controller name → last sound name triggered by its keyframe */
    private static final Map<String, String> CONTROLLER_SOUNDS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger sourceCounter = new java.util.concurrent.atomic.AtomicInteger(0);
    /** Lazily cached SoundSystem reflection handle */
    private static Object sndSystem = null;
    private static boolean sndSystemSearched = false;
    // Cooldown: disabled — the real issue is sounds playing when they shouldn't,
    // not rapid retriggering. See cap_controller '使用' keyframe on GUI model select.
    // private static final Map<String, Long> LAST_PLAY_TIME = new ConcurrentHashMap<>();
    // private static final long SOUND_COOLDOWN_MS = 500L;

    private YSMSoundManager() {}

    // ── Public API ────────────────────────────────────────

    public static void registerModelSounds(ResourceLocation modelId, RawYsmModel raw) {
        if (raw.soundFiles == null || raw.soundFiles.isEmpty()) return;
        try { Files.createDirectories(SOUND_CACHE); } catch (IOException e) {
            ysmu.LOG.warn("Failed to create sound cache", e);
            return;
        }
        for (Map.Entry<String, RawYsmModel.RawDataFile> e : raw.soundFiles.entrySet()) {
            String name = e.getKey();
            RawYsmModel.RawDataFile sf = e.getValue();
            if (sf == null || sf.data == null || sf.data.length == 0) continue;
            String hash = sf.hash.length() > 8 ? sf.hash.substring(0, 8) : sf.hash;
            String file = sanitize(name) + "_" + hash + ".ogg";
            Path path = SOUND_CACHE.resolve(file);
            try {
                if (!Files.exists(path)) Files.write(path, sf.data);
                SOUND_FILES.put(name, path);
                ysmu.LOG.info("[YSM Sound] cached '{}' → {} ({} bytes)", name, file, sf.data.length);
            } catch (IOException ex) {
                ysmu.LOG.warn("Failed to cache sound {}: {}", name, ex.getMessage());
            }
        }
    }

    /**
     * 播放模型音效。如果同名音效已在播放，先停止旧的再播新的。
     * 会先停止所有活跃的 YSM 音源（防止音源泛滥）。
     */
    public static void playSound(EntityPlayer player, String soundName, float volume, float pitch) {
        if (soundName == null || soundName.isEmpty()) return;

        // Cooldown disabled — the real issue is sounds playing when they shouldn't.
        // long now = System.currentTimeMillis();
        // Long lastPlay = LAST_PLAY_TIME.get(soundName);
        // if (lastPlay != null && (now - lastPlay) < SOUND_COOLDOWN_MS) {
        //     ysmu.LOG.warn("[YSM Sound] COOLDOWN: '{}' blocked ({}ms since last play, min={}ms)",
        //         soundName, (now - lastPlay), SOUND_COOLDOWN_MS);
        //     String throttleKey = "stack_" + soundName;
        //     Long lastStack = LAST_PLAY_TIME.get(throttleKey);
        //     if (lastStack == null || (now - lastStack) > 10000L) {
        //         LAST_PLAY_TIME.put(throttleKey, now);
        //         java.io.StringWriter sw = new java.io.StringWriter();
        //         new Throwable("playSound rapid retrigger caller").printStackTrace(new java.io.PrintWriter(sw));
        //         String[] lines = sw.toString().split("\n");
        //         StringBuilder sb = new StringBuilder();
        //         for (int i = 0; i < Math.min(lines.length, 20); i++) {
        //             sb.append(lines[i]).append("\n");
        //         }
        //         ysmu.LOG.warn("[YSM Sound] COOLDOWN caller stack for '{}':\n{}", soundName, sb);
        //     }
        //     return;
        // }
        // LAST_PLAY_TIME.put(soundName, now);

        // Model sound: try exact name lookup first, then filename-based lookup
        Path file = SOUND_FILES.get(soundName);
        if (file == null) {
            // Fallback: search by the cached filename (e.g. ___93154897.ogg → '使用')
            for (Map.Entry<String, Path> e : SOUND_FILES.entrySet()) {
                if (e.getValue().getFileName().toString().equals(soundName)
                    || e.getValue().getFileName().toString().equalsIgnoreCase(soundName)) {
                    file = e.getValue();
                    break;
                }
            }
        }
        if (file != null) {
            // Stop previous sound with same name
            stopSound(soundName);
            playOggDirect(file, volume, pitch);
            return;
        }
        // Vanilla fallback
        if (soundName.contains(":")) {
            Minecraft mc = Minecraft.getMinecraft();
            mc.getSoundHandler().playSound(
                PositionedSoundRecord.func_147674_a(new ResourceLocation(soundName), volume));
        }
    }

    public static void playSoundAtPlayer(String soundName) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) playSound(mc.thePlayer, soundName, 1.0f, 1.0f);
    }

    /**
     * 由关键帧音效监听器调用。记录该音效来自哪个 GeckoLib 控制器，
     * 以便当该控制器/动画停止时能清理对应音效。
     */
    public static void onSoundKeyframe(String controllerName, String soundName) {
        if (controllerName == null || soundName == null) return;
        // If this controller was playing a different sound, stop the old one
        String oldSound = CONTROLLER_SOUNDS.get(controllerName);
        if (oldSound != null && !oldSound.equals(soundName)) {
            stopSound(oldSound);
        }
        CONTROLLER_SOUNDS.put(controllerName, soundName);
        playSoundAtPlayer(soundName);
    }

    /** 停止指定控制器触发的音效（动画停止时调用） */
    public static void stopController(String controllerName) {
        String soundName = CONTROLLER_SOUNDS.remove(controllerName);
        if (soundName != null) stopSound(soundName);
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

    /** Returns an unmodifiable view of all registered sound files (name → path). */
    public static Map<String, Path> getSoundFiles() {
        return java.util.Collections.unmodifiableMap(SOUND_FILES);
    }

    /** 清理注册的音效文件并停止播放 */
    public static void clear() {
        stopAll();
        SOUND_FILES.clear();
        sndSystem = null;
        sndSystemSearched = false;
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
            ysmu.LOG.warn("[YSM Sound] Failed to stop source '{}': {}", srcName, e.getMessage());
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
            ysmu.LOG.warn("[YSM Sound] Failed to resolve SoundSystem: {}", e.getMessage());
        }
        return null;
    }

    /** Check that the file is an OGG container with Vorbis audio.
     *  Reads the OGG page header + segment table, then looks for "vorbis"
     *  at the start of the first packet. Files that pass OggS check but lack
     *  Vorbis data (e.g. OGG FLAC/Opus) will crash CodecJOrbis. */
    private static boolean isValidOgg(Path path) {
        try (java.io.InputStream is = java.nio.file.Files.newInputStream(path)) {
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
        } catch (IOException e) {
            return false;
        }
    }

    /** 通过 SoundSystem 直接播放 OGG */
    private static void playOggDirect(Path oggPath, float volume, float pitch) {
        Object ss = resolveSndSystem();
        if (ss == null) return;
        // Skip invalid OGG files – passing them to CodecJOrbis can freeze the
        // SoundSystem background thread.
        if (!java.nio.file.Files.exists(oggPath) || !isValidOgg(oggPath)) {
            ysmu.LOG.warn("[YSM Sound] skipping invalid OGG: {}", oggPath);
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return; // world not fully loaded yet
        try {
            String srcName = "ysm_" + sourceCounter.incrementAndGet();
            float px = (float) mc.thePlayer.posX;
            float py = (float) mc.thePlayer.posY;
            float pz = (float) mc.thePlayer.posZ;
            java.net.URL url = oggPath.toUri().toURL();

            // Use the URL overload with streaming=true to bypass
            // LibraryLWJGLOpenAL.loadSound() (which uses Java AudioSystem and
            // doesn't support OGG).  Streaming sources go through CodecJOrbis
            // directly, which understands Vorbis.
            java.net.URL absUrl = oggPath.toAbsolutePath().toUri().toURL();
            try {
                // boolean参数: priority=false, toLoop=false → 不循环播放
                ss.getClass().getMethod("newSource", boolean.class, String.class,
                    java.net.URL.class, String.class, boolean.class, float.class,
                    float.class, float.class, int.class, float.class)
                    .invoke(ss, false, srcName, absUrl, absUrl.toString(),
                        false, px, py, pz, 0, 16f);
            } catch (NoSuchMethodException e) {
                ysmu.LOG.warn("[YSM Sound] newSource(URL) not available");
                return;
            }
            // Set pitch/volume before play (Minecraft's order)
            try { ss.getClass().getMethod("setPitch", String.class, float.class).invoke(ss, srcName, pitch); } catch (NoSuchMethodException ignored) {}
            try { ss.getClass().getMethod("setVolume", String.class, float.class).invoke(ss, srcName, volume); } catch (NoSuchMethodException ignored) {}
            ss.getClass().getMethod("play", String.class).invoke(ss, srcName);

            // Track source (if we can get the sound name from the oggPath)
            String soundName = findSoundNameByPath(oggPath);
            if (soundName != null) {
                ACTIVE_SOURCES.put(soundName, srcName);
            }
            ysmu.LOG.info("[YSM Sound] playing '{}' as {}", oggPath.getFileName(), srcName);
        } catch (Exception e) {
            ysmu.LOG.warn("[YSM Sound] Failed to play: {}", e.getMessage());
        }
    }

    private static String findSoundNameByPath(Path oggPath) {
        return SOUND_FILES.entrySet().stream()
            .filter(e -> e.getValue().equals(oggPath))
            .map(Map.Entry::getKey)
            .findFirst().orElse(null);
    }
}
