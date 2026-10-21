package com.fox.ysmu.network.sync;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.format.OpenYsmSyncInfo;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.C2SCompleteFeedback17;
import com.fox.ysmu.network.message.S2CModelSyncPayload17;
import com.fox.ysmu.network.message.S2CSyncIndexChunk17;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Maps;

import io.netty.buffer.Unpooled;
import rip.ysm.security.YSMByteBuf;
import rip.ysm.security.YsmCrypt;

public final class OpenYsmModelSyncServer {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DEFAULT_CHUNK_SIZE = 24000;
    private static final int LOW_BANDWIDTH_CHUNK_SIZE = 8192;
    /**
     * Largest single-packet (encrypted) sync index. Over this the index is sent in
     * chunks: 1.7.10 custom-payload packets have a ~32 KB limit that the transport
     * splits into separate messages, corrupting large model-library indexes.
     */
    private static final int SYNC_INDEX_SINGLE_MAX = 30000;
    /** Encrypted sync-index chunk size, safely under the ~32 KB custom-payload limit. */
    private static final int SYNC_INDEX_CHUNK_SIZE = 20000;
    private static final Map<UUID, PlayerSyncState> SYNC_STATES = Maps.newConcurrentMap();
    private static final SyncReplyGate<EntityPlayerMP> EXPECTED_VERSION = new SyncReplyGate<>();
    private static final Map<UUID, LegacyTransfer> LEGACY_TRANSFERS = Maps.newConcurrentMap();

    /** Only server-issued requests may start a handshake; replies belong to this connection. */
    public static void expectVersionReply(EntityPlayerMP player) {
        UUID id = player.getUniqueID();
        RESYNC_REQUESTS.track(id, player);
        SYNC_STATES.remove(id);
        LEGACY_TRANSFERS.remove(id);
        EXPECTED_VERSION.expect(id, player);
    }

    public static boolean consumeVersionReply(EntityPlayerMP player) {
        return EXPECTED_VERSION.consume(player.getUniqueID(), player);
    }

    public static LegacyTransfer expectLegacyReply(EntityPlayerMP player) {
        LegacyTransfer transfer = new LegacyTransfer(player);
        RESYNC_REQUESTS.track(player.getUniqueID(), player);
        SYNC_STATES.remove(player.getUniqueID());
        EXPECTED_VERSION.clear(player.getUniqueID());
        LEGACY_TRANSFERS.put(player.getUniqueID(), transfer);
        return transfer;
    }

    public static LegacyTransfer claimLegacyReply(EntityPlayerMP player) {
        LegacyTransfer transfer = LEGACY_TRANSFERS.get(player.getUniqueID());
        return transfer != null && transfer.player == player && transfer.claimed.compareAndSet(false, true)
            ? transfer : null;
    }

    public static boolean isLegacyActive(LegacyTransfer transfer) {
        return transfer != null && LEGACY_TRANSFERS.get(transfer.player.getUniqueID()) == transfer;
    }

    public static final class LegacyTransfer {
        private final EntityPlayerMP player;
        private final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();

        private LegacyTransfer(EntityPlayerMP player) {
            this.player = player;
        }
    }
    /**
     * 模型文件流式发送专用线程池（守护线程）。与通用 THREAD_POOL 隔离：低带宽模式下
     * 一次同步会长时间 sleep 节流，若占用通用池的核心线程（默认仅 4 个），并发同步的
     * 玩家会挤占服务端缓存重建/其他包处理。专用池只做流式发送，不影响其他任务。
     */
    private static final java.util.concurrent.ExecutorService STREAM_POOL =
        java.util.concurrent.Executors.newFixedThreadPool(
            Math.max(1, Math.min(2, Config.THREAD_COUNT)),
            r -> {
                Thread t = new Thread(r, "YSMU-SyncStream");
                t.setDaemon(true);
                return t;
            });

    /**
     * 当前连接及玩家主动重同步的冷却（/ysm sync 与空模型列表恢复共用）。冷却只作用于玩家
     * 主动触发的重同步；进服握手路径（sendRequestSyncModelMessage → S2CVersionCheck17 →
     * startSync）不受影响。登出时在 CommonEventHandler.onPlayerLoggedOut 清理。
     */
    private static final ResyncRequestGate<EntityPlayerMP> RESYNC_REQUESTS = new ResyncRequestGate<>();

