package com.fox.ysmu.model.resource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import software.bernie.geckolib3.geo.raw.pojo.Converter;

public class YSMFolderDeserializer implements AutoCloseable {

    private static final String YSM_JSON = "ysm.json";
    private static final String MAIN_JSON = "main.json";
    private static final String ARM_JSON = "arm.json";
    private static final String MAIN_ANIMATION_JSON = "main.animation.json";
    private static final String ARM_ANIMATION_JSON = "arm.animation.json";
    private static final String EXTRA_ANIMATION_JSON = "extra.animation.json";

    private final Map<String, String> readFilesMd5Map = new TreeMap<>();
    private final Path rootPath;
    /** 内存文件源（legacy 裸 YSGP .ysm 解包结果）；磁盘文件夹模式为 null。 */
    private final Map<String, byte[]> virtualFiles;
    private final RawYsmModel model = new RawYsmModel();
    private String finalFolderHash = "";

    public YSMFolderDeserializer(Path sourcePath) throws IOException {
        if (sourcePath == null || !Files.exists(sourcePath)) {
            throw new FileNotFoundException("Model source not found: " + sourcePath);
        }
        if (!Files.isDirectory(sourcePath)) {
            throw new IllegalArgumentException("Expected an OpenYSM model directory: " + sourcePath);
        }
        this.rootPath = sourcePath.toAbsolutePath().normalize();
        this.virtualFiles = null;
        this.model.formatVersion = 65535;
        this.model.modelId = sourcePath.getFileName() == null ? "" : sourcePath.getFileName().toString();
    }

