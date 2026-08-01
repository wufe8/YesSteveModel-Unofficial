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

    private OpenYsmModelSyncClient() {}

    public static void handlePayload(byte[] data) {
        ThreadTools.THREAD_POOL.submit(() -> processServerData(data));
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
        int serverModelCount = buf.readVarInt();
        ClientModelManager.SYNC_TOTAL = serverModelCount;
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
                    if (parseAndRegisterModel(clearBytes, context)) {
                        loadedModelsCount++;
                    }
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
            byte[] clientCacheBytes = YsmCrypt
                .transcodeServerDataToClientCache(context.fileBuffer, serverKey, clientKey, hash1, hash2);
            File outFile = new File(getCacheDir(), YSMClientCache.generateCacheFileName(hash1, hash2, clientKey));
            FileUtils.writeByteArrayToFile(outFile, clientCacheBytes);
            context.fileBuffer = null;

            byte[] clearBytes = YsmCrypt.read(clientCacheBytes, clientKey);
            if (parseAndRegisterModel(clearBytes, context)) {
                loadedModelsCount++;
                downloadedModelsCount++;
            }
            pendingModelsCount--;
            if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SYNC) {
                ysmu.LOG.info("OpenYSM client downloaded and cached {} to {}", context.modelId, outFile);
            }
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

            // Always register extra wheel data, even for non-bridgeable models.
            // Must not throw on the main thread: this runs via func_152344_a for
            // EVERY synced model, and an exception here would propagate to
            // Minecraft's crash handler (repeated "Negative index in crash report
            // handler" spam while the model library syncs).
            Minecraft.getMinecraft().func_152344_a(() -> {
                try {
                    ClientModelManager.registerExtraWheel(modelId, raw);
                } catch (Exception e) {
                    ysmu.LOG.warn("Failed to register extra wheel data for {}: {}",
                        context.modelId, e.getMessage());
                }
            });
            if (!RawYsmModelAdapter.isBridgeable(raw)) {
                // Even when the model can't be bridged to legacy ModelData, we still
                // need to register projectile sub-entity models so arrow rendering works.
                // Must run on the main render thread because registerProjectilesFromRaw
                // calls OpenGL via registerTexture -> TextureManager -> glGenTextures.
                if (raw.projectiles != null && !raw.projectiles.isEmpty()) {
                    Minecraft.getMinecraft().func_152344_a(() -> {
                        try {
                            ResourceLocation baseModelId = ModelIdUtil.getModelIdFromMainId(modelId);
                            registerProjectilesFromRaw(raw, baseModelId);
                        } catch (Exception e) {
                            ysmu.LOG.warn("Failed to register projectile models for non-bridgeable model {}",
                                context.modelId, e);
                        }
                    });
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

            // Parse geometry/animation on background thread, only register on main thread.
            com.fox.ysmu.client.model.PreParsedModelBundle bundle;
            try {
                bundle = ClientModelManager.preParseModel(data);
            bundle.previewAnimation = raw.properties.previewAnimation;
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to pre-parse model {}: {}", context.modelId, e.getMessage());
                return false;
            }
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

    /**
     * Register projectile sub-entity models/textures directly from a RawYsmModel,
     * without requiring full legacy ModelData bridging.
     * Used when isBridgeable() returns false but projectiles still need to render.
     */
    private static void registerProjectilesFromRaw(RawYsmModel raw, ResourceLocation baseModelId) {
        if (raw.projectiles == null || raw.projectiles.isEmpty()) return;
        for (Map.Entry<String, RawYsmModel.RawSubEntity> entry : raw.projectiles.entrySet()) {
            RawYsmModel.RawSubEntity sub = entry.getValue();
            if (sub.model == null) continue;
            String[] matchIds = sub.matchIds != null && sub.matchIds.length > 0
                ? sub.matchIds : new String[]{sub.identifier};
            for (String matchId : matchIds) {
                if (matchId == null || matchId.isEmpty()) continue;
                try {
                    // Register geometry
                    byte[] geoBytes = RawYsmModelAdapter.toGeometryJson(null, sub.model, false);
                    ResourceLocation geoId = ModelIdUtil.getSubModelId(baseModelId, "projectile_" + matchId);
                    ClientModelManager.registerGeo(geoId, geoBytes);
                    ClientModelManager.PROJECTILE_MODEL_IDS
                        .computeIfAbsent(baseModelId, k -> new ArrayList<>())
                        .add(matchId);
                    // Register textures
                    for (RawYsmModel.RawTexture tex : sub.textures.values()) {
                        if (tex.data == null) continue;
                        byte[] texData = RawYsmModelAdapter.getLegacyTextureData(tex);
                        if (texData == null) continue;
                        String texKey = "projectile_" + matchId + "_" + tex.name;
                        if (!texKey.endsWith(".png")) texKey += ".png";
                        ResourceLocation texId = ModelIdUtil.getSubModelId(baseModelId, texKey);
                        ClientModelManager.registerTexture(texId, texData);
                        ClientModelManager.PROJECTILE_TEXTURE_IDS
                            .computeIfAbsent(baseModelId, k -> new ArrayList<>())
                            .add(texId);
                    }

                    // Register animation files under the projectile's GeoModel ID
                    ResourceLocation projAnimId = ModelIdUtil.getSubModelId(baseModelId, "projectile_" + matchId);
                    for (RawYsmModel.RawAnimationFile animFile : sub.animationFiles.values()) {
                        if (animFile.animations == null || animFile.animations.isEmpty()) continue;
                        try {
                            // Binary-format models don't have sourceJson; re-serialize from structured data.
                            byte[] jsonBytes = animFile.sourceJson != null
                                ? animFile.sourceJson
                                : com.fox.ysmu.model.resource.RawYsmModelAdapter.createAnimationJson(animFile);
                            String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);
                            com.google.gson.JsonObject jsonObject = com.fox.ysmu.util.GsonHelper.fromJson(
                                com.fox.ysmu.ysmu.GSON, json, com.google.gson.JsonObject.class);
                            if (jsonObject == null) continue;
                            software.bernie.geckolib3.file.AnimationFile parsedAnim =
                                new software.bernie.geckolib3.file.AnimationFile();
                            software.bernie.geckolib3.core.molang.MolangParser parser =
                                software.bernie.geckolib3.resource.GeckoLibCache.getInstance().parser;
                            for (java.util.Map.Entry<String, com.google.gson.JsonElement> ae
                                : software.bernie.geckolib3.util.json.JsonAnimationUtils.getAnimations(jsonObject)) {
                                try {
                                    software.bernie.geckolib3.core.builder.Animation anim =
                                        software.bernie.geckolib3.util.json.JsonAnimationUtils
                                            .deserializeJsonToAnimation(ae, parser);
                                    parsedAnim.putAnimation(ae.getKey(), anim);
                                } catch (Exception ex) {
                                    ysmu.LOG.warn("Failed to deserialize projectile animation '{}': {}",
                                        ae.getKey(), ex.getMessage());
                                }
                            }
                            if (!parsedAnim.animations.isEmpty()) {
                                // Merge into a single AnimationFile per projectile
                                software.bernie.geckolib3.file.AnimationFile existing =
                                    software.bernie.geckolib3.resource.GeckoLibCache.getInstance().getAnimations()
                                        .get(projAnimId);
                                if (existing == null) {
                                    existing = new software.bernie.geckolib3.file.AnimationFile();
                                    software.bernie.geckolib3.resource.GeckoLibCache.getInstance().getAnimations()
                                        .put(projAnimId, existing);
                                }
                                for (java.util.Map.Entry<String, software.bernie.geckolib3.core.builder.Animation> ae
                                    : parsedAnim.animations.entrySet()) {
                                    existing.putAnimation(ae.getKey(), ae.getValue());
                                }
                                if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD && com.fox.ysmu.Config.DEBUG_MODEL_SYNC) {
                                    ysmu.LOG.info("[YSMU-MODEL] Registered projectile animation for {}: {} anims from {}",
                                        projAnimId, parsedAnim.animations.size(), animFile.fileHash);
                                }
                            }
                        } catch (Exception e) {
                            ysmu.LOG.warn("Failed to parse projectile animation for {}: {}",
                                projAnimId, e.getMessage());
                        }
                    }

                    // Register controller files under the projectile's animation ID
                    for (RawYsmModel.RawAnimationControllerFile ctrlFile : sub.animationControllerFiles) {
                        if (ctrlFile.controllers == null || ctrlFile.controllers.isEmpty()) continue;
                        try {
                            // Binary-format models don't have sourceJson; re-serialize from structured data.
                            byte[] ctrlBytes = ctrlFile.sourceJson != null
                                ? ctrlFile.sourceJson
                                : com.fox.ysmu.model.resource.RawYsmModelAdapter.createControllerJson(ctrlFile);
                            OpenYsmAnimationControllerRegistry.register(projAnimId,
                                java.util.Collections.singleton(ctrlBytes));
                            if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD && com.fox.ysmu.Config.DEBUG_MODEL_SYNC) {
                                ysmu.LOG.info("[YSMU-MODEL] Registered projectile controller for {}: '{}' ({} states)",
                                    projAnimId, ctrlFile.name,
                                    ctrlFile.controllers.size());
                            }
                        } catch (Exception e) {
                            ysmu.LOG.warn("Failed to register projectile controller for {}: {}",
                                projAnimId, e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    ysmu.LOG.warn("Failed to register projectile {} for model {}",
                        matchId, baseModelId, e);
                }
            }
        }
        if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SYNC) {
            ysmu.LOG.info("[YSMU-MODEL] Inline-registered projectile models for {}: {}",
                baseModelId, ClientModelManager.PROJECTILE_MODEL_IDS.get(baseModelId));
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
            // 在聊天栏输出客户端完成信息
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(
                    new net.minecraft.util.ChatComponentTranslation(
                        "message.yes_steve_model.sync.complete", elapsed));
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