    /** {@link #requestResync} 返回码。 */
    public static final int RESYNC_OK = 0;
    public static final int RESYNC_COOLDOWN = 1;
    public static final int RESYNC_DISABLED = 2;
    public static final int RESYNC_FAILED = 3;

    /**
     * 玩家主动请求重新同步（/ysm sync）。受 {@code RESYNC_COOLDOWN_SECONDS} 限频：
     * -1 完全拒绝；0 不限；>0 每个玩家冷却。玩家可高频触发 resync 消耗服务器上行带宽，
     * 因此该入口必须限频，且冷却判定在服务端（客户端不可信）。
     */
    public static int requestResync(EntityPlayerMP player) {
        if (player == null) {
            return RESYNC_FAILED;
        }
        // Keep authorization and handshake issuance atomic with logout/connection replacement.
        return RESYNC_REQUESTS.request(
            player.getUniqueID(), player, Config.RESYNC_COOLDOWN_SECONDS, System.currentTimeMillis(),
            () -> ServerModelManager.sendRequestSyncModelMessage(player));
    }

    /** 冷却剩余秒数（供命令层提示；未在冷却中返回 0）。 */
    public static long resyncRemainingSeconds(UUID playerId) {
        return playerId == null ? 0L : RESYNC_REQUESTS.remainingSeconds(
            playerId, Config.RESYNC_COOLDOWN_SECONDS, System.currentTimeMillis());
    }

    /** 登出时清理冷却记录。 */
    public static void clearResyncCooldown(UUID playerId) {
        if (playerId != null) {
            RESYNC_REQUESTS.clear(playerId);
        }
    }

    private OpenYsmModelSyncServer() {}

    /**
     * Is this sync entry the configured default model?
     *
     * <p>Compares against the <b>encoded</b> internal id: the configured name is encoded as
     * soon as it is not a plain lower-case name, so comparing the raw config value against
     * the registered id silently stopped matching for any name containing a space (or an
     * upper-case letter / non-ASCII character). The default model then lost its "send it
     * first" slot — and, on the client, its anti-eviction pinning. This path was
     * investigated alongside issue #4, whose root cause is not yet confirmed.
     */
    public static boolean isDefaultModelEntry(OpenYsmSyncInfo info) {
        return info != null && Config.getDefaultModelInternalId().equals(info.getModelId());
    }

    /**
     * 启动统一模型同步。返回 {@code false} 表示无法启动（参数/开关/服务器索引密钥
     * 缺失），调用方应回退到 legacy 同步，保证模型仍能加载。
     */
    public static boolean startSync(EntityPlayerMP player) {
        if (player == null || !Config.ENABLE_SYNC_PROTOCOL) {
            return false;
        }
        byte[] serverKey = ServerModelManager.OPEN_YSM_SERVER_KEY;
        if (serverKey == null || serverKey.length != 56) {
            ysmu.LOG.warn("Skipping OpenYSM model sync because server_index key is not initialized");
            return false;
        }

        UUID playerId = player.getUniqueID();
        PlayerSyncState state = new PlayerSyncState(player, createClientCacheKey(serverKey));
        // Order the sync list so the default model is sent first. The client
        // processes cache hits sequentially in this order, so this makes the
        // default model's scheduleApply() run before every other model — the
        // client-side priority queue then applies it first. Nearly every model
        // falls back to the default model's animation file / bones, so it must
        // be ready before the rest of the sync.
        state.allowedModels.addAll(ServerModelManager.OPEN_YSM_SYNC_INFO.values());
        // 比较**编码后**的内部 id：info.getModelId() 是注册表里的内部 id（名字不是安全名时
        // 形如 "_name_<hex>"），拿配置里的原始字符串去比会永远不相等 —— default 就再也
        // 不会被排到索引最前。渲染兜底仍是独立的内置 default，不由此配置决定。
        state.allowedModels.sort(Comparator.comparingInt(
            (OpenYsmSyncInfo info) -> isDefaultModelEntry(info) ? 0 : 1));
        // 索引已按缓存文件内容去重（cacheFileName 即 hash1+hash2 十六进制拼接），
        // (hash1, hash2) 在索引内唯一。预建哈希索引把 findAllowedModel 从 O(n) 降到
        // O(1)，大库全 miss 首同步不再做 ~n² 次线性扫描。
        for (OpenYsmSyncInfo info : state.allowedModels) {
            state.allowedByHash.computeIfAbsent(info.getHash1(), k -> new HashMap<>())
                .put(info.getHash2(), info);
        }
        if (SYNC_STATES.putIfAbsent(playerId, state) != null) return false;
        ysmu.LOG.info(
            "Starting OpenYSM model sync for {}: models={}",
            player.getCommandSenderName(),
            state.allowedModels.size());
        ThreadTools.THREAD_POOL.submit(() -> sendPacket01(playerId, state));
        return true;
    }

