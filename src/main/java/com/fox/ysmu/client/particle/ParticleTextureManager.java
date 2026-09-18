package com.fox.ysmu.client.particle;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import net.minecraft.client.renderer.texture.TextureUtil;

import com.fox.ysmu.compat.LocalAssetProvider;
import com.fox.ysmu.ysmu;

/**
 * 自定义粒子纹理加载与缓存。
 *
 * <p>从高版本游戏资产（{@link LocalAssetProvider}，复用音效那套
 * {@code assets/objects/<hash>} 读取机制）加载 {@code textures/particle/*.png}，
 * 解码为 GL 纹理并缓存（粒子每帧可能生成多个，绝不能每帧重新上传纹理）。</p>
 *
 * <p>只应在客户端线程调用（GL 上下文）。纹理随游戏生命周期缓存，不主动释放
 * （粒子数量有限，占用可忽略）。</p>
 */
public final class ParticleTextureManager {

    /** 粒子名（已剥离命名空间）→ GL 纹理 id。 */
    private static final Map<String, Integer> TEXTURES = new ConcurrentHashMap<>();
    /** 加载失败的粒子名：缓存失败状态，避免每帧重复 IO/解码。 */
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    /** 已提示过 vanilla fallback 的粒子名：DEBUG_PARTICLE 下每个名字只刷一次日志。 */
    private static final Set<String> FALLBACK_WARNED = ConcurrentHashMap.newKeySet();
    /** 纹理名（已剥离命名空间，非粒子名）→ GL 纹理 id：多帧粒子的每一帧。 */
    private static final Map<String, Integer> NAMED_TEXTURES = new ConcurrentHashMap<>();
    private static final Set<String> NAMED_FAILED = ConcurrentHashMap.newKeySet();
    /** 粒子名 → 逐帧纹理 GL id（序列粒子，如 bubble_pop_0..4）。空数组 = 没有序列定义。 */
    private static final Map<String, int[]> FRAME_TEXTURES = new ConcurrentHashMap<>();
    private static final int[] NO_FRAMES = new int[0];

    private ParticleTextureManager() {}

    /**
     * 记录一次 vanilla fallback 提示。返回 true 表示本次是首次（应打日志），
     * 后续同粒子名返回 false（静默）。仅 DEBUG_PARTICLE 下调用。
     */
    public static boolean firstFallbackWarning(String particleName) {
        return FALLBACK_WARNED.add(particleName);
    }

    /**
     * 获取粒子纹理的 GL id；不可用（无高版本游戏路径 / 找不到 PNG / 解码失败）
     * 返回 -1，并缓存失败状态避免反复尝试。
     */
    public static int getTextureId(String particleName) {
        String key = particleName;
        int colon = particleName.indexOf(':');
        if (colon >= 0) {
            key = particleName.substring(colon + 1);
        }
        if (key.isEmpty()) {
            return -1;
        }
        Integer cached = TEXTURES.get(key);
        if (cached != null) {
            return cached;
        }
        if (FAILED.contains(key)) {
            return -1;
        }
        int texId = loadTexture(key);
        if (texId < 0) {
            FAILED.add(key);
            return -1;
        }
        TEXTURES.put(key, texId);
        return texId;
    }

    /** 该粒子名是否有可用自定义纹理（失败会被缓存，不会反复 IO）。 */
    public static boolean hasTexture(String particleName) {
        return getTextureId(particleName) >= 0;
    }

    /**
     * 逐帧纹理 GL id 序列（序列粒子专用；没有序列定义时返回空数组）。
     *
     * <p>高版本 {@code particles/<name>.json} 可以声明多张纹理，粒子类用
     * {@code setSpriteFromAge} 按年龄轮播（例如 {@code bubble_pop} 的 5 张图）。1.7.10 的
     * {@code CustomParticleFX} 默认只会绑定一张，所以这里把整串 GL id 交出去，由粒子按 age 换帧。</p>
     */
    public static int[] getFrameTextureIds(String particleName) {
        String key = stripNamespaceOrSelf(particleName);
        if (key.isEmpty()) return NO_FRAMES;
        int[] cached = FRAME_TEXTURES.get(key);
        if (cached != null) return cached;
        java.util.List<String> names = LocalAssetProvider.readParticleTextureNames(key);
        if (names.size() <= 1) {
            // 单张（或没有定义）：交给 getTextureId 的单纹理路径，不重复加载。
            FRAME_TEXTURES.put(key, NO_FRAMES);
            return NO_FRAMES;
        }
        int[] ids = new int[names.size()];
        int loaded = 0;
        for (String name : names) {
            int id = loadNamedTexture(name);
            if (id > 0) ids[loaded++] = id;
        }
        if (loaded == 0) {
            FRAME_TEXTURES.put(key, NO_FRAMES);
            return NO_FRAMES;
        }
        int[] result = loaded == ids.length ? ids : java.util.Arrays.copyOf(ids, loaded);
        FRAME_TEXTURES.put(key, result);
        return result;
    }

