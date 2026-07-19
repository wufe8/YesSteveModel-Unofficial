package com.fox.ysmu.model.format;

import static com.fox.ysmu.model.ServerModelManager.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import com.fox.ysmu.Config;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Maps;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;

public final class FolderFormat {

    public static void cacheAllModels(Path rootPath) {
        File root = rootPath.toFile();
        File[] dirs = root.listFiles(file -> file.isDirectory());
        if (dirs == null) {
            return;
        }
        if (Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.info("[YSMU-MODEL] FolderFormat scanning {}: found {} subdirectories", rootPath, dirs.length);
        }
        for (File dir : dirs) {
            String dirName = dir.getName();
            boolean noMainModelFile = true;
            boolean noArmModelFile = true;
            boolean noTextureFile = true;
            Collection<File> files = FileUtils.listFiles(
                dir,
                FileFileFilter.FILE,
                null);
            for (File file : files) {
                String fileName = file.getName();
                if (MAIN_MODEL_FILE_NAME.equals(fileName) && isNotBlankFile(file)) {
                    noMainModelFile = false;
                }
                if (ARM_MODEL_FILE_NAME.equals(fileName) && isNotBlankFile(file)) {
                    noArmModelFile = false;
                }
                if (fileName.endsWith(".png")) {
                    noTextureFile = false;
                }
            }
            if (noMainModelFile) {
                if (Config.DEBUG_MODEL_LOAD) {
                    ysmu.LOG.info("[YSMU-MODEL] FolderFormat: {} skipped (no main.json)", dirName);
                }
                continue;
            }
            if (noArmModelFile) {
                if (Config.DEBUG_MODEL_LOAD) {
                    ysmu.LOG.info("[YSMU-MODEL] FolderFormat: {} skipped (no arm.json)", dirName);
                }
                continue;
            }
            if (noTextureFile) {
                if (Config.DEBUG_MODEL_LOAD) {
                    ysmu.LOG.info("[YSMU-MODEL] FolderFormat: {} skipped (no .png texture)", dirName);
                }
                continue;
            }
            String modelId = ModelIdUtil.getInternalModelId(dirName);
            if (Config.DEBUG_MODEL_LOAD) {
                ysmu.LOG.info("[YSMU-MODEL] FolderFormat caching: {} -> modelId={}", dirName, modelId);
            }
            ServerModelInfo info = cacheModel(dir.toPath(), modelId);
            if (info != null) {
                CACHE_NAME_INFO.put(modelId, info);
                if (Config.DEBUG_MODEL_LOAD) {
                    ysmu.LOG.info("[YSMU-MODEL] FolderFormat {}: cached to CACHE_NAME_INFO", modelId);
                }
            }
        }
    }

    /**
     * Collect legacy folder model-processing Runnable tasks for parallel execution.
     */
    public static void collectTasks(Path rootPath, java.util.List<java.lang.Runnable> tasks) {
        java.io.File root = rootPath.toFile();
        java.io.File[] dirs = root.listFiles(java.io.File::isDirectory);
        if (dirs == null) return;
        for (java.io.File dir : dirs) {
            String dirName = dir.getName();
            boolean noMain = true, noArm = true, noTex = true;
            java.util.Collection<java.io.File> files = org.apache.commons.io.FileUtils.listFiles(dir, org.apache.commons.io.filefilter.FileFileFilter.FILE, null);
            for (java.io.File f : files) {
                String fn = f.getName();
                if (MAIN_MODEL_FILE_NAME.equals(fn) && f.length() > 0) noMain = false;
                if (ARM_MODEL_FILE_NAME.equals(fn) && f.length() > 0) noArm = false;
                if (fn.endsWith(".png")) noTex = false;
            }
            if (noMain || noArm || noTex) continue;
            String modelId = ModelIdUtil.getInternalModelId(dirName);
            java.nio.file.Path dirPath = dir.toPath();
            tasks.add(() -> {
                ServerModelInfo info = cacheModel(dirPath, modelId);
                if (info != null) CACHE_NAME_INFO.put(modelId, info);
            });
        }
    }

