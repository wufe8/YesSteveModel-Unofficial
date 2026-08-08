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
        TEXTURES.clear();
        FAILED.clear();
        FALLBACK_WARNED.clear();
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
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) {
                if (com.fox.ysmu.Config.DEBUG_PARTICLE) {
                    ysmu.LOG.warn("[YSMU-PARTICLE] Failed to decode texture bytes for '{}'", particleName);
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
                    particleName, texId, image.getWidth(), image.getHeight());
            }
            return texId;
        } catch (Exception e) {
            if (com.fox.ysmu.Config.DEBUG_PARTICLE) {
                ysmu.LOG.warn("[YSMU-PARTICLE] Failed to load texture '{}': {}", particleName, e.toString());
            }
            return -1;
        }
    }
}
