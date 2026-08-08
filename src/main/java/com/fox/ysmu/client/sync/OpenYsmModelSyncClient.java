package com.fox.ysmu.client.sync;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.controller.OpenYsmAnimationControllerRegistry;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.format.OpenYsmFormat;
import com.fox.ysmu.model.format.OpenYsmSyncInfo;
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.YSMBinaryDeserializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.sync.OpenYsmModelSyncServer;
import com.fox.ysmu.network.message.C2SCompleteFeedback17;
import com.fox.ysmu.network.message.C2SModelSyncPayload17;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Maps;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.Unpooled;
import rip.ysm.security.YSMByteBuf;
import rip.ysm.security.YSMClientCache;
import rip.ysm.security.YsmCrypt;

@SideOnly(Side.CLIENT)
public final class OpenYsmModelSyncClient {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 同步索引重组缓冲上限：超出即拒绝（异常/恶意服务端可借超大 totalLength 触发 OOM）。 */
    private static final int MAX_SYNC_INDEX_BYTES = 64 * 1024 * 1024;

    // ── 会话级字段（跨同步存活）─────────────────────────────
    // clientKey/currentCacheFolderName/serverKey 刻意留在外层：懒加载重解密（闲置卸载
    // 后恢复）与 /ysmlocal 会在后台线程读取它们，且跨同步存活；单次同步的状态字段
    // （syncStep/密钥/索引缓冲/计数器/看门狗时间戳）在 SyncState 内。
    private static byte[] serverKey;
    private static byte[] clientKey;
    private static String currentCacheFolderName;

    /** 当前同步状态。每次握手开始时新建（handlePayload 见 step==1 即换新实例），
     *  reset/完成时整体替换，杜绝旧同步残留字段污染新一轮握手。 */
    private static volatile SyncState STATE = new SyncState();

    /** 单次 OpenYSM 同步的私有状态（会话级字段见外层注释）。 */
    private static final class SyncState {

        private int syncStep = 1;
        private byte[] key1;
        private byte[] lastKey;
        /** Reassembly buffer for the (encrypted, possibly chunked) sync index. */
        private byte[] syncIndexChunks;
        private int syncIndexTotal;
        private int syncIndexReceived;
        private final Map<UUID, ServerModelContext> serverModels = Maps.newConcurrentMap();
        /** 剩余待解析模型数（缓存命中 + 下载）。全部为 0 时才允许发送完成信号。 */
        private final java.util.concurrent.atomic.AtomicInteger remainingTasks =
            new java.util.concurrent.atomic.AtomicInteger(0);
        /** 完成等待是否已启动（防止多线程重复触发）。 */
        private final java.util.concurrent.atomic.AtomicBoolean completionScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        /** 完成信号是否已发送（防止看门狗/错误路径/正常路径重复发送）。 */
        private final java.util.concurrent.atomic.AtomicBoolean completionSent =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        /** 统计计数器：缓存命中与下载路径的解析都在后台线程并发执行，++ 不再安全。 */
        private final java.util.concurrent.atomic.AtomicInteger loadedModelsCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
        private final java.util.concurrent.atomic.AtomicInteger downloadedModelsCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
        private final java.util.concurrent.atomic.AtomicInteger cacheHitCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
        private volatile long syncStartTimeMs;
        /** 最近一次同步进度推进时间（模型解析完成 / 下载字节到达）。看门狗据此判断是否「停滞」。 */
        private volatile long lastProgressTimeMs;

        // ── 单次同步的包处理 / 完成逻辑（实例方法，直接访问本状态字段）──────────

        /** 处理一个（解密前的）同步包。调用方 handlePayload 已持有类锁，与旧实现的
         *  static synchronized 语义一致。 */
        private void processServerData(byte[] packetBytes) {
            if (packetBytes == null || packetBytes.length == 0) {
                resetConnectionState();
                return;
            }

            try {
                if (syncStep == 1) {
                    byte[] decrypted = YsmCrypt.decrypt(packetBytes, YsmCrypt.publicKey);
                    if (decrypted != null) {
                        handlePacket01(decrypted);
                    }
                } else if (syncStep == 2) {
                    byte[] decrypted = YsmCrypt.decrypt(packetBytes, lastKey);
                    if (decrypted != null) {
                        try (YSMByteBuf buf = new YSMByteBuf(Unpooled.wrappedBuffer(decrypted))) {
                            handlePacket03(buf);
                        }
                    }
                } else if (syncStep == 3) {
                    byte[] decrypted = YsmCrypt.decrypt(packetBytes, key1);
                    if (decrypted != null) {
                        try (YSMByteBuf buf = new YSMByteBuf(Unpooled.wrappedBuffer(decrypted))) {
                            handlePacket05(buf);
                        }
                    }
                }
            } catch (Exception e) {
                sendComplete(C2SCompleteFeedback17.STATUS_FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
                ysmu.LOG.warn("OpenYSM client sync error at step " + syncStep, e);
            }
        }

        private void handlePacket01(byte[] decryptedBuffer) throws Exception {
            // 状态在 handlePayload 进入 step==1 时已换新，无需（也不应）在此 reset——
            // reset 会整体替换 STATE，而本方法继续在旧实例上跑会丢失后续包。
            if (decryptedBuffer.length < 56) {
                return;
            }
            key1 = Arrays.copyOfRange(decryptedBuffer, decryptedBuffer.length - 56, decryptedBuffer.length);
            syncStep = 2;

            byte[] garbage = randomGarbage();
            try (YSMByteBuf out = new YSMByteBuf(Unpooled.buffer())) {
                out.writeGarbageHeader(garbage.length, garbage);
                out.writeByte((byte) 0x02);
                out.writeByte((byte) 0x00);
                YsmCrypt.EncryptedPacket encrypted = YsmCrypt.encrypt(out.toArray(), key1, true);
                lastKey = encrypted.nextKey();
                sendPayload(encrypted.data());
            }
        }

        private void handlePacket03(YSMByteBuf buf) throws Exception {
            buf.skipGarbageHeader();
            int type = buf.readVarInt();
            if (type != 3) {
                return;
            }

            long folderHash = buf.readVarLong();
            currentCacheFolderName = Long.toHexString(folderHash);
            serverKey = new byte[56];
            buf.getRawBuf().readBytes(serverKey);
            clientKey = new byte[56];
            buf.getRawBuf().readBytes(clientKey);

            serverModels.clear();
            File cacheDir = getCacheDir();
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
                ysmu.LOG.warn("Failed to create OpenYSM client cache directory {}", cacheDir);
            }

            Map<UUID, File> localCacheMap = YSMClientCache.buildCacheIndex(cacheDir, clientKey);
            List<ModelHash> modelsToRequest = new ArrayList<>();
            // packet03 头部：progressTotal（OpenYSM+legacy 并集）供进度条/完成统计使用；
            // serverModelCount 仍是本索引的条目数（仅 OpenYSM 集合）。
            int progressTotal = buf.readVarInt();
            int serverModelCount = buf.readVarInt();
            ClientModelManager.SYNC_TOTAL = progressTotal;
            ClientModelManager.SYNC_LOADED = 0;
            ClientModelManager.SYNC_FAILED = 0;
            ClientModelManager.SYNC_IN_PROGRESS = true;
            // 完成信号等待全部模型（缓存命中 + 下载）解析并应用完成。
            remainingTasks.set(serverModelCount);
            syncStartTimeMs = System.currentTimeMillis();
            startSyncWatchdog();
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SYNC) {
                ysmu.LOG.info("[YSMU-MODEL] OpenYSM client received sync index: models={}", serverModelCount);
                ysmu.LOG.info("[YSMU-MODEL] Client sync handlePacket03: serverModelCount={}, cachedModels={}",
                    serverModelCount, localCacheMap.size());
            }