    private static ServerModelInfo cacheModel(Path modelPath, String modelId) {
        try {
            ModelData data = getModelDataFromPath(modelPath, modelId);
            return ModelCacheWriter.write(data);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @NotNull
    public static ModelData getModelData(Path rootPath, String modelName) throws IOException {
        return getModelDataFromPath(rootPath.resolve(modelName), ModelIdUtil.getInternalModelId(modelName));
    }

    @NotNull
    private static ModelData getModelDataFromPath(Path modelPath, String modelId) throws IOException {
        Map<String, byte[]> model = Maps.newHashMap();
        model.put("main", getBytes(modelPath, MAIN_MODEL_FILE_NAME));
        model.put("arm", getBytes(modelPath, ARM_MODEL_FILE_NAME));

        Map<String, byte[]> texture = Maps.newHashMap();
        Collection<File> textures = FileUtils.listFiles(modelPath.toFile(), new String[] { "png" }, false);
        for (File png : textures) {
            String fileName = png.getName();
            texture.put(fileName, getBytes(modelPath, fileName));
        }

        // Load projectile sub-entity models/textures/animations/controllers from ysm.json
        Map<String, byte[]> projAnimations = Maps.newHashMap();
        loadProjectiles(modelPath, model, texture, projAnimations);

        Map<String, byte[]> animation = Maps.newHashMap();
        animation.putAll(projAnimations);
        animation.put("main", getBytes(modelPath, MAIN_ANIMATION_FILE_NAME));
        animation.put("arm", getBytes(modelPath, ARM_ANIMATION_FILE_NAME));
        animation.put("extra", getBytes(modelPath, EXTRA_ANIMATION_FILE_NAME));

        return new ModelData(modelId, Type.FOLDER, model, texture, animation);
    }

    /**
     * Parse ysm.json's files.projectiles section and add projectile models,
     * textures, animation files, and controller files with the "projectile_"
     * prefix expected by ClientModelManager.
     */
    private static void loadProjectiles(Path modelPath, Map<String, byte[]> model,
        Map<String, byte[]> texture, Map<String, byte[]> animation) throws IOException {
        Path ysmJsonPath = modelPath.resolve("ysm.json");
        if (!ysmJsonPath.toFile().isFile()) return;

        JsonObject root;
        try {
            root = new JsonParser().parse(FileUtils.readFileToString(ysmJsonPath.toFile(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (Exception e) {
            ysmu.LOG.warn("[YSMU-MODEL] Failed to parse ysm.json for projectiles: {}", modelPath, e);
            return;
        }

        JsonObject files = root.getAsJsonObject("files");
        if (files == null) return;
        JsonObject projs = files.getAsJsonObject("projectiles");
        if (projs == null) return;

        for (java.util.Map.Entry<String, JsonElement> entry : projs.entrySet()) {
            String projKey = entry.getKey();
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject projObj = entry.getValue().getAsJsonObject();

            // Load projectile model
            JsonElement modelElem = projObj.get("model");
            if (modelElem != null) {
                String modelPathStr = modelElem.getAsString();
                Path modelFile = modelPath.resolve(modelPathStr);
                if (modelFile.toFile().isFile()) {
                    model.put("projectile_" + projKey, FileUtils.readFileToByteArray(modelFile.toFile()));
                }
            }

            // Load projectile texture
            JsonElement texElem = projObj.get("texture");
            if (texElem != null) {
                // Texture can be a string or an object with "uv" field
                String texPathStr;
                if (texElem.isJsonPrimitive() && texElem.getAsJsonPrimitive().isString()) {
                    texPathStr = texElem.getAsString();
                } else if (texElem.isJsonObject()) {
                    JsonElement uvElem = texElem.getAsJsonObject().get("uv");
                    if (uvElem != null) texPathStr = uvElem.getAsString();
                    else continue;
                } else {
                    continue;
                }
                // Extract filename from path (e.g. "textures/#arrow.png" → "#arrow.png")
                String texName = texPathStr.substring(texPathStr.lastIndexOf('/') + 1);
                Path texFile = modelPath.resolve(texPathStr);
                if (texFile.toFile().isFile()) {
                    texture.put("projectile_" + projKey + "_" + texName,
                        FileUtils.readFileToByteArray(texFile.toFile()));
                }
            }

            // Load projectile animation file and store with projectile_ prefix key
            // so parseAnimationsToBundle registers it under the projectile GeoModel ID.
            JsonElement animElem = projObj.get("animation");
            if (animElem != null) {
                String animPathStr = animElem.getAsString();
                Path animFile = modelPath.resolve(animPathStr);
                if (animFile.toFile().isFile()) {
                    animation.put("projectile_" + projKey, FileUtils.readFileToByteArray(animFile.toFile()));
                }
            }

            // Load projectile controller file and store with projectile_ctrl_ prefix key
            // so parseAnimationsToBundle can register it under the projectile's animation ID.
            JsonElement ctrlElem = projObj.get("controller");
            if (ctrlElem != null) {
                String ctrlPathStr = ctrlElem.getAsString();
                Path ctrlFile = modelPath.resolve(ctrlPathStr);
                if (ctrlFile.toFile().isFile()) {
                    animation.put("projectile_ctrl_" + projKey, FileUtils.readFileToByteArray(ctrlFile.toFile()));
                }
            }
        }
    }

    private static byte[] getBytes(Path root, String fileName) throws IOException {
        Path filePath = root.resolve(fileName);
        if (MAIN_ANIMATION_FILE_NAME.equals(fileName) && !filePath.toFile()
            .isFile()) {
            filePath = CUSTOM.resolve("default/main.animation.json");
        }
        if (ARM_ANIMATION_FILE_NAME.equals(fileName) && !filePath.toFile()
            .isFile()) {
            filePath = CUSTOM.resolve("default/arm.animation.json");
        }
        if (EXTRA_ANIMATION_FILE_NAME.equals(fileName) && !filePath.toFile()
            .isFile()) {
            filePath = CUSTOM.resolve("default/extra.animation.json");
        }

        if (MAIN_MODEL_FILE_NAME.equals(fileName) || ARM_MODEL_FILE_NAME.equals(fileName)) {
            String modelJson = FileUtils.readFileToString(filePath.toFile(), StandardCharsets.UTF_8);
            RawGeoModel rawModel = Converter.fromJsonString(modelJson);
            // 直接返回JSON字符串的字节数组，而不是尝试序列化RawGeoModel对象
            return modelJson.getBytes(StandardCharsets.UTF_8);
        }

        return FileUtils.readFileToByteArray(filePath.toFile());
    }

    private static boolean isNotBlankFile(File file) {
        try {
            String fileText = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
            return StringUtils.isNoneBlank(fileText);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }
}
