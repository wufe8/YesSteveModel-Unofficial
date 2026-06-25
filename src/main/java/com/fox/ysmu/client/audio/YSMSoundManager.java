package com.fox.ysmu.client.audio;

import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.resources.FallbackResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.client.resources.data.IMetadataSerializer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.ysmu;

/**
 * YSM 模型自定义音效管理器。
 * 将 OGG 文件提取到缓存目录，通过注入自定义 IResourcePack
 * 使 Minecraft 音效系统可直接播放模型音效。
 */
public final class YSMSoundManager {

    private static final Path SOUND_CACHE = ServerModelManager.CACHE.resolve("sounds");
    private static final String SOUND_DOMAIN = "ysm_sounds";
    /** 音效名（动画/控制器中的 effect 值）→ 缓存文件名 */
    private static final Map<String, String> SOUND_FILES = new ConcurrentHashMap<>();
    private static volatile boolean packInjected = false;

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
            if (sf == null || sf.data == null || sf.data.length == 0) {
                ysmu.LOG.debug("[YSM Sound]  skip empty soundFile '{}'", name);
                continue;
            }
            String hash = sf.hash.length() > 8 ? sf.hash.substring(0, 8) : sf.hash;
            String file = sanitize(name) + "_" + hash + ".ogg";
            Path path = SOUND_CACHE.resolve(file);
            try {
                if (!Files.exists(path)) Files.write(path, sf.data);
                SOUND_FILES.put(name, file);
                ysmu.LOG.info("[YSM Sound]  cached '{}' → {} ({} bytes)", name, file, sf.data.length);
            } catch (IOException ex) {
                ysmu.LOG.warn("Failed to cache sound {}: {}", name, ex.getMessage());
            }
        }
        injectPack();
    }

    public static void playSound(EntityPlayer player, String soundName, float volume, float pitch) {
        if (soundName == null || soundName.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        ysmu.LOG.info("[YSM Sound] playSound called: '{}' (vol={}, pitch={})", soundName, volume, pitch);
        // Try model sound via our custom resource pack domain
        if (SOUND_FILES.containsKey(soundName)) {
            String s = sanitize(soundName);
            ResourceLocation loc = new ResourceLocation(SOUND_DOMAIN, s);
            ysmu.LOG.info("[YSM Sound]  playing model sound as {}", loc);
            mc.getSoundHandler().playSound(
                PositionedSoundRecord.func_147674_a(loc, volume));
            return;
        }
        // Try vanilla
        if (soundName.contains(":")) {
            ResourceLocation loc = new ResourceLocation(soundName);
            ysmu.LOG.info("[YSM Sound]  playing vanilla sound as {}", loc);
            mc.getSoundHandler().playSound(
                PositionedSoundRecord.func_147674_a(loc, volume));
            return;
        }
        ysmu.LOG.warn("[YSM Sound]  sound '{}' not found in SOUND_FILES (keys={})", soundName, SOUND_FILES.keySet());
    }

    public static void playSoundAtPlayer(String soundName) {
        Minecraft mc = Minecraft.getMinecraft();
        ysmu.LOG.info("[YSM Sound] playSoundAtPlayer: '{}'", soundName);
        if (mc.thePlayer != null) playSound(mc.thePlayer, soundName, 1.0f, 1.0f);
        else ysmu.LOG.warn("[YSM Sound]  mc.thePlayer is null, cannot play");
    }

    public static void clear() { SOUND_FILES.clear(); packInjected = false; }

    private static String sanitize(String n) {
        return n.replaceAll("[^a-z0-9._-]", "_").toLowerCase(java.util.Locale.ROOT);
    }

    // ── Custom resource pack injection ──────────────────────────

    @SuppressWarnings("unchecked")
    private static void injectPack() {
        if (packInjected) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (!(mc.getResourceManager() instanceof SimpleReloadableResourceManager)) return;
            SimpleReloadableResourceManager rm = (SimpleReloadableResourceManager) mc.getResourceManager();
            // Reflection: get domainResourceManagers map
            java.lang.reflect.Field f = null;
            for (java.lang.reflect.Field ff : SimpleReloadableResourceManager.class.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(ff.getType())) { f = ff; break; }
            }
            if (f == null) return;
            f.setAccessible(true);
            Map<String, FallbackResourceManager> domains = (Map<String, FallbackResourceManager>) f.get(rm);
            FallbackResourceManager fbr = domains.get(SOUND_DOMAIN);
            if (fbr == null) {
                // Get IMetadataSerializer from any existing domain manager
                IMetadataSerializer metaSer = null;
                for (FallbackResourceManager existing : domains.values()) {
                    try {
                        java.lang.reflect.Field metaF = FallbackResourceManager.class.getDeclaredFields()[1];
                        metaF.setAccessible(true);
                        metaSer = (IMetadataSerializer) metaF.get(existing);
                        break;
                    } catch (Exception ignored) {}
                }
                if (metaSer == null) {
                    // Last resort: get from SimpleReloadableResourceManager
                    for (java.lang.reflect.Field ff : SimpleReloadableResourceManager.class.getDeclaredFields()) {
                        if (IMetadataSerializer.class.isAssignableFrom(ff.getType())) {
                            ff.setAccessible(true);
                            metaSer = (IMetadataSerializer) ff.get(rm);
                            break;
                        }
                    }
                }
                fbr = new FallbackResourceManager(metaSer);
                domains.put(SOUND_DOMAIN, fbr);
            }
            // Get pack list from FallbackResourceManager
            java.lang.reflect.Field listF = null;
            for (java.lang.reflect.Field ff : FallbackResourceManager.class.getDeclaredFields()) {
                if (java.util.List.class.isAssignableFrom(ff.getType())) { listF = ff; break; }
            }
            if (listF != null) {
                listF.setAccessible(true);
                java.util.List<IResourcePack> packs = (java.util.List<IResourcePack>) listF.get(fbr);
                boolean exists = packs.stream().anyMatch(p -> p instanceof YsmSoundPack);
                if (!exists) packs.add(new YsmSoundPack());
            }
            packInjected = true;
            ysmu.LOG.info("[YSM Sound] Resource pack injected for domain '{}', SOUND_FILES={}", SOUND_DOMAIN, SOUND_FILES);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to inject sound pack: {}", e.getMessage());
        }
    }

    /** Serves OGG files from the YSM sound cache directory. */
    private static class YsmSoundPack implements IResourcePack {
        @Override
        public InputStream getInputStream(ResourceLocation loc) throws IOException {
            ysmu.LOG.info("[YSM SoundPack] getInputStream called: {}", loc);
            if (!SOUND_DOMAIN.equals(loc.getResourceDomain()))
                throw new FileNotFoundException(loc.toString());
            String p = loc.getResourcePath();
            if (p.startsWith("sounds/")) p = p.substring(7);
            if (!p.endsWith(".ogg")) p += ".ogg";
            ysmu.LOG.info("[YSM SoundPack]  resolved path = '{}'", p);
            // Try exact match first
            Path file = SOUND_CACHE.resolve(p);
            if (Files.exists(file)) {
                ysmu.LOG.info("[YSM SoundPack]  exact match: {}", file);
                return Files.newInputStream(file);
            }
            // Try prefix match: requested name might be without hash suffix
            String baseName = p.contains(".ogg") ? p.substring(0, p.length() - 4) : p;
            for (String v : SOUND_FILES.values()) {
                if (v.startsWith(baseName + "_") || v.equals(p) || v.equals(loc.getResourcePath())) {
                    Path f2 = SOUND_CACHE.resolve(v);
                    if (Files.exists(f2)) {
                        ysmu.LOG.info("[YSM SoundPack]  prefix match: {} → {}", v, f2);
                        return Files.newInputStream(f2);
                    }
                }
            }
            ysmu.LOG.warn("[YSM SoundPack]  NOT FOUND. SOUND_FILES values: {}", SOUND_FILES.values());
            throw new FileNotFoundException("YSM sound: " + loc);
        }
        @Override public boolean resourceExists(ResourceLocation loc) {
            if (!SOUND_DOMAIN.equals(loc.getResourceDomain())) return false;
            String p = loc.getResourcePath();
            if (p.startsWith("sounds/")) p = p.substring(7);
            if (!p.endsWith(".ogg")) p += ".ogg";
            if (Files.exists(SOUND_CACHE.resolve(p))) return true;
            String baseName = p.contains(".ogg") ? p.substring(0, p.length() - 4) : p;
            boolean found = SOUND_FILES.values().stream().anyMatch(v -> v.startsWith(baseName + "_"));
            ysmu.LOG.info("[YSM SoundPack] resourceExists({}): {} (resolved='{}')", loc, found, p);
            return found;
        }
        @Override public Set<String> getResourceDomains() { return Collections.singleton(SOUND_DOMAIN); }
        @Override public IMetadataSection getPackMetadata(IMetadataSerializer s, String k) {
            ysmu.LOG.info("[YSM SoundPack] getPackMetadata({})", k);
            return null;
        }
        @Override public String getPackName() { return "ysm_sounds"; }
        @Override public BufferedImage getPackImage() throws IOException {
            throw new FileNotFoundException("No pack image for YSM sounds");
        }
    }
}
