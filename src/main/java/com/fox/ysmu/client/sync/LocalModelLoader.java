package com.fox.ysmu.client.sync;

import java.io.File;
import java.util.Map;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.format.OpenYsmSyncInfo;
import com.fox.ysmu.ysmu;

import org.apache.commons.io.FileUtils;

/**
 * 客户端本地模型加载（仅自己可见）。
 *
 * 由客户端命令 /ysmlocal 触发（{@code CommandLoadLocal}），把客户端本地扫描到的
 * 模型（config/ysmu/custom + builtin，由 {@code ServerModelManager.reloadPacks()}
 * 在客户端也构建）注册为客户端模型，仅自己可见——其他玩家没有这些模型，看到你
 * 仍为默认模型。
 *
 * 隔离性：
 * - 完全复用真实同步的「转码/解密/解析/apply」路径
 *   （{@link OpenYsmModelSyncClient#registerLocalModel(OpenYsmSyncInfo, byte[])}），不经过网络；
 * - 与服务器同名的模型（MODELS 中已注册）跳过不覆盖，避免本地/服务端同名版本混淆；
 * - 本地注册与真实同步通过 OpenYsmModelSyncClient 的类锁隔离，绝不污染同步状态；
 * - 懒加载通过 {@code readClientCacheToClearBytes} 的本地密钥回退保持可用（不白模）。
 */
@SideOnly(Side.CLIENT)
public final class LocalModelLoader {

    private LocalModelLoader() {}

    /**
     * 把本地扫描索引中的模型全部注册为客户端模型（后台线程，渐进式）。
     *
     * @return int[3] = { 新注册数, 已存在跳过数, 失败数 }
     */
    public static int[] registerLocalModels() {
        int registered = 0, skipped = 0, failed = 0;
        Map<String, OpenYsmSyncInfo> index = ServerModelManager.OPEN_YSM_SYNC_INFO;
        if (index == null || index.isEmpty()) {
            ysmu.LOG.info("[YSMU-LOCAL] No locally scanned models to register");
            return new int[] { 0, 0, 0 };
        }
        for (OpenYsmSyncInfo info : index.values()) {
            // 已注册的模型（服务器同名模型 / default / 之前已加载的本地模型）跳过，
            // 避免本地与服务器同名模型相互覆盖造成混乱。
            ResourceLocation baseId = new ResourceLocation(ysmu.MODID, info.getModelId());
            if (ClientModelManager.MODELS.containsKey(baseId)) {
                skipped++;
                continue;
            }
            File serverCache = ServerModelManager.CACHE_SERVER.resolve(info.getCacheFileName()).toFile();
            if (!serverCache.isFile()) {
                failed++;
                continue;
            }
            try {
                byte[] serverBytes = FileUtils.readFileToByteArray(serverCache);
                if (OpenYsmModelSyncClient.registerLocalModel(info, serverBytes)) {
                    registered++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                ysmu.LOG.warn("[YSMU-LOCAL] Failed to register local model {}: {}",
                    info.getModelId(), e.getMessage());
            }
        }
        ysmu.LOG.info("[YSMU-LOCAL] Local model load complete: registered={}, skipped={}, failed={}",
            registered, skipped, failed);
        return new int[] { registered, skipped, failed };
    }
}