    public static void handlePayload(EntityPlayerMP player, byte[] data) {
        UUID playerId = player == null ? null : player.getUniqueID();
        PlayerSyncState state = playerId == null ? null : SYNC_STATES.get(playerId);
        if (state == null || state.player != player || data == null || data.length == 0) return;
        synchronized (state) {
            // One processing packet and one queued successor; a fast legitimate packet04
            // may arrive before the packet02 worker has returned after sending packet03.
            if (state.payloads.size() >= 1) return;
            state.payloads.add(data);
            if (!state.payloadPending.compareAndSet(false, true)) return;
        }
        try {
            ThreadTools.THREAD_POOL.submit(() -> {
                while (true) {
                    byte[] next;
                    synchronized (state) {
                        next = state.payloads.poll();
                        if (next == null) {
                            state.payloadPending.set(false);
                            return;
                        }
                    }
                    try {
                        handlePayloadAsync(playerId, state, next);
                    } catch (Throwable t) {
                        synchronized (state) {
                            state.payloads.clear();
                            state.payloadPending.set(false);
                        }
                        clear(playerId, state);
                        throw t;
                    }
                }
            });
        } catch (RuntimeException e) {
            synchronized (state) {
                state.payloads.clear();
                state.payloadPending.set(false);
            }
            throw e;
        }
    }

    public static void complete(EntityPlayerMP player, int status, int loaded, int downloaded, int cacheHits, String message) {
        UUID playerId = player == null ? null : player.getUniqueID();
        PlayerSyncState state = playerId == null ? null : SYNC_STATES.get(playerId);
        if (state == null || state.player != player || !SYNC_STATES.remove(playerId, state)) {
            return;
        }
        if (status == C2SCompleteFeedback17.STATUS_SUCCESS) {
            ysmu.LOG.info(
                "OpenYSM model sync completed for {}: loaded={}, downloaded={}, cacheHits={}",
                state.playerName,
                loaded,
                downloaded,
                cacheHits);
        } else {
            ysmu.LOG.warn(
                "OpenYSM model sync failed for {}: loaded={}, downloaded={}, cacheHits={}, message={}",
                state.playerName,
                loaded,
                downloaded,
                cacheHits,
                message);
        }
    }

    public static void clear(UUID playerId) {
        if (playerId != null) {
            RESYNC_REQUESTS.clear(playerId);
            SYNC_STATES.remove(playerId);
            EXPECTED_VERSION.clear(playerId);
            LEGACY_TRANSFERS.remove(playerId);
        }
    }

    private static void clear(UUID playerId, PlayerSyncState state) {
        SYNC_STATES.remove(playerId, state);
    }

    /** Derives the per-client cache key from the server key. Public so the client's
     *  local-model loader (LocalModelLoader) can compute the same key for its own
     *  local cache files. */
    public static byte[] createClientCacheKey(byte[] serverKey) {
        byte[] key = Arrays.copyOf(serverKey, 56);
        for (int i = 0; i < key.length; i++) {
            key[i] ^= (byte) (0x5A + i * 31);
        }
        return key;
    }

    /**
     * 统一模型加载后的模型总数：OpenYSM 同步索引已覆盖全部模型（文件夹 +
     * BOM+YSGP .ysm + 经转换的 legacy 裸 YSGP .ysm），进度条/完成统计直接以
     * 索引大小为准，无需再与 legacy 表求并集。
     */
    private static int totalModelCount() {
        return ServerModelManager.OPEN_YSM_SYNC_INFO.size();
    }

    private static void handlePayloadAsync(UUID playerId, PlayerSyncState state, byte[] packetBytes) {
        if (!isActive(playerId, state) || isTimedOut(state)) {
            clear(playerId, state);
            return;
        }

        try {
            state.touch();
            if (state.step == 1) {
                handlePacket02(playerId, state, packetBytes);
            } else if (state.step == 2) {
                handlePacket04(playerId, state, packetBytes);
            }
        } catch (Exception e) {
            clear(playerId, state);
            ysmu.LOG.warn("OpenYSM server sync error for " + state.playerName, e);
        }
    }

