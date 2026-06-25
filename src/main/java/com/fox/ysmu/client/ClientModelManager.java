package com.fox.ysmu.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.client.animation.AnimationManager;
import com.fox.ysmu.client.animation.condition.ConditionManager;
import com.fox.ysmu.client.animation.controller.OpenYsmAnimationControllerRegistry;
import com.fox.ysmu.client.animation.molang.MolangFunctionParser;
import com.fox.ysmu.client.animation.molang.MolangInstructionExecutor;
import com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime;
import com.fox.ysmu.client.sync.OpenYsmModelSyncClient;
import com.fox.ysmu.client.texture.OuterFileTexture;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.model.format.FolderFormat;
import com.fox.ysmu.model.resource.YsmControllerResources;
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
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;
import software.bernie.geckolib3.util.json.JsonAnimationUtils;

public class ClientModelManager {

    public static Map<ResourceLocation, List<ResourceLocation>> MODELS = Maps.newHashMap();
    public static Map<ResourceLocation, Pair<Double, Double>> SCALE_INFO = Maps.newHashMap();
    public static Map<ResourceLocation, List<IChatComponent>> EXTRA_INFO = Maps.newHashMap();
    public static Map<ResourceLocation, String[]> EXTRA_ANIMATION_NAME = Maps.newHashMap();
    /** Rich wheel data (YSM 2.3.0+ sub-pages). */
    public static Map<ResourceLocation, ExtraWheelData> EXTRA_WHEEL = Maps.newHashMap();
    /** Preview animation name per model, read from ysm.json preview_animation field. */
    public static Map<ResourceLocation, String> PREVIEW_ANIMATION = Maps.newHashMap();

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

    public static AnimationFile DEFAULT_ANIMATION_FILE = new AnimationFile();
    public static List<String> CACHE_MD5 = Collections.synchronizedList(Lists.newArrayList());
    public static volatile byte[] PASSWORD;
    public static volatile UUID PASSWORD_UUID;

    public static void registerAll(ModelData data) {
        ResourceLocation modelId = getModelId(data);
        ysmu.LOG.info(
            "YSM client registering model {}: geometry={}, textures={}, animations={}",
            modelId,
            data.getModel().keySet(),
            data.getTexture().keySet(),
            data.getAnimation().keySet());
        registerGeometry(modelId, data);
        registerModelTextures(modelId, data);
        try {
            registerModelAnimations(modelId, data);
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to register animations for model {}", modelId, e);
        }
        ysmu.LOG.info(
            "YSM client registered model {}: totalModelEntries={}, textureCount={}",
            modelId,
            MODELS.size(),
            MODELS.get(modelId) == null ? 0 : MODELS.get(modelId).size());
        detectModelPacks();
    }

    /**
     * 扫描 MODELS 中所有模型ID，根据其显示名称中的 '/' 分隔符检测模型包分组，
     * 填充 MODEL_PACKS 和 MODEL_PACK_OF。
     * 每次模型注册完成后应调用一次。
     */
    public static void detectModelPacks() {
        MODEL_PACKS.clear();
        MODEL_PACK_OF.clear();
        for (ResourceLocation modelId : MODELS.keySet()) {
            String display = ModelIdUtil.getModelDisplayName(modelId);
            int slash = display.indexOf('/');
            if (slash <= 0) {
                MODEL_PACK_OF.put(modelId, null);
                continue;
            }
            String packName = display.substring(0, slash);
            MODEL_PACKS.computeIfAbsent(packName, k -> new ArrayList<>()).add(modelId);
            MODEL_PACK_OF.put(modelId, packName);
        }
        ysmu.LOG.info("YSM client detected {} model packs from {} models: {}",
            MODEL_PACKS.size(), MODELS.size(), MODEL_PACKS.keySet());
    }

    private static ResourceLocation getModelId(ModelData data) {
        return new ResourceLocation(ysmu.MODID, data.getModelId());
    }

    private static void registerGeometry(ResourceLocation modelId, ModelData data) {
        registerGeo(modelId, data.getModel());
    }

    private static void registerModelAnimations(ResourceLocation modelId, ModelData data) {
        registerAnimations(ModelIdUtil.getMainId(modelId), data.getAnimation());
    }

    private static void registerModelTextures(ResourceLocation modelId, ModelData data) {
        registerTexture(modelId, data.getTexture());
    }

    public static void registerGeo(ResourceLocation id, Map<String, byte[]> mapData) {
        for (String name : mapData.keySet()) {
            byte[] data = mapData.get(name);
            registerGeo(ModelIdUtil.getSubModelId(id, name), data);
        }
    }