            for (int i = 0; i < serverModelCount; i++) {
                long hash1 = buf.readVarLong();
                long hash2 = buf.readVarLong();
                String modelId = buf.readString();
                int customSkinModel = buf.readVarInt();
                int version = buf.readVarInt();
                ServerModelContext context = new ServerModelContext(hash1, hash2, modelId, customSkinModel, version);
                serverModels.put(context.uuid, context);

                File cachedFile = localCacheMap.get(context.uuid);
                if (YSMClientCache.verifyFileContent(cachedFile, hash1, hash2)) {
                    cacheHitCount.incrementAndGet();
                    try {
                        byte[] cachedBytes = FileUtils.readFileToByteArray(cachedFile);
                        byte[] clearBytes = YsmCrypt.read(cachedBytes, clientKey);
                        // Pre-parsing hundreds of cache hits inline blocks the single
                        // synchronized index loop for minutes on large libraries (progress
                        // bar looks frozen). Defer the heavy parse/registration to the
                        // background pool so it runs in parallel with the download path;
                        // the cheap read+decrypt stay inline so a corrupt cache file still
                        // falls back to download before packet04 is sent.
                        ThreadTools.THREAD_POOL.submit(() -> {
                            try {
                                if (parseAndRegisterModel(clearBytes, context)) {
                                    loadedModelsCount.incrementAndGet();
                                }
                            } finally {
                                taskFinished();
                            }
                        });
                    } catch (Exception e) {
                        // 缓存文件解密失败（如 session key 变更导致 clientKey 不匹配），降级为 cache miss，
                        // 避免整个同步流程因此崩溃，导致加载进度条无法显示。
                        ysmu.LOG.warn("OpenYSM client cache HIT but decrypt FAILED for {} ({}), falling back to download: {}",
                            modelId, context.uuid, e.getMessage());
                        modelsToRequest.add(new ModelHash(hash1, hash2));
                    }
                } else {
                    modelsToRequest.add(new ModelHash(hash1, hash2));
                    if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SYNC) {
                        ysmu.LOG.info("[YSMU-MODEL] Client cache MISS for {} ({}), cachedFile={}",
                            modelId, context.uuid,
                            cachedFile != null ? cachedFile : "(no local cache)");
                    }
                }
            }