    private static void handlePacket02(UUID playerId, PlayerSyncState state, byte[] packetBytes) throws Exception {
        byte[] decrypted = YsmCrypt.decrypt(packetBytes, state.key1);
        if (decrypted == null || decrypted.length < 56) {
            return;
        }

        state.clientNextKey = Arrays.copyOfRange(decrypted, decrypted.length - 56, decrypted.length);
        byte[] payload = Arrays.copyOfRange(decrypted, 0, decrypted.length - 56);
        try (YSMByteBuf buf = new YSMByteBuf(Unpooled.wrappedBuffer(payload))) {
            buf.skipGarbageHeader();
            if (buf.getRawBuf().readByte() != 0x02) {
                return;
            }
        }

        state.step = 2;
        sendPacket03(playerId, state);
    }

    private static void handlePacket04(UUID playerId, PlayerSyncState state, byte[] packetBytes) throws Exception {
        byte[] decrypted = YsmCrypt.decrypt(packetBytes, state.key1);
        if (decrypted == null) {
            return;
        }

        List<OpenYsmSyncInfo> requested;
        try (YSMByteBuf buf = new YSMByteBuf(Unpooled.wrappedBuffer(decrypted))) {
            buf.skipGarbageHeader();
            if (buf.getRawBuf().readByte() != 0x04) return;
            requested = readRequestedModels(buf, state.allowedByHash, state.allowedModels.size());
        }

        state.step = 3;
        sendPacket05(playerId, state, requested);
    }

    static List<OpenYsmSyncInfo> readRequestedModels(YSMByteBuf buf,
        Map<Long, Map<Long, OpenYsmSyncInfo>> allowed, int maxModels) {
        int requestCount = buf.readVarInt();
        if (requestCount < 0 || requestCount > maxModels
            || requestCount > buf.getRawBuf().readableBytes() / 2) {
            throw new IllegalArgumentException("Invalid requested model count: " + requestCount);
        }
        List<OpenYsmSyncInfo> requested = new ArrayList<>();
        java.util.Set<OpenYsmSyncInfo> unique = new java.util.HashSet<>();
        for (int i = 0; i < requestCount; i++) {
            long h1 = buf.readVarLong();
            long h2 = buf.readVarLong();
            Map<Long, OpenYsmSyncInfo> bySecond = allowed.get(h1);
            OpenYsmSyncInfo info = bySecond == null ? null : bySecond.get(h2);
            if (info != null && unique.add(info)) requested.add(info);
        }
        return requested;
    }

    private static void sendPacket01(UUID playerId, PlayerSyncState state) {
        if (!isActive(playerId, state)) {
            return;
        }
        try {
            byte[] garbage = randomGarbage();
            try (YSMByteBuf out = new YSMByteBuf(Unpooled.buffer())) {
                out.writeGarbageHeader(garbage.length, garbage);
                out.writeByte((byte) 0x01);
                YsmCrypt.EncryptedPacket encrypted = YsmCrypt.encrypt(out.toArray(), YsmCrypt.publicKey, true);
                state.key1 = encrypted.nextKey();
                sendPayload(playerId, state, encrypted.data());
            }
        } catch (Exception e) {
            clear(playerId, state);
            ysmu.LOG.warn("Failed to send OpenYSM packet 01 to " + state.playerName, e);
        }
    }