    private static void registerGeo(ResourceLocation id, byte[] data) {
        Map<ResourceLocation, GeoModel> geoModels = GeckoLibCache.getInstance()
            .getGeoModels();
        try {
            // 直接从字节数组解析JSON，而不是尝试反序列化对象
            String modelJson = new String(data, StandardCharsets.UTF_8);
            RawGeoModel rawModel = Converter.fromJsonString(modelJson);

            if (rawModel.getFormatVersion() == FormatVersion.VERSION_1_12_0
                || rawModel.getFormatVersion() == FormatVersion.VERSION_1_14_0
                || rawModel.getFormatVersion() == FormatVersion.VERSION_1_21_0) {
                RawGeometryTree rawGeometryTree = RawGeometryTree.parseHierarchy(rawModel);
                GeoModel geoModel = GeoBuilder.getGeoBuilder(id.getResourceDomain())
                    .constructGeoModel(rawGeometryTree);
                SCALE_INFO.put(
                    id,
                    Pair.of(rawGeometryTree.properties.getHeightScale(), rawGeometryTree.properties.getWidthScale()));
                ExtraInfo extraInfo = rawGeometryTree.properties.getExtraInfo();
                EXTRA_INFO.put(id, handleExtraInfo(id, extraInfo));
                if (extraInfo != null && extraInfo.getExtraAnimationNames() != null
                    && extraInfo.getExtraAnimationNames().length > 0) {
                    EXTRA_ANIMATION_NAME.put(id, extraInfo.getExtraAnimationNames());
                }
                geoModels.put(id, geoModel);
                ysmu.LOG.info(
                    "YSM client registered geometry {}: heightScale={}, widthScale={}, hasExtraInfo={}, extraAnimationNames={}",
                    id,
                    rawGeometryTree.properties.getHeightScale(),
                    rawGeometryTree.properties.getWidthScale(),
                    extraInfo != null,
                    extraInfo != null && extraInfo.getExtraAnimationNames() != null
                        ? extraInfo.getExtraAnimationNames().length
                        : 0);
            }
        } catch (Exception e) {
            ysmu.LOG.warn("Failed to register geometry " + id, e);
            e.printStackTrace();
        }
    }

    public static void registerTexture(ResourceLocation id, Map<String, byte[]> mapData) {
        List<ResourceLocation> textures = Lists.newArrayList();
        for (String name : mapData.keySet()) {
            ResourceLocation textureId = ModelIdUtil.getSubModelId(id, name);
            textures.add(textureId);
        }
        MODELS.put(id, textures);
        for (String name : mapData.keySet()) {
            byte[] data = mapData.get(name);
            ResourceLocation textureId = ModelIdUtil.getSubModelId(id, name);
            try {
                registerTexture(textureId, data);
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to register texture {} for model {}", textureId, id, e);
            }
        }
        ysmu.LOG.info("YSM client registered textures for {}: {}", id, textures);
    }

    private static void registerTexture(ResourceLocation id, byte[] data) {
        Minecraft.getMinecraft()
            .getTextureManager()
            .loadTexture(id, new OuterFileTexture(data));
    }

