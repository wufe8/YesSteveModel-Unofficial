package com.fox.ysmu.client.animation.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.network.message.S2CMolangSync;
import com.fox.ysmu.ysmu;

/**
 * 收到 {@code ysm.sync(...)} 的下行广播后，在本地触发对应玩家、对应模型的 {@code sync}
 * 事件脚本（YSM-wiki: molang/script「主动同步」）。
 *
 * <p>发起者用**自己当前的主模型**发起同步，因此接收端按包里带的模型 id 去跑那个模型的
 * {@code sync} 脚本；模型没在本地加载时静默跳过（不同玩家装的模型包可能不一样）。</p>
 */
public final class MolangSyncClient {

    private MolangSyncClient() {}

    public static void handle(S2CMolangSync message) {
        if (message == null || message.getSenderId() == null) {
            return;
        }
        ResourceLocation modelId = parseModelId(message.getModelId());
        if (modelId == null) {
            return;
        }
        EntityPlayer player = findPlayer(message.getSenderId());
        if (player == null) {
            return;
        }
        if (!com.fox.ysmu.client.animation.molang.MolangScriptRegistry.hasScripts(modelId)) {
            return;
        }
        List<Double> arguments = new ArrayList<>();
        int[] raw = message.getArguments();
        if (raw != null) {
            for (int value : raw) {
                arguments.add((double) value);
            }
        }
        if (com.fox.ysmu.Config.DEBUG_ANIMATION) {
            ysmu.LOG.info("[YSMU-SYNC] received sync for {} args={} (sender {})", modelId, arguments,
                message.getSenderId());
        }
        OpenYsmScriptRuntime.runSyncScripts(player, modelId, arguments);
    }

    static ResourceLocation parseModelId(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return new ResourceLocation(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static EntityPlayer findPlayer(UUID playerId) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
            return null;
        }
        for (Object candidate : mc.theWorld.playerEntities) {
            if (candidate instanceof EntityPlayer player && playerId.equals(player.getUniqueID())) {
                return player;
            }
        }
        return null;
    }
}