    private static void sendPacket03(UUID playerId, PlayerSyncState state) {
        if (!isActive(playerId, state)) {
            return;
        }
        try {
            byte[] garbage = randomGarbage();
            try (YSMByteBuf out = new YSMByteBuf(Unpooled.buffer())) {
                out.writeGarbageHeader(garbage.length, garbage);
                out.writeVarInt(3);
                out.writeVarLong(0L);
                out.getRawBuf().writeBytes(ServerModelManager.OPEN_YSM_SERVER_KEY);
                out.getRawBuf().writeBytes(state.clientCacheKey);

                // 进度条总模型数 = OpenYSM 同步索引大小。统一模型加载后索引已覆盖全部
                // 模型（文件夹 + BOM+YSGP + 转换后的裸 YSGP），与下方索引条目数一致；
                // 两个字段分开写是为了兼容旧协议布局，客户端已按同一数值使用。
                out.writeVarInt(totalModelCount());
                out.writeVarInt(state.allowedModels.size());
                for (OpenYsmSyncInfo model : state.allowedModels) {
                    out.writeVarLong(model.getHash1());
                    out.writeVarLong(model.getHash2());
                    out.writeString(model.getModelId());
                    out.writeVarInt(model.isCustomSkinModel() ? 1 : 0);
                    out.writeVarInt(model.getFormat());
                }

                // 写入包数据
                out.writeVarInt(ServerModelManager.PACKS.size());
                for (ServerModelManager.ServerPackData pack : ServerModelManager.PACKS.values()) {
                    out.writeString(pack.folderPath);
                    // 图标
                    if (pack.iconData != null) {
                        out.writeVarInt(1);
                        out.writeByteArray(pack.iconData);
                        out.writeVarInt(pack.iconWidth);
                        out.writeVarInt(pack.iconHeight);
                        out.writeVarInt(pack.iconFormat);
                        out.writeVarInt(1); // unkImageData
                    } else {
                        out.writeVarInt(0);
                    }
                    // 名称/描述
                    if (pack.name != null || pack.description != null) {
                        out.writeVarInt(1);
                        out.writeString(pack.name != null ? pack.name : "");
                        out.writeString(pack.description != null ? pack.description : "");
                    } else {
                        out.writeVarInt(0);
                    }
                    // 本地化
                    out.writeVarInt(pack.lang.size());
                    for (Map.Entry<String, Map<String, String>> langEntry : pack.lang.entrySet()) {
                        out.writeString(langEntry.getKey());
                        out.writeVarInt(langEntry.getValue().size());
                        for (Map.Entry<String, String> kv : langEntry.getValue().entrySet()) {
                            out.writeString(kv.getKey());
                            out.writeString(kv.getValue());
                        }
                    }
                }
                out.writeVarInt(0);

                YsmCrypt.EncryptedPacket encrypted = YsmCrypt.encrypt(out.toArray(), state.clientNextKey, false);
                byte[] payload = encrypted.data();
                // The sync index can exceed the ~32 KB custom-payload limit on large
                // model libraries (e.g. 976 models); the transport splits it into
                // separate messages and the client can't parse them. Chunk it so the
                // client reassembles the single logical packet03 before decrypting.
                if (payload.length <= SYNC_INDEX_SINGLE_MAX) {
                    sendPayload(playerId, state, payload);
                } else {
                    sendSyncIndexChunked(playerId, state, payload);
                }
            }
        } catch (Exception e) {
            clear(playerId, state);
            ysmu.LOG.warn("Failed to send OpenYSM packet 03 to " + state.playerName, e);
        }
    }

    /**
     * Sends the (already encrypted) sync index in chunks, each well under the ~32 KB
     * custom-payload limit, so large model libraries don't get the index truncated or
     * split mid-packet. The client reassembles the chunks before decrypting.
     */
    private static void sendSyncIndexChunked(UUID playerId, PlayerSyncState state, byte[] payload) {
        int total = payload.length;
        int offset = 0;
        int count = 0;
        while (offset < total) {
            if (!isActive(playerId, state)) return;
            int length = Math.min(SYNC_INDEX_CHUNK_SIZE, total - offset);
            byte[] chunk = Arrays.copyOfRange(payload, offset, offset + length);
            NetworkHandler.sendToClientPlayer(
                new S2CSyncIndexChunk17(total, offset, chunk, offset + length >= total),
                state.player);
            offset += length;
            count++;
        }
        if (count > 1) {
            ysmu.LOG.info(
                "OpenYSM server sent sync index to {} in {} chunks ({} bytes)",
                state.playerName,
                count,
                total);
        }
    }

    private static void sendPacket05(UUID playerId, PlayerSyncState state, List<OpenYsmSyncInfo> requested) {
        STREAM_POOL.submit(() -> {
            int sentModels = 0;
            try {
                int chunkSize = getChunkSize();
                for (OpenYsmSyncInfo model : requested) {
                    if (!isActive(playerId, state)) {
                        return;
                    }
                    File cacheFile = ServerModelManager.CACHE_SERVER.resolve(model.getCacheFileName()).toFile();
                    if (!cacheFile.isFile()) {
                        throw new java.io.IOException("Missing model cache: " + model.getModelId());
                    }
                    byte[] fileData = FileUtils.readFileToByteArray(cacheFile);
                    int offset = 0;
                    while (offset < fileData.length) {
                        if (!isActive(playerId, state)) {
                            return;
                        }
                        int length = Math.min(chunkSize, fileData.length - offset);
                        byte[] packet = createPacket05(state, model, fileData, offset, length);
                        sendPayload(playerId, state, packet);
                        throttle(length);
                        offset += length;
                    }
                    sentModels++;
                }
                ysmu.LOG.info(
                    "OpenYSM server sent {} requested model cache files to {}",
                    sentModels,
                    state.playerName);
            } catch (Exception e) {
                sendSyncFailure(playerId, state, "Model cache transfer failed: " + e.getClass().getSimpleName());
                clear(playerId, state);
                ysmu.LOG.warn("Failed to send OpenYSM model chunks to " + state.playerName, e);
            }
        });
    }