    private static void registerAnimations(ResourceLocation id, Map<String, byte[]> mapData) {
        Map<ResourceLocation, AnimationFile> animations = GeckoLibCache.getInstance()
            .getAnimations();
        AnimationFile main = new AnimationFile();
        Map<String, byte[]> controllerFiles = new LinkedHashMap<>();
        Map<String, String> molangMapping = new LinkedHashMap<>();
        Map<String, List<org.apache.commons.lang3.tuple.Pair<String, String>>> molangConditional = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : mapData.entrySet()) {
            String key = entry.getKey();
            byte[] data = entry.getValue();
            if (YsmControllerResources.isMolangResource(key)) {
                // 解析 .molang 函数文件并提取 ctrl.<state> → 动画名 映射
                Map<String, String> parsed = MolangFunctionParser.parseStateToAnimationMap(data);
                if (!parsed.isEmpty()) {
                    molangMapping.putAll(parsed);
                    ysmu.LOG.info("YSM parsed molang function {} for {}: mapping={}",
                        YsmControllerResources.molangName(key), id, parsed);
                }
                // 提取有条件分支的替代动画（如 v.show_car → 开车动画）
                Map<String, List<org.apache.commons.lang3.tuple.Pair<String, String>>> condParsed =
                    MolangFunctionParser.parseConditionalAnimations(data);
                for (Map.Entry<String, List<org.apache.commons.lang3.tuple.Pair<String, String>>> ce : condParsed.entrySet()) {
                    molangConditional.merge(ce.getKey(), ce.getValue(), (a, b) -> { a.addAll(b); return a; });
                }
                continue;
            }
            if (isControllerResource(key, data)) {
                controllerFiles.put(key, data);
                continue;
            }
            try {
                AnimationFile other = getAnimationFile(new String(data, StandardCharsets.UTF_8));
                mergeAnimationFile(main, other);
            } catch (Exception e) {
                ysmu.LOG.warn(
                    "Failed to parse animation file {} for model {}: {}: {}",
                    key,
                    id,
                    e.getClass().getSimpleName(),
                    StringUtils.defaultString(e.getMessage()));
            }
        }
        // 注册 molang 映射，供传统谓词系统在播放动画时重定向
        if (!molangMapping.isEmpty()) {
            AnimationManager.MOLANG_STATE_MAP.put(id, molangMapping);
        }
        if (!molangConditional.isEmpty()) {
            AnimationManager.MOLANG_CONDITIONAL_MAP.put(id, molangConditional);
        }
        DEFAULT_ANIMATION_FILE.animations.forEach((name, action) -> {
            if (!main.animations.containsKey(name)) {
                main.putAnimation(name, action);
            }
        });
        main.animations.forEach((name, animation) -> {
            try {
                ConditionManager.addTest(id, name);
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to register animation condition {} for model {}", name, id, e);
            }
        });
        animations.put(id, main);
        OpenYsmAnimationControllerRegistry.register(id, controllerFiles.values());
        ysmu.LOG.info("YSM client registered animations for {}: count={}, molangMappings={}",
            id, main.animations.size(), molangMapping.size());
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
        return animationFile;
    }

    private static AnimationFile mergeAnimationFile(AnimationFile main, AnimationFile other) {
        other.animations.forEach(main::putAnimation);
        return main;
    }

    public static void loadDefaultModel() {
        try {
            ModelData data = FolderFormat.getModelData(ServerModelManager.CUSTOM, "default");
            data.getAnimation()
                .forEach((name, bytes) -> {
                    AnimationFile animationFile = getAnimationFile(new String(bytes, StandardCharsets.UTF_8));
                    mergeAnimationFile(DEFAULT_ANIMATION_FILE, animationFile);
                });
            ClientModelManager.registerAll(data);
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to load default model", e);
            e.printStackTrace();
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
            "YSM client clearing runtime model caches: models={}, scales={}, extraInfo={}, extraAnimations={}",
            MODELS.size(),
            SCALE_INFO.size(),
            EXTRA_INFO.size(),
            EXTRA_ANIMATION_NAME.size());
        MODELS.clear();
        SCALE_INFO.clear();
        EXTRA_INFO.clear();
        EXTRA_ANIMATION_NAME.clear();
        EXTRA_WHEEL.clear();
        MODEL_PACKS.clear();
        MODEL_PACK_OF.clear();
        ConditionManager.clear();
        OpenYsmAnimationControllerRegistry.clear();
        MolangPhysicsRuntime.clear();
        MolangInstructionExecutor.clearWarnings();
    }

    /** Registers rich extra wheel data from a RawYsmModel. */
    public static void registerExtraWheel(ResourceLocation modelId, com.fox.ysmu.model.resource.pojo.RawYsmModel raw) {
        EXTRA_WHEEL.put(modelId, ExtraWheelData.from(raw));
        if (StringUtils.isNotBlank(raw.properties.previewAnimation)) {
            PREVIEW_ANIMATION.put(modelId, raw.properties.previewAnimation);
        }
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
            String[] split = extraInfo.getTips()
                .split("\n");
            for (String s : split) {
                IChatComponent lineComponent = new ChatComponentText(s);
                lineComponent.getChatStyle()
                    .setColor(EnumChatFormatting.GRAY);
                component.add(lineComponent);
            }
        }
        if (extraInfo.getAuthors() != null && extraInfo.getAuthors().length != 0) {
            component.add(
                new ChatComponentTranslation(
                    "gui.yes_steve_model.model.authors",
                    StringUtils.join(extraInfo.getAuthors(), "丨")));
        }
        if (StringUtils.isNoneBlank(extraInfo.getLicense())) {
            component.add(new ChatComponentTranslation("gui.yes_steve_model.model.license", extraInfo.getLicense()));
        }
        return component;
    }
}
