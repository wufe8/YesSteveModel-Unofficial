package com.fox.ysmu.client.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
 * 利用反射直接通过 paulscode SoundSystem 播放缓存的 OGG 文件，
 * 绕开 1.7.10 残缺的 SoundRegistry 资源加载流程。
 */
public final class YSMSoundManager {

    private static final Path SOUND_CACHE = ServerModelManager.CACHE.resolve("sounds");
    private static final Map<String, Path> SOUND_FILES = new ConcurrentHashMap<>();
    private static volatile java.util.concurrent.atomic.AtomicInteger sourceCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    private YSMSoundManager() {}

    public static void registerModelSounds(ResourceLocation modelId, RawYsmModel raw) {
        if (raw.soundFiles == null || raw.soundFiles.isEmpty()) {
            ysmu.LOG.debug("[YSM Sound] registerModelSounds called for {} but no soundFiles", modelId);
            return;
        }
        ysmu.LOG.info("[YSM Sound] registerModelSounds for {}, soundFiles count={}", modelId, raw.soundFiles.size());
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
                ysmu.LOG.info("[YSM Sound]  cached '{}' → {} ({} bytes)", name, file, sf.data.length);
            } catch (IOException ex) {
                ysmu.LOG.warn("Failed to cache sound {}: {}", name, ex.getMessage());
            }
        }
    }

    public static void playSound(EntityPlayer player, String soundName, float volume, float pitch) {
        if (soundName == null || soundName.isEmpty()) return;
        // Try model sound
        Path file = SOUND_FILES.get(soundName);
        if (file != null) {
            playOggDirect(file, volume, pitch);
            return;
        }
        // Try vanilla sound
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

    public static void clear() { SOUND_FILES.clear(); }

    private static String sanitize(String n) {
        return n.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 通过反射直接操作 Minecraft 的 SoundManager.sndSystem 播放 OGG。
     * 1.7.10 中 sndSystem 字段实际类型是 ISoundSystem（内部接口），
     * 它的 func_148692_a_ 方法接受 ResourceLocation 参数。
     */
    @SuppressWarnings("unchecked")
    private static void playOggDirect(Path oggPath, float volume, float pitch) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            SoundHandler sh = mc.getSoundHandler();
            // Get SoundManager from SoundHandler
            java.lang.reflect.Field sndF = null;
            for (java.lang.reflect.Field f : SoundHandler.class.getDeclaredFields()) {
                if (SoundManager.class.isAssignableFrom(f.getType())) { sndF = f; break; }
            }
            if (sndF == null) { ysmu.LOG.warn("[YSM Sound] Cannot find SoundManager field"); return; }
            sndF.setAccessible(true);
            SoundManager sndMgr = (SoundManager) sndF.get(sh);

            // -- DEBUG: dump all SoundManager fields --
            ysmu.LOG.info("[YSM Sound] SoundManager class={}", sndMgr.getClass().getName());
            for (java.lang.reflect.Field f : SoundManager.class.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(sndMgr);
                String typeName = (val == null) ? "null" : val.getClass().getName();
                ysmu.LOG.info("[YSM Sound]  field '{}' type={} → {}", f.getName(), f.getType().getName(), typeName);
            }

            // Try to find sndSystem by common field names
            String[] candidateNames = {"sndSystem", "field_148617_c", "field_148614_b", "sndManager"};
            Object sndSystem = null;
            for (String name : candidateNames) {
                try {
                    java.lang.reflect.Field f = SoundManager.class.getDeclaredField(name);
                    f.setAccessible(true);
                    sndSystem = f.get(sndMgr);
                    if (sndSystem != null) {
                        ysmu.LOG.info("[YSM Sound] Found sndSystem via field name '{}': {}", name, sndSystem.getClass().getName());
                        break;
                    }
                } catch (NoSuchFieldException ignored) {}
            }
            if (sndSystem == null) {
                ysmu.LOG.warn("[YSM Sound] Cannot find sndSystem field by any name");
                return;
            }

            // -- Try to use Minecraft's internal playSound method via reflection --
            // SoundManager has a private method: playSound(SoundPoolEntry, float, float, double, double, double, boolean)
            // We can create a SoundPoolEntry with our file path and call this method
            String absPath = oggPath.toAbsolutePath().toString();
            ysmu.LOG.info("[YSM Sound]  absPath={}", absPath);

            // Method 1: Try func_148692_a_ on sndSystem (ISoundSystem method, takes ResourceLocation)
            try {
                String srcName = "ysm_" + sourceCounter.incrementAndGet();
                float px = (float) mc.thePlayer.posX;
                float py = (float) mc.thePlayer.posY;
                float pz = (float) mc.thePlayer.posZ;
                // Use ResourceLocation with file:// protocol
                ResourceLocation fileLoc = new ResourceLocation("ysm_sounds", oggPath.getFileName().toString().replace(".ogg", ""));
                // Try func_148692_a_(String name, ResourceLocation loc, ...)
                // OR newSource(String name, ResourceLocation loc, ...)
                // The ISoundSystem interface has: void func_148692_a_(String name, ResourceLocation loc, boolean stream, double x, double y, double z, int attmodel, float distOrRoll)
                java.lang.reflect.Method m = null;
                try {
                    m = sndSystem.getClass().getMethod("func_148692_a_", String.class, ResourceLocation.class, boolean.class, double.class, double.class, double.class, int.class, float.class);
                } catch (NoSuchMethodException e1) {
                    try {
                        m = sndSystem.getClass().getMethod("newSource", boolean.class, String.class, ResourceLocation.class, boolean.class, double.class, double.class, double.class, int.class, float.class);
                    } catch (NoSuchMethodException e2) {
                        // list all methods
                        StringBuilder sb = new StringBuilder();
                        for (java.lang.reflect.Method mm : sndSystem.getClass().getMethods()) {
                            sb.append(mm.getName()).append("(");
                            Class<?>[] pts = mm.getParameterTypes();
                            for (int i = 0; i < pts.length; i++) {
                                if (i > 0) sb.append(",");
                                sb.append(pts[i].getSimpleName());
                            }
                            sb.append(") ");
                        }
                        ysmu.LOG.info("[YSM Sound] Available methods on sndSystem: {}", sb.toString());
                        throw e2;
                    }
                }
                if (m != null) {
                    m.invoke(sndSystem, false, srcName, fileLoc, false, (double) px, (double) py, (double) pz, 2, 16.0);
                    sndSystem.getClass().getMethod("setPitch", String.class, float.class).invoke(sndSystem, srcName, pitch);
                    sndSystem.getClass().getMethod("setVolume", String.class, float.class).invoke(sndSystem, srcName, volume);
                    sndSystem.getClass().getMethod("play", String.class).invoke(sndSystem, srcName);
                    ysmu.LOG.info("[YSM Sound] ISoundSystem method succeeded: {} as {}", oggPath.getFileName(), srcName);
                    return;
                }
            } catch (Exception e) {
                ysmu.LOG.info("[YSM Sound] ISoundSystem method failed: {}", e.getMessage());
            }

            // Method 2: Direct paulscode SoundSystem.newSource with file:/// URL
            try {
                String srcName = "ysm_" + sourceCounter.incrementAndGet();
                float px = (float) mc.thePlayer.posX;
                float py = (float) mc.thePlayer.posY;
                float pz = (float) mc.thePlayer.posZ;
                java.net.URL url = oggPath.toUri().toURL();
                String urlStr = url.toString();
                ysmu.LOG.info("[YSM Sound]  Trying paulscode newSource with URL: {}", urlStr);
                sndSystem.getClass().getMethod("newSource", boolean.class, String.class,
                    java.net.URL.class, String.class, boolean.class, float.class, float.class,
                    float.class, int.class, float.class)
                    .invoke(sndSystem, false, srcName, url, urlStr, false, px, py, pz, 0, 16f);
                sndSystem.getClass().getMethod("setPitch", String.class, float.class)
                    .invoke(sndSystem, srcName, pitch);
                sndSystem.getClass().getMethod("setVolume", String.class, float.class)
                    .invoke(sndSystem, srcName, volume);
                sndSystem.getClass().getMethod("play", String.class).invoke(sndSystem, srcName);
                // Follow Minecraft's ordering: setPitch/setVolume BEFORE play
                ysmu.LOG.info("[YSM Sound] paulscode URL method succeeded: {} as {}", oggPath.getFileName(), srcName);
                return;
            } catch (Exception e) {
                ysmu.LOG.warn("[YSM Sound] paulscode URL method also failed: {}", e.getMessage());
            }
        } catch (Exception e) {
            ysmu.LOG.warn("[YSM Sound] DirectSound failed: {}", e.getMessage());
        }
    }

    /** Plays a model sound via the SoundSystem. */
    public static void playOggDirect(String soundName) {
        Path file = SOUND_FILES.get(soundName);
        if (file != null) playOggDirect(file, 1.0f, 1.0f);
    }
}
