package com.fox.ysmu.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.util.*;

import com.fox.ysmu.client.model.PreParsedModelBundle;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.animation.AnimationManager;
import com.fox.ysmu.client.animation.condition.ConditionManager;
import com.fox.ysmu.client.animation.controller.OpenYsmAnimationControllerRegistry;
import com.fox.ysmu.client.animation.molang.MolangInstructionExecutor;
import com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime;
import com.fox.ysmu.client.sync.OpenYsmModelSyncClient;
import com.fox.ysmu.client.texture.OuterFileTexture;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.format.FolderFormat;
import com.fox.ysmu.model.resource.RawYsmModelAdapter;
import com.fox.ysmu.model.resource.YSMFolderDeserializer;
import com.fox.ysmu.model.resource.YsmControllerResources;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SyncModelFiles;
import com.fox.ysmu.util.GsonHelper;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.Pair;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.ExtraInfo;
import software.bernie.geckolib3.geo.raw.pojo.FormatVersion;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;
import software.bernie.geckolib3.geo.raw.tree.RawGeometryTree;
import software.bernie.geckolib3.geo.render.GeoBuilder;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;
import software.bernie.geckolib3.util.json.JsonAnimationUtils;

public class ClientModelManager {

    /**
     * Model statistics for tooltip display: [totalBones, totalFaces, totalAnimations].
     * Computed after model registration in registerAll().
     */
    public static final Map<ResourceLocation, int[]> MODEL_STATS = Maps.newHashMap();

    // ── Sync progress tracking ──────────────────────────────────────────
    /** Total models to load (-1 = unknown, used by legacy sync). */
    public static volatile int SYNC_TOTAL = -1;
    /** Models loaded so far. */
    public static volatile int SYNC_LOADED = 0;
    /** True while a model sync is in progress. */
    public static volatile boolean SYNC_IN_PROGRESS = false;
    /** Display name of the model currently being registered (for progress overlay). */
    public static volatile String SYNC_CURRENT_MODEL = "";

    public static Map<ResourceLocation, List<ResourceLocation>> MODELS = Maps.newHashMap();
    public static Map<ResourceLocation, Pair<Double, Double>> SCALE_INFO = Maps.newHashMap();
    public static Map<ResourceLocation, List<IChatComponent>> EXTRA_INFO = Maps.newHashMap();
    public static Map<ResourceLocation, String[]> EXTRA_ANIMATION_NAME = Maps.newHashMap();
    /** Rich wheel data (YSM 2.3.0+ sub-pages). */
    public static Map<ResourceLocation, ExtraWheelData> EXTRA_WHEEL = Maps.newHashMap();
    /** Preview animation name per model, read from ysm.json preview_animation field. */
    public static Map<ResourceLocation, String> PREVIEW_ANIMATION = Maps.newHashMap();
    /** GUI foreground texture RawImage per model, from ysm.json gui_foreground. */
    public static Map<ResourceLocation, com.fox.ysmu.model.resource.pojo.RawYsmModel.RawImage> GUI_FOREGROUND_IMAGE = Maps.newHashMap();
    /** GUI background texture RawImage per model, from ysm.json gui_background. */
    public static Map<ResourceLocation, com.fox.ysmu.model.resource.pojo.RawYsmModel.RawImage> GUI_BACKGROUND_IMAGE = Maps.newHashMap();
    /** Per-model disable_preview_rotation flag, from ysm.json properties. */
    public static Map<ResourceLocation, Boolean> DISABLE_PREVIEW_ROTATION = Maps.newHashMap();
    /** Per-model gui_no_lighting flag, from ysm.json properties. */
    public static Map<ResourceLocation, Boolean> GUI_NO_LIGHTING = Maps.newHashMap();

    /**
     * 模型包/文件夹分组：pack显示名称 → 该包内的模型ID列表。
     * 当模型位于 config/ysmu/custom/<packName>/<modelName>/ 时被归入包。
     * 顶层模型（直接位于 custom/ 下）不在此表中。
     */
    public static final LinkedHashMap<String, List<ResourceLocation>> MODEL_PACKS = new LinkedHashMap<>();
    /**
     * 快速查找：模型ID → 所属包的显示名称（不在包内的模型为 null）。
     */
    public static final Map<ResourceLocation, String> MODEL_PACK_OF = Maps.newHashMap();
    /**
     * 客户端包元数据：包路径 → ClientPackData（名称、描述、多语言、图标）。
     * 在同步过程中从协议数据解析填充。
     */
    public static final Map<String, ClientPackData> CLIENT_PACKS = Maps.newHashMap();
    /**
     * 模型显示名称映射：模型ID → ysm.json metadata.name。
     * 在 registerExtraWheel 时从 RawYsmModel 填充。
     */
    public static final Map<ResourceLocation, String> MODEL_DISPLAY_NAMES = Maps.newHashMap();

    /**
     * Projectile sub-entity model IDs per player model.
     * Maps player model ID → list of projectile entity type strings (e.g. "minecraft:arrow").
     */
    public static final Map<ResourceLocation, List<String>> PROJECTILE_MODEL_IDS = Maps.newHashMap();

    /**
     * Projectile texture ResourceLocations per player model.
     * Maps player model ID → list of texture IDs for projectile sub-entities.
     */
    public static final Map<ResourceLocation, List<ResourceLocation>> PROJECTILE_TEXTURE_IDS = Maps.newHashMap();

    private static final String PROJECTILE_KEY_PREFIX = "projectile_";

    private static boolean isProjectileKey(String key) {
        return key.startsWith(PROJECTILE_KEY_PREFIX);
    }

    /**
     * Extract the entity type from a projectile key like "projectile_minecraft:arrow".
     */
    private static String projectileEntityType(String key) {
        // Everything after "projectile_"
        return key.substring(PROJECTILE_KEY_PREFIX.length());
    }

    public static AnimationFile DEFAULT_ANIMATION_FILE = new AnimationFile();
    public static List<String> CACHE_MD5 = Collections.synchronizedList(Lists.newArrayList());
    public static volatile byte[] PASSWORD;
    public static volatile UUID PASSWORD_UUID;