    /** Packet type 6 shares the existing encrypted payload channel and session key. */
    private static void sendSyncFailure(UUID playerId, PlayerSyncState state, String reason) {
        if (!isActive(playerId, state)) return;
        try (YSMByteBuf out = new YSMByteBuf(Unpooled.buffer())) {
            byte[] garbage = randomGarbage();
            out.writeGarbageHeader(garbage.length, garbage);
            out.writeVarInt(6);
            out.writeString(reason);
            sendPayload(playerId, state, YsmCrypt.encrypt(out.toArray(), state.key1, false).data());
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to report sync transfer failure to {}: {}", state.playerName, e.toString());
        }
    }

    private static byte[] createPacket05(PlayerSyncState state, OpenYsmSyncInfo model, byte[] fileData, int offset,
        int length) throws Exception {
        byte[] garbage = randomGarbage();
        try (YSMByteBuf out = new YSMByteBuf(Unpooled.buffer())) {
            out.writeGarbageHeader(garbage.length, garbage);
            out.writeVarInt(5);
            out.writeVarLong(model.getHash1());
            out.writeVarLong(model.getHash2());
            out.writeVarInt(fileData.length);
            out.writeVarInt(offset);
            out.writeVarInt(length);
            out.getRawBuf().writeBytes(fileData, offset, length);
            return YsmCrypt.encrypt(out.toArray(), state.key1, false).data();
        }
    }

    private static void sendPayload(UUID playerId, PlayerSyncState state, byte[] payload) {
        if (isActive(playerId, state)) {
            NetworkHandler.sendToClientPlayer(new S2CModelSyncPayload17(payload), state.player);
        }
    }

    private static byte[] randomGarbage() {
        byte[] garbage = new byte[16 + RANDOM.nextInt(48)];
        RANDOM.nextBytes(garbage);
        return garbage;
    }

    private static int getChunkSize() {
        return Config.LOW_BANDWIDTH_USAGE ? LOW_BANDWIDTH_CHUNK_SIZE : DEFAULT_CHUNK_SIZE;
    }

    private static void throttle(int bytes) throws InterruptedException {
        int bandwidthLimit = Config.BANDWIDTH_LIMIT;
        if (Config.LOW_BANDWIDTH_USAGE && bandwidthLimit <= 0) {
            bandwidthLimit = 64 * 1024;
        }
        if (bandwidthLimit <= 0) {
            return;
        }
        long sleepMillis = Math.max(1L, bytes * 1000L / bandwidthLimit);
        Thread.sleep(sleepMillis);
    }

    private static boolean isActive(UUID playerId, PlayerSyncState state) {
        return SYNC_STATES.get(playerId) == state;
    }

    private static boolean isTimedOut(PlayerSyncState state) {
        long timeoutMillis = Config.PLAYER_SYNC_TIMEOUT * 1000L;
        return timeoutMillis > 0L && System.currentTimeMillis() - state.lastTouched > timeoutMillis;
    }

    private static final class PlayerSyncState {

        private final EntityPlayerMP player;
        private final String playerName;
        private final byte[] clientCacheKey;
        private final List<OpenYsmSyncInfo> allowedModels = new ArrayList<>();
        /** (hash1, hash2) → info 查找索引，startSync 构建 allowedModels 后填充。 */
        private final Map<Long, Map<Long, OpenYsmSyncInfo>> allowedByHash = new HashMap<>();
        private final java.util.concurrent.atomic.AtomicBoolean payloadPending = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.Queue<byte[]> payloads = new java.util.ArrayDeque<>();
        private byte[] key1;
        private byte[] clientNextKey;
        private int step = 1;
        private long lastTouched = System.currentTimeMillis();

        private PlayerSyncState(EntityPlayerMP player, byte[] clientCacheKey) {
            this.player = player;
            this.playerName = player.getCommandSenderName();
            this.clientCacheKey = clientCacheKey;
        }

        private void touch() {
            this.lastTouched = System.currentTimeMillis();
        }
    }
}
