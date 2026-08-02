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
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.YSMBinaryDeserializer;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.network.NetworkHandler;
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
    private static final Map<UUID, ServerModelContext> SERVER_MODELS = Maps.newConcurrentMap();

    private static volatile int syncStep = 1;
    private static volatile int pendingModelsCount;
    private static volatile int loadedModelsCount;
    private static volatile int downloadedModelsCount;
    private static volatile int cacheHitCount;
    private static volatile long syncStartTimeMs;
    private static byte[] key1;
    private static byte[] lastKey;
    private static byte[] serverKey;
    private static byte[] clientKey;
    private static String currentCacheFolderName;
    /** Reassembly buffer for the (encrypted, possibly chunked) sync index. */
    private static byte[] syncIndexChunks;
    private static int syncIndexTotal;
    private static int syncIndexReceived;

    private OpenYsmModelSyncClient() {}

    public static void handlePayload(byte[] data) {
        ThreadTools.THREAD_POOL.submit(() -> processServerData(data));
    }

    /**
     * Receives a chunk of the (encrypted) sync index from the server and, once the
     * final chunk arrives, hands the reassembled blob to {@link #handlePayload} for
     * the normal decrypt + packet03 parse. Only used for oversized indexes that the
     * server chunks to stay under the ~32 KB custom-payload limit.
     */
    public static synchronized void handleSyncIndexChunk(int totalLength, int offset, byte[] chunk, boolean last) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        if (syncIndexChunks == null || syncIndexTotal != totalLength) {
            syncIndexChunks = new byte[totalLength];
            syncIndexTotal = totalLength;
            syncIndexReceived = 0;
        }
        if (offset < 0 || offset + chunk.length > syncIndexChunks.length) {
            ysmu.LOG.warn("OpenYSM client sync index chunk out of range: offset={}, len={}, total={}",
                offset, chunk.length, totalLength);
            return;
        }
        System.arraycopy(chunk, 0, syncIndexChunks, offset, chunk.length);
        syncIndexReceived += chunk.length;
        if (last) {
            byte[] full = syncIndexChunks;
            syncIndexChunks = null;
            syncIndexTotal = 0;
            syncIndexReceived = 0;
            handlePayload(full);
        }
    }

    public static synchronized void resetConnectionState() {
        syncStep = 1;
        pendingModelsCount = 0;
        loadedModelsCount = 0;
        downloadedModelsCount = 0;
        cacheHitCount = 0;
        key1 = null;
        lastKey = null;
        serverKey = null;
        currentCacheFolderName = null;
        syncIndexChunks = null;
        syncIndexTotal = 0;
        syncIndexReceived = 0;
        SERVER_MODELS.clear();
        // NOTE: clientKey is intentionally KEPT — lazy geo/anim/texture reload
        // re-decrypts the client cache files with it after an idle unload, and
        // the sync teardown (sendComplete) also calls resetConnectionState(). It
        // is only cleared on disconnect (clearConnectionState).
    }

    public static synchronized void clearConnectionState() {
        resetConnectionState();
        clientKey = null;
    }

    private static synchronized void processServerData(byte[] packetBytes) {
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

    private static void handlePacket01(byte[] decryptedBuffer) throws Exception {
        resetConnectionState();
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

    private static void handlePacket03(YSMByteBuf buf) throws Exception {
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

        SERVER_MODELS.clear();
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
        ClientModelManager.SYNC_IN_PROGRESS = true;
        syncStartTimeMs = System.currentTimeMillis();
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
            SERVER_MODELS.put(context.uuid, context);

            File cachedFile = localCacheMap.get(context.uuid);
            if (YSMClientCache.verifyFileContent(cachedFile, hash1, hash2)) {
                cacheHitCount++;
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
                        if (parseAndRegisterModel(clearBytes, context)) {
                            loadedModelsCount++;
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
    }

    private static void handlePacket05(YSMByteBuf buf) throws Exception {
        buf.skipGarbageHeader();
        int type = buf.readVarInt();
        if (type != 5) {
            return;
        }

        long hash1 = buf.readVarLong();
        long hash2 = buf.readVarLong();
        UUID uuid = new UUID(hash1, hash2);
        ServerModelContext context = SERVER_MODELS.get(uuid);
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

        if (context.bytesReceived >= context.totalSize) {
            byte[] fileBuffer = context.fileBuffer;
            context.fileBuffer = null;
            try {
                byte[] clientCacheBytes = YsmCrypt
                    .transcodeServerDataToClientCache(fileBuffer, serverKey, clientKey, hash1, hash2);
                File outFile = new File(getCacheDir(), YSMClientCache.generateCacheFileName(hash1, hash2, clientKey));
                FileUtils.writeByteArrayToFile(outFile, clientCacheBytes);

                byte[] clearBytes = YsmCrypt.read(clientCacheBytes, clientKey);
                if (parseAndRegisterModel(clearBytes, context)) {
                    loadedModelsCount++;
                    downloadedModelsCount++;
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
            }
            pendingModelsCount--;
            if (pendingModelsCount <= 0) {
                sendComplete(C2SCompleteFeedback17.STATUS_SUCCESS, "");
            }
        }
    }

    private static void sendPacket04(List<ModelHash> modelsToRequest) throws Exception {
        syncStep = 3;
        pendingModelsCount = modelsToRequest.size();

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

        if (pendingModelsCount == 0) {
            sendComplete(C2SCompleteFeedback17.STATUS_SUCCESS, "");
        }
    }

    private static boolean parseAndRegisterModel(byte[] clearBytes, ServerModelContext context) {
        try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(clearBytes, 32)) {
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
     *  ClientModelManager lazy-reload (geo/anim/texture) after an idle unload. */
    public static byte[] readClientCacheToClearBytes(byte[] cacheBytes) {
        if (clientKey == null) return null;
        try {
            return YsmCrypt.read(cacheBytes, clientKey);
        } catch (Exception e) {
            return null;
        }
    }

    private static File getCacheDir() {
        String folder = currentCacheFolderName == null ? "0" : currentCacheFolderName;
        return ServerModelManager.CACHE_CLIENT.resolve(folder).toFile();
    }

    private static void sendPayload(byte[] payload) {
        NetworkHandler.CHANNEL.sendToServer(new C2SModelSyncPayload17(payload));
    }

    private static void sendComplete(int status, String message) {
        NetworkHandler.CHANNEL.sendToServer(
            new C2SCompleteFeedback17(status, loadedModelsCount, downloadedModelsCount, cacheHitCount, message));
        if (status == C2SCompleteFeedback17.STATUS_SUCCESS) {
            long elapsed = System.currentTimeMillis() - syncStartTimeMs;
            ysmu.LOG.info(
                "OpenYSM client sync complete: loaded={}, downloaded={}, cacheHits={}, time={}ms",
                loadedModelsCount, downloadedModelsCount, cacheHitCount, elapsed);
            // 在聊天栏输出客户端完成信息（成功/失败/总数统计；总数=统一索引大小）
            int registered = ClientModelManager.MODELS.size();
            int failed = ClientModelManager.SYNC_FAILED;
            int total = ClientModelManager.SYNC_TOTAL;
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(
                    new net.minecraft.util.ChatComponentTranslation(
                        "message.yes_steve_model.sync.complete", elapsed));
                mc.thePlayer.addChatMessage(
                    new net.minecraft.util.ChatComponentTranslation(
                        "message.yes_steve_model.sync.complete_models", registered, failed, total));
            }
        }
        ClientModelManager.SYNC_IN_PROGRESS = false;
        resetConnectionState();
    }

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