    // ── Tick‑based applyPreParsed queue ────────────────────────────────
    // Process at most ONE model per render tick so the main thread stays
    // responsive (handles window messages, rendering, etc.) even when 40+
    // models arrive from background parsing simultaneously.

    private static final java.util.Queue<PreParsedModelBundle> PENDING_APPLY =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Schedules {@link #applyPreParsed(PreParsedModelBundle)} to run on the
     * Minecraft main thread, at most one bundle per render tick.
     */
    public static void scheduleApply(PreParsedModelBundle bundle) {
        PENDING_APPLY.add(bundle);
        // Ensure at least one processor is queued on the main thread.
        Minecraft.getMinecraft().func_152344_a(ClientModelManager::processNextApply);
    }

    private static void processNextApply() {
        PreParsedModelBundle bundle = PENDING_APPLY.poll();
        if (bundle == null) return;
        try {
            applyPreParsed(bundle);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to apply pre-parsed model {}: {}", bundle.modelId, e.getMessage());
        }
        // Schedule the next one if more are pending.
        if (!PENDING_APPLY.isEmpty()) {
            Minecraft.getMinecraft().func_152344_a(ClientModelManager::processNextApply);
        }
    }

    /**
     * Caches content hashes of registered textures across reloads.
     * Survives {@link #clearRuntimeModelCaches()} so that unchanged textures
     * (same ResourceLocation, same byte content) are not re-decoded and
     * re-uploaded to the GPU on every {@code /ysm reload}.
     */
    private static final Map<ResourceLocation, Integer> TEXTURE_CONTENT_HASH = new java.util.HashMap<>();

    /**
     * Parses model geometries and animations on the calling thread (should be a background thread).
     * Returns a bundle that {@link #applyPreParsed(PreParsedModelBundle)} applies on the main thread.
     */
    public static PreParsedModelBundle preParseModel(ModelData data) {
        ResourceLocation modelId = getModelId(data);
        PreParsedModelBundle bundle = new PreParsedModelBundle(modelId);

        // Separate projectile sub-entity data from main model data
        Map<String, byte[]> mainModelMap = new LinkedHashMap<>();
        Map<String, byte[]> projModelMap = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : data.getModel().entrySet()) {
            if (isProjectileKey(e.getKey())) {
                projModelMap.put(e.getKey(), e.getValue());
            } else {
                mainModelMap.put(e.getKey(), e.getValue());
            }
        }
        Map<String, byte[]> mainTexMap = new LinkedHashMap<>();
        Map<String, byte[]> projTexMap = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : data.getTexture().entrySet()) {
            if (isProjectileKey(e.getKey())) {
                projTexMap.put(e.getKey(), e.getValue());
            } else {
                mainTexMap.put(e.getKey(), e.getValue());
            }
        }

        // HEAVY: Parse geometries on background thread
        for (Map.Entry<String, byte[]> entry : mainModelMap.entrySet()) {
            parseGeoToBundle(bundle, ModelIdUtil.getSubModelId(modelId, entry.getKey()), entry.getValue());
        }
        // HEAVY: Parse projectile geometries on background thread
        for (Map.Entry<String, byte[]> entry : projModelMap.entrySet()) {
            ResourceLocation geoId = ModelIdUtil.getSubModelId(modelId, entry.getKey());
            parseGeoToBundle(bundle, geoId, entry.getValue());
            String entityType = projectileEntityType(entry.getKey());
            bundle.projectileModelIds.computeIfAbsent(modelId, k -> new ArrayList<>()).add(entityType);
        }

        // HEAVY: Parse animation files on background thread
        try {
            parseAnimationsToBundle(bundle, modelId, data);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to parse animations for model {}: {}", modelId, e.getMessage());
        }

