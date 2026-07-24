package com.fox.ysmu.compat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.util.ResourceLocation;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 音效命名空间转译层。
 *
 * <p>YSM 模型动画中引用的高版本音效名（如 {@code minecraft:entity.player.attack.crit}）
 * 在 1.7.10 中不存在。该层将原始音效名翻译到可用的命名空间下（如 Et-Futurum 的
 * {@code minecraft_1.21.10} 命名空间），使高版本音效能够通过 {@link
 * net.minecraft.client.audio.SoundHandler} 播放。</p>
 *
 * <p>命名空间提供者按注册顺序排列。调用 {@link #resolve(String)} 时会依次尝试
 * 每个提供者，返回第一个提供者的翻译结果。如果没有任何提供者能解析，
 * 则返回 {@code null}。</p>
 *
 * <p>扩展方式：调用 {@link #registerProvider(NamespaceProvider)} 注册新的命名空间提供者。
 * 将来如需自建音效下载器，只需实现 {@link NamespaceProvider} 接口并注册即可，
 * 无需修改 {@code YSMSoundManager}。</p>
 */
@SideOnly(Side.CLIENT)
public final class SoundNamespaceCompat {

    private static final List<NamespaceProvider> PROVIDERS = new ArrayList<>();
    private static boolean initialized = false;

    private SoundNamespaceCompat() {}

    /**
     * 初始化并注册内置的命名空间提供者。
     * 安全地重复调用——只会执行一次。
     */
    public static void init() {
        if (initialized) return;
        initialized = true;
        registerEtFuturumProvider();
    }

    /**
     * 注册一个命名空间提供者。
     * 提供者按注册顺序尝试——先注册的优先级更高。
     *
     * @param provider 提供者实例
     */
    public static void registerProvider(NamespaceProvider provider) {
        if (provider != null) {
            PROVIDERS.add(provider);
        }
    }

    /**
     * 解析音效名，返回对应命名空间下的 {@link ResourceLocation}。
     *
     * <p>遍历所有注册的提供者，返回第一个提供者的翻译结果。
     * 音效是否实际存在于 {@code SoundHandler} 中不由本层验证——
     * {@code SoundHandler} 对未注册的音效会静默跳过，调用方无需额外检查。</p>
     *
     * @param soundName 原始音效名（如 {@code "minecraft:entity.player.attack.crit"}）
     * @return 翻译后的 ResourceLocation，或 {@code null}
     */
    public static ResourceLocation resolve(String soundName) {
        if (soundName == null || !soundName.contains(":")) return null;
        init();
        for (NamespaceProvider provider : PROVIDERS) {
            ResourceLocation loc = provider.translate(soundName);
            if (loc != null) {
                return loc;
            }
        }
        return null;
    }

    /**
     * 返回所有已注册的提供者，供外部遍历或调试。
     */
    public static List<TranslatedSound> listAll(String soundName) {
        init();
        if (soundName == null || !soundName.contains(":")) return Collections.emptyList();
        List<TranslatedSound> results = new ArrayList<>();
        for (NamespaceProvider provider : PROVIDERS) {
            ResourceLocation loc = provider.translate(soundName);
            if (loc != null) {
                results.add(new TranslatedSound(provider.id(), loc));
            }
        }
        return results;
    }

    // ── Internal ──────────────────────────────────────────

    private static void registerEtFuturumProvider() {
        if (!Loader.isModLoaded("etfuturum")) return;
        try {
            Class<?> refClass = Class.forName("ganymedes01.etfuturum.lib.Reference");
            Field verField = refClass.getDeclaredField("MCAssetVer");
            String namespace = (String) verField.get(null);
            PROVIDERS.add(new EtFuturumProvider(namespace));
        } catch (Exception e) {
            // Et-Futurum 已加载但无法读取命名空间——静默跳过
        }
    }

    // ── Data classes ──────────────────────────────────────

    /** 命名空间提供者接口。实现此接口可添加新的音效命名空间来源。 */
    public interface NamespaceProvider {
        /** 提供者的唯一标识，用于调试和日志 */
        String id();

        /**
         * 将原始音效名翻译为当前命名空间下的 {@link ResourceLocation}。
         * 返回 {@code null} 表示该提供者不处理此音效。
         */
        ResourceLocation translate(String originalSoundName);
    }

    /** 一次翻译尝试的结果，包含提供者信息和 ResourceLocation。 */
    public static final class TranslatedSound {
        public final String providerId;
        public final ResourceLocation location;

        public TranslatedSound(String providerId, ResourceLocation location) {
            this.providerId = providerId;
            this.location = location;
        }
    }

    // ── Built-in providers ───────────────────────────────

    /**
     * Et-Futurum-Requiem 命名空间提供者。
     *
     * <p>将 {@code minecraft:xxx} 翻译为 Et-Futurum 的高版本命名空间
     * （如 {@code minecraft_1.21.10:xxx}），使其能通过 Minecraft 的
     * {@code SoundHandler} 播放 AssetDirector 已下载的音效。</p>
     */
    static class EtFuturumProvider implements NamespaceProvider {
        private final String namespace;

        EtFuturumProvider(String namespace) {
            this.namespace = namespace;
        }

        @Override
        public String id() {
            return "etfuturum";
        }

        @Override
        public ResourceLocation translate(String originalSoundName) {
            int colon = originalSoundName.indexOf(':');
            if (colon < 0) return null;
            String path = originalSoundName.substring(colon + 1);
            return new ResourceLocation(namespace, path);
        }
    }
}