    /**
     * 从内存文件映射构建模型（不落盘）。用于把旧版裸 YSGP 的 .ysm 解包出的文件集
     * （main.json/arm.json/动画/贴图）直接解析为 RawYsmModel，从而可统一写入
     * OpenYSM 同步缓存——这是合并旧/新两条加载路径的关键一步。
     */
    public YSMFolderDeserializer(Map<String, byte[]> files, String modelId) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Expected non-empty virtual model files");
        }
        this.rootPath = null;
        this.virtualFiles = files;
        this.model.formatVersion = 65535;
        this.model.modelId = modelId == null ? "" : modelId;
    }

    public RawYsmModel deserialize() throws IOException {
        byte[] ysmJsonBytes = readResource(YSM_JSON);
        if (ysmJsonBytes != null) {
            JsonObject ysmJson = parseObject(ysmJsonBytes, YSM_JSON);
            parseYsmJson(ysmJson);
        } else {
            parseLegacyFormat();
        }

        parseGlobalResources();
        populateExtraAnimationsFromLang();
        populateMetadataFromLang();
        this.finalFolderHash = calculateFinalFolderHash();
        this.model.properties.sha256 = this.finalFolderHash;
        this.model.footer.version = 65535;
        validateMainPlayerModel();
        return this.model;
    }

    @Override
    public void close() {}

    public String getFolderHash() {
        return this.finalFolderHash;
    }

    private byte[] readResource(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isEmpty()) {
            return null;
        }
        String normalizedRelative = normalizeResourcePath(relativePath);
        if (virtualFiles != null) {
            byte[] data = virtualFiles.get(normalizedRelative);
            if (data == null) {
                return null;
            }
            if (!this.readFilesMd5Map.containsKey(normalizedRelative)) {
                this.readFilesMd5Map.put(normalizedRelative, md5Hex(data));
            }
            return data;
        }
        Path target = this.rootPath.resolve(normalizedRelative).normalize();
        if (!target.startsWith(this.rootPath) || !Files.isRegularFile(target)) {
            return null;
        }
        byte[] data = Files.readAllBytes(target);
        if (!this.readFilesMd5Map.containsKey(normalizedRelative)) {
            this.readFilesMd5Map.put(normalizedRelative, md5Hex(data));
        }
        return data;
    }

    private static String normalizeResourcePath(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private void parseYsmJson(JsonObject ysmJson) throws IOException {
        if (hasObject(ysmJson, "metadata")) {
            parseMetadata(ysmJson.getAsJsonObject("metadata"));
        }
        if (hasObject(ysmJson, "properties")) {
            parseProperties(ysmJson.getAsJsonObject("properties"));
        }
        if (!hasObject(ysmJson, "files")) {
            throw new IOException("OpenYSM model is missing files section");
        }

        JsonObject files = ysmJson.getAsJsonObject("files");
        if (hasObject(files, "player")) {
            parseMainEntity(files.getAsJsonObject("player"));
        }
        if (files.has("vehicles")) {
            parseSubEntities(files.get("vehicles"), this.model.vehicles, "vehicle");
        }
        if (files.has("projectiles")) {
            parseSubEntities(files.get("projectiles"), this.model.projectiles, "projectile");
        }
    }

    private void parseMetadata(JsonObject metaObj) throws IOException {
        this.model.metadata.name = getStr(metaObj, "name", "");
        this.model.metadata.tips = getStr(metaObj, "tips", "");
        if (hasObject(metaObj, "license")) {
            JsonObject license = metaObj.getAsJsonObject("license");
            this.model.metadata.licenseType = getStr(license, "type", "");
            this.model.metadata.licenseDescription = getStr(license, "desc", "");
        }

        if (hasArray(metaObj, "authors")) {
            for (JsonElement elem : metaObj.getAsJsonArray("authors")) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject authorObj = elem.getAsJsonObject();
                RawYsmModel.RawMetadata.Author author = new RawYsmModel.RawMetadata.Author();
                author.name = getStr(authorObj, "name", "");
                author.role = getStr(authorObj, "role", "");
                author.comment = getStr(authorObj, "comment", "");
                if (hasObject(authorObj, "contact")) {
                    copyStringMap(authorObj.getAsJsonObject("contact"), author.contacts);
                }
                author.avatar = getStr(authorObj, "avatar", "");
                if (!author.avatar.isEmpty()) {
                    byte[] avatarData = readResource(author.avatar);
                    if (avatarData != null) {
                        author.avatarImage = parseImage(author.name, avatarData);
                    }
                }
                this.model.metadata.authors.add(author);
            }
        }

        if (hasObject(metaObj, "link")) {
            copyStringMap(metaObj.getAsJsonObject("link"), this.model.metadata.links);
        }
    }

    private void parseProperties(JsonObject propsObj) throws IOException {
        this.model.properties.widthScale = (float) getDouble(propsObj, "width_scale", 0.7d);
        this.model.properties.heightScale = (float) getDouble(propsObj, "height_scale", 0.7d);
        this.model.properties.defaultTexture = getStr(propsObj, "default_texture", "default");
        this.model.properties.previewAnimation = getStr(propsObj, "preview_animation", "");
        this.model.properties.isFree = getBool(propsObj, "free", false);
        this.model.properties.renderLayersFirst = getBool(propsObj, "render_layers_first", false);
        this.model.properties.allCutout = getBool(propsObj, "all_cutout", false);
        this.model.properties.disablePreviewRotation = getBool(propsObj, "disable_preview_rotation", false);
        this.model.properties.guiNoLighting = getBool(propsObj, "gui_no_lighting", false);
        this.model.properties.mergeMultilineExpr = getBool(propsObj, "merge_multiline_expr", true);
        this.model.properties.guiForeground = getStr(propsObj, "gui_foreground", "");
        this.model.properties.guiBackground = getStr(propsObj, "gui_background", "");

        if (hasObject(propsObj, "extra_animation")) {
            copyStringMap(propsObj.getAsJsonObject("extra_animation"), this.model.properties.extraAnimations);
        }
        if (hasArray(propsObj, "extra_animation_classify")) {
            for (JsonElement elem : propsObj.getAsJsonArray("extra_animation_classify")) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject classifyObj = elem.getAsJsonObject();
                RawYsmModel.ExtraAnimationClassify classify = new RawYsmModel.ExtraAnimationClassify();
                classify.id = getStr(classifyObj, "id", "");
                if (hasObject(classifyObj, "extra_animation")) {
                    copyStringMap(classifyObj.getAsJsonObject("extra_animation"), classify.extras);
                }
                this.model.properties.extraAnimationClassifies.add(classify);
            }
        }
        if (hasArray(propsObj, "extra_animation_buttons")) {
            parseExtraAnimationButtons(propsObj.getAsJsonArray("extra_animation_buttons"));
        }

        loadGuiImage(this.model.properties.guiForeground, "gui_foreground");
        loadGuiImage(this.model.properties.guiBackground, "gui_background");
    }

    private void parseExtraAnimationButtons(JsonArray buttons) {
        for (JsonElement elem : buttons) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject buttonObj = elem.getAsJsonObject();
            RawYsmModel.ExtraAnimationButton button = new RawYsmModel.ExtraAnimationButton();
            button.id = getStr(buttonObj, "id", "");
            button.name = getStr(buttonObj, "name", "");
            button.description = getStr(buttonObj, "description", "");
            if (hasArray(buttonObj, "config_forms")) {
                for (JsonElement formElem : buttonObj.getAsJsonArray("config_forms")) {
                    if (!formElem.isJsonObject()) {
                        continue;
                    }
                    JsonObject formObj = formElem.getAsJsonObject();
                    RawYsmModel.ConfigForm form = new RawYsmModel.ConfigForm();
                    form.type = getStr(formObj, "type", "");
                    form.title = getStr(formObj, "title", "");
                    form.description = getStr(formObj, "description", "");
                    form.defaultValue = getStr(formObj, "value", "");
                    form.step = (float) getDouble(formObj, "step", 0d);
                    form.min = (float) getDouble(formObj, "min", 0d);
                    form.max = (float) getDouble(formObj, "max", 0d);
                    if (hasObject(formObj, "labels")) {
                        copyStringMap(formObj.getAsJsonObject("labels"), form.labels);
                    }
                    button.forms.add(form);
                }
            }
            this.model.properties.extraAnimationButtons.add(button);
        }
    }

    private void loadGuiImage(String path, String id) throws IOException {
        if (path == null || path.isEmpty()) {
            return;
        }
        byte[] data = readResource(path);
        if (data == null) {
            data = readResource("background/" + id + ".png");
        }
        if (data != null) {
            this.model.properties.backgroundImages.add(parseImage(id, data));
        }
    }

    private void parseMainEntity(JsonObject playerObj) throws IOException {
        if (!hasObject(playerObj, "model")) {
            throw new IOException("OpenYSM player section is missing model object");
        }
        JsonObject modelObj = playerObj.getAsJsonObject("model");
        if (modelObj.has("main")) {
            byte[] data = readResource(modelObj.get("main").getAsString());
            if (data != null) {
                this.model.mainEntity.mainModel = parseGeometry(data, 1, modelObj.get("main").getAsString());
            }
        }
        if (modelObj.has("arm")) {
            byte[] data = readResource(modelObj.get("arm").getAsString());
            if (data != null) {
                this.model.mainEntity.armModel = parseGeometry(data, 2, modelObj.get("arm").getAsString());
            }
        }

        if (playerObj.has("texture")) {
            Iterable<JsonElement> textureElements = asIterable(playerObj.get("texture"));
            for (JsonElement elem : textureElements) {
                parseTextureReference(elem, this.model.mainEntity.textures);
            }
        }

        if (hasObject(playerObj, "animation")) {
            JsonObject animObj = playerObj.getAsJsonObject("animation");
            for (Map.Entry<String, JsonElement> entry : animObj.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                byte[] animData = readResource(entry.getValue().getAsString());
                if (animData != null) {
                    RawYsmModel.RawAnimationFile animationFile = parseAnimations(animData);
                    animationFile.sourceJson = animData;
                    animationFile.fileHash = sha256Hex(animData);
                    animationFile.animType = getAnimTypeFromKey(entry.getKey());
                    this.model.mainEntity.animationFiles.put(entry.getKey(), animationFile);
                }
            }
        }

        if (hasArray(playerObj, "animation_controllers")) {
            for (JsonElement elem : playerObj.getAsJsonArray("animation_controllers")) {
                if (!elem.isJsonPrimitive()) {
                    continue;
                }
                String path = elem.getAsString();
                byte[] data = readResource(path);
                if (data != null) {
                    RawYsmModel.RawAnimationControllerFile file = new RawYsmModel.RawAnimationControllerFile();
                    file.name = extractFileName(path);
                    file.hash = sha256Hex(data);
                    file.sourceJson = data;
                    parseAnimationControllers(data, file.controllers);
                    this.model.mainEntity.animationControllerFiles.add(file);
                }
            }
        }
    }

    private void parseTextureReference(JsonElement elem, Map<String, RawYsmModel.RawTexture> textures)
        throws IOException {
        String texturePath = null;
        if (elem.isJsonPrimitive()) {
            texturePath = elem.getAsString();
        } else if (elem.isJsonObject() && elem.getAsJsonObject().has("uv")) {
            texturePath = elem.getAsJsonObject().get("uv").getAsString();
        }
        if (texturePath == null || texturePath.isEmpty()) {
            return;
        }
        byte[] textureData = readResource(texturePath);
        if (textureData == null) {
            return;
        }

        RawYsmModel.RawTexture texture = parseTexture(texturePath, textureData);
        if (elem.isJsonObject()) {
            JsonObject obj = elem.getAsJsonObject();
            if (obj.has("normal")) {
                addSubTexture(texture, obj.get("normal").getAsString(), 1);
            }
            if (obj.has("specular")) {
                addSubTexture(texture, obj.get("specular").getAsString(), 2);
            }
        }
        textures.put(texture.name, texture);
    }

    private void addSubTexture(RawYsmModel.RawTexture texture, String path, int specularType) throws IOException {
        byte[] data = readResource(path);
        if (data == null) {
            return;
        }
        ImageMeta meta = parseImageMeta(data);
        RawYsmModel.RawTexture.SubTexture subTexture = new RawYsmModel.RawTexture.SubTexture();
        subTexture.specularType = specularType;
        subTexture.hash = sha256Hex(data);
        subTexture.width = meta.width;
        subTexture.height = meta.height;
        subTexture.imageFormat = meta.format;
        subTexture.data = data;
        subTexture.unknownFlag = 1;
        texture.subTextures.add(subTexture);
    }

    private RawYsmModel.RawTexture parseTexture(String path, byte[] data) throws IOException {
        ImageMeta meta = parseImageMeta(data);
        RawYsmModel.RawTexture texture = new RawYsmModel.RawTexture();
        texture.name = extractFileName(path);
        texture.sourceFileName = extractFileNameWithExtension(path);
        texture.hash = sha256Hex(data);
        texture.width = meta.width;
        texture.height = meta.height;
        texture.imageFormat = meta.format;
        texture.data = data;
        texture.unknownFlag = 1;
        return texture;
    }

    private void parseSubEntities(JsonElement sectionElem, Map<String, RawYsmModel.RawSubEntity> targetMap,
        String defaultIdentifier) throws IOException {
        if (!sectionElem.isJsonArray() && !sectionElem.isJsonObject()) {
            return;
        }
        List<JsonObject> items = new ArrayList<>();
        if (sectionElem.isJsonArray()) {
            for (JsonElement elem : sectionElem.getAsJsonArray()) {
                if (elem.isJsonObject()) {
                    items.add(elem.getAsJsonObject());
                }
            }
        } else {
            JsonObject object = sectionElem.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    JsonObject item = entry.getValue().getAsJsonObject();
                    if (!item.has("match")) {
                        item.addProperty("__temp_identifier", entry.getKey());
                    }
                    items.add(item);
                }
            }
        }

        int index = 0;
        for (JsonObject item : items) {
            RawYsmModel.RawSubEntity sub = new RawYsmModel.RawSubEntity();
            sub.identifier = getStr(item, "__temp_identifier", defaultIdentifier + "_" + index);
            if (item.has("match")) {
                sub.matchIds = readStringArray(item.get("match"));
            }
            if (item.has("model")) {
                byte[] modelData = readResource(item.get("model").getAsString());
                if (modelData != null) {
                    sub.model = parseGeometry(modelData, 0, item.get("model").getAsString());
                }
            }
            if (item.has("texture")) {
                for (JsonElement textureElem : asIterable(item.get("texture"))) {
                    parseTextureReference(textureElem, sub.textures);
                }
            }
            if (item.has("animation")) {
                for (JsonElement animElem : asIterable(item.get("animation"))) {
                    if (!animElem.isJsonPrimitive()) {
                        continue;
                    }
                    byte[] animData = readResource(animElem.getAsString());
                    if (animData != null) {
                        RawYsmModel.RawAnimationFile animationFile = parseAnimations(animData);
                        animationFile.sourceJson = animData;
                        animationFile.fileHash = sha256Hex(animData);
                        sub.animationFiles.put(extractFileName(animElem.getAsString()), animationFile);
                    }
                }
            }
            if (item.has("controller")) {
                for (JsonElement ctrlElem : asIterable(item.get("controller"))) {
                    if (!ctrlElem.isJsonPrimitive()) {
                        continue;
                    }
                    byte[] ctrlData = readResource(ctrlElem.getAsString());
                    if (ctrlData != null) {
                        RawYsmModel.RawAnimationControllerFile ctrlFile = new RawYsmModel.RawAnimationControllerFile();
                        ctrlFile.name = extractFileName(ctrlElem.getAsString());
                        ctrlFile.hash = sha256Hex(ctrlData);
                        ctrlFile.sourceJson = ctrlData;
                        parseAnimationControllers(ctrlData, ctrlFile.controllers);
                        sub.animationControllerFiles.add(ctrlFile);
                    }
                }
            }
            targetMap.put(sub.identifier, sub);
            index++;
        }
    }

    private RawYsmModel.RawGeometry parseGeometry(byte[] data, int modelType, String sourcePath) throws IOException {
        String json = new String(data, StandardCharsets.UTF_8);
        Converter.fromJsonString(json);

        RawYsmModel.RawGeometry geometry = new RawYsmModel.RawGeometry();
        geometry.modelType = modelType;
        geometry.sha256 = sha256Hex(data);
        geometry.sourceJson = data;
        JsonObject root = parseObject(data, sourcePath);
        JsonObject description = findGeometryDescription(root);
        if (description != null) {
            geometry.identifier = getStr(description, "identifier", "");
            geometry.textureWidth = (float) getDouble(description, "texture_width", 64d);
            geometry.textureHeight = (float) getDouble(description, "texture_height", 64d);
            geometry.visibleBoundsWidth = (float) getDouble(description, "visible_bounds_width", 0d);
            geometry.visibleBoundsHeight = (float) getDouble(description, "visible_bounds_height", 0d);
            geometry.visibleBoundsOffset = getFloatArray(description, "visible_bounds_offset", 3);
        }
        JsonArray bones = findGeometryBones(root);
        if (bones != null) {
            for (JsonElement boneElem : bones) {
                if (!boneElem.isJsonObject()) {
                    continue;
                }
                JsonObject boneObj = boneElem.getAsJsonObject();
                RawYsmModel.RawBone bone = new RawYsmModel.RawBone();
                bone.name = getStr(boneObj, "name", "");
                bone.parentName = getStr(boneObj, "parent", "");

                // RawYsmModel 内部约定与 .ysm 二进制一致：
                // pivot.x 取负、rotation 为弧度制（x/y 取负、z 取正）。
                // 客户端 createGeometryJson -> generatedPivotArray/generatedRotationArray
                // 会再反转回 BlockBench JSON 的数值。如果这里存原始 JSON 值，
                // 走 OpenYSM 二进制同步路径时骨骼旋转/轴心会整体出错。
                if (hasArray(boneObj, "pivot")) {
                    JsonArray pivot = boneObj.getAsJsonArray("pivot");
                    bone.pivot = new float[] {
                        -pivot.get(0).getAsFloat(),
                        pivot.get(1).getAsFloat(),
                        pivot.get(2).getAsFloat()
                    };
                }
                if (hasArray(boneObj, "rotation")) {
                    JsonArray rot = boneObj.getAsJsonArray("rotation");
                    bone.rotation = new float[] {
                        (float) -Math.toRadians(rot.get(0).getAsFloat()),
                        (float) -Math.toRadians(rot.get(1).getAsFloat()),
                        (float) Math.toRadians(rot.get(2).getAsFloat())
                    };
                }

                // 关键修复：填充 bone.cubes。此前 parseGeometry 只填骨骼元数据，
                // cubes 恒为空，导致 writeOpenYsm 序列化出空几何，客户端反序列化后
                // isBridgeable 失败、文件夹模型（含内置模型）全部从列表消失。
                float boneInflate = (float) getDouble(boneObj, "inflate", 0.0);
                boolean boneMirror = getBool(boneObj, "mirror", false);
                if (hasArray(boneObj, "cubes")) {
                    for (JsonElement cElem : boneObj.getAsJsonArray("cubes")) {
                        if (!cElem.isJsonObject()) {
                            continue;
                        }
                        JsonObject cObj = cElem.getAsJsonObject();
                        RawYsmModel.RawCube cube = new RawYsmModel.RawCube();

                        float inflate = cObj.has("inflate")
                            ? cObj.get("inflate").getAsFloat() : boneInflate;
                        boolean mirror = cObj.has("mirror")
                            ? cObj.get("mirror").getAsBoolean() : boneMirror;

                        float[] origin = getFloatArray(cObj, "origin", 3);
                        float[] size = getFloatArray(cObj, "size", 3);

                        // OpenYSM 展开的立方体区域：x 镜像到负数侧
                        float cx = -origin[0] - size[0] - inflate;
                        float cy = origin[1] - inflate;
                        float cz = origin[2] - inflate;
                        float cw = size[0] + inflate * 2;
                        float ch = size[1] + inflate * 2;
                        float cd = size[2] + inflate * 2;

                        Matrix4f cubeBakeMat = new Matrix4f();
                        if (cObj.has("rotation") || cObj.has("pivot")) {
                            float[] cpvt = getFloatArray(cObj, "pivot", 3);
                            float[] crot = getFloatArray(cObj, "rotation", 3);
                            cubeBakeMat.translate(-cpvt[0] / 16f, cpvt[1] / 16f, cpvt[2] / 16f);
                            cubeBakeMat.rotateZ((float) Math.toRadians(crot[2]));
                            cubeBakeMat.rotateY((float) -Math.toRadians(crot[1]));
                            cubeBakeMat.rotateX((float) -Math.toRadians(crot[0]));
                            cubeBakeMat.translate(cpvt[0] / 16f, -cpvt[1] / 16f, -cpvt[2] / 16f);
                        }
                        Matrix3f cubeNormalMat = new Matrix3f();
                        cubeBakeMat.normal(cubeNormalMat);

                        if (cObj.has("uv")) {
                            JsonElement uvElem = cObj.get("uv");
                            if (uvElem.isJsonObject()) {
                                JsonObject uvObj = uvElem.getAsJsonObject();
                                bakeFaceToRaw(cube, uvObj, "north", "north", mirror,
                                    cx, cy, cz, cw, ch, cd, geometry.textureWidth, geometry.textureHeight,
                                    new Vector3f(0, 0, -1), cubeBakeMat, cubeNormalMat);
                                bakeFaceToRaw(cube, uvObj, "south", "south", mirror,
                                    cx, cy, cz, cw, ch, cd, geometry.textureWidth, geometry.textureHeight,
                                    new Vector3f(0, 0, 1), cubeBakeMat, cubeNormalMat);
                                bakeFaceToRaw(cube, uvObj, "east", mirror ? "west" : "east", mirror,
                                    cx, cy, cz, cw, ch, cd, geometry.textureWidth, geometry.textureHeight,
                                    new Vector3f(1, 0, 0), cubeBakeMat, cubeNormalMat);
                                bakeFaceToRaw(cube, uvObj, "west", mirror ? "east" : "west", mirror,
                                    cx, cy, cz, cw, ch, cd, geometry.textureWidth, geometry.textureHeight,
                                    new Vector3f(-1, 0, 0), cubeBakeMat, cubeNormalMat);
                                bakeFaceToRaw(cube, uvObj, "up", "up", mirror,
                                    cx, cy, cz, cw, ch, cd, geometry.textureWidth, geometry.textureHeight,
                                    new Vector3f(0, 1, 0), cubeBakeMat, cubeNormalMat);
                                bakeFaceToRaw(cube, uvObj, "down", "down", mirror,
                                    cx, cy, cz, cw, ch, cd, geometry.textureWidth, geometry.textureHeight,
                                    new Vector3f(0, -1, 0), cubeBakeMat, cubeNormalMat);
                            } else if (uvElem.isJsonArray()) {
                                // 旧版 BlockBench UV：单个 [u, v] 按尺寸自动展开六面
                                JsonArray uvArr = uvElem.getAsJsonArray();
                                float uvX = uvArr.get(0).getAsFloat();
                                float uvY = uvArr.get(1).getAsFloat();
                                float dx = (float) Math.floor(size[0]);
                                float dy = (float) Math.floor(size[1]);
                                float dz = (float) Math.floor(size[2]);

                                JsonObject fakeUvObj = new JsonObject();
                                fakeUvObj.add("north", createFaceUVNode(uvX + dz, uvY + dz, dx, dy));
                                fakeUvObj.add("south", createFaceUVNode(uvX + dz + dx + dz, uvY + dz, dx, dy));
                                fakeUvObj.add("east", createFaceUVNode(uvX, uvY + dz, dz, dy));
                                fakeUvObj.add("west", createFaceUVNode(uvX + dz + dx, uvY + dz, dz, dy));
                                fakeUvObj.add("up", createFaceUVNode(uvX + dz, uvY, dx, dz));
                                fakeUvObj.add("down", createFaceUVNode(uvX + dz + dx, uvY + dz, dx, -dz));

                                bakeFaceToRaw(cube, fakeUvObj, "north", "north", mirror,
                                    cx, cy, cz, cw, ch, cd, geometry.textureWidth, geometry.textureHeight,
                                    new Vector3f(0, 0, -1), cubeBakeMat, cubeNormalMat);
                                bakeFaceToRaw(cube, fakeUvObj, "south", "south", mirror,
                                    cx, cy, cz, cw, ch, cd, geometry.textureWidth, geometry.textureHeight,
                                    new Vector3f(0, 0, 1), cubeBakeMat, cubeNormalMat);
                                bakeFaceToRaw(cube, fakeUvObj, "east", mirror ? "west" : "east", mirror,
                                    cx, cy, cz, cw, ch, cd, geometry.textureWidth, geometry.textureHeight,
                                    new Vector3f(1, 0, 0), cubeBakeMat, cubeNormalMat);
                                bakeFaceToRaw(cube, fakeUvObj, "west", mirror ? "east" : "west", mirror,
                                    cx, cy, cz, cw, ch, cd, geometry.textureWidth, geometry.textureHeight,
                                    new Vector3f(-1, 0, 0), cubeBakeMat, cubeNormalMat);
                                bakeFaceToRaw(cube, fakeUvObj, "up", "up", mirror,
                                    cx, cy, cz, cw, ch, cd, geometry.textureWidth, geometry.textureHeight,
                                    new Vector3f(0, 1, 0), cubeBakeMat, cubeNormalMat);
                                bakeFaceToRaw(cube, fakeUvObj, "down", "down", mirror,
                                    cx, cy, cz, cw, ch, cd, geometry.textureWidth, geometry.textureHeight,
                                    new Vector3f(0, -1, 0), cubeBakeMat, cubeNormalMat);
                            }
                        }
                        bone.cubes.add(cube);
                    }
                }
                geometry.bones.add(bone);
            }
        }
        return geometry;
    }

    /** 将 BlockBench 六面 UV 展开为一个 RawFace（顶点、法线、UV），与 .ysm 二进制一致。 */
    private static void bakeFaceToRaw(RawYsmModel.RawCube cube, JsonObject uvObj, String faceType,
        String uvFaceName, boolean mirror, float x, float y, float z, float w, float h, float d,
        float tw, float th, Vector3f rawNormal, Matrix4f cubeBakeMat, Matrix3f cubeNormalMat) {
        if (!uvObj.has(uvFaceName)) {
            return;
        }
        JsonObject faceData = uvObj.getAsJsonObject(uvFaceName);
        float[] uv = getFloatArray(faceData, "uv", 2);
        float[] uvSize = getFloatArray(faceData, "uv_size", 2);

        float u0 = uv[0] / tw;
        float v0 = uv[1] / th;
        float u1 = (uv[0] + uvSize[0]) / tw;
        float v1 = (uv[1] + uvSize[1]) / th;

        if (!mirror) {
            float temp = u0;
            u0 = u1;
            u1 = temp;
        }

        RawYsmModel.RawFace face = new RawYsmModel.RawFace();
        Vector3f bakedNormal = new Vector3f(rawNormal).mul(cubeNormalMat).normalize();
        face.normal = new float[] { bakedNormal.x, bakedNormal.y, bakedNormal.z };

        float x1 = x / 16f, x2 = (x + w) / 16f;
        float y1 = y / 16f, y2 = (y + h) / 16f;
        float z1 = z / 16f, z2 = (z + d) / 16f;

        Vector3f p1 = new Vector3f(x1, y1, z1);
        Vector3f p2 = new Vector3f(x1, y1, z2);
        Vector3f p3 = new Vector3f(x1, y2, z1);
        Vector3f p4 = new Vector3f(x1, y2, z2);
        Vector3f p5 = new Vector3f(x2, y1, z1);
        Vector3f p6 = new Vector3f(x2, y1, z2);
        Vector3f p7 = new Vector3f(x2, y2, z1);
        Vector3f p8 = new Vector3f(x2, y2, z2);

        Vector3f[] positions = switch (faceType) {
            case "west" -> new Vector3f[] { p4, p3, p1, p2 };
            case "east" -> new Vector3f[] { p7, p8, p6, p5 };
            case "north" -> new Vector3f[] { p3, p7, p5, p1 };
            case "south" -> new Vector3f[] { p8, p4, p2, p6 };
            case "up" -> new Vector3f[] { p4, p8, p7, p3 };
            case "down" -> new Vector3f[] { p1, p5, p6, p2 };
            default -> null;
        };

        Vector4f tempPos = new Vector4f();
        for (int i = 0; i < 4; i++) {
            tempPos.set(positions[i].x(), positions[i].y(), positions[i].z(), 1.0f).mul(cubeBakeMat);
            face.positions[i] = new float[] { tempPos.x(), tempPos.y(), tempPos.z() };
        }

        face.u = new float[] { u0, u1, u1, u0 };
        face.v = new float[] { v0, v0, v1, v1 };
        cube.faces.add(face);
    }

    private static JsonObject createFaceUVNode(float u, float v, float w, float h) {
        JsonObject node = new JsonObject();
        JsonArray uv = new JsonArray();
        uv.add(new JsonPrimitive(u));
        uv.add(new JsonPrimitive(v));
        JsonArray size = new JsonArray();
        size.add(new JsonPrimitive(w));
        size.add(new JsonPrimitive(h));
        node.add("uv", uv);
        node.add("uv_size", size);
        return node;
    }

    private RawYsmModel.RawAnimationFile parseAnimations(byte[] data) {
        RawYsmModel.RawAnimationFile file = new RawYsmModel.RawAnimationFile();
        JsonObject root = parseObject(data, "animation");
        if (!hasObject(root, "animations")) {
            return file;
        }
        JsonObject animations = root.getAsJsonObject("animations");
        for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
            RawYsmModel.RawAnimation animation = new RawYsmModel.RawAnimation();
            animation.name = entry.getKey();
            // YSMU: "empty" 是内置调试动画，模型不得定义——加载期直接丢弃（见 YsmBuiltinAnimations）。
            if ("empty".equals(animation.name)) {
                continue;
            }
            if (entry.getValue().isJsonObject()) {
                JsonObject animObj = entry.getValue().getAsJsonObject();
                animation.length = (float) getDouble(animObj, "animation_length", 0d);
                animation.loopMode = parseLoopMode(animObj.get("loop"));
                if (hasObject(animObj, "blend_weight")) {
                    animation.blendWeight = animObj.get("blend_weight");
                }
                // YSMU: Bedrock-style anim_time_update（自定义动画时间推进表达式，秒）
                if (hasObject(animObj, "anim_time_update")) {
                    animation.animTimeUpdate = getStr(animObj, "anim_time_update", "");
                }
                // YSMU: anim_speed（逐动画播放倍率，数字或 Molang 表达式）
                if (hasObject(animObj, "anim_speed")) {
                    animation.animSpeed = getStr(animObj, "anim_speed", "");
                }
                if (hasObject(animObj, "bones")) {
                    for (Map.Entry<String, JsonElement> boneEntry : animObj.getAsJsonObject("bones").entrySet()) {
                        RawYsmModel.RawBoneAnimation bone = new RawYsmModel.RawBoneAnimation();
                        bone.boneName = boneEntry.getKey();
                        if (boneEntry.getValue().isJsonObject()) {
                            JsonObject boneObj = boneEntry.getValue().getAsJsonObject();
                            parseChannelFromJson(bone.rotation, boneObj.get("rotation"));
                            parseChannelFromJson(bone.position, boneObj.get("position"));
                            parseChannelFromJson(bone.scale, boneObj.get("scale"));
                        }
                        animation.boneAnimations.add(bone);
                    }
                }
                // Parse timeline (Molang expressions at timestamps)
                if (hasObject(animObj, "timeline")) {
                    JsonObject tlObj = animObj.getAsJsonObject("timeline");
                    for (Map.Entry<String, JsonElement> tlEntry : tlObj.entrySet()) {
                        RawYsmModel.RawTimelineEvent tle = new RawYsmModel.RawTimelineEvent();
                        tle.timestamp = Float.parseFloat(tlEntry.getKey());
                        JsonElement val = tlEntry.getValue();
                        if (val.isJsonArray()) {
                            for (JsonElement e : val.getAsJsonArray()) {
                                tle.events.add(e.getAsString());
                            }
                        } else {
                            tle.events.add(val.getAsString());
                        }
                        animation.timelineEvents.add(tle);
                    }
                }
                // Parse sound effects (e.g. {"0.0": {"effect": "minecraft:item.trident.throw"}})
                if (hasObject(animObj, "sound_effects")) {
                    JsonObject sfxObj = animObj.getAsJsonObject("sound_effects");
                    for (Map.Entry<String, JsonElement> sfxEntry : sfxObj.entrySet()) {
                        RawYsmModel.RawSoundEffect sfx = new RawYsmModel.RawSoundEffect();
                        sfx.timestamp = Float.parseFloat(sfxEntry.getKey());
                        sfx.effectName = getStr(sfxEntry.getValue().getAsJsonObject(), "effect", "");
                        animation.soundEffects.add(sfx);
                    }
                }
            }
            file.animations.put(animation.name, animation);
        }
        return file;
    }

    /**
     * Parse a single bone channel (rotation/position/scale) from JSON into RawKeyframe list.
     * Handles: string expression, number constant, or object with time-keyframe pairs.
     */
    private void parseChannelFromJson(java.util.List<RawYsmModel.RawKeyframe> channel, JsonElement element) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonPrimitive()) {
            // Single keyframe at time 0: "scale": "expression" or "scale": 0.0
            RawYsmModel.RawKeyframe kf = new RawYsmModel.RawKeyframe();
            kf.timestamp = 0f;
            kf.interpolationMode = 0;
            kf.hasPreData = false;
            String strVal = element.getAsJsonPrimitive().isNumber()
                ? Float.toString(element.getAsFloat())
                : element.getAsString();
            kf.postData = new Object[]{strVal, strVal, strVal};
            channel.add(kf);
            return;
        }
        if (element.isJsonArray()) {
            // Single keyframe at time 0 with [x, y, z] vector:
            // "position": [0, -20, 0], "rotation": [-15, 10, 0], "scale": [1, 1, 1]
            // Previously dropped — folder models exported through the OpenYSM
            // binary cache lost every direct-array channel (mouth zui2-7 position,
            // fly/sit Root/body transforms), causing bones to render at their
            // bind-pose position (e.g. mouth floating above the head).
            RawYsmModel.RawKeyframe kf = new RawYsmModel.RawKeyframe();
            kf.timestamp = 0f;
            kf.interpolationMode = 0;
            kf.hasPreData = false;
            kf.postData = readMolangArray(element.getAsJsonArray());
            channel.add(kf);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject obj = element.getAsJsonObject();
        // Check if this is a Molang array (has "vector") — currently not used in animations
        // Iterate time-keyed entries: "0.0": ..., "0.0833": ...
        for (java.util.Map.Entry<String, JsonElement> kfEntry : obj.entrySet()) {
            String timeStr = kfEntry.getKey();
            JsonElement val = kfEntry.getValue();
            float time;
            try { time = Float.parseFloat(timeStr); } catch (NumberFormatException e) { continue; }
            RawYsmModel.RawKeyframe kf = parseSingleKeyframe(val);
            if (kf != null) {
                kf.timestamp = time;
                channel.add(kf);
            }
        }
    }

    /**
     * Parse a single keyframe value into RawKeyframe (without timestamp).
     * Handles: [x,y,z] array, {"pre":...,"post":...} object, or primitive.
     */
    private RawYsmModel.RawKeyframe parseSingleKeyframe(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        RawYsmModel.RawKeyframe kf = new RawYsmModel.RawKeyframe();
        kf.interpolationMode = 0;
        if (element.isJsonArray()) {
            // [x, y, z] — simple array
            kf.hasPreData = false;
            kf.postData = readMolangArray(element.getAsJsonArray());
            return kf;
        }
        if (!element.isJsonObject()) {
            // Primitive: number or string, expand to [val, val, val]
            kf.hasPreData = false;
            String str = element.getAsJsonPrimitive().isNumber()
                ? Float.toString(element.getAsFloat())
                : element.getAsString();
            kf.postData = new Object[]{str, str, str};
            return kf;
        }
        JsonObject obj = element.getAsJsonObject();
        // {"pre":..., "post":..., "lerp_mode":...}
        if (obj.has("pre") || obj.has("post")) {
            boolean hasPre = obj.has("pre");
            kf.hasPreData = hasPre;
            JsonElement preElem = hasPre ? obj.get("pre") : null;
            if (hasPre) {
                if (preElem.isJsonArray()) {
                    kf.preData = readMolangArray(preElem.getAsJsonArray());
                } else {
                    String s = preElem.getAsJsonPrimitive().isNumber()
                        ? Float.toString(preElem.getAsFloat())
                        : preElem.getAsString();
                    kf.preData = new Object[]{s, s, s};
                }
            }
            JsonElement postElem = obj.get("post");
            if (postElem.isJsonArray()) {
                kf.postData = readMolangArray(postElem.getAsJsonArray());
            } else {
                String s = postElem.getAsJsonPrimitive().isNumber()
                    ? Float.toString(postElem.getAsFloat())
                    : postElem.getAsString();
                kf.postData = new Object[]{s, s, s};
            }
            if (obj.has("lerp_mode")) {
                String lm = obj.get("lerp_mode").getAsString();
                if ("step".equals(lm)) kf.interpolationMode = 1;
                else if ("catmullrom".equals(lm)) kf.interpolationMode = 2;
            }
            return kf;
        }
        // Unknown object format — treat as [val, val, val] from "vector" or just skip
        if (obj.has("vector")) {
            kf.hasPreData = false;
            kf.postData = readMolangArray(obj.getAsJsonArray("vector"));
            return kf;
        }
        return null;
    }

    private Object[] readMolangArray(JsonArray arr) {
        Object[] result = new Object[3];
        for (int i = 0; i < 3 && i < arr.size(); i++) {
            JsonElement e = arr.get(i);
            if (e.isJsonPrimitive()) {
                JsonPrimitive p = e.getAsJsonPrimitive();
                if (p.isNumber()) {
                    result[i] = p.getAsFloat();
                } else {
                    result[i] = p.getAsString();
                }
            } else {
                result[i] = 0f;
            }
        }
        return result;
    }

    private static int parseLoopMode(JsonElement loop) {
        if (loop == null || loop.isJsonNull()) {
            return 0;
        }
        if (loop.isJsonPrimitive() && loop.getAsJsonPrimitive().isBoolean()) {
            return loop.getAsBoolean() ? 1 : 0;
        }
        if (loop.isJsonPrimitive()) {
            String value = loop.getAsString();
            if ("true".equals(value) || "loop".equals(value)) {
                return 1;
            }
            if ("hold_on_last_frame".equals(value)) {
                return 2;
            }
        }
        return 0;
    }

    private void parseAnimationControllers(byte[] data,
        Map<String, RawYsmModel.RawAnimationController> targetMap) {
        JsonObject root = parseObject(data, "animation_controller");
        if (!hasObject(root, "animation_controllers")) {
            return;
        }
        JsonObject controllers = root.getAsJsonObject("animation_controllers");
        for (Map.Entry<String, JsonElement> entry : controllers.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject controllerObj = entry.getValue().getAsJsonObject();
            RawYsmModel.RawAnimationController controller = new RawYsmModel.RawAnimationController();
            controller.animationName = entry.getKey();
            controller.initialState = getStr(controllerObj, "initial_state", "");
            if (hasObject(controllerObj, "states")) {
                JsonObject states = controllerObj.getAsJsonObject("states");
                for (Map.Entry<String, JsonElement> stateEntry : states.entrySet()) {
                    if (!stateEntry.getValue().isJsonObject()) {
                        continue;
                    }
                    controller.states.add(parseControllerState(stateEntry.getKey(), stateEntry.getValue().getAsJsonObject()));
                }
            }
            targetMap.put(controller.animationName, controller);
        }
    }

    private RawYsmModel.RawControllerState parseControllerState(String name, JsonObject stateObj) {
        RawYsmModel.RawControllerState state = new RawYsmModel.RawControllerState();
        state.name = name;
        if (hasArray(stateObj, "animations")) {
            for (JsonElement elem : stateObj.getAsJsonArray("animations")) {
                if (elem.isJsonPrimitive()) {
                    state.animations.put(elem.getAsString(), "");
                } else if (elem.isJsonObject()) {
                    copyStringMap(elem.getAsJsonObject(), state.animations);
                }
            }
        }
        if (hasArray(stateObj, "transitions")) {
            for (JsonElement elem : stateObj.getAsJsonArray("transitions")) {
                if (elem.isJsonObject()) {
                    copyStringMap(elem.getAsJsonObject(), state.transitions);
                }
            }
        }
        if (hasArray(stateObj, "on_entry")) {
            addStringArray(stateObj.getAsJsonArray("on_entry"), state.onEntry);
        }
        if (hasArray(stateObj, "on_exit")) {
            addStringArray(stateObj.getAsJsonArray("on_exit"), state.onExit);
        }
        if (hasArray(stateObj, "sound_effects")) {
            for (JsonElement elem : stateObj.getAsJsonArray("sound_effects")) {
                if (elem.isJsonObject()) {
                    state.soundEffects.add(getStr(elem.getAsJsonObject(), "effect", ""));
                } else if (!elem.isJsonNull()) {
                    state.soundEffects.add(elem.getAsString());
                }
            }
        }
        if (stateObj.has("blend_transition")) {
            JsonElement blend = stateObj.get("blend_transition");
            if (blend.isJsonPrimitive() && blend.getAsJsonPrimitive().isNumber()) {
                state.blendTransitionValue = blend.getAsFloat();
            } else if (blend.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : blend.getAsJsonObject().entrySet()) {
                    state.blendTransitions.put(Float.parseFloat(entry.getKey()), entry.getValue().getAsFloat());
                }
            }
        }
        state.blendViaShortestPath = getBool(stateObj, "blend_via_shortest_path", false);
        return state;
    }

    private void parseGlobalResources() throws IOException {
        if (virtualFiles != null) {
            // 虚拟模式：legacy .ysm 通常没有 sounds/functions/lang 子目录，跳过即可。
            for (Map.Entry<String, byte[]> entry : virtualFiles.entrySet()) {
                String relative = normalizeResourcePath(entry.getKey());
                if (relative.startsWith("sounds/") || relative.endsWith(".ogg")) {
                    this.model.soundFiles.put(extractFileName(relative),
                        new RawYsmModel.RawDataFile(sha256Hex(entry.getValue()), entry.getValue()));
                } else if (relative.startsWith("functions/") && relative.endsWith(".molang")) {
                    this.model.functionFiles.put(extractFileName(relative),
                        new RawYsmModel.RawDataFile(sha256Hex(entry.getValue()), entry.getValue()));
                } else if (relative.startsWith("lang/") && relative.endsWith(".json")) {
                    this.model.languageFiles.put(parseLocale(relative), parseLanguageFile(entry.getValue()));
                }
            }
            return;
        }
        try (Stream<Path> stream = Files.walk(this.rootPath)) {
            for (Path path : iterable(stream)) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String relative = normalizeResourcePath(this.rootPath.relativize(path).toString());
                if (relative.startsWith("sounds/") || relative.endsWith(".ogg")) {
                    byte[] data = readResource(relative);
                    if (data != null) {
                        this.model.soundFiles.put(extractFileName(relative), new RawYsmModel.RawDataFile(sha256Hex(data), data));
                    }
                } else if (relative.startsWith("functions/") && relative.endsWith(".molang")) {
                    byte[] data = readResource(relative);
                    if (data != null) {
                        this.model.functionFiles.put(extractFileName(relative), new RawYsmModel.RawDataFile(sha256Hex(data), data));
                    }
                } else if (relative.startsWith("lang/") && relative.endsWith(".json")) {
                    byte[] data = readResource(relative);
                    if (data != null) {
                        this.model.languageFiles.put(parseLocale(relative), parseLanguageFile(data));
                    }
                }
            }
        }
    }

    private RawYsmModel.RawLanguageFile parseLanguageFile(byte[] data) {
        Map<String, String> values = new LinkedHashMap<>();
        JsonObject root = parseObject(data, "lang");
        copyStringMap(root, values);
        return new RawYsmModel.RawLanguageFile(sha256Hex(data), values);
    }

    private void parseLegacyFormat() throws IOException {
        byte[] mainData = readResource(MAIN_JSON);
        byte[] armData = readResource(ARM_JSON);
        if (mainData == null) {
            throw new IOException("Legacy model missing main.json");
        }
        if (armData == null) {
            throw new IOException("Legacy model missing arm.json");
        }
        this.model.mainEntity.mainModel = parseGeometry(mainData, 1, MAIN_JSON);
        this.model.mainEntity.armModel = parseGeometry(armData, 2, ARM_JSON);

        boolean hasTexture = false;
        if (virtualFiles != null) {
            // 虚拟模式：legacy .ysm 解包文件可能把贴图放在任意路径，遍历所有 .png。
            for (Map.Entry<String, byte[]> entry : virtualFiles.entrySet()) {
                String fileName = entry.getKey();
                if (fileName.endsWith(".png")) {
                    // 经 readResource 读取以同时记录 md5，保证 folderHash 覆盖贴图。
                    byte[] textureData = readResource(fileName);
                    if (textureData != null) {
                        RawYsmModel.RawTexture texture = parseTexture(fileName, textureData);
                        this.model.mainEntity.textures.put(texture.name, texture);
                        hasTexture = true;
                    }
                }
            }
        } else {
            try (Stream<Path> stream = Files.list(this.rootPath)) {
                for (Path path : iterable(stream)) {
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    String fileName = path.getFileName().toString();
                    if (fileName.endsWith(".png")) {
                        byte[] textureData = readResource(fileName);
                        RawYsmModel.RawTexture texture = parseTexture(fileName, textureData);
                        this.model.mainEntity.textures.put(texture.name, texture);
                        hasTexture = true;
                    }
                }
            }
        }
        if (!hasTexture) {
            throw new IOException("Legacy model requires at least one PNG texture");
        }

        readLegacyAnimation(MAIN_ANIMATION_JSON, "main");
        readLegacyAnimation(ARM_ANIMATION_JSON, "arm");
        readLegacyAnimation(EXTRA_ANIMATION_JSON, "extra");
        if (!this.model.mainEntity.textures.isEmpty()) {
            this.model.properties.defaultTexture = this.model.mainEntity.textures.keySet().iterator().next();
        }
    }

    private void readLegacyAnimation(String fileName, String key) throws IOException {
        byte[] data = readResource(fileName);
        if (data == null) {
            return;
        }
        RawYsmModel.RawAnimationFile animationFile = parseAnimations(data);
        animationFile.sourceJson = data;
        animationFile.fileHash = sha256Hex(data);
        animationFile.animType = getAnimTypeFromKey(key);
        this.model.mainEntity.animationFiles.put(key, animationFile);
    }

    private void validateMainPlayerModel() throws IOException {
        if (this.model.mainEntity.mainModel == null) {
            throw new IOException("OpenYSM model missing player main model");
        }
        if (this.model.mainEntity.armModel == null) {
            throw new IOException("OpenYSM model missing player arm model");
        }
        boolean hasPng = false;
        for (RawYsmModel.RawTexture texture : this.model.mainEntity.textures.values()) {
            // Accept PNG (2) and WebP (4). WebP is commonly produced by
            // third-party C++ .ysm→folder converters that re-encode textures
            // to WebP for smaller file size. The downstream adapter
            // (RawYsmModelAdapter.getLegacyTextureData) can decode WebP→PNG,
            // so there's no reason to reject WebP here.
            if (texture.data != null && (texture.imageFormat == 2 || texture.imageFormat == 4)) {
                hasPng = true;
                break;
            }
        }
        if (!hasPng) {
            throw new IOException("OpenYSM model requires at least one PNG or WebP player texture");
        }
    }

    /**
     * Fallback: if ysm.json does not define an extra_animation section, populate
     * extraAnimations from language file entries (old-style roulette).
     * Keys follow the pattern "properties.extra_animation.extraN" (extra0–extra7).
     * Uses the same locale preference as RawYsmModelAdapter.getExtraAnimationNames().
     */
    private void populateExtraAnimationsFromLang() {
        if (!this.model.properties.extraAnimations.isEmpty()) {
            return; // already populated from ysm.json
        }
        String[] locales = { "en_us", "en_US", "zh_cn", "zh_CN" };
        for (int i = 0; i < 8; i++) {
            String key = "properties.extra_animation.extra" + i;
            String value = null;
            // Try preferred locales first
            for (String locale : locales) {
                RawYsmModel.RawLanguageFile lang = this.model.languageFiles.get(locale);
                if (lang != null && lang.data.containsKey(key)) {
                    value = lang.data.get(key);
                    break;
                }
            }
            // Fallback: search all language files
            if (value == null) {
                for (RawYsmModel.RawLanguageFile lang : this.model.languageFiles.values()) {
                    if (lang.data.containsKey(key)) {
                        value = lang.data.get(key);
                        break;
                    }
                }
            }
            if (value != null) {
                this.model.properties.extraAnimations.put("extra" + i, value);
            }
        }
    }

    /**
     * If the language files contain a localized metadata.name, use it to
     * override the ysm.json name. This ensures the localized name is
     * included in the cached model data and sent to all clients regardless
     * of whether the client-side registerExtraWheel processes lang files.
     */
    private void populateMetadataFromLang() {
        if (this.model.languageFiles.isEmpty()) return;
        // Try zh_cn first, then en_us, then any
        String[] locales = { "zh_cn", "zh_CN", "en_us", "en_US" };
        for (String locale : locales) {
            RawYsmModel.RawLanguageFile lang = this.model.languageFiles.get(locale);
            if (lang != null && lang.data.containsKey("metadata.name")) {
                this.model.metadata.name = lang.data.get("metadata.name");
                return;
            }
        }
        // Fallback: search all language files
        for (RawYsmModel.RawLanguageFile lang : this.model.languageFiles.values()) {
            if (lang.data.containsKey("metadata.name")) {
                this.model.metadata.name = lang.data.get("metadata.name");
                return;
            }
        }
    }

    private RawYsmModel.RawImage parseImage(String name, byte[] data) throws IOException {
        ImageMeta meta = parseImageMeta(data);
        RawYsmModel.RawImage image = new RawYsmModel.RawImage();
        image.name = name;
        image.data = data;
        image.width = meta.width;
        image.height = meta.height;
        image.format = meta.format;
        image.unknownFlag = 1;
        image.isPng = meta.format == 2;
        return image;
    }

    private static ImageMeta parseImageMeta(byte[] data) throws IOException {
        int format = detectFormat(data);
        if (format == 2 && data.length >= 24) {
            int width = ((data[16] & 0xFF) << 24) | ((data[17] & 0xFF) << 16) | ((data[18] & 0xFF) << 8)
                | (data[19] & 0xFF);
            int height = ((data[20] & 0xFF) << 24) | ((data[21] & 0xFF) << 16) | ((data[22] & 0xFF) << 8)
                | (data[23] & 0xFF);
            return new ImageMeta(width, height, format);
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image != null) {
            return new ImageMeta(image.getWidth(), image.getHeight(), format);
        }
        return new ImageMeta(0, 0, format);
    }

    public static int detectFormat(byte[] data) {
        if (data == null || data.length < 2) {
            return 0;
        }
        if (data.length >= 2 && data[0] == 0x42 && data[1] == 0x4D) {
            return 1;
        }
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E
            && data[3] == 0x47) {
            return 2;
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            return 3;
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
            && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return 4;
        }
        if (data.length >= 12 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p') {
            return 5;
        }
        return 0;
    }

    public static int getAnimTypeFromKey(String key) {
        if ("main".equals(key)) {
            return 1;
        }
        if ("arm".equals(key)) {
            return 2;
        }
        if ("extra".equals(key)) {
            return 3;
        }
        if ("tac".equals(key)) {
            return 4;
        }
        if ("arrow".equals(key)) {
            return 5;
        }
        if ("carryon".equals(key)) {
            return 6;
        }
        if ("parcool".equals(key)) {
            return 7;
        }
        if ("slashblade".equals(key)) {
            return 9;
        }
        if ("tlm".equals(key)) {
            return 10;
        }
        if ("fp_arm".equals(key)) {
            return 11;
        }
        if ("immersive_melodies".equals(key)) {
            return 12;
        }
        if ("irons_spell_books".equals(key) || "iss".equals(key)) {
            return 13;
        }
        return 0;
    }

    public static String getAnimKeyFromType(int type) {
        switch (type) {
            case 1:
                return "main";
            case 2:
                return "arm";
            case 3:
                return "extra";
            case 4:
                return "tac";
            case 5:
                return "arrow";
            case 6:
                return "carryon";
            case 7:
                return "parcool";
            case 9:
                return "slashblade";
            case 10:
                return "tlm";
            case 11:
                return "fp_arm";
            case 12:
                return "immersive_melodies";
            case 13:
                return "irons_spell_books";
            default:
                return "unknown_" + type;
        }
    }

    public static boolean isModelFolder(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        if (Files.isRegularFile(dir.resolve(YSM_JSON))) {
            return true;
        }
        return Files.isRegularFile(dir.resolve(MAIN_JSON)) && Files.isRegularFile(dir.resolve(ARM_JSON));
    }

    private static JsonObject findGeometryDescription(JsonObject root) {
        JsonObject geometry = findFirstGeometry(root);
        if (geometry != null && hasObject(geometry, "description")) {
            return geometry.getAsJsonObject("description");
        }
        return null;
    }

    private static JsonArray findGeometryBones(JsonObject root) {
        JsonObject geometry = findFirstGeometry(root);
        if (geometry != null && hasArray(geometry, "bones")) {
            return geometry.getAsJsonArray("bones");
        }
        return null;
    }

    private static JsonObject findFirstGeometry(JsonObject root) {
        if (hasArray(root, "minecraft:geometry")) {
            JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
            for (JsonElement elem : geometries) {
                if (elem.isJsonObject()) {
                    return elem.getAsJsonObject();
                }
            }
        }
        return root;
    }

    private static JsonObject parseObject(byte[] data, String sourceName) {
        JsonElement element = new JsonParser().parse(new String(data, StandardCharsets.UTF_8));
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Expected JSON object in " + sourceName);
        }
        return element.getAsJsonObject();
    }

    private static String[] readStringArray(JsonElement elem) {
        if (elem.isJsonArray()) {
            JsonArray array = elem.getAsJsonArray();
            String[] values = new String[array.size()];
            for (int i = 0; i < array.size(); i++) {
                values[i] = array.get(i).getAsString();
            }
            return values;
        }
        return new String[] { elem.getAsString() };
    }

    private static Iterable<JsonElement> asIterable(JsonElement elem) {
        if (elem == null || elem.isJsonNull()) {
            return Collections.emptyList();
        }
        if (elem.isJsonArray()) {
            return elem.getAsJsonArray();
        }
        return Collections.singletonList(elem);
    }

    private static <T> Iterable<T> iterable(final Stream<T> stream) {
        return stream::iterator;
    }

    private static void copyStringMap(JsonObject source, Map<String, String> target) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (!entry.getValue().isJsonNull()) {
                target.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
    }

    private static void addStringArray(JsonArray array, List<String> target) {
        for (JsonElement elem : array) {
            if (!elem.isJsonNull()) {
                target.add(elem.getAsString());
            }
        }
    }

    private static float[] getFloatArray(JsonObject obj, String key, int size) {
        float[] values = new float[size];
        if (hasArray(obj, key)) {
            JsonArray array = obj.getAsJsonArray(key);
            for (int i = 0; i < Math.min(size, array.size()); i++) {
                values[i] = array.get(i).getAsFloat();
            }
        }
        return values;
    }

    private static String parseLocale(String relativePath) {
        String name = relativePath.substring("lang/".length());
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - ".json".length());
        }
        return name;
    }

    private static String extractFileName(String fullPath) {
        String fileName = extractFileNameWithExtension(fullPath);
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String extractFileNameWithExtension(String fullPath) {
        String name = normalizeResourcePath(fullPath);
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static boolean hasObject(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonObject();
    }

    private static boolean hasArray(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonArray();
    }

    private static String getStr(JsonObject obj, String key, String def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : def;
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsBoolean() : def;
    }

    private static double getDouble(JsonObject obj, String key, double def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsDouble() : def;
    }

    private String calculateFinalFolderHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            for (Map.Entry<String, String> entry : this.readFilesMd5Map.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    /**
     * 计算文件夹模型的「内容指纹」：遍历目录内全部文件，按（相对路径, 文件 md5）
     * 聚合为一个 MD5。用于服务端重建在反序列化之前判定模型是否变化
     * （见 {@code ModelIndexCache}）。与 {@link #calculateFinalFolderHash} 同算法，
     * 但覆盖全部文件（更保守：任何文件变化都会触发重建）。
     */
    public static String computeFolderHash(Path dir) {
        try {
            Map<String, String> md5s = new TreeMap<>();
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> stream = Files.walk(dir)) {
                    for (Path path : iterable(stream)) {
                        if (!Files.isRegularFile(path)) {
                            continue;
                        }
                        String rel = normalizeResourcePath(dir.relativize(path).toString());
                        md5s.put(rel, md5Hex(Files.readAllBytes(path)));
                    }
                }
            }
            MessageDigest digest = MessageDigest.getInstance("MD5");
            for (Map.Entry<String, String> entry : md5s.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
            return toHex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static String md5Hex(byte[] data) {
        return digestHex("MD5", data);
    }

    private static String sha256Hex(byte[] data) {
        return digestHex("SHA-256", data);
    }

    private static String digestHex(String algorithm, byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return toHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " digest is not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] output = new char[bytes.length * 2];
        char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            output[i * 2] = hex[value >>> 4];
            output[i * 2 + 1] = hex[value & 0x0F];
        }
        return new String(output);
    }

    private static final class ImageMeta {
        private final int width;
        private final int height;
        private final int format;

        private ImageMeta(int width, int height, int format) {
            this.width = width;
            this.height = height;
            this.format = format;
        }
    }
}