        // Store texture data for main-thread upload
        for (Map.Entry<String, byte[]> e : mainTexMap.entrySet()) {
            ResourceLocation texId = ModelIdUtil.getSubModelId(modelId, e.getKey());
            bundle.texturesToRegister.put(texId, e.getValue());
            bundle.textureIdList.add(texId);
        }
        List<ResourceLocation> projTexIdList = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : projTexMap.entrySet()) {
            ResourceLocation texId = ModelIdUtil.getSubModelId(modelId, e.getKey());
            bundle.projTexturesToRegister.put(texId, e.getValue());
            projTexIdList.add(texId);
        }
        if (!projTexIdList.isEmpty()) {
            bundle.projectileTextureIds.put(modelId, projTexIdList);
        }

        // Count stats from parsed geometries
        for (GeoModel geoModel : bundle.geoModels.values()) {
            bundle.totalBones += countTotalBones(geoModel);
            bundle.totalCubes += countTotalCubes(geoModel);
        }
        if (bundle.animationFile.animations != null) {
            bundle.totalAnims = bundle.animationFile.animations.size();
        }

        return bundle;
    }

    /**
     * Applies a pre-parsed model bundle on the main thread.
     * Only does: GeckoLib cache writes, OpenGL texture upload, map registrations.
     */
    public static void applyPreParsed(PreParsedModelBundle bundle) {
        ResourceLocation modelId = bundle.modelId;
        SYNC_CURRENT_MODEL = ModelIdUtil.getModelDisplayName(modelId);
        MolangInstructionExecutor.clearCache();
        if (Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.info("[YSMU-MODEL] applyPreParsed start: modelId={}", modelId);
        }

        // Register parsed geometries to GeckoLib cache
        Map<ResourceLocation, GeoModel> geoModels = GeckoLibCache.getInstance().getGeoModels();
        for (Map.Entry<ResourceLocation, GeoModel> entry : bundle.geoModels.entrySet()) {
            geoModels.put(entry.getKey(), entry.getValue());
        }
        // Apply scale/extra info
        SCALE_INFO.putAll(bundle.scaleInfo);
        for (Map.Entry<ResourceLocation, ExtraInfo> e : bundle.extraInfo.entrySet()) {
            EXTRA_INFO.put(ModelIdUtil.getMainId(modelId), handleExtraInfo(ModelIdUtil.getMainId(modelId), e.getValue()));
        }
        if (!bundle.extraAnimationNames.isEmpty()) {
            EXTRA_ANIMATION_NAME.putAll(bundle.extraAnimationNames);
        }

        // Register textures (OpenGL — must be main thread)
        for (Map.Entry<ResourceLocation, byte[]> e : bundle.texturesToRegister.entrySet()) {
            try {
                registerTexture(e.getKey(), e.getValue());
            } catch (Exception ex) {
                ysmu.LOG.warn("Failed to register texture {} for model {}", e.getKey(), modelId, ex);
            }
        }
        MODELS.put(modelId, bundle.textureIdList);

        // Register animations to GeckoLib cache
        if (!bundle.animationFile.animations.isEmpty()) {
            GeckoLibCache.getInstance().getAnimations().put(ModelIdUtil.getMainId(modelId), bundle.animationFile);
        }
        // Register projectile animations under their own GeoModel IDs
        if (!bundle.projAnimationFiles.isEmpty() && com.fox.ysmu.Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.info("[YSMU-MODEL] applyPreParsed: registering {} projectile animations for {}",
                bundle.projAnimationFiles.size(), modelId);
            for (Map.Entry<ResourceLocation, AnimationFile> e : bundle.projAnimationFiles.entrySet()) {
                ysmu.LOG.info("[YSMU-MODEL]   proj anim: id={}, animCount={}",
                    e.getKey(), e.getValue().animations != null ? e.getValue().animations.size() : 0);
            }
        }
        for (Map.Entry<ResourceLocation, AnimationFile> e : bundle.projAnimationFiles.entrySet()) {
            if (!e.getValue().animations.isEmpty()) {
                GeckoLibCache.getInstance().getAnimations().put(e.getKey(), e.getValue());
            }
        }
        // Register projectile controllers under their own animation IDs
        if (!bundle.projControllerFiles.isEmpty() && com.fox.ysmu.Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.info("[YSMU-MODEL] applyPreParsed: registering {} projectile controllers for {}",
                bundle.projControllerFiles.size(), modelId);
            for (Map.Entry<ResourceLocation, byte[]> e : bundle.projControllerFiles.entrySet()) {
                ysmu.LOG.info("[YSMU-MODEL]   proj controller: id={}, bytes={}",
                    e.getKey(), e.getValue() != null ? e.getValue().length : 0);
            }
        }
        for (Map.Entry<ResourceLocation, byte[]> e : bundle.projControllerFiles.entrySet()) {
            OpenYsmAnimationControllerRegistry.register(e.getKey(), java.util.Collections.singleton(e.getValue()));
        }
        // Apply molang mappings
        if (!bundle.molangMapping.isEmpty()) {
            AnimationManager.MOLANG_STATE_MAP.put(ModelIdUtil.getMainId(modelId), bundle.molangMapping);
        }
        if (!bundle.molangConditional.isEmpty()) {
            AnimationManager.MOLANG_CONDITIONAL_MAP.put(ModelIdUtil.getMainId(modelId), bundle.molangConditional);
        }
        // Register controller files
        if (!bundle.controllerFiles.isEmpty()) {
            OpenYsmAnimationControllerRegistry.register(ModelIdUtil.getMainId(modelId), bundle.controllerFiles.values());
        }
        // Register animation conditions (must be on main thread with GeckoLib state)
        ResourceLocation mainId = ModelIdUtil.getMainId(modelId);
        if (bundle.animationFile.animations != null) {
            for (Map.Entry<String, software.bernie.geckolib3.core.builder.Animation> animEntry : bundle.animationFile.animations.entrySet()) {
                try {
                    com.fox.ysmu.client.animation.condition.ConditionManager.addTest(mainId, animEntry.getKey());
                } catch (Exception ex) {
                    ysmu.LOG.warn("Failed to register animation condition {} for model {}", animEntry.getKey(), modelId, ex);
                }
            }
        }

        // Register projectile textures (OpenGL — must be main thread)
        for (Map.Entry<ResourceLocation, byte[]> e : bundle.projTexturesToRegister.entrySet()) {
            try {
                registerTexture(e.getKey(), e.getValue());
            } catch (Exception ex) {
                ysmu.LOG.warn("Failed to register projectile texture {} for model {}", e.getKey(), modelId, ex);
            }
        }
        if (!bundle.projectileModelIds.isEmpty()) {
            PROJECTILE_MODEL_IDS.putAll(bundle.projectileModelIds);
        }
        if (!bundle.projectileTextureIds.isEmpty()) {
            PROJECTILE_TEXTURE_IDS.putAll(bundle.projectileTextureIds);
        }

        // Log and update progress
        int texCount = bundle.textureIdList.size();
        ysmu.LOG.info(
            "YSM client registered model {}: totalModelEntries={}, textureCount={}, projectileModels={}",
            modelId, geoModels.size(), texCount,
            PROJECTILE_MODEL_IDS.containsKey(modelId) ? PROJECTILE_MODEL_IDS.get(modelId).size() : 0);

        MODEL_STATS.put(ModelIdUtil.getMainId(modelId),
            new int[]{bundle.totalBones, bundle.totalCubes * 6, bundle.totalAnims});
        SYNC_LOADED++;
        if (Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.info("[YSMU-MODEL] applyPreParsed done: modelId={}, textures={}, bones={}, faces={}, anims={}",
                modelId, texCount, bundle.totalBones, bundle.totalCubes * 6, bundle.totalAnims);
        }
        detectModelPacks();
    }

    /**
     * Legacy entry point — parses model then applies on the calling thread.
     * Callers that are already on a background thread should use
     * {@link #preParseModel(ModelData)} + {@link #applyPreParsed(PreParsedModelBundle)} instead.
     */
    public static void registerAll(ModelData data) {
        try {
            PreParsedModelBundle bundle = preParseModel(data);
            applyPreParsed(bundle);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to register model: {}", e.getMessage());
        }
    }

    /**
     * 扫描 MODELS 中所有模型ID，根据其显示名称中的 '/' 分隔符检测模型包分组，
     * 填充 MODEL_PACKS 和 MODEL_PACK_OF。
     * 从 CLIENT_PACKS 获取包的显示名称。
     * 每次模型注册完成后应调用一次。
     */
    public static void detectModelPacks() {
        MODEL_PACKS.clear();
        MODEL_PACK_OF.clear();
        // First, collect model→pack mapping by scanning for '/' in display name
        for (ResourceLocation modelId : MODELS.keySet()) {
            String display = ModelIdUtil.getModelDisplayName(modelId);
            int slash = display.indexOf('/');
            if (slash <= 0) {
                MODEL_PACK_OF.put(modelId, null);
                continue;
            }
            String packFolder = display.substring(0, slash);
            MODEL_PACKS.computeIfAbsent(packFolder, k -> new ArrayList<>()).add(modelId);
            MODEL_PACK_OF.put(modelId, packFolder);
        }
        // Rename pack keys to use CLIENT_PACKS display names if available
        if (!CLIENT_PACKS.isEmpty()) {
            java.util.Map<String, java.util.List<ResourceLocation>> renamed = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, java.util.List<ResourceLocation>> entry : MODEL_PACKS.entrySet()) {
                ClientPackData cpd = CLIENT_PACKS.get(entry.getKey());
                String displayName = cpd != null ? cpd.getDisplayName() : entry.getKey();
                renamed.put(displayName, entry.getValue());
                // Update MODEL_PACK_OF for models in this pack
                for (ResourceLocation id : entry.getValue()) {
                    MODEL_PACK_OF.put(id, displayName);
                }
            }
            MODEL_PACKS.clear();
            MODEL_PACKS.putAll(renamed);
        }
        ysmu.LOG.info("YSM client detected {} model packs from {} models: {}",
            MODEL_PACKS.size(), MODELS.size(), MODEL_PACKS.keySet());
    }

    private static ResourceLocation getModelId(ModelData data) {
        return new ResourceLocation(ysmu.MODID, data.getModelId());
    }

    /**
     * Public convenience: parses geometry bytes and immediately applies to GeckoLib cache.
     * Used by {@link com.fox.ysmu.client.sync.OpenYsmModelSyncClient#registerProjectilesFromRaw}.
     * Call from a background thread; the main-thread part (texture/GL) is handled separately.
     */
    public static void registerGeo(ResourceLocation geoId, byte[] data) {
        PreParsedModelBundle bundle = new PreParsedModelBundle(ModelIdUtil.getMainId(geoId));
        parseGeoToBundle(bundle, geoId, data);
        GeoModel parsed = bundle.geoModels.get(geoId);
        if (parsed != null) {
            GeckoLibCache.getInstance().getGeoModels().put(geoId, parsed);
        }
        for (Map.Entry<ResourceLocation, it.unimi.dsi.fastutil.Pair<Double, Double>> e : bundle.scaleInfo.entrySet()) {
            SCALE_INFO.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<ResourceLocation, ExtraInfo> e : bundle.extraInfo.entrySet()) {
            EXTRA_INFO.put(e.getKey(), handleExtraInfo(e.getKey(), e.getValue()));
        }
        if (!bundle.extraAnimationNames.isEmpty()) {
            EXTRA_ANIMATION_NAME.putAll(bundle.extraAnimationNames);
        }
    }

    /**
     * Parses a single geometry from raw bytes into a GeoModel on the background thread.
     * Stores results into the bundle (does NOT touch GeckoLib caches or OpenGL).
     */
    private static void parseGeoToBundle(PreParsedModelBundle bundle, ResourceLocation geoId, byte[] data) {
        try {
            String modelJson = new String(data, StandardCharsets.UTF_8);
            RawGeoModel rawModel = Converter.fromJsonString(modelJson);

            if (rawModel.getFormatVersion() == FormatVersion.VERSION_1_12_0
                || rawModel.getFormatVersion() == FormatVersion.VERSION_1_14_0
                || rawModel.getFormatVersion() == FormatVersion.VERSION_1_21_0) {
                RawGeometryTree rawGeometryTree = RawGeometryTree.parseHierarchy(rawModel);
                GeoModel geoModel = GeoBuilder.getGeoBuilder(geoId.getResourceDomain())
                    .constructGeoModel(rawGeometryTree);

                // Check for empty overwrites against existing geoModels in the cache
                GeoModel existing = GeckoLibCache.getInstance().getGeoModels().get(geoId);
                int existingCubeCount = 0;
                if (existing != null && existing.topLevelBones != null) {
                    existingCubeCount = existing.topLevelBones.stream()
                        .mapToInt(b -> countChildCubesRecursive(b)).sum();
                }
                int newCubeCount = geoModel.topLevelBones.stream()
                    .mapToInt(b -> countChildCubesRecursive(b)).sum();
                if (existing != null && existingCubeCount > 0 && newCubeCount == 0) {
                    ysmu.LOG.warn("Skipping overwrite of {} (existing has {} cubes, new has 0)",
                        geoId, existingCubeCount);
                    return; // keep existing
                }

                bundle.geoModels.put(geoId, geoModel);
                bundle.scaleInfo.put(geoId,
                    it.unimi.dsi.fastutil.Pair.of(
                        rawGeometryTree.properties.getHeightScale(),
                        rawGeometryTree.properties.getWidthScale()));
                ExtraInfo extra = rawGeometryTree.properties.getExtraInfo();
                if (extra != null) {
                    bundle.extraInfo.put(geoId, extra);
                }
                if (extra != null && extra.getExtraAnimationNames() != null
                    && extra.getExtraAnimationNames().length > 0) {
                    bundle.extraAnimationNames.put(geoId, extra.getExtraAnimationNames());
                }
            } else {
                ysmu.LOG.warn("YSM geometry {} has unsupported format version: {}", geoId, rawModel.getFormatVersion());
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to parse geometry " + geoId, e);
        }
    }

    /**
     * Parses animation files from ModelData on the background thread.
     * Stores AnimationFile, molang mappings, and controller files into the bundle.
     */
    private static void parseAnimationsToBundle(PreParsedModelBundle bundle, ResourceLocation modelId, ModelData data) {
        ResourceLocation mainId = ModelIdUtil.getMainId(modelId);
        Map<String, byte[]> mapData = data.getAnimation();
        if (mapData == null || mapData.isEmpty()) return;

        AnimationFile main = new AnimationFile();
        for (Map.Entry<String, byte[]> entry : mapData.entrySet()) {
            String key = entry.getKey();
            byte[] animData = entry.getValue();

            if (YsmControllerResources.isMolangResource(key)) {
                Map<String, String> parsed = com.fox.ysmu.client.animation.molang.MolangFunctionParser.parseStateToAnimationMap(animData);
                if (!parsed.isEmpty()) {
                    bundle.molangMapping.putAll(parsed);
                }
                Map<String, List<org.apache.commons.lang3.tuple.Pair<String, String>>> condParsed =
                    com.fox.ysmu.client.animation.molang.MolangFunctionParser.parseConditionalAnimations(animData);
                for (Map.Entry<String, List<org.apache.commons.lang3.tuple.Pair<String, String>>> ce : condParsed.entrySet()) {
                    bundle.molangConditional.merge(ce.getKey(), ce.getValue(), (a, b) -> { a.addAll(b); return a; });
                }
                continue;
            }
            // Projectile controller keys: registered under the projectile's own animation ID
            if (key.startsWith("projectile_ctrl_")) {
                String projKey = key.substring("projectile_ctrl_".length());
                ResourceLocation projAnimId = ModelIdUtil.getSubModelId(modelId, "projectile_" + projKey);
                bundle.projControllerFiles.put(projAnimId, animData);
                continue;
            }
            // Projectile animation keys: parsed as AnimationFile and registered under projectile GeoModel ID
            if (key.startsWith(PROJECTILE_KEY_PREFIX)) {
                try {
                    AnimationFile projAnim = getAnimationFile(new String(animData, StandardCharsets.UTF_8));
                    ResourceLocation projAnimId = ModelIdUtil.getSubModelId(modelId, key);
                    bundle.projAnimationFiles.put(projAnimId, projAnim);
                } catch (Exception e) {
                    ysmu.LOG.warn("Failed to parse projectile animation {} for model {}: {}",
                        key, modelId, e.getMessage());
                }
                continue;
            }
            if (isControllerResource(key, animData)) {
                bundle.controllerFiles.put(key, animData);
                continue;
            }
            try {
                AnimationFile other = getAnimationFile(new String(animData, StandardCharsets.UTF_8));
                mergeAnimationFile(main, other);
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to parse animation file {} for model {}: {}: {}",
                    key, modelId, e.getClass().getSimpleName(),
                    org.apache.commons.lang3.StringUtils.defaultString(e.getMessage()));
            }
        }
        bundle.animationFile = main;
    }

    public static void registerTexture(ResourceLocation id, byte[] data) {
        int newHash = java.util.Arrays.hashCode(data);
        Integer oldHash = TEXTURE_CONTENT_HASH.get(id);
        if (oldHash != null && oldHash == newHash) {
            return; // Texture unchanged — existing OpenGL texture ID is still valid
        }
        // Diagnostic: validate texture data before registration
        if (data == null) {
            ysmu.LOG.warn("[YSMU-TEX] registerTexture({}): data is NULL, skipping!", id);
            return;
        }
        if (data.length == 0) {
            ysmu.LOG.warn("[YSMU-TEX] registerTexture({}): data is EMPTY (0 bytes), skipping!", id);
            return;
        }
        if (Config.DEBUG_MODEL_LOAD) {
            String magic = data.length >= 4
                ? String.format("%02X%02X%02X%02X", data[0], data[1], data[2], data[3])
                : "too-short";
            ysmu.LOG.info("[YSMU-TEX] registerTexture({}): {} bytes, magic={}", id, data.length, magic);
        }
        OuterFileTexture outerTex = new OuterFileTexture(data);
        try {
            Minecraft.getMinecraft()
                .getTextureManager()
                .loadTexture(id, outerTex);
            if (Config.DEBUG_MODEL_LOAD) {
                ysmu.LOG.info("[YSMU-TEX] registerTexture({}): TextureManager.loadTexture OK", id);
            }
        } catch (Exception e) {
            ysmu.LOG.warn("[YSMU-TEX] registerTexture({}): TextureManager.loadTexture threw:", id, e);
        }
        // Post-registration verification (only when DEBUG_MODEL_LOAD enabled)
        if (Config.DEBUG_MODEL_LOAD) {
            try {
                Minecraft.getMinecraft().getTextureManager().bindTexture(id);
                ysmu.LOG.info("[YSMU-TEX] registerTexture({}): post-bind verification SUCCEEDED", id);
            } catch (Exception e) {
                ysmu.LOG.warn("[YSMU-TEX] registerTexture({}): post-bind verification FAILED — texture NOT in TextureManager map!", id, e);
            }
        }
        TEXTURE_CONTENT_HASH.put(id, newHash);
    }

    private static boolean isControllerResource(String name, byte[] data) {
        if (YsmControllerResources.isControllerResource(name)) {
            return true;
        }
        if (data == null || data.length == 0) {
            return false;
        }
        try {
            JsonObject jsonObject = GsonHelper.fromJson(
                ysmu.GSON,
                new String(data, StandardCharsets.UTF_8),
                JsonObject.class);
            return jsonObject != null && jsonObject.has("animation_controllers");
        } catch (Exception e) {
            return false;
        }
    }

    private static AnimationFile getAnimationFile(String file) {
        AnimationFile animationFile = new AnimationFile();
        try {
            MolangParser parser = GeckoLibCache.getInstance().parser;
            JsonObject jsonObject = GsonHelper.fromJson(ysmu.GSON, file, JsonObject.class);
            if (jsonObject != null) {
                for (Map.Entry<String, JsonElement> entry : JsonAnimationUtils.getAnimations(jsonObject)) {
                    String animationName = entry.getKey();
                    Animation animation;
                    try {
                        animation = JsonAnimationUtils
                            .deserializeJsonToAnimation(JsonAnimationUtils.getAnimation(jsonObject, animationName), parser);
                        animationFile.putAnimation(animationName, animation);
                    } catch (Exception e) {
                        ysmu.LOG.warn(
                            "Failed to register animation {}: {}: {}",
                            animationName,
                            e.getClass().getSimpleName(),
                            StringUtils.defaultString(e.getMessage()));
                    }
                }
            }
        } catch (Exception e) {
            ysmu.LOG.warn(
                "Failed to parse animation file: {}: {}",
                e.getClass().getSimpleName(),
                StringUtils.defaultString(e.getMessage()));
        }
        return animationFile;
    }

    private static AnimationFile mergeAnimationFile(AnimationFile main, AnimationFile other) {
        for (java.util.Map.Entry<String, Animation> entry : other.animations.entrySet()) {
            String name = entry.getKey();
            Animation incoming = entry.getValue();
            Animation existing = main.animations.get(name);
            // If we already have a non-empty animation for this name, only overwrite
            // if the incoming animation also has bone keyframes.  This prevents
            // arm.animation.json (which may have empty stubs like "swing_hand" with
            // no bones) from overwriting the real animation in main.animation.json.
            if (existing != null && existing.boneAnimations != null && !existing.boneAnimations.isEmpty()
                && (incoming.boneAnimations == null || incoming.boneAnimations.isEmpty())) {
                continue;
            }
            main.putAnimation(name, incoming);
        }
        return main;
    }

    public static void loadDefaultModel() {
        // Try old format (FolderFormat) first, then fallback to new OpenYSM format.
        ModelData data = null;
        try {
            data = FolderFormat.getModelData(ServerModelManager.CUSTOM, "default");
        } catch (IOException e) {
            ysmu.LOG.info("Legacy default model not found at CUSTOM/default, trying OpenYSM format...");
        }
        if (data == null) {
            // Fallback: new OpenYSM format at BUILT/default/
            Path builtinDefault = ServerModelManager.BUILT.resolve("default");
            if (Files.isDirectory(builtinDefault)) {
                try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(builtinDefault)) {
                    RawYsmModel raw = deserializer.deserialize();
                    raw.modelId = "default";
                    if (RawYsmModelAdapter.isBridgeable(raw)) {
                        data = RawYsmModelAdapter.toLegacyModelData(raw, "default");
                    }
                } catch (Exception e2) {
                    ysmu.LOG.warn("Failed to load default model from OpenYSM format", e2);
                }
            }
        }
        if (data != null) {
            data.getAnimation()
                .forEach((name, bytes) -> {
                    AnimationFile animationFile = getAnimationFile(new String(bytes, StandardCharsets.UTF_8));
                    mergeAnimationFile(DEFAULT_ANIMATION_FILE, animationFile);
                });
            ClientModelManager.registerAll(data);
        } else {
            ysmu.LOG.warn("Failed to load default model from any format");
        }
    }

    /** Client-side pack metadata, parsed from the sync protocol. */
    public static final class ClientPackData {
        public final String folderPath;
        public final String name;
        public final String description;
        public final byte[] iconData;
        public final int iconWidth;
        public final int iconHeight;
        public final int iconFormat;
        public final java.util.Map<String, java.util.Map<String, String>> lang;

        public ClientPackData(String folderPath, String name, String description,
            byte[] iconData, int iconWidth, int iconHeight, int iconFormat,
            java.util.Map<String, java.util.Map<String, String>> lang) {
            this.folderPath = folderPath;
            this.name = name;
            this.description = description;
            this.iconData = iconData;
            this.iconWidth = iconWidth;
            this.iconHeight = iconHeight;
            this.iconFormat = iconFormat;
            this.lang = lang;
        }

        /** Returns the localized pack name, falling back to en_us, zh_cn, raw name, then folder path. */
        public String getDisplayName() {
            if (lang != null) {
                String mcLang = net.minecraft.client.Minecraft.getMinecraft().getLanguageManager().getCurrentLanguage().getLanguageCode();
                java.util.Map<String, String> trans = lang.get(mcLang);
                if (trans != null && trans.containsKey("name")) return trans.get("name");
                // Fallback: en_us, then zh_cn, then raw name
                trans = lang.get("en_us");
                if (trans != null && trans.containsKey("name")) return trans.get("name");
                trans = lang.get("zh_cn");
                if (trans != null && trans.containsKey("name")) return trans.get("name");
            }
            return name != null && !name.isEmpty() ? name : folderPath;
        }
    }

    public static void sendSyncModelMessage() {
        ysmu.LOG.info(
            "YSM client starting model sync: currentModels={}, rememberedCachedModels={}",
            MODELS.size(),
            CACHE_MD5.size());
        PASSWORD = null;
        PASSWORD_UUID = null;
        clearCachedModelMd5();
        SYNC_TOTAL = -1;
        SYNC_LOADED = 0;
        SYNC_IN_PROGRESS = true;
        Minecraft.getMinecraft()
            .func_152344_a(ClientModelManager::clearRuntimeModelCaches);
        String[] md5Info = getMd5Info();
        ysmu.LOG.info("YSM client sending model sync md5 list: count={}, values={}", md5Info.length, Lists.newArrayList(md5Info));
        SyncModelFiles syncModelFiles = new SyncModelFiles(md5Info);
        ThreadTools.THREAD_POOL.submit(() -> {
            while (Minecraft.getMinecraft().theWorld == null) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            NetworkHandler.CHANNEL.sendToServer(syncModelFiles);
        });
    }

    private static String[] getMd5Info() {
        File cacheDir = ServerModelManager.CACHE_CLIENT.toFile();
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            ysmu.LOG.warn("Failed to create YSM client model cache directory: {}", cacheDir);
            return new String[0];
        }
        Collection<File> files = FileUtils.listFiles(cacheDir, FileFileFilter.FILE, null);
        String[] output = new String[files.size()];
        int i = 0;
        for (File file : files) {
            output[i] = file.getName();
            i++;
        }
        return output;
    }

    private static void clearRuntimeModelCaches() {
        ysmu.LOG.info(
            "YSM client clearing runtime model caches: models={}, scales={}, extraInfo={}, extraAnimations={}, modelStats={}, previewAnimations={}, molangStateMaps={}, molangConditionalMaps={}, previewBoneCache={}, geoModels={}, animations={}",
            MODELS.size(),
            SCALE_INFO.size(),
            EXTRA_INFO.size(),
            EXTRA_ANIMATION_NAME.size(),
            MODEL_STATS.size(),
            PREVIEW_ANIMATION.size(),
            com.fox.ysmu.client.animation.AnimationManager.MOLANG_STATE_MAP.size(),
            com.fox.ysmu.client.animation.AnimationManager.MOLANG_CONDITIONAL_MAP.size(),
            com.fox.ysmu.client.model.CustomPlayerModel.getPreviewBoneCacheSize(),
            GeckoLibCache.getInstance().getGeoModels().size(),
            GeckoLibCache.getInstance().getAnimations().size());
        MODELS.clear();
        SCALE_INFO.clear();
        EXTRA_INFO.clear();
        EXTRA_ANIMATION_NAME.clear();
        EXTRA_WHEEL.clear();
        MODEL_PACKS.clear();
        MODEL_PACK_OF.clear();
        CLIENT_PACKS.clear();
        MODEL_DISPLAY_NAMES.clear();
        PREVIEW_ANIMATION.clear();
        GUI_FOREGROUND_IMAGE.clear();
        GUI_BACKGROUND_IMAGE.clear();
        DISABLE_PREVIEW_ROTATION.clear();
        GUI_NO_LIGHTING.clear();
        MODEL_STATS.clear();
        GeckoLibCache.getInstance().getGeoModels().clear();
        GeckoLibCache.getInstance().getAnimations().clear();
        com.fox.ysmu.client.animation.AnimationManager.MOLANG_STATE_MAP.clear();
        com.fox.ysmu.client.animation.AnimationManager.MOLANG_CONDITIONAL_MAP.clear();
        com.fox.ysmu.client.model.CustomPlayerModel.clearPreviewBoneCache();
        com.fox.ysmu.client.audio.YSMSoundManager.clear();
        ConditionManager.clear();
        OpenYsmAnimationControllerRegistry.clear();
        com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime.clearModelRoamingVars();
        MolangPhysicsRuntime.clear();
        MolangInstructionExecutor.clearWarnings();
        SYNC_TOTAL = -1;
        SYNC_LOADED = 0;
        SYNC_IN_PROGRESS = false;
        SYNC_CURRENT_MODEL = "";
        ysmu.LOG.info("YSM client runtime model caches cleared");
    }

    /** Registers rich extra wheel data and GUI image textures from a RawYsmModel. */
    public static void registerExtraWheel(ResourceLocation modelId, com.fox.ysmu.model.resource.pojo.RawYsmModel raw) {
        EXTRA_WHEEL.put(modelId, ExtraWheelData.from(raw));
        // Initialize roaming variables for range sliders with sensible defaults.
        for (RawYsmModel.ExtraAnimationButton btn : raw.properties.extraAnimationButtons) {
            for (RawYsmModel.ConfigForm form : btn.forms) {
                // Extract variable name from defaultValue: "v.roaming.X" or "v.X=0"
                // → strip "v." prefix and "=value" suffix to get the bare key.
                String rawDefault = form.defaultValue;
                if (StringUtils.isBlank(rawDefault)) continue;
                String varName = rawDefault;
                if (varName.startsWith("v.")) varName = varName.substring(2);
                int eqIdx = varName.indexOf('=');
                if (eqIdx >= 0) varName = varName.substring(0, eqIdx);
                if (StringUtils.isBlank(varName)) continue;
                com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime
                    .registerModelRoamingVar(modelId, varName);
                if (!com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime
                    .PENDING_ROAMING.containsKey(varName)) {
                    double initVal;
                    if ("range".equals(form.type) && form.min < form.max) {
                        // For range sliders, pick a sensible default.
                        if (form.min <= 0.0f && 0.0f <= form.max) {
                            initVal = 0.0;
                        } else if (form.min <= 1.0f && 1.0f <= form.max) {
                            initVal = 1.0;
                        } else {
                            initVal = form.min;
                        }
                        if (form.step > 0) initVal = Math.round(initVal / form.step) * form.step;
                    } else {
                        // Checkbox/radio: default to 0 (off).
                        initVal = 0.0;
                    }
                    com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime
                        .PENDING_ROAMING.put(varName, initVal);
                }
            }
        }
        if (StringUtils.isNotBlank(raw.properties.previewAnimation)) {
            PREVIEW_ANIMATION.put(modelId, raw.properties.previewAnimation);
        }
        // Resolve display name: lang file (当前语言) > ysm.json metadata > empty
        String displayName = null;
        if (raw.languageFiles != null && !raw.languageFiles.isEmpty()) {
            try {
                String mcLang = net.minecraft.client.Minecraft.getMinecraft()
                    .getLanguageManager().getCurrentLanguage().getLanguageCode();
                RawYsmModel.RawLanguageFile langFile = raw.languageFiles.get(mcLang);
                if (langFile != null && langFile.data != null && langFile.data.containsKey("metadata.name")) {
                    displayName = langFile.data.get("metadata.name");
                }
            } catch (Exception ignored) {}
        }
        if (displayName == null && raw.metadata != null && StringUtils.isNotBlank(raw.metadata.name)) {
            displayName = raw.metadata.name;
        }
        if (displayName != null) {
            MODEL_DISPLAY_NAMES.put(modelId, displayName);
        }
        // Register model sound files
        com.fox.ysmu.client.audio.YSMSoundManager.registerModelSounds(modelId, raw);
        // Store GUI foreground/background images
        for (com.fox.ysmu.model.resource.pojo.RawYsmModel.RawImage img : raw.properties.backgroundImages) {
            if ("gui_foreground".equals(img.name)) {
                GUI_FOREGROUND_IMAGE.put(modelId, img);
            } else if ("gui_background".equals(img.name)) {
                GUI_BACKGROUND_IMAGE.put(modelId, img);
            }
        }
        // Store disablePreviewRotation flag
        DISABLE_PREVIEW_ROTATION.put(modelId, raw.properties.disablePreviewRotation);
        // Store gui_no_lighting flag
        GUI_NO_LIGHTING.put(modelId, raw.properties.guiNoLighting);
    }

    // ── Model stats helpers ───────────────────────────────────────

    /** Counts all bones (including children) in a GeoModel. */
    public static int countTotalBones(GeoModel model) {
        int count = 0;
        for (GeoBone bone : model.topLevelBones) {
            count += countBoneTree(bone);
        }
        return count;
    }

    private static int countBoneTree(GeoBone bone) {
        int count = 1;
        for (GeoBone child : bone.childBones) {
            count += countBoneTree(child);
        }
        return count;
    }

    /** Counts all cubes (including children) in a GeoModel. */
    public static int countTotalCubes(GeoModel model) {
        int count = 0;
        for (GeoBone bone : model.topLevelBones) {
            count += countCubesInBone(bone);
        }
        return count;
    }

    private static int countCubesInBone(GeoBone bone) {
        int count = bone.childCubes.size();
        for (GeoBone child : bone.childBones) {
            count += countCubesInBone(child);
        }
        return count;
    }

    public static void rememberCachedModel(String md5) {
        synchronized (CACHE_MD5) {
            if (!CACHE_MD5.contains(md5)) {
                CACHE_MD5.add(md5);
            }
        }
    }

    public static List<String> getCachedModelSnapshot() {
        synchronized (CACHE_MD5) {
            return new ArrayList<>(CACHE_MD5);
        }
    }

    public static void clearConnectionState() {
        PASSWORD = null;
        PASSWORD_UUID = null;
        clearCachedModelMd5();
        OpenYsmModelSyncClient.clearConnectionState();
    }

    private static void clearCachedModelMd5() {
        synchronized (CACHE_MD5) {
            CACHE_MD5.clear();
        }
    }

    private static byte[] getBytes(Path root, String fileName) throws IOException {
        return FileUtils.readFileToByteArray(
            root.resolve(fileName)
                .toFile());
    }

    @Nullable
    private static List<IChatComponent> handleExtraInfo(ResourceLocation id, @Nullable ExtraInfo extraInfo) {
        if (extraInfo == null || StringUtils.isBlank(extraInfo.getName())) {
            return null;
        }
        List<IChatComponent> component = Lists.newArrayList();
        IChatComponent textComponent = new ChatComponentText(extraInfo.getName());
        textComponent.getChatStyle()
            .setColor(EnumChatFormatting.GOLD);
        component.add(textComponent);
        if (StringUtils.isNoneBlank(extraInfo.getTips())) {
            String[] split = autoWrapText(extraInfo.getTips(), 60).split("\n");
            for (String s : split) {
                IChatComponent lineComponent = new ChatComponentText(s);
                lineComponent.getChatStyle()
                    .setColor(EnumChatFormatting.GRAY);
                component.add(lineComponent);
            }
        }
        if (extraInfo.getAuthors() != null && extraInfo.getAuthors().length != 0) {
            // Single header line, then one line per author (replacing old "|" join)
            component.add(new ChatComponentTranslation("gui.yes_steve_model.model.authors", ""));
            for (String author : extraInfo.getAuthors()) {
                IChatComponent authorLine = new ChatComponentText("  " + author);
                authorLine.getChatStyle().setColor(EnumChatFormatting.GRAY);
                component.add(authorLine);
            }
        }
        if (StringUtils.isNoneBlank(extraInfo.getLicense())) {
            component.add(new ChatComponentTranslation("gui.yes_steve_model.model.license", extraInfo.getLicense()));
        }
        return component;
    }

    /**
     * Auto-wraps long text that has no line breaks by inserting \n at word
     * boundaries. CJK characters count as 2 cells wide, ASCII as 1.
     * Pre-existing \n are preserved; only segments exceeding maxWidthCells
     * get wrapped.
     */
    private static String autoWrapText(String text, int maxWidthCells) {
        if (StringUtils.isBlank(text)) return text;
        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            if (li > 0) result.append('\n');
            String line = lines[li];
            if (line.isEmpty()) continue;
            // Measure visual width (CJK=2, ASCII=1)
            int width = 0;
            int lastBreak = 0;
            int lastSpace = -1;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                int charWidth = isCJK(c) ? 2 : 1;
                // Track last space for clean word breaks
                if (c == ' ' || c == '\t') {
                    lastSpace = i;
                }
                if (width + charWidth > maxWidthCells && i > lastBreak) {
                    int breakAt;
                    if (lastSpace > lastBreak) {
                        breakAt = lastSpace;
                    } else {
                        breakAt = i;
                    }
                    result.append(line, lastBreak, breakAt);
                    result.append('\n');
                    // Skip the space itself if breaking at it
                    lastBreak = breakAt + (lastSpace > lastBreak && line.charAt(breakAt) == ' ' ? 1 : 0);
                    width = 0;
                    lastSpace = -1;
                    // Re-measure from the break point
                    for (int j = lastBreak; j <= i && j < line.length(); j++) {
                        width += isCJK(line.charAt(j)) ? 2 : 1;
                    }
                } else {
                    width += charWidth;
                }
            }
            if (lastBreak < line.length()) {
                result.append(line, lastBreak, line.length());
            }
        }
        return result.toString();
    }

    private static boolean isCJK(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
            || block == Character.UnicodeBlock.HIRAGANA
            || block == Character.UnicodeBlock.KATAKANA
            || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
            || block == Character.UnicodeBlock.HANGUL_SYLLABLES
            || block == Character.UnicodeBlock.HANGUL_JAMO
            || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
            || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    // ========== DIAGNOSTIC helpers ==========

    private static int countChildCubesRecursive(software.bernie.geckolib3.geo.render.built.GeoBone bone) {
        int count = bone.childCubes != null ? bone.childCubes.size() : 0;
        if (bone.childBones != null) {
            for (software.bernie.geckolib3.geo.render.built.GeoBone child : bone.childBones) {
                count += countChildCubesRecursive(child);
            }
        }
        return count;
    }


}
