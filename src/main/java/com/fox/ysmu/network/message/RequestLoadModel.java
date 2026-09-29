package com.fox.ysmu.network.message;

import java.io.File;
import java.util.UUID;

import net.minecraft.client.Minecraft;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.data.EncryptTools;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.util.UuidUtils;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;

public class RequestLoadModel implements IMessage {

    /** 等待 SendModelPassword 的轮询间隔与总时长上限（原实现是 40 次 × 500 ms）。 */
    private static final long PASSWORD_POLL_INTERVAL_MS = 500L;
    private static final long PASSWORD_WAIT_TOTAL_MS = 20_000L;

    private String fileName;

    public RequestLoadModel() {}

    public RequestLoadModel(String fileName) {
        this.fileName = fileName;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.fileName = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.fileName);
    }

    public static class Handler implements IMessageHandler<RequestLoadModel, IMessage> {

        @Override
        public IMessage onMessage(RequestLoadModel message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT) {
                ClientModelManager.rememberCachedModel(message.fileName);
                loadModel(message.fileName);
            }
            return null;
        }
    }

    @SideOnly(Side.CLIENT)
    public static void loadModel(String fileName) {
        // 密码（SendModelPassword）由客户端 Netty 线程写入，缓存文件必须等它到才能解密。
        // 等待改用客户端 tick 驱动的重试：原先在 THREAD_POOL 上 sleep 轮询，ThreadCount=1
        // 时这一个睡觉的任务就占住唯一工作线程，把它后面所有解析/下载任务堵死（同一类
        // 饥饿问题，见 TickScheduler）。超时计入 SYNC_FAILED —— 原实现直接 return，
        // 失败数永远不涨，进度条收不了尾。重活仍在 THREAD_POOL 上跑。
        final long deadline = System.currentTimeMillis() + PASSWORD_WAIT_TOTAL_MS;
        com.fox.ysmu.util.TickScheduler.Handle[] waiter = new com.fox.ysmu.util.TickScheduler.Handle[1];
        waiter[0] = com.fox.ysmu.util.TickScheduler.client()
            .scheduleEvery(PASSWORD_POLL_INTERVAL_MS, () -> {
                byte[] password = ClientModelManager.PASSWORD;
                UUID passwordUuid = ClientModelManager.PASSWORD_UUID;
                if ((password == null || passwordUuid == null) && System.currentTimeMillis() < deadline) {
                    return; // 密码还没到，再等一轮
                }
                waiter[0].cancel();
                if (password == null || passwordUuid == null) {
                    ysmu.LOG.warn("Timed out waiting for YSM model password before loading cache file {}", fileName);
                    ClientModelManager.SYNC_FAILED++;
                    return;
                }
                ThreadTools.THREAD_POOL.submit(() -> decryptAndSchedule(fileName, password, passwordUuid));
            });
    }

    /** 后台线程：读取 + 解密缓存文件并提交 apply（原 loadModel 任务体，去掉等待段）。 */
    @SideOnly(Side.CLIENT)
    private static void decryptAndSchedule(String fileName, byte[] password, UUID passwordUuid) {
        try {
            if (Minecraft.getMinecraft().thePlayer == null) {
                return;
            }
            File modelFile = ServerModelManager.CACHE_CLIENT.resolve(fileName)
                .toFile();
            byte[] fileBytes = FileUtils.readFileToByteArray(modelFile);
            ModelData data = EncryptTools.decryptModel(UuidUtils.asBytes(passwordUuid), password, fileBytes, fileName);
            if (data != null) {
                if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SYNC) {
                    ysmu.LOG.info("[YSMU-MODEL] Decrypted model {} (id={}), registering...",
                        fileName, data.getModelId());
                }
                // Record MD5 mapping for lazy animation re-loading
                ClientModelManager.rememberModelMd5(
                    new net.minecraft.util.ResourceLocation(com.fox.ysmu.ysmu.MODID, data.getModelId()),
                    fileName);
                // Parse geometry on background thread, only register on main thread.
                // Lazy animation: the heavy AnimationFile is deferred to first use.
                com.fox.ysmu.client.model.PreParsedModelBundle bundle;
                try {
                    bundle = ClientModelManager.preParseModel(data, true);
                } catch (Exception e) {
                    ysmu.LOG.warn("Failed to pre-parse model {}: {}", fileName, e.getMessage());
                    ClientModelManager.SYNC_FAILED++;
                    return;
                }
                ClientModelManager.scheduleApply(bundle);
            } else {
                ysmu.LOG.warn("Failed to decrypt YSM model cache file {}", fileName);
                ClientModelManager.SYNC_FAILED++;
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to load YSM model cache file " + fileName, e);
            ClientModelManager.SYNC_FAILED++;
        }
    }
}
