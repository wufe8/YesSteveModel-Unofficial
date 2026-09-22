package com.fox.ysmu.data;

import java.util.Map;
import java.util.UUID;

import net.minecraft.util.ResourceLocation;

import com.google.common.collect.Maps;

import it.unimi.dsi.fastutil.Pair;

/**
 * 客户端侧"NPC / 非玩家实体 → (模型, 贴图)"表，由 {@code SyncNpcDataMessage} 与
 * {@code UpdateNpcDataMessage} 填充，渲染时 {@code CustomPlayerRenderer} 会优先查它。
 *
 * <p><b>本树恒为空</b>：那两个包都没有发送方（NPC 模型是上游 Bukkit 侧集成的特性，
 * 1.7.10 端没搬触发器），所以渲染里的 {@code NPCData.contains(pid)} 分支不可达。
 * 详见 {@code NetworkHandler#initBukkit()} 的注释。</p>
 */
public final class NPCData {

    private static Map<UUID, Pair<ResourceLocation, ResourceLocation>> DATA = Maps.newHashMap();

    public static void clear() {
        DATA.clear();
    }

    public static void addAll(Map<UUID, Pair<ResourceLocation, ResourceLocation>> data) {
        DATA = data;
    }

    public static void put(UUID uuid, ResourceLocation modelId, ResourceLocation textureId) {
        DATA.put(uuid, Pair.of(modelId, textureId));
    }

    public static boolean contains(UUID uuid) {
        return DATA.containsKey(uuid);
    }

    public static Pair<ResourceLocation, ResourceLocation> getData(UUID uuid) {
        return DATA.get(uuid);
    }
}