    private static String stripNamespaceOrSelf(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        int colon = trimmed.indexOf(':');
        return colon >= 0 ? trimmed.substring(colon + 1) : trimmed;
    }

    /** 按纹理名（非粒子名）加载并缓存 {@code textures/particle/<name>.png}。 */
    private static int loadNamedTexture(String textureName) {
        Integer cached = NAMED_TEXTURES.get(textureName);
        if (cached != null) return cached;
        if (NAMED_FAILED.contains(textureName)) return -1;
        byte[] data = LocalAssetProvider.readAssetBytes("textures/particle/" + textureName + ".png");
        if (data == null) {
            NAMED_FAILED.add(textureName);
            return -1;
        }
        int texId = decodeTexture(data, textureName);
        if (texId < 0) {
            NAMED_FAILED.add(textureName);
            return -1;
        }
        NAMED_TEXTURES.put(textureName, texId);
        return texId;
    }

    /**
     * 清空纹理/失败缓存（{@code LocalAssetProvider.reset()} 配置变更时调用）。
     * 否则旧的 GL 纹理 id 与失败状态会残留，导致同一进程内"同时出现高版本粒子
     * 与 fallback 粒子"，或配置修好后仍一直 fallback（需重启才能生效）。
     */
    public static void clearCache() {
        for (int texId : TEXTURES.values()) {
            try {
                TextureUtil.deleteTexture(texId);
            } catch (Exception ignored) {
            }
        }
        for (int texId : NAMED_TEXTURES.values()) {
            try {
                TextureUtil.deleteTexture(texId);
            } catch (Exception ignored) {
            }
        }
        TEXTURES.clear();
        FAILED.clear();
        FALLBACK_WARNED.clear();
        NAMED_TEXTURES.clear();
        NAMED_FAILED.clear();
        FRAME_TEXTURES.clear();
    }

    private static int loadTexture(String particleName) {
        byte[] data = LocalAssetProvider.readParticleTextureBytes(particleName);
        if (data == null) {
            if (com.fox.ysmu.Config.DEBUG_PARTICLE) {
                ysmu.LOG.info("[YSMU-PARTICLE] no high-version texture for '{}': "
                    + "particles/<name>.json or textures/particle/<name>.png missing. "
                    + "Check HighVersionGamePath / HighVersionAssetVersion / "
                    + "HighVersionJarVersion point to a COMPLETE high-version game install "
                    + "(with textures/particle).", particleName);
            }
            return -1;
        }
        return decodeTexture(data, particleName);
    }

    /** PNG 字节 → GL 纹理 id（统一转 ARGB，失败返回 -1）。 */
    private static int decodeTexture(byte[] data, String label) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) {
                if (com.fox.ysmu.Config.DEBUG_PARTICLE) {
                    ysmu.LOG.warn("[YSMU-PARTICLE] Failed to decode texture bytes for '{}'", label);
                }
                return -1;
            }
            // 统一为 ARGB（部分 PNG 无 alpha 或为灰度，TextureUtil 需 ARGB）
            if (image.getType() != BufferedImage.TYPE_INT_ARGB) {
                BufferedImage argb = new BufferedImage(
                    image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                argb.getGraphics().drawImage(image, 0, 0, null);
                image = argb;
            }
            int texId = TextureUtil.glGenTextures();
            TextureUtil.uploadTextureImage(texId, image);
            if (com.fox.ysmu.Config.DEBUG_PARTICLE) {
                ysmu.LOG.info("[YSMU-PARTICLE] loaded custom texture '{}' -> gl{} ({}x{})",
                    label, texId, image.getWidth(), image.getHeight());
            }
            return texId;
        } catch (Exception e) {
            if (com.fox.ysmu.Config.DEBUG_PARTICLE) {
                ysmu.LOG.warn("[YSMU-PARTICLE] Failed to load texture '{}': {}", label, e.toString());
            }
            return -1;
        }
    }
}