            parsePackData(buf);
            sendPacket04(modelsToRequest);
            // 全缓存命中/零模型时完成信号由后台解析驱动；此处兜底极端情况
            // （serverModelCount==0 或全部解析已在发送 packet04 前完成）。
            if (remainingTasks.get() <= 0) {
                maybeSendComplete();
            }
        }

        private void handlePacket05(YSMByteBuf buf) throws Exception {
            buf.skipGarbageHeader();
            int type = buf.readVarInt();
            if (type != 5) {
                return;
            }

            long hash1 = buf.readVarLong();
            long hash2 = buf.readVarLong();
            UUID uuid = new UUID(hash1, hash2);
            ServerModelContext context = serverModels.get(uuid);
            if (context == null) {
                ysmu.LOG.warn("OpenYSM client received unexpected chunk for {}", uuid);
                return;
            }

            int totalSize = buf.readVarInt();
            int chunkOffset = buf.readVarInt();
            int chunkLength = buf.readVarInt();
            if (context.fileBuffer == null) {
                context.fileBuffer = new byte[totalSize];
                context.totalSize = totalSize;
                context.bytesReceived = 0;
            }
            buf.getRawBuf().readBytes(context.fileBuffer, chunkOffset, chunkLength);
            context.bytesReceived += chunkLength;
            // 下载仍在推进：重置看门狗的停滞倒计时（与任务完成一样算「有进展」）。
            touchProgress();

            if (context.bytesReceived >= context.totalSize) {
                byte[] fileBuffer = context.fileBuffer;
                context.fileBuffer = null;
                try {
                    byte[] clientCacheBytes = YsmCrypt
                        .transcodeServerDataToClientCache(fileBuffer, serverKey, clientKey, hash1, hash2);
                    File outFile = new File(getCacheDir(), YSMClientCache.generateCacheFileName(hash1, hash2, clientKey));
                    FileUtils.writeByteArrayToFile(outFile, clientCacheBytes);

                    byte[] clearBytes = YsmCrypt.read(clientCacheBytes, clientKey);
                    // Defer the heavy parse to the background pool (mirrors the cache-hit
                    // path): processServerData is synchronized, so parsing inline here
                    // would hold the class lock for every model and serialize the whole
                    // download+parse pipeline on a single pool thread. The cheap
                    // transcode + file write + decrypt stay inline so a corrupt cache
                    // still falls back to a fresh download on the next sync.
                    final byte[] modelClearBytes = clearBytes;
                    ThreadTools.THREAD_POOL.submit(() -> {
                        try {
                            if (parseAndRegisterModel(modelClearBytes, context)) {
                                loadedModelsCount.incrementAndGet();
                                downloadedModelsCount.incrementAndGet();
                            }
                            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SYNC) {
                                ysmu.LOG.info("OpenYSM client downloaded and cached {} to {}", context.modelId, outFile);
                            }
                        } catch (Exception e) {
                            // A single corrupt/truncated model cache file must not abort the whole
                            // sync (which previously failed the progress bar and left the library
                            // unloaded). Log it, skip the model, and keep going.
                            ysmu.LOG.warn("OpenYSM client failed to process downloaded model {} ({}): {}",
                                context.modelId, uuid, e.getMessage());
                            ClientModelManager.SYNC_FAILED++;
                        } finally {
                            // 该模型解析已完成（成功或失败），计入总任务数；全部完成后触发完成信号。
                            taskFinished();
                        }
                    });
                } catch (Exception e) {
                    // 转码/解密失败（如会话密钥不匹配/服务端缓存损坏）：直接跳过该模型，
                    // 不再进入后台解析，但同样计入总任务数，避免完成信号永不到达。
                    ysmu.LOG.warn("OpenYSM client failed to decrypt downloaded model {} ({}): {}",
                        context.modelId, uuid, e.getMessage());
                    ClientModelManager.SYNC_FAILED++;
                    taskFinished();
                }
            }
        }

        private void sendPacket04(List<ModelHash> modelsToRequest) throws Exception {
            syncStep = 3;

            byte[] garbage = randomGarbage();
            try (YSMByteBuf out = new YSMByteBuf(Unpooled.buffer())) {
                out.writeGarbageHeader(garbage.length, garbage);
                out.writeByte((byte) 0x04);
                out.writeVarInt(modelsToRequest.size());
                for (ModelHash hash : modelsToRequest) {
                    out.writeVarLong(hash.hash1);
                    out.writeVarLong(hash.hash2);
                }
                sendPayload(YsmCrypt.encrypt(out.toArray(), key1, false).data());
            }
            // 完成信号不再由「下载数==0」触发（全缓存命中时会提前结束），改由
            // taskFinished() → remainingTasks 全部完成 + 应用管线排空后触发。
        }

        /**
         * 一个模型（缓存命中或下载）的解析已完成。全部模型解析完成后，等待主线程
         * apply 管线排空（进度/统计准确），再发送完成信号。
         */
        private void taskFinished() {
            // 每个模型解析完成都视为一次进展：重置看门狗停滞倒计时。
            touchProgress();
            if (remainingTasks.decrementAndGet() <= 0) {
                maybeSendComplete();
            }
        }

        /** 记录一次同步进展（模型解析完成或下载字节到达），供看门狗判断是否停滞。 */
        private void touchProgress() {
            lastProgressTimeMs = System.currentTimeMillis();
        }

        /**
         * 启动完成等待：所有模型解析已提交，但主线程的 applyPreParsed 可能仍在逐帧
         * 消费队列——全缓存命中（下载数 0）时若立即发完成，成功数会远小于总数
         * （如 343/681）。轮询 {@link ClientModelManager#isApplyPipelineDrained()}，
         * 排空后发送完成；超时或同步被重置则兜底退出，不挂死。
         */
        private void maybeSendComplete() {
            if (syncStep < 3 || !completionScheduled.compareAndSet(false, true)) {
                return;
            }
            ThreadTools.THREAD_POOL.submit(() -> {
                long deadline = System.currentTimeMillis() + COMPLETION_APPLY_DRAIN_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    if (!ClientModelManager.SYNC_IN_PROGRESS) {
                        return; // 同步已被重置/断开，不再补发完成。
                    }
                    if (ClientModelManager.isApplyPipelineDrained()) {
                        sendComplete(C2SCompleteFeedback17.STATUS_SUCCESS, "");
                        return;
                    }
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        sendComplete(C2SCompleteFeedback17.STATUS_SUCCESS, "");
                        return;
                    }
                }
                // 超时兜底：应用仍未排空（如主线程长时间阻塞），按当前状态发送完成。
                sendComplete(C2SCompleteFeedback17.STATUS_SUCCESS, "");
            });
        }

        /** 同步开始时启动；所有任务正常完成或同步被重置即退出。若 1 分钟内没有任何进展
         *  （如某些模型被请求但服务端从未下发、缓存文件缺失/损坏），强制发送完成兜底。 */
        private void startSyncWatchdog() {
            lastProgressTimeMs = System.currentTimeMillis();
            ThreadTools.THREAD_POOL.submit(() -> {
                while (ClientModelManager.SYNC_IN_PROGRESS && remainingTasks.get() > 0) {
                    if (System.currentTimeMillis() - lastProgressTimeMs >= SYNC_STALL_TIMEOUT_MS) {
                        ysmu.LOG.warn("OpenYSM client sync stalled (no progress for {}s, {} task(s) left), forcing completion",
                            SYNC_STALL_TIMEOUT_MS / 1000, remainingTasks.get());
                        sendComplete(C2SCompleteFeedback17.STATUS_SUCCESS, "");
                        return;
                    }
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        private void sendComplete(int status, String message) {
            // 只有当前同步（STATE 指向本实例）能发送完成信号。快速重连 / 加载期间 /ysm
            // reload 会整体替换 STATE（handlePayload 见 step==1 换新、S2CVersionCheck17 调
            // resetConnectionState），旧同步的后台看门狗/完成轮询/解析任务仍会在旧实例上
            // 运行；若它们继续发送完成，服务端 complete() 会把新同步的 SYNC_STATES 条目
            // 误删，导致新同步的 packet05 下载被掐断（模型缺失 + 60s 停滞兜底）。
            if (STATE != this) {
                return;
            }
            // 防止看门狗/错误路径/正常路径重复发送完成信号。
            if (!completionSent.compareAndSet(false, true)) {
                return;
            }
            NetworkHandler.CHANNEL.sendToServer(
                new C2SCompleteFeedback17(status, loadedModelsCount.get(), downloadedModelsCount.get(),
                    cacheHitCount.get(), message));
            if (status == C2SCompleteFeedback17.STATUS_SUCCESS) {
                long elapsed = System.currentTimeMillis() - syncStartTimeMs;
                ysmu.LOG.info(
                    "OpenYSM client sync complete: loaded={}, downloaded={}, cacheHits={}, time={}ms",
                    loadedModelsCount.get(), downloadedModelsCount.get(), cacheHitCount.get(), elapsed);
                // 在聊天栏输出客户端完成信息（成功/失败/总数统计；总数=统一索引大小）
                int registered = ClientModelManager.MODELS.size();
                int failed = ClientModelManager.SYNC_FAILED;
                int total = ClientModelManager.SYNC_TOTAL;
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
                if (mc.thePlayer != null) {
                    // chat 开关（ShowModelLoadChat，默认关）：开启时显示耗时与数量；
                    // 关闭时仅在有模型加载失败时才显示加载数量（不显示耗时）。
                    if (com.fox.ysmu.Config.SHOW_MODEL_LOAD_CHAT) {
                        mc.thePlayer.addChatMessage(
                            new net.minecraft.util.ChatComponentTranslation(
                                "message.yes_steve_model.sync.complete", elapsed));
                        mc.thePlayer.addChatMessage(
                            new net.minecraft.util.ChatComponentTranslation(
                                "message.yes_steve_model.sync.complete_models", registered, failed, total));
                    } else if (failed > 0) {
                        mc.thePlayer.addChatMessage(
                            new net.minecraft.util.ChatComponentTranslation(
                                "message.yes_steve_model.sync.complete_models", registered, failed, total));
                    }
                }
            }
            ClientModelManager.SYNC_IN_PROGRESS = false;
            resetConnectionState();
        }
    }

    private OpenYsmModelSyncClient() {}

    public static void handlePayload(byte[] data) {
        ThreadTools.THREAD_POOL.submit(() -> {
            // 与旧实现 processServerData 的 static synchronized 一致：所有同步包处理
            // 串行在类锁内（与 registerLocalModel 等互斥）。
            synchronized (OpenYsmModelSyncClient.class) {
                if (data == null || data.length == 0) {
                    resetConnectionState();
                    return;
                }
                // 每轮同步从 packet01（step==1）开始：此时换用全新的 SyncState，
                // 等价于旧 handlePacket01 开头的 resetConnectionState()。
                SyncState state = STATE;
                if (state == null || state.syncStep == 1) {
                    state = new SyncState();
                    STATE = state;
                }
                state.processServerData(data);
            }
        });
    }

    /**
     * Receives a chunk of the (encrypted) sync index from the server and, once the
     * final chunk arrives, hands the reassembled blob to {@link #handlePayload} for
     * the normal decrypt + packet03 parse. Only used for oversized indexes that the
     * server chunks to stay under the ~32 KB custom-payload limit.
     */
    public static synchronized void handleSyncIndexChunk(int totalLength, int offset, byte[] chunk, boolean last) {
        SyncState state = STATE;
        // 索引分块只会在等待 packet03（syncStep==2）时到达；其他阶段收到即忽略，
        // 避免陈旧/乱序 chunk 污染重组缓冲或误触发后续处理。
        if (state == null || state.syncStep != 2 || chunk == null || chunk.length == 0) {
            return;
        }
        if (totalLength <= 0 || totalLength > MAX_SYNC_INDEX_BYTES) {
            ysmu.LOG.warn("OpenYSM client rejected sync index chunk: total={}", totalLength);
            state.syncIndexChunks = null;
            state.syncIndexTotal = 0;
            state.syncIndexReceived = 0;
            return;
        }
        if (state.syncIndexChunks == null || state.syncIndexTotal != totalLength) {
            state.syncIndexChunks = new byte[totalLength];
            state.syncIndexTotal = totalLength;
            state.syncIndexReceived = 0;
        }
        if (offset < 0 || offset + chunk.length > state.syncIndexChunks.length) {
            ysmu.LOG.warn("OpenYSM client sync index chunk out of range: offset={}, len={}, total={}",
                offset, chunk.length, totalLength);
            return;
        }
        System.arraycopy(chunk, 0, state.syncIndexChunks, offset, chunk.length);
        state.syncIndexReceived += chunk.length;
        if (last) {
            byte[] full = state.syncIndexChunks;
            state.syncIndexChunks = null;
            state.syncIndexTotal = 0;
            state.syncIndexReceived = 0;
            handlePayload(full);
        }
    }

    public static synchronized void resetConnectionState() {
        // 整体替换 SyncState：清空所有单次同步字段（等价于旧实现逐字段重置，
        // 且原子、无中途被观察到的半重置状态）。
        STATE = new SyncState();
        serverKey = null;
        currentCacheFolderName = null;
        // NOTE: clientKey is intentionally KEPT — lazy geo/anim/texture reload
        // re-decrypts the client cache files with it after an idle unload, and
        // the sync teardown (sendComplete) also calls resetConnectionState(). It
        // is only cleared on disconnect (clearConnectionState).
    }

    public static synchronized void clearConnectionState() {
        resetConnectionState();
        clientKey = null;
        ClientModelManager.SYNC_IN_PROGRESS = false;
    }









    private static boolean parseAndRegisterModel(byte[] clearBytes, ServerModelContext context) {
        try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(clearBytes, OpenYsmFormat.OPEN_YSM_SYNC_FORMAT)) {
            RawYsmModel raw = deserializer.deserializeKeepOpen();
            deserializer.parseYSMFooter(raw);
            raw.modelId = context.modelId;
            ResourceLocation modelId = ModelIdUtil.getMainId(new ResourceLocation(ysmu.MODID, context.modelId));

            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_BINARY) {
                // Diagnose the "no mainModel geometry after OpenYSM deserialization" bug:
                // service side parses the folder model fine (isBridgeable passes), but the
                // client-side YSMBinaryDeserializer often leaves mainModel null for the
                // built-in legacy folder models. Print what actually came out of the binary.
                RawYsmModel.RawMainEntity me = raw.mainEntity;
                String geoTypes = "";
                if (me != null) {
                    StringBuilder sb = new StringBuilder();
                    for (RawYsmModel.RawGeometry g : new RawYsmModel.RawGeometry[] { me.mainModel, me.armModel }) {
                        if (g == null) {
                            sb.append("null,");
                        } else {
                            int cubes = 0, faces = 0;
                            for (RawYsmModel.RawBone b : g.bones) {
                                cubes += b.cubes != null ? b.cubes.size() : 0;
                                for (RawYsmModel.RawCube c : b.cubes) {
                                    faces += c.faces != null ? c.faces.size() : 0;
                                }
                            }
                            sb.append("type=").append(g.modelType)
                                .append(",bones=").append(g.bones != null ? g.bones.size() : 0)
                                .append(",cubes=").append(cubes)
                                .append(",faces=").append(faces)
                                .append(",hasSrcJson=").append(g.sourceJson != null)
                                .append(",sha=").append(g.sha256 != null ? g.sha256.substring(0, Math.min(8, g.sha256.length())) : "null")
                                .append(',');
                        }
                    }
                    geoTypes = sb.toString();
                }
                int geoCount = 0;
                if (me != null) {
                    geoCount = (me.mainModel != null ? 1 : 0) + (me.armModel != null ? 1 : 0);
                }
                ysmu.LOG.info("[YSMU-MODEL] OpenYSM deser check {}: main={}, arm={}, geoCount={}, types=[{}], subCount={}",
                    context.modelId,
                    me != null && me.mainModel != null,
                    me != null && me.armModel != null,
                    geoCount,
                    geoTypes,
                    raw.projectiles != null ? raw.projectiles.size() : 0);
            }

            if (!RawYsmModelAdapter.isBridgeable(raw)) {
                // Even when the model can't be bridged to legacy ModelData, we still
                // register its extra wheel data and projectile sub-entity models (so
                // arrow rendering works). Extra wheel is cheap and scheduled here;
                // projectile geo/anims are PARSED on the background thread (we are
                // already on THREAD_POOL) and only applied on the main thread.
                Minecraft.getMinecraft().func_152344_a(() -> {
                    try {
                        ClientModelManager.registerExtraWheel(modelId, raw);
                    } catch (Exception e) {
                        ysmu.LOG.warn("Failed to register extra wheel data for {}: {}",
                            context.modelId, e.getMessage());
                    }
                });
                if (raw.projectiles != null && !raw.projectiles.isEmpty()) {
                    ResourceLocation baseModelId = ModelIdUtil.getModelIdFromMainId(modelId);
                    java.util.List<ProjectileMatch> projectileMatches = parseProjectileResources(raw, baseModelId);
                    if (!projectileMatches.isEmpty()) {
                        Minecraft.getMinecraft().func_152344_a(() -> {
                            try {
                                applyProjectileResources(projectileMatches);
                            } catch (Exception e) {
                                ysmu.LOG.warn("Failed to register projectile models for non-bridgeable model {}",
                                    context.modelId, e);
                            }
                        });
                    }
                }
                if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SYNC) {
                    ysmu.LOG.info("[YSMU-MODEL] OpenYSM synced model {} is not bridgeable, but projectiles registered",
                        context.modelId);
                }
                return false;
            }
            ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, context.modelId);

            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SYNC) {
                ysmu.LOG.info("[YSMU-MODEL] Client registering model {}: models={}, textures={}, animations={}",
                    context.modelId, data.getModel().keySet(), data.getTexture().keySet(), data.getAnimation().keySet());
            }

            // Parse geometry on background thread, only register on main thread.
            // Lazy animation: the heavy AnimationFile is deferred to first use.
            com.fox.ysmu.client.model.PreParsedModelBundle bundle;
            try {
                bundle = ClientModelManager.preParseModel(data, true);
            bundle.previewAnimation = raw.properties.previewAnimation;
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to pre-parse model {}: {}", context.modelId, e.getMessage());
                ClientModelManager.SYNC_FAILED++;
                return false;
            }
            // Fold extra-wheel registration into the single apply task (previously a
            // separate func_152344_a per model, doubling the sync frame count).
            bundle.extraWheelRaw = raw;
            // Record the encrypted client cache file (relative path under
            // CACHE_CLIENT, OpenYSM format) so lazy geo/anim/texture reload can
            // re-decrypt it on demand after an idle unload. Without this the
            // OpenYSM cache files are never re-discovered and unloaded models
            // cannot be restored.
            ClientModelManager.rememberOpenYsmModelCache(
                new ResourceLocation(ysmu.MODID, context.modelId),
                currentCacheFolderName + "/" + YSMClientCache.generateCacheFileName(context.hash1, context.hash2, clientKey));
            ClientModelManager.scheduleApply(bundle);
            return true;
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to parse OpenYSM synced model " + context.modelId, e);
            return false;
        }
    }

    /** Parsed projectile sub-entity resources for one matchId of a non-bridgeable
     *  model. Populated on the background thread by {@link #parseProjectileResources},
     *  applied on the main thread by {@link #applyProjectileResources}. Kept fully
     *  decoupled from the main animation business logic. */
    private static final class ProjectileMatch {

        final ResourceLocation baseModelId;
        final String matchId;
        final List<ResourceLocation> geoIds = new ArrayList<>();
        final List<com.fox.ysmu.client.model.PreParsedModelBundle> geoBundles = new ArrayList<>();
        final List<ResourceLocation> animIds = new ArrayList<>();
        final List<software.bernie.geckolib3.file.AnimationFile> anims = new ArrayList<>();
        final List<ResourceLocation> ctrlIds = new ArrayList<>();
        final List<byte[]> ctrlBytes = new ArrayList<>();
        final List<ResourceLocation> texIds = new ArrayList<>();
        final List<byte[]> texDatas = new ArrayList<>();

        ProjectileMatch(ResourceLocation baseModelId, String matchId) {
            this.baseModelId = baseModelId;
            this.matchId = matchId;
        }
    }

    /** Background thread: parses projectile sub-entity geo/anim/controller/texture
     *  resources into plain objects (NO GeckoLibCache / GL writes — those are applied
     *  on the main thread by {@link #applyProjectileResources}). Runs on THREAD_POOL. */
    private static java.util.List<ProjectileMatch> parseProjectileResources(RawYsmModel raw, ResourceLocation baseModelId) {
        java.util.List<ProjectileMatch> out = new java.util.ArrayList<>();
        if (raw.projectiles == null || raw.projectiles.isEmpty()) return out;
        for (Map.Entry<String, RawYsmModel.RawSubEntity> entry : raw.projectiles.entrySet()) {
            RawYsmModel.RawSubEntity sub = entry.getValue();
            if (sub.model == null) continue;
            String[] matchIds = sub.matchIds != null && sub.matchIds.length > 0
                ? sub.matchIds : new String[]{sub.identifier};
            for (String matchId : matchIds) {
                if (matchId == null || matchId.isEmpty()) continue;
                ProjectileMatch m = new ProjectileMatch(baseModelId, matchId);
                try {
                    // Geometry (heavy parse — background)
                    byte[] geoBytes = RawYsmModelAdapter.toGeometryJson(null, sub.model, false);
                    ResourceLocation geoId = ModelIdUtil.getSubModelId(baseModelId, "projectile_" + matchId);
                    com.fox.ysmu.client.model.PreParsedModelBundle geoBundle =
                        ClientModelManager.parseSingleGeoToBundle(geoId, geoBytes);
                    if (geoBundle != null) {
                        m.geoIds.add(geoId);
                        m.geoBundles.add(geoBundle);
                    }
                    // Textures (bytes only; GL upload happens on the main thread)
                    for (RawYsmModel.RawTexture tex : sub.textures.values()) {
                        if (tex.data == null) continue;
                        byte[] texData = RawYsmModelAdapter.getLegacyTextureData(tex);
                        if (texData == null) continue;
                        String texKey = "projectile_" + matchId + "_" + tex.name;
                        if (!texKey.endsWith(".png")) texKey += ".png";
                        m.texIds.add(ModelIdUtil.getSubModelId(baseModelId, texKey));
                        m.texDatas.add(texData);
                    }
                    // Animations (heavy parse — background)
                    ResourceLocation projAnimId = ModelIdUtil.getSubModelId(baseModelId, "projectile_" + matchId);
                    for (RawYsmModel.RawAnimationFile animFile : sub.animationFiles.values()) {
                        if (animFile.animations == null || animFile.animations.isEmpty()) continue;
                        try {
                            // Binary-format models don't have sourceJson; re-serialize from structured data.
                            byte[] jsonBytes = animFile.sourceJson != null
                                ? animFile.sourceJson
                                : com.fox.ysmu.model.resource.RawYsmModelAdapter.createAnimationJson(animFile);
                            software.bernie.geckolib3.file.AnimationFile parsedAnim =
                                ClientModelManager.parseAnimationFileFromJson(
                                    new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8));
                            if (parsedAnim != null && !parsedAnim.animations.isEmpty()) {
                                m.animIds.add(projAnimId);
                                m.anims.add(parsedAnim);
                                if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SYNC) {
                                    ysmu.LOG.info("[YSMU-MODEL] Parsed projectile animation for {}: {} anims from {}",
                                        projAnimId, parsedAnim.animations.size(), animFile.fileHash);
                                }
                            }
                        } catch (Exception e) {
                            ysmu.LOG.warn("Failed to parse projectile animation for {}: {}",
                                projAnimId, e.getMessage());
                        }
                    }
                    // Controllers (bytes kept; JSON parse happens on apply, main thread)
                    for (RawYsmModel.RawAnimationControllerFile ctrlFile : sub.animationControllerFiles) {
                        if (ctrlFile.controllers == null || ctrlFile.controllers.isEmpty()) continue;
                        try {
                            // Binary-format models don't have sourceJson; re-serialize from structured data.
                            byte[] ctrlBytes = ctrlFile.sourceJson != null
                                ? ctrlFile.sourceJson
                                : com.fox.ysmu.model.resource.RawYsmModelAdapter.createControllerJson(ctrlFile);
                            m.ctrlIds.add(projAnimId);
                            m.ctrlBytes.add(ctrlBytes);
                            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SYNC) {
                                ysmu.LOG.info("[YSMU-MODEL] Parsed projectile controller for {}: '{}' ({} states)",
                                    projAnimId, ctrlFile.name, ctrlFile.controllers.size());
                            }
                        } catch (Exception e) {
                            ysmu.LOG.warn("Failed to parse projectile controller for {}: {}",
                                projAnimId, e.getMessage());
                        }
                    }
                    out.add(m);
                } catch (Exception e) {
                    ysmu.LOG.warn("Failed to parse projectile {} for model {}", matchId, baseModelId, e);
                }
            }
        }
        return out;
    }

    /** Main thread: applies parsed projectile resources (GeckoLibCache writes, GL
     *  texture upload, projectile id maps, controller registry). GeckoLibCache is a
     *  plain HashMap and texture upload needs OpenGL, so this MUST run on the client
     *  render thread. */
    private static void applyProjectileResources(java.util.List<ProjectileMatch> matches) {
        if (matches == null || matches.isEmpty()) return;
        for (ProjectileMatch m : matches) {
            try {
                for (int i = 0; i < m.geoIds.size(); i++) {
                    ClientModelManager.applySingleGeo(m.geoBundles.get(i), m.geoIds.get(i));
                }
                // Match id is registered regardless of geo success (matches legacy
                // behavior — the renderer keys projectile entity types by this list).
                ClientModelManager.PROJECTILE_MODEL_IDS
                    .computeIfAbsent(m.baseModelId, k -> new ArrayList<>())
                    .add(m.matchId);
                for (int i = 0; i < m.texIds.size(); i++) {
                    ClientModelManager.registerTexture(m.texIds.get(i), m.texDatas.get(i));
                    ClientModelManager.PROJECTILE_TEXTURE_IDS
                        .computeIfAbsent(m.baseModelId, k -> new ArrayList<>())
                        .add(m.texIds.get(i));
                }
                for (int i = 0; i < m.animIds.size(); i++) {
                    ResourceLocation animId = m.animIds.get(i);
                    software.bernie.geckolib3.file.AnimationFile existing =
                        software.bernie.geckolib3.resource.GeckoLibCache.getInstance().getAnimations().get(animId);
                    if (existing == null) {
                        existing = new software.bernie.geckolib3.file.AnimationFile();
                        software.bernie.geckolib3.resource.GeckoLibCache.getInstance().getAnimations().put(animId, existing);
                    }
                    for (java.util.Map.Entry<String, software.bernie.geckolib3.core.builder.Animation> ae
                        : m.anims.get(i).animations.entrySet()) {
                        existing.putAnimation(ae.getKey(), ae.getValue());
                    }
                }
                for (int i = 0; i < m.ctrlIds.size(); i++) {
                    OpenYsmAnimationControllerRegistry.register(m.ctrlIds.get(i),
                        java.util.Collections.singleton(m.ctrlBytes.get(i)));
                }
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to apply projectile {} for model {}", m.matchId, m.baseModelId, e);
            }
        }
        if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SYNC) {
            ysmu.LOG.info("[YSMU-MODEL] Applied projectile models for {}: {}",
                matches.get(0).baseModelId,
                ClientModelManager.PROJECTILE_MODEL_IDS.get(matches.get(0).baseModelId));
        }
    }

    private static void parsePackData(YSMByteBuf buf) {
        int packCount = buf.readVarInt();
        if (packCount <= 0) {
            if (buf.getRawBuf().readableBytes() > 0) {
                buf.readVarInt();
            }
            return;
        }
        final java.util.Map<String, ClientModelManager.ClientPackData> parsed = new java.util.LinkedHashMap<>();
        for (int i = 0; i < packCount; i++) {
            String folderPath = buf.readString();

            byte[] iconData = null;
            int iconW = 0, iconH = 0, iconFmt = 0;
            if (buf.readVarInt() != 0) {
                iconData = buf.readByteArray();
                iconW = buf.readVarInt();
                iconH = buf.readVarInt();
                iconFmt = buf.readVarInt();
                buf.readVarInt(); // unkImageData
            }

            String name = "";
            String description = "";
            if (buf.readVarInt() != 0) {
                name = buf.readString();
                description = buf.readString();
            }

            int languageCount = buf.readVarInt();
            java.util.Map<String, java.util.Map<String, String>> lang = new java.util.LinkedHashMap<>();
            for (int langIdx = 0; langIdx < languageCount; langIdx++) {
                String langCode = buf.readString();
                int translationCount = buf.readVarInt();
                java.util.Map<String, String> trans = new java.util.LinkedHashMap<>();
                for (int t = 0; t < translationCount; t++) {
                    trans.put(buf.readString(), buf.readString());
                }
                lang.put(langCode, trans);
            }

            parsed.put(folderPath, new ClientModelManager.ClientPackData(
                folderPath, name, description, iconData, iconW, iconH, iconFmt, lang));
        }
        // Apply packs to ClientModelManager on the client thread,
        // then re-detect model packs so pack display names use localized names.
        Minecraft.getMinecraft().func_152344_a(() -> {
            ClientModelManager.CLIENT_PACKS.clear();
            ClientModelManager.CLIENT_PACKS.putAll(parsed);
            ClientModelManager.detectModelPacks();
        });
        if (buf.getRawBuf().readableBytes() > 0) {
            buf.readVarInt();
        }
    }

    /** Decrypts a client cache file written by this session's OpenYSM sync into
     *  clear bytes using the session client key. Returns null on failure. Used by
     *  ClientModelManager lazy-reload (geo/anim/texture) after an idle unload.
     *  Falls back to the client's own local key so locally-registered models
     *  (LocalModelLoader) stay restorable even after a real server sync
     *  overwrote {@link #clientKey}. */
    public static byte[] readClientCacheToClearBytes(byte[] cacheBytes) {
        if (cacheBytes == null) return null;
        if (clientKey != null) {
            try {
                return YsmCrypt.read(cacheBytes, clientKey);
            } catch (Exception ignored) {
                // fall through to the local key below
            }
        }
        byte[] localKey = localClientKey();
        if (localKey != null && localKey != clientKey) {
            try {
                return YsmCrypt.read(cacheBytes, localKey);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** The client's own local cache key (derived from the local server_index key),
     *  used for local model fallback registration. */
    private static byte[] localClientKey() {
        byte[] serverKey = ServerModelManager.OPEN_YSM_SERVER_KEY;
        if (serverKey == null || serverKey.length != 56) return null;
        return OpenYsmModelSyncServer.createClientCacheKey(serverKey);
    }

    /**
     * 注册单个本地模型（仅自己可见），由 {@link LocalModelLoader} 调用。
     * 复用真实同步的「服务端缓存 → transcode → 写客户端缓存 → 解密 → 解析 → apply」
     * 路径，不经过网络。synchronized 与真实同步（processServerData 等）共用类锁，
     * 避免并发篡改 clientKey/currentCacheFolderName；本地注册期间真实同步会等待，
     * 反之亦然（每个模型粒度，不影响整体）。
     *
     * @return 模型主体注册成功（不可桥接的模型也会注册投射物/轮盘，返回 false）
     */
    public static synchronized boolean registerLocalModel(OpenYsmSyncInfo info, byte[] serverCacheBytes) {
        if (info == null || serverCacheBytes == null || serverCacheBytes.length == 0) {
            return false;
        }
        byte[] localKey = localClientKey();
        if (localKey == null) {
            return false;
        }
        // 保存现场，本地注册结束后恢复，绝不污染真实同步状态。
        String prevFolder = currentCacheFolderName;
        byte[] prevClientKey = clientKey;
        byte[] prevServerKey = serverKey;
        try {
            currentCacheFolderName = "0";
            clientKey = localKey;
            serverKey = ServerModelManager.OPEN_YSM_SERVER_KEY;
            byte[] clientCacheBytes = YsmCrypt.transcodeServerDataToClientCache(
                serverCacheBytes, serverKey, clientKey, info.getHash1(), info.getHash2());
            File outFile = new File(getCacheDir(),
                YSMClientCache.generateCacheFileName(info.getHash1(), info.getHash2(), clientKey));
            FileUtils.writeByteArrayToFile(outFile, clientCacheBytes);
            byte[] clearBytes = YsmCrypt.read(clientCacheBytes, clientKey);
            ServerModelContext context = new ServerModelContext(
                info.getHash1(), info.getHash2(), info.getModelId(),
                info.isCustomSkinModel() ? 1 : 0, info.getFormat());
            return parseAndRegisterModel(clearBytes, context);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to register local model {}: {}", info.getModelId(), e.getMessage());
            return false;
        } finally {
            currentCacheFolderName = prevFolder;
            clientKey = prevClientKey;
            serverKey = prevServerKey;
        }
    }

    private static File getCacheDir() {
        String folder = currentCacheFolderName == null ? "0" : currentCacheFolderName;
        return ServerModelManager.CACHE_CLIENT.resolve(folder).toFile();
    }

    private static void sendPayload(byte[] payload) {
        NetworkHandler.CHANNEL.sendToServer(new C2SModelSyncPayload17(payload));
    }





    /** 完成等待的上限：应用管线在正常游戏内排空很快，此值仅作极端情况的兜底。 */
    private static final long COMPLETION_APPLY_DRAIN_TIMEOUT_MS = 2 * 60_000L;

    /** 同步停滞判定：超过此时长没有任何进展（模型解析完成/下载字节到达）即强制完成。
     *  原实现是固定的 5 分钟总超时，慢速但仍在推进的同步（低带宽/大库）会被误判为
     *  挂死并截断模型列表；改为「1 分钟无进展」的停滞判定后，只要同步还在推进就不会触发。 */
    private static final long SYNC_STALL_TIMEOUT_MS = 60_000L;





    private static byte[] randomGarbage() {
        byte[] garbage = new byte[16 + RANDOM.nextInt(48)];
        RANDOM.nextBytes(garbage);
        return garbage;
    }

    private static final class ModelHash {

        private final long hash1;
        private final long hash2;

        private ModelHash(long hash1, long hash2) {
            this.hash1 = hash1;
            this.hash2 = hash2;
        }
    }

    private static final class ServerModelContext {

        private final long hash1;
        private final long hash2;
        private final UUID uuid;
        private final String modelId;
        @SuppressWarnings("unused")
        private final int customSkinModel;
        @SuppressWarnings("unused")
        private final int version;
        private byte[] fileBuffer;
        private int totalSize;
        private int bytesReceived;

        private ServerModelContext(long hash1, long hash2, String modelId, int customSkinModel, int version) {
            this.hash1 = hash1;
            this.hash2 = hash2;
            this.uuid = new UUID(hash1, hash2);
            this.modelId = modelId;
            this.customSkinModel = customSkinModel;
            this.version = version;
        }
    }
}
