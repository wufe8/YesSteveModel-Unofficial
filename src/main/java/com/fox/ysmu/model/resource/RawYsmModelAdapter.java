package com.fox.ysmu.model.resource;

import static com.fox.ysmu.model.ServerModelManager.ARM_ANIMATION_FILE_NAME;
import static com.fox.ysmu.model.ServerModelManager.BUILT;
import static com.fox.ysmu.model.ServerModelManager.CUSTOM;
import static com.fox.ysmu.model.ServerModelManager.EXTRA_ANIMATION_FILE_NAME;
import static com.fox.ysmu.model.ServerModelManager.MAIN_ANIMATION_FILE_NAME;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MediaTracker;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.vecmath.Vector3f;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.ysmu;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import rip.ysm.imagestream.webp.WebpDecoder;

public final class RawYsmModelAdapter {

    private static final byte[] EMPTY_ANIMATION = "{\"animations\":{}}".getBytes(StandardCharsets.UTF_8);
    private static final String ANIMATION_FORMAT_VERSION = "1.8.0";
    private static final int RGBA_FORMAT = -1;
    private static final int PNG_FORMAT = 2;
    private static final int EXTRA_ANIMATION_SLOT_COUNT = 8;
    private static final String[] LOCALE_PREFERENCE = new String[] { "en_us", "en_US", "zh_cn", "zh_CN" };

    private RawYsmModelAdapter() {}

    public static boolean isBridgeable(RawYsmModel raw) {
        if (raw == null) {
            if (Config.DEBUG_MODEL_LOAD) ysmu.LOG.info("[YSMU-MODEL] isBridgeable false: raw is null");
            return false;
        }
        if (raw.mainEntity == null) {
            if (Config.DEBUG_MODEL_LOAD) ysmu.LOG.info("[YSMU-MODEL] isBridgeable false: mainEntity is null for {}", raw.modelId);
            return false;
        }
        if (!hasGeometry(raw.mainEntity.mainModel)) {
            if (Config.DEBUG_MODEL_LOAD) ysmu.LOG.info("[YSMU-MODEL] isBridgeable false: no mainModel geometry for {}", raw.modelId);
            return false;
        }
        if (!hasGeometry(raw.mainEntity.armModel)) {
            if (Config.DEBUG_MODEL_LOAD) ysmu.LOG.info("[YSMU-MODEL] isBridgeable false: no armModel geometry for {}", raw.modelId);
            return false;
        }
        boolean hasTexture = false;
        for (RawYsmModel.RawTexture texture : raw.mainEntity.textures.values()) {
            boolean texOk = hasLegacyTextureData(texture);
            if (Config.DEBUG_MODEL_LOAD) {
                ysmu.LOG.info("[YSMU-MODEL]   check texture {}: format={}, dataLen={}, w={}, h={}, ok={}",
                    texture.name, texture.imageFormat,
                    texture.data == null ? 0 : texture.data.length,
                    texture.width, texture.height, texOk);
            }
            if (texOk) {
                hasTexture = true;
                break;
            }
        }
        if (!hasTexture) {
            ysmu.LOG.warn("isBridgeable false: no legacy-compatible texture for {} ({} textures checked)",
                raw.modelId, raw.mainEntity.textures.size());
            for (RawYsmModel.RawTexture texture : raw.mainEntity.textures.values()) {
                ysmu.LOG.warn("  texture {}: format={}, dataLen={}, w={}, h={}",
                    texture.name, texture.imageFormat,
                    texture.data == null ? 0 : texture.data.length,
                    texture.width, texture.height);
            }
        }
        return hasTexture;
    }

    public static ModelData toLegacyModelData(RawYsmModel raw, String modelId) throws IOException {
        if (!isBridgeable(raw)) {
            throw new IOException("RawYsmModel cannot be bridged to legacy ModelData");
        }

        Map<String, byte[]> model = new LinkedHashMap<>();
        model.put("main", toGeometryJson(raw, raw.mainEntity.mainModel, true));
        model.put("arm", toGeometryJson(raw, raw.mainEntity.armModel, false));

        // Include projectile sub-entity geometries (e.g. #arrow model from minecraft:arrow)
        for (Map.Entry<String, RawYsmModel.RawSubEntity> entry : raw.projectiles.entrySet()) {
            RawYsmModel.RawSubEntity sub = entry.getValue();
            if (sub.model == null) continue;
            String[] matchIds = sub.matchIds != null && sub.matchIds.length > 0
                ? sub.matchIds : new String[]{sub.identifier};
            for (String matchId : matchIds) {
                if (StringUtils.isBlank(matchId)) continue;
                try {
                    model.put("projectile_" + matchId, toGeometryJson(null, sub.model, false));
                } catch (Exception e) {
                    ysmu.LOG.warn("Failed to convert projectile geometry {} for model {}", matchId, modelId, e);
                }
            }
        }

        Map<String, byte[]> textures = new LinkedHashMap<>();
        // Author avatars are sometimes ALSO stored in the texture section of
        // binary .ysm files (same name as metadata avatar). Exclude them so they
        // don't show up as selectable player textures. Matching is by name, with
        // a byte-content fallback for .ysm files whose avatar name differs from
        // the texture name.
        java.util.Set<String> avatarNames = collectAvatarNames(raw);
        java.util.List<byte[]> avatarDataList = collectAvatarData(raw);
        if (Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.info("[YSMU-MODEL] avatar filter for model {}: names={}, textureCount={}",
                modelId, avatarNames, raw.mainEntity.textures.size());
        }
        for (RawYsmModel.RawTexture texture : raw.mainEntity.textures.values()) {
            if (texture.data == null) {
                continue;
            }
            boolean nameMatch = avatarNames.contains(texture.name);
            boolean contentMatch = matchesAvatarContent(texture.data, avatarDataList);
            if (nameMatch || contentMatch) {
                if (Config.DEBUG_MODEL_LOAD) {
                    ysmu.LOG.info("[YSMU-MODEL] Excluding avatar texture {} from model {} (name={}, content={})",
                        texture.name, modelId, nameMatch, contentMatch);
                }
                continue;
            }
            byte[] textureData = getLegacyTextureData(texture);
            if (textureData == null) {
                ysmu.LOG.warn(
                    "Skipping unsupported OpenYSM texture {} (format {}) in model {}",
                    textureName(texture),
                    texture.imageFormat,
                    modelId);
                continue;
            }
            String fileName = texture.sourceFileName;
            if (StringUtils.isBlank(fileName)) {
                fileName = texture.name.endsWith(".png") ? texture.name : texture.name + ".png";
            }
            textures.put(fileName, textureData);
        }
        // Include projectile sub-entity textures
        for (Map.Entry<String, RawYsmModel.RawSubEntity> entry : raw.projectiles.entrySet()) {
            RawYsmModel.RawSubEntity sub = entry.getValue();
            String[] matchIds = sub.matchIds != null && sub.matchIds.length > 0
                ? sub.matchIds : new String[]{sub.identifier};
            for (String matchId : matchIds) {
                if (StringUtils.isBlank(matchId)) continue;
                for (RawYsmModel.RawTexture tex : sub.textures.values()) {
                    if (tex.data == null) continue;
                    byte[] texData = getLegacyTextureData(tex);
                    if (texData == null) {
                        ysmu.LOG.warn("Skipping unsupported projectile texture {} for {}", tex.name, matchId);
                        continue;
                    }
                    String texKey = "projectile_" + matchId + "_" + tex.name;
                    if (!texKey.endsWith(".png")) texKey += ".png";
                    textures.put(texKey, texData);
                }
            }
        }
        if (textures.isEmpty()) {
            throw new IOException("RawYsmModel has no legacy-compatible player textures");
        }

        Map<String, byte[]> animations = new LinkedHashMap<>();
        putAnimation(animations, raw, "main", MAIN_ANIMATION_FILE_NAME);
        putAnimation(animations, raw, "arm", ARM_ANIMATION_FILE_NAME);
        putAnimation(animations, raw, "extra", EXTRA_ANIMATION_FILE_NAME);
        for (Map.Entry<String, RawYsmModel.RawAnimationFile> entry : raw.mainEntity.animationFiles.entrySet()) {
            if (!animations.containsKey(entry.getKey())) {
                putAnimationFile(animations, entry.getKey(), entry.getValue());
            }
        }
        // Note: projectile animation/controller files are deliberately NOT merged here.
        // They will be registered separately when the full projectile entity system renders them.
        putAnimationControllers(animations, raw);
        putMolangFunctions(animations, raw);

        return new ModelData(modelId, Type.FOLDER, model, textures, animations);
    }

    /**
     * Collects the names of author avatar images so they can be excluded from the
     * selectable texture list. Binary .ysm files often store the author avatar in
     * the texture section (same name as the metadata avatar), which would otherwise
     * appear as a bogus texture option in the GUI.
     */
    private static java.util.Set<String> collectAvatarNames(RawYsmModel raw) {
        java.util.Set<String> names = new java.util.HashSet<>();
        if (raw.metadata == null) {
            return names;
        }
        for (RawYsmModel.RawMetadata.Author author : raw.metadata.authors) {
            if (author == null) continue;
            // Author display name — the avatar texture in the texture section is
            // usually named exactly after the author (e.g. "Almeta_owx").
            addNameVariants(names, author.name);
            // Author avatar RawImage.name
            if (author.avatarImage != null) {
                addNameVariants(names, author.avatarImage.name);
            }
            // Path form e.g. "avatar/Almeta_owx.png" → basename variants
            if (!StringUtils.isBlank(author.avatar)) {
                addNameVariants(names, org.apache.commons.io.FilenameUtils.getName(author.avatar));
            }
        }
        // extraAvatars (RawImage.name)
        for (RawYsmModel.RawImage img : raw.metadata.extraAvatars) {
            if (img != null) {
                addNameVariants(names, img.name);
            }
        }
        return names;
    }

    /** Adds a name plus its ".png"-suffixed / stripped variants so matching is
     *  robust against extension differences. */
    private static void addNameVariants(java.util.Set<String> names, String name) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        names.add(name);
        if (name.endsWith(".png")) {
            names.add(name.substring(0, name.length() - 4));
        } else {
            names.add(name + ".png");
        }
    }

    /** Collects the raw byte content of author avatars for content-based matching. */
    private static java.util.List<byte[]> collectAvatarData(RawYsmModel raw) {
        java.util.List<byte[]> data = new java.util.ArrayList<>();
        if (raw.metadata == null) {
            return data;
        }
        for (RawYsmModel.RawMetadata.Author author : raw.metadata.authors) {
            if (author != null && author.avatarImage != null && author.avatarImage.data != null) {
                data.add(author.avatarImage.data);
            }
        }
        for (RawYsmModel.RawImage img : raw.metadata.extraAvatars) {
            if (img != null && img.data != null) {
                data.add(img.data);
            }
        }
        return data;
    }

    /** True if the given texture bytes are byte-identical to any author avatar image. */
    private static boolean matchesAvatarContent(byte[] textureData, java.util.List<byte[]> avatarDataList) {
        if (textureData == null || avatarDataList == null || avatarDataList.isEmpty()) {
            return false;
        }
        for (byte[] avatar : avatarDataList) {
            if (avatar != null && avatar.length == textureData.length
                && java.util.Arrays.equals(avatar, textureData)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasGeometry(RawYsmModel.RawGeometry geometry) {
        if (geometry == null) {
            return false;
        }
        if (geometry.sourceJson != null) {
            return true;
        }
        for (RawYsmModel.RawBone bone : geometry.bones) {
            for (RawYsmModel.RawCube cube : bone.cubes) {
                if (!cube.faces.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasLegacyTextureData(RawYsmModel.RawTexture texture) {
        if (texture == null || texture.data == null) {
            if (Config.DEBUG_MODEL_LOAD) ysmu.LOG.info("[YSMU-MODEL]   hasLegacyTextureData: texture null or data null");
            return false;
        }
        // PNG 可直接使用
        if (texture.imageFormat == PNG_FORMAT) {
            if (Config.DEBUG_MODEL_LOAD) ysmu.LOG.info("[YSMU-MODEL]   hasLegacyTextureData: PNG format, OK");
            return true;
        }
        // Raw RGBA 且数据尺寸足够
        if (texture.imageFormat == RGBA_FORMAT && canConvertRgba(texture)) {
            if (Config.DEBUG_MODEL_LOAD) ysmu.LOG.info("[YSMU-MODEL]   hasLegacyTextureData: RGBA format, OK");
            return true;
        }
        // WebP（format=4）：尝试用 WebpDecoder 实际解码
        if (texture.imageFormat == 4) {
            boolean ok = tryDecodeWebp(texture.data);
            if (Config.DEBUG_MODEL_LOAD) ysmu.LOG.info("[YSMU-MODEL]   hasLegacyTextureData: WebP format, tryDecodeWebp={}", ok);
            return ok;
        }
        // 其他格式：尝试用 ImageIO 解码
        if (Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.info("[YSMU-MODEL]   hasLegacyTextureData: unknown format={}, trying ImageIO...", texture.imageFormat);
        }
        BufferedImage img = readImageToBufferedImage(texture.data);
        if (Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.info("[YSMU-MODEL]   hasLegacyTextureData: ImageIO result={}", img != null);
        }
        return img != null;
    }

    public static byte[] getLegacyTextureData(RawYsmModel.RawTexture texture) {
        if (texture.imageFormat == PNG_FORMAT) {
            if (Config.DEBUG_MODEL_LOAD) ysmu.LOG.info("[YSMU-MODEL]   getLegacyTextureData: PNG, returning as-is ({} bytes)", texture.data.length);
            return texture.data;
        }
        if (texture.imageFormat == RGBA_FORMAT && canConvertRgba(texture)) {
            if (Config.DEBUG_MODEL_LOAD) ysmu.LOG.info("[YSMU-MODEL]   getLegacyTextureData: RGBA->PNG ({}x{})", texture.width, texture.height);
            return convertRgbaToPng(texture.data, texture.width, texture.height);
        }
        // WebP（format=4）：用 WebpDecoder 解码后转 PNG
        if (texture.imageFormat == 4) {
            if (Config.DEBUG_MODEL_LOAD) ysmu.LOG.info("[YSMU-MODEL]   getLegacyTextureData: WebP->PNG decode ({} bytes)", texture.data.length);
            BufferedImage webpImage = decodeWebpToImage(texture.data);
            if (webpImage != null) {
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (ImageIO.write(webpImage, "PNG", output)) {
                        if (Config.DEBUG_MODEL_LOAD) ysmu.LOG.info("[YSMU-MODEL]   getLegacyTextureData: WebP->PNG success, {} bytes", output.size());
                        return output.toByteArray();
                    }
                } catch (Exception ignored) {}
            }
            ysmu.LOG.warn("Failed to decode WebP texture {} ({}x{}, {} bytes)",
                texture.name, texture.width, texture.height, texture.data.length);
            return null;
        }
        // 尝试用 ImageIO 解码（支持 PNG/JPEG/BMP，不保证 WebP）
        if (Config.DEBUG_MODEL_LOAD) {
            ysmu.LOG.info("[YSMU-MODEL]   getLegacyTextureData: format={}, trying ImageIO fallback...", texture.imageFormat);
        }
        BufferedImage image = readImageToBufferedImage(texture.data);
        if (image != null) {
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (ImageIO.write(image, "PNG", output)) {
                    if (Config.DEBUG_MODEL_LOAD) ysmu.LOG.info("[YSMU-MODEL]   getLegacyTextureData: ImageIO fallback success, {} bytes", output.size());
                    return output.toByteArray();
                }
            } catch (Exception ignored) {}
        }
        ysmu.LOG.warn("Failed to decode texture {} (format={}, {} bytes, {}x{}): unsupported format",
            texture.name, texture.imageFormat, texture.data.length, texture.width, texture.height);
        return null;
    }

    /** 尝试用多种方式将字节数据解码为 BufferedImage。 */
    private static BufferedImage readImageToBufferedImage(byte[] data) {
        // 1. ImageIO（标准方式，支持 PNG/JPEG/BMP/GIF）
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img != null) return img;
        } catch (Exception ignored) {}

        // 2. 写临时文件 + ImageIO.read(File)
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("ysmu_", ".png");
            Files.write(tempFile, data);
            BufferedImage img = ImageIO.read(tempFile.toFile());
            if (img != null) return img;
        } catch (Exception ignored) {} finally {
            try { if (tempFile != null) Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
        }

        // 3. 写 .webp 临时文件 + Toolkit.createImage(String)
        try {
            tempFile = Files.createTempFile("ysmu_", ".webp");
            Files.write(tempFile, data);
            Image tkImg = Toolkit.getDefaultToolkit().createImage(tempFile.toAbsolutePath().toString());
            if (tkImg != null) {
                MediaTracker tracker = new MediaTracker(new java.awt.Canvas());
                tracker.addImage(tkImg, 0);
                try { tracker.waitForID(0); } catch (InterruptedException ignored) {}
                if (tkImg.getWidth(null) > 0 && tkImg.getHeight(null) > 0) {
                    BufferedImage bi = new BufferedImage(
                        tkImg.getWidth(null), tkImg.getHeight(null),
                        BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = bi.createGraphics();
                    g.drawImage(tkImg, 0, 0, null);
                    g.dispose();
                    return bi;
                }
            }
        } catch (Exception ignored) {} finally {
            try { if (tempFile != null) Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
        }

        return null;
    }

    /** 尝试用 WebpDecoder 解码 WebP 数据，返回是否可解码。 */
    private static boolean tryDecodeWebp(byte[] data) {
        try {
            BufferedImage img = new WebpDecoder().read(data);
            return img != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 用 WebpDecoder 解码 WebP 数据为 BufferedImage，失败返回 null。 */
    private static BufferedImage decodeWebpToImage(byte[] data) {
        try {
            return new WebpDecoder().read(data);
        } catch (Exception e) {
            ysmu.LOG.warn("WebpDecoder failed for {} bytes", data.length, e);
            return null;
        }
    }

    private static boolean canConvertRgba(RawYsmModel.RawTexture texture) {
        long requiredBytes = (long) texture.width * texture.height * 4L;
        return texture.width > 0 && texture.height > 0 && texture.data.length >= requiredBytes;
    }

    private static byte[] convertRgbaToPng(byte[] rgbaData, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int r = rgbaData[i * 4] & 0xFF;
            int g = rgbaData[i * 4 + 1] & 0xFF;
            int b = rgbaData[i * 4 + 2] & 0xFF;
            int a = rgbaData[i * 4 + 3] & 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (ImageIO.write(image, "PNG", output)) {
                return output.toByteArray();
            }
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to convert OpenYSM RGBA texture to PNG", e);
        }
        return null;
    }

    public static byte[] toGeometryJson(RawYsmModel raw, RawYsmModel.RawGeometry geometry, boolean includeModelInfo)
        throws IOException {
        JsonObject root;
        if (geometry.sourceJson != null) {
            root = parseJsonObject(geometry.sourceJson, geometry.identifier);
        } else {
            root = createGeometryJson(geometry);
        }

        // Remove faces with zero uv_size — a common modelling technique to
        // hide faces. GeckoLib's GeoQuad does not handle zero-area UVs; they
        // render as a single-texel sample (usually black).
        sanitizeGeometryJson(root);

        JsonObject description = getOrCreateDescription(root);
        if (includeModelInfo) {
            applyOpenYsmModelInfo(raw, description);
        }
        return ysmu.GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Sanitises geometry JSON in-place:
     * 1. Removes per-face UV entries where uv_size has a zero dimension.
     * 2. Normalises negative cube sizes (BlockBench allows negative sizes to
     *    indicate the cube extends from origin in the opposite direction;
     *    GeckoLib's vertex winding assumes positive size).
     */
    private static void sanitizeGeometryJson(JsonObject root) {
        JsonElement geomElem = root.get("minecraft:geometry");
        if (geomElem == null || !geomElem.isJsonArray()) return;
        int totalRemovedFaces = 0;
        int totalFaces = 0;
        int totalNegSizeCubes = 0;
        for (JsonElement entry : geomElem.getAsJsonArray()) {
            if (!entry.isJsonObject()) continue;
            JsonObject model = entry.getAsJsonObject();
            JsonElement bonesElem = model.get("bones");
            if (bonesElem == null || !bonesElem.isJsonArray()) continue;
            for (JsonElement boneElem : bonesElem.getAsJsonArray()) {
                if (!boneElem.isJsonObject()) continue;
                JsonObject bone = boneElem.getAsJsonObject();
                JsonElement cubesElem = bone.get("cubes");
                if (cubesElem == null || !cubesElem.isJsonArray()) continue;
                for (JsonElement cubeElem : cubesElem.getAsJsonArray()) {
                    if (!cubeElem.isJsonObject()) continue;
                    JsonObject cube = cubeElem.getAsJsonObject();

                    // ── Keep negative cube sizes ──────────────────────────────
                    // We intentionally do NOT normalise negative sizes here.
                    // Negative-size cubes in BlockBench represent inside-out shells
                    // (hollow/void areas). GeoCube.createFromPojoCube() handles
                    // them by abs-ing the size, adjusting the origin, and setting
                    // hasNegSize. The renderer then uses CULL_FRONT to render only
                    // back faces (facing away from camera), writing far-side depth,
                    // so the paired positive cube renders on top at the centre while
                    // the negative cube's edge forms a glow ring.
                    // If we normalised to positive here, the cube would be solid
                    // and occlude the inner positive cube entirely.
                    // ── Count for debug logging ────────────────────────────────
                    JsonElement sizeElem = cube.get("size");
                    boolean negSizeFixed = false;
                    if (sizeElem != null && sizeElem.isJsonArray()
                        && sizeElem.getAsJsonArray().size() >= 3) {
                        JsonArray sizeArr = sizeElem.getAsJsonArray();
                        double sx = sizeArr.get(0).getAsDouble();
                        double sy = sizeArr.get(1).getAsDouble();
                        double sz = sizeArr.get(2).getAsDouble();
                        if (sx < 0 || sy < 0 || sz < 0) {
                            negSizeFixed = true;
                        }
                    }
                    if (negSizeFixed) totalNegSizeCubes++;

                    // ── Remove zero-size UV faces ──────────────────────────────
                    JsonElement uvElem = cube.get("uv");
                    if (uvElem == null || !uvElem.isJsonObject()) continue;
                    JsonObject uvObj = uvElem.getAsJsonObject();
                    java.util.List<String> toRemove = new java.util.ArrayList<>();
                    for (java.util.Map.Entry<String, JsonElement> faceEntry : uvObj.entrySet()) {
                        if (!faceEntry.getValue().isJsonObject()) continue;
                        totalFaces++;
                        JsonObject face = faceEntry.getValue().getAsJsonObject();
                        JsonElement faceSizeElem = face.get("uv_size");
                        if (faceSizeElem == null || !faceSizeElem.isJsonArray()
                            || faceSizeElem.getAsJsonArray().size() < 2) continue;
                        JsonArray uvSize = faceSizeElem.getAsJsonArray();
                        double w = uvSize.get(0).getAsDouble();
                        double h = uvSize.get(1).getAsDouble();
                        if (w == 0.0d || h == 0.0d) {
                            toRemove.add(faceEntry.getKey());
                        }
                    }
                    totalRemovedFaces += toRemove.size();
                    for (String faceName : toRemove) {
                        uvObj.remove(faceName);
                    }
                }
            }
        }
        if (Config.DEBUG_MODEL_LOAD) {
            if (totalRemovedFaces > 0) {
                ysmu.LOG.info("[YSMU-MODEL] sanitizeGeometryJson: removed {}/{} zero-uv faces",
                    totalRemovedFaces, totalFaces);
            }
            if (totalNegSizeCubes > 0) {
                ysmu.LOG.info("[YSMU-MODEL] sanitizeGeometryJson: normalised {} negative-size cubes",
                    totalNegSizeCubes);
            }
        }
    }

    private static JsonObject parseJsonObject(byte[] bytes, String sourceName) throws IOException {
        JsonElement element;
        try {
            element = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("Invalid geometry JSON " + sourceName, e);
        }
        if (element == null || !element.isJsonObject()) {
            throw new IOException("Expected geometry JSON object " + sourceName);
        }
        return element.getAsJsonObject();
    }

    private static JsonObject createGeometryJson(RawYsmModel.RawGeometry geometry) {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.12.0");
        JsonArray geometries = new JsonArray();
        JsonObject model = new JsonObject();
        JsonObject description = new JsonObject();
        description.addProperty(
            "identifier",
            StringUtils.defaultIfBlank(geometry.identifier, "geometry.ysmu.generated"));
        description.addProperty("texture_width", (double) defaultPositive(geometry.textureWidth, 64f));
        description.addProperty("texture_height", (double) defaultPositive(geometry.textureHeight, 64f));
        if (geometry.visibleBoundsWidth > 0f) {
            description.addProperty("visible_bounds_width", (double) geometry.visibleBoundsWidth);
        }
        if (geometry.visibleBoundsHeight > 0f) {
            description.addProperty("visible_bounds_height", (double) geometry.visibleBoundsHeight);
        }
        if (geometry.visibleBoundsOffset != null && geometry.visibleBoundsOffset.length > 0) {
            description.add("visible_bounds_offset", floatArray(geometry.visibleBoundsOffset));
        }
        model.add("description", description);

        JsonArray bones = new JsonArray();
        for (RawYsmModel.RawBone rawBone : geometry.bones) {
            bones.add(createBoneJson(rawBone, geometry));
        }
        model.add("bones", bones);
        geometries.add(model);
        root.add("minecraft:geometry", geometries);
        return root;
    }

    /**
     * Detects whether a RawCube represents a negative-volume (inside-out) shell.
     * Uses OpenYSM's two-phase detection:
     * Phase 1 — For each face, compute cross(e1, e2) · normal.
     *           If any face has dot < -1e-5f, the cube is negative volume.
     * Phase 2 — For pairs of faces with opposite normals (dot < -0.99f),
     *           check whether the face with the positive normal is geometrically
     *           closer to the origin than the face with the negative normal.
     *           If so, the cube is negative volume.
     */
    private static boolean isNegativeVolume(RawYsmModel.RawCube cube) {
        if (cube.faces.isEmpty()) return false;

        // Phase 1: per-face cross-product check
        for (RawYsmModel.RawFace face : cube.faces) {
            Vector3f v0 = new Vector3f(
                getVectorValue(face.positions[0], 0),
                getVectorValue(face.positions[0], 1),
                getVectorValue(face.positions[0], 2));
            Vector3f v1 = new Vector3f(
                getVectorValue(face.positions[1], 0),
                getVectorValue(face.positions[1], 1),
                getVectorValue(face.positions[1], 2));
            Vector3f v2 = new Vector3f(
                getVectorValue(face.positions[2], 0),
                getVectorValue(face.positions[2], 1),
                getVectorValue(face.positions[2], 2));
            Vector3f normal = new Vector3f(
                getVectorValue(face.normal, 0),
                getVectorValue(face.normal, 1),
                getVectorValue(face.normal, 2));
            Vector3f e1 = new Vector3f(v1);
            e1.sub(v0);
            Vector3f e2 = new Vector3f(v2);
            e2.sub(v1);
            Vector3f cross = new Vector3f();
            cross.cross(e1, e2);
            if (cross.dot(normal) < -1e-5f) {
                return true;
            }
        }

        // Phase 2: opposite-face position check
        int faceCount = cube.faces.size();
        for (int i = 0; i < faceCount; i++) {
            RawYsmModel.RawFace faceA = cube.faces.get(i);
            Vector3f normA = new Vector3f(
                getVectorValue(faceA.normal, 0),
                getVectorValue(faceA.normal, 1),
                getVectorValue(faceA.normal, 2));
            for (int j = i + 1; j < faceCount; j++) {
                RawYsmModel.RawFace faceB = cube.faces.get(j);
                Vector3f normB = new Vector3f(
                    getVectorValue(faceB.normal, 0),
                    getVectorValue(faceB.normal, 1),
                    getVectorValue(faceB.normal, 2));
                if (normA.dot(normB) < -0.99f) {
                    Vector3f centerA = getFaceCenter(faceA);
                    Vector3f centerB = getFaceCenter(faceB);
                    Vector3f diff = new Vector3f(centerA);
                    diff.sub(centerB);
                    if (diff.dot(normA) < -1e-5f) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Computes the average of the 4 vertex positions of a face. */
    private static Vector3f getFaceCenter(RawYsmModel.RawFace face) {
        float cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < 4; i++) {
            cx += getVectorValue(face.positions[i], 0);
            cy += getVectorValue(face.positions[i], 1);
            cz += getVectorValue(face.positions[i], 2);
        }
        return new Vector3f(cx / 4f, cy / 4f, cz / 4f);
    }

    private static JsonObject createBoneJson(RawYsmModel.RawBone rawBone, RawYsmModel.RawGeometry geometry) {
        JsonObject bone = new JsonObject();
        bone.addProperty("name", StringUtils.defaultIfBlank(rawBone.name, "bone"));
        if (StringUtils.isNotBlank(rawBone.parentName)) {
            bone.addProperty("parent", rawBone.parentName);
        }
        bone.add("pivot", generatedPivotArray(rawBone.pivot));
        bone.add("rotation", generatedRotationArray(rawBone.rotation));

        // Separate cubes into positive and negative volume groups.
        // Negative-volume cubes (inside-out shells) need a separate poly_mesh
        // so GeoBuilder can create a GeoCube with hasNegSize=true, enabling
        // the two-pass CULL_FRONT rendering for the outline effect.
        List<RawYsmModel.RawCube> posCubes = new ArrayList<>();
        List<RawYsmModel.RawCube> negCubes = new ArrayList<>();
        for (RawYsmModel.RawCube rawCube : rawBone.cubes) {
            if (isNegativeVolume(rawCube)) {
                negCubes.add(rawCube);
            } else {
                posCubes.add(rawCube);
            }
        }

        JsonObject polyMesh = createPolyMeshFromCubes(posCubes, false);
        if (polyMesh != null) {
            bone.add("poly_mesh", polyMesh);
        }
        // Additional poly_mesh for negative-volume cubes — GeoBuilder will
        // create a second GeoCube with hasNegSize=true from this field.
        // Vertex winding is reversed because BlockBench negative-size cubes
        // have CW winding; reversing to standard CCW makes CULL_FRONT
        // correctly show only the far-side faces (outline effect).
        JsonObject negMesh = createPolyMeshFromCubes(negCubes, true);
        if (negMesh != null) {
            bone.add("__ysm_neg_mesh", negMesh);
        }
        return bone;
    }

    /** Builds a poly_mesh JSON object from a list of RawCubes.
     * @param reverseWinding if true, reverses vertex winding (swaps v2↔v3)
     *        so quads become standard CCW. Needed for negative-volume cubes
     *        whose BlockBench faces have CW winding from the .ysm exporter. */
    private static JsonObject createPolyMeshFromCubes(List<RawYsmModel.RawCube> cubes, boolean reverseWinding) {
        JsonArray positions = new JsonArray();
        JsonArray normals = new JsonArray();
        JsonArray uvs = new JsonArray();

        // Vertex index order for each quad: normal=[0,1,2,3], reversed=[0,3,2,1]
        // [0,3,2,1] walks the circular order backwards (v0→v3→v2→v1), reversing
        // winding from CW→CCW. Both GL_QUADS decomposition triangles become CCW.
        int[] order = reverseWinding ? new int[] { 0, 3, 2, 1 } : new int[] { 0, 1, 2, 3 };

        for (RawYsmModel.RawCube rawCube : cubes) {
            for (RawYsmModel.RawFace face : rawCube.faces) {
                for (int idx = 0; idx < 4; idx++) {
                    int i = order[idx];
                    float[] position = face.positions[i];
                    positions.add(new JsonPrimitive((double) getVectorValue(position, 0)));
                    positions.add(new JsonPrimitive((double) getVectorValue(position, 1)));
                    positions.add(new JsonPrimitive((double) getVectorValue(position, 2)));

                    normals.add(new JsonPrimitive((double) getVectorValue(face.normal, 0)));
                    normals.add(new JsonPrimitive((double) getVectorValue(face.normal, 1)));
                    normals.add(new JsonPrimitive((double) getVectorValue(face.normal, 2)));

                    uvs.add(new JsonPrimitive((double) face.u[i]));
                    uvs.add(new JsonPrimitive((double) face.v[i]));
                }
            }
        }

        if (positions.size() == 0) {
            return null;
        }

        JsonObject mesh = new JsonObject();
        mesh.addProperty("normalized_uvs", true);
        mesh.addProperty("polys", "quad_list");
        mesh.add("positions", positions);
        mesh.add("normals", normals);
        mesh.add("uvs", uvs);
        return mesh;
    }

    private static JsonObject getOrCreateDescription(JsonObject root) {
        JsonObject geometry = getOrCreateGeometry(root);
        JsonObject description;
        if (geometry.has("description") && geometry.get("description").isJsonObject()) {
            description = geometry.getAsJsonObject("description");
        } else {
            description = new JsonObject();
            geometry.add("description", description);
        }
        return description;
    }

    private static JsonObject getOrCreateGeometry(JsonObject root) {
        JsonArray geometries;
        if (root.has("minecraft:geometry") && root.get("minecraft:geometry").isJsonArray()) {
            geometries = root.getAsJsonArray("minecraft:geometry");
        } else {
            geometries = new JsonArray();
            root.add("minecraft:geometry", geometries);
        }
        if (geometries.size() > 0 && geometries.get(0).isJsonObject()) {
            return geometries.get(0).getAsJsonObject();
        }
        JsonObject geometry = new JsonObject();
        geometries.add(geometry);
        return geometry;
    }

    private static void applyOpenYsmModelInfo(RawYsmModel raw, JsonObject description) {
        description.addProperty("ysm_height_scale", (double) raw.properties.heightScale);
        description.addProperty("ysm_width_scale", (double) raw.properties.widthScale);

        JsonObject extraInfo = new JsonObject();
        extraInfo.addProperty("name", raw.metadata.name);
        extraInfo.addProperty("tips", raw.metadata.tips);
        String[] extraAnimationNames = getExtraAnimationNames(raw);
        if (extraAnimationNames.length > 0) {
            extraInfo.add("extra_animation_names", stringArray(extraAnimationNames));
        }

        List<String> authors = new ArrayList<>();
        for (RawYsmModel.RawMetadata.Author author : raw.metadata.authors) {
            if (StringUtils.isBlank(author.name)) {
                continue;
            }
            if (StringUtils.isBlank(author.role)) {
                authors.add(author.name);
            } else {
                authors.add(author.name + " (" + author.role + ")");
            }
        }
        if (!authors.isEmpty()) {
            extraInfo.add("authors", stringArray(authors.toArray(new String[0])));
        }

        String license = StringUtils.defaultIfBlank(raw.metadata.licenseType, raw.metadata.licenseDescription);
        if (StringUtils.isNotBlank(license)) {
            extraInfo.addProperty("license", license);
        }
        description.add("ysm_extra_info", extraInfo);
    }

    private static String[] getExtraAnimationNames(RawYsmModel raw) {
        // If extraAnimations is empty, attempt fallback from language files (old-style roulette)
        if (raw.properties.extraAnimations.isEmpty()) {
            String[] fallback = getExtraAnimationNamesFromLang(raw);
            if (fallback.length > 0) {
                return fallback;
            }
            return new String[0];
        }
        String[] names = new String[EXTRA_ANIMATION_SLOT_COUNT];
        boolean hasAny = false;
        for (int i = 0; i < EXTRA_ANIMATION_SLOT_COUNT; i++) {
            String key = "extra" + i;
            if (!raw.properties.extraAnimations.containsKey(key)) {
                continue;
            }
            String label = getLocalizedValue(raw, "properties.extra_animation." + key);
            if (StringUtils.isBlank(label)) {
                String configured = raw.properties.extraAnimations.get(key);
                if (StringUtils.isNotBlank(configured) && !configured.startsWith("#")) {
                    label = configured;
                }
            }
            if (StringUtils.isBlank(label)) {
                label = key;
            }
            names[i] = label;
            hasAny = true;
        }
        return hasAny ? names : new String[0];
    }

    /**
     * Fallback: read extra animation names directly from language files when
     * extraAnimations is empty (old-style models that only define names in lang).
     */
    private static String[] getExtraAnimationNamesFromLang(RawYsmModel raw) {
        String[] names = new String[EXTRA_ANIMATION_SLOT_COUNT];
        boolean hasAny = false;
        for (int i = 0; i < EXTRA_ANIMATION_SLOT_COUNT; i++) {
            String lookupKey = "properties.extra_animation.extra" + i;
            String label = getLocalizedValue(raw, lookupKey);
            if (StringUtils.isNotBlank(label)) {
                names[i] = label;
                hasAny = true;
            }
        }
        return hasAny ? names : new String[0];
    }

    private static String getLocalizedValue(RawYsmModel raw, String key) {
        for (String locale : LOCALE_PREFERENCE) {
            RawYsmModel.RawLanguageFile file = raw.languageFiles.get(locale);
            if (file != null && file.data.containsKey(key)) {
                return file.data.get(key);
            }
        }
        for (RawYsmModel.RawLanguageFile file : raw.languageFiles.values()) {
            if (file.data.containsKey(key)) {
                return file.data.get(key);
            }
        }
        return "";
    }

    private static JsonArray stringArray(String[] values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(new JsonPrimitive(value == null ? "" : value));
        }
        return array;
    }

    private static JsonArray floatArray(float[] values) {
        JsonArray array = new JsonArray();
        if (values == null) {
            return array;
        }
        for (float value : values) {
            array.add(new JsonPrimitive((double) value));
        }
        return array;
    }

    private static JsonArray generatedPivotArray(float[] pivot) {
        float x = getVectorValue(pivot, 0);
        float y = getVectorValue(pivot, 1);
        float z = getVectorValue(pivot, 2);
        return doubleArray(-x, y, z);
    }

    private static JsonArray generatedRotationArray(float[] rotation) {
        float x = getVectorValue(rotation, 0);
        float y = getVectorValue(rotation, 1);
        float z = getVectorValue(rotation, 2);
        return doubleArray(-Math.toDegrees(x), -Math.toDegrees(y), Math.toDegrees(z));
    }

    private static float getVectorValue(float[] values, int index) {
        return values != null && values.length > index ? values[index] : 0f;
    }

    private static JsonArray doubleArray(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            array.add(new JsonPrimitive(value));
        }
        return array;
    }

    private static float defaultPositive(float value, float defaultValue) {
        return value > 0f ? value : defaultValue;
    }

    private static String textureName(RawYsmModel.RawTexture texture) {
        return StringUtils.defaultIfBlank(texture.sourceFileName, texture.name);
    }

    private static void putAnimation(Map<String, byte[]> animations, RawYsmModel raw, String key, String defaultFileName)
        throws IOException {
        if (putAnimationFile(animations, key, raw.mainEntity.animationFiles.get(key))) {
            return;
        }
        animations.put(key, readDefaultAnimation(defaultFileName));
    }

    private static boolean putAnimationFile(Map<String, byte[]> animations, String key,
        RawYsmModel.RawAnimationFile animationFile) {
        if (animationFile == null) {
            return false;
        }
        if (animationFile.sourceJson != null) {
            animations.put(key, animationFile.sourceJson);
            return true;
        }
        if (!animationFile.animations.isEmpty()) {
            animations.put(key, createAnimationJson(animationFile));
            return true;
        }
        return false;
    }

    public static byte[] createAnimationJson(RawYsmModel.RawAnimationFile animationFile) {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", ANIMATION_FORMAT_VERSION);
        JsonObject animations = new JsonObject();
        for (RawYsmModel.RawAnimation animation : animationFile.animations.values()) {
            if (StringUtils.isBlank(animation.name)) {
                continue;
            }
            animations.add(animation.name, createAnimationJson(animation));
        }
        root.add("animations", animations);
        return ysmu.GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject createAnimationJson(RawYsmModel.RawAnimation animation) {
        JsonObject json = new JsonObject();
        if (isFinite(animation.length) && animation.length > 0f) {
            json.addProperty("animation_length", (double) animation.length);
        }
        putLoopMode(json, animation.loopMode);
        if (animation.blendWeight != null) {
            json.add("blend_weight", molangValue(animation.blendWeight));
        }

        JsonObject bones = new JsonObject();
        for (RawYsmModel.RawBoneAnimation boneAnimation : animation.boneAnimations) {
            if (StringUtils.isBlank(boneAnimation.boneName)) {
                continue;
            }
            JsonObject bone = new JsonObject();
            putChannel(bone, "rotation", boneAnimation.rotation);
            putChannel(bone, "position", boneAnimation.position);
            putChannel(bone, "scale", boneAnimation.scale);
            if (!bone.entrySet().isEmpty()) {
                bones.add(boneAnimation.boneName, bone);
            }
        }
        if (!bones.entrySet().isEmpty()) {
            json.add("bones", bones);
        }

        putTimeline(json, animation.timelineEvents);
        putSoundEffects(json, animation.soundEffects);
        return json;
    }

    private static void putLoopMode(JsonObject json, int loopMode) {
        if (loopMode == 1) {
            json.addProperty("loop", true);
        } else if (loopMode == 3) {
            json.addProperty("loop", "hold_on_last_frame");
        } else if (loopMode == 0) {
            json.addProperty("loop", false);
        }
    }

    private static void putChannel(JsonObject bone, String channelName, List<RawYsmModel.RawKeyframe> keyframes) {
        if (keyframes.isEmpty()) {
            return;
        }
        if (keyframes.size() == 1) {
            RawYsmModel.RawKeyframe keyframe = keyframes.get(0);
            if (!keyframe.hasPreData && isZeroTime(keyframe.timestamp)) {
                bone.add(channelName, molangArray(keyframe.postData));
                return;
            }
        }
        JsonObject channel = new JsonObject();
        for (RawYsmModel.RawKeyframe keyframe : keyframes) {
            channel.add(timeKey(keyframe.timestamp), createKeyframeJson(keyframe));
        }
        bone.add(channelName, channel);
    }

    private static JsonElement createKeyframeJson(RawYsmModel.RawKeyframe keyframe) {
        String lerpMode = lerpMode(keyframe.interpolationMode);
        if (!keyframe.hasPreData) {
            JsonArray value = molangArray(keyframe.postData);
            if (StringUtils.isBlank(lerpMode)) {
                return value;
            }
            JsonObject json = new JsonObject();
            json.add("post", value);
            json.addProperty("lerp_mode", lerpMode);
            return json;
        }
        JsonObject json = new JsonObject();
        json.add("pre", molangArray(keyframe.preData));
        json.add("post", molangArray(keyframe.postData));
        if (StringUtils.isNotBlank(lerpMode)) {
            json.addProperty("lerp_mode", lerpMode);
        }
        return json;
    }

    private static String lerpMode(int interpolationMode) {
        if (interpolationMode == 1) {
            return "step";
        }
        if (interpolationMode == 2) {
            return "catmullrom";
        }
        return "";
    }

    private static JsonArray molangArray(Object[] values) {
        JsonArray array = new JsonArray();
        for (int i = 0; i < 3; i++) {
            array.add(molangValue(values != null && values.length > i ? values[i] : Float.valueOf(0f)));
        }
        return array;
    }

    private static JsonPrimitive molangValue(Object value) {
        if (value instanceof Number) {
            return new JsonPrimitive((Number) value);
        }
        if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        }
        return new JsonPrimitive(value == null ? "0" : value.toString());
    }

    private static void putTimeline(JsonObject animationJson, List<RawYsmModel.RawTimelineEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        JsonObject timeline = new JsonObject();
        for (RawYsmModel.RawTimelineEvent event : events) {
            if (event.events.isEmpty()) {
                continue;
            }
            String key = timeKey(event.timestamp);
            if (event.events.size() == 1 && !timeline.has(key)) {
                timeline.addProperty(key, event.events.get(0));
            } else {
                JsonArray array = timeline.has(key) && timeline.get(key).isJsonArray()
                    ? timeline.getAsJsonArray(key)
                    : new JsonArray();
                if (timeline.has(key) && timeline.get(key).isJsonPrimitive()) {
                    array.add(new JsonPrimitive(timeline.get(key).getAsString()));
                }
                for (String instruction : event.events) {
                    array.add(new JsonPrimitive(instruction));
                }
                timeline.add(key, array);
            }
        }
        if (!timeline.entrySet().isEmpty()) {
            animationJson.add("timeline", timeline);
        }
    }

    private static void putSoundEffects(JsonObject animationJson, List<RawYsmModel.RawSoundEffect> effects) {
        if (effects.isEmpty()) {
            return;
        }
        JsonObject soundEffects = new JsonObject();
        for (RawYsmModel.RawSoundEffect effect : effects) {
            if (StringUtils.isBlank(effect.effectName)) {
                continue;
            }
            JsonObject frame = new JsonObject();
            frame.addProperty("effect", effect.effectName);
            soundEffects.add(timeKey(effect.timestamp), frame);
        }
        if (!soundEffects.entrySet().isEmpty()) {
            animationJson.add("sound_effects", soundEffects);
        }
    }

    private static String timeKey(float seconds) {
        return isFinite(seconds) ? Float.toString(seconds) : "0.0";
    }

    private static boolean isZeroTime(float seconds) {
        return isFinite(seconds) && Math.abs(seconds) < 0.000001f;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static byte[] readDefaultAnimation(String fileName) throws IOException {
        Path defaultPath = CUSTOM.resolve("default").resolve(fileName);
        if (Files.isRegularFile(defaultPath)) {
            return Files.readAllBytes(defaultPath);
        }
        // Fallback: try BUILT/default (new format location)
        Path builtinDefault = BUILT.resolve("default").resolve(fileName);
        if (Files.isRegularFile(builtinDefault)) {
            return Files.readAllBytes(builtinDefault);
        }
        return EMPTY_ANIMATION;
    }

    private static void putAnimationControllers(Map<String, byte[]> animations, RawYsmModel raw) {
        int index = 0;
        for (RawYsmModel.RawAnimationControllerFile file : raw.mainEntity.animationControllerFiles) {
            if (file == null || (file.sourceJson == null && file.controllers.isEmpty())) {
                index++;
                continue;
            }
            byte[] data = file.sourceJson == null ? createControllerJson(file) : file.sourceJson;
            animations.put(YsmControllerResources.resourceName(file.name, index), data);
            index++;
        }
    }

    public static byte[] createControllerJson(RawYsmModel.RawAnimationControllerFile file) {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.19.0");
        JsonObject controllers = new JsonObject();
        for (RawYsmModel.RawAnimationController controller : file.controllers.values()) {
            controllers.add(controller.animationName, createControllerJson(controller));
        }
        root.add("animation_controllers", controllers);
        return ysmu.GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject createControllerJson(RawYsmModel.RawAnimationController controller) {
        JsonObject json = new JsonObject();
        if (StringUtils.isNotBlank(controller.initialState)) {
            json.addProperty("initial_state", controller.initialState);
        }
        JsonObject states = new JsonObject();
        for (RawYsmModel.RawControllerState state : controller.states) {
            states.add(state.name, createStateJson(state));
        }
        json.add("states", states);
        return json;
    }

    private static JsonObject createStateJson(RawYsmModel.RawControllerState state) {
        JsonObject json = new JsonObject();
        JsonArray animations = new JsonArray();
        for (Map.Entry<String, String> entry : state.animations.entrySet()) {
            if (StringUtils.isBlank(entry.getValue())) {
                animations.add(new JsonPrimitive(entry.getKey()));
            } else {
                JsonObject conditional = new JsonObject();
                conditional.addProperty(entry.getKey(), entry.getValue());
                animations.add(conditional);
            }
        }
        if (animations.size() > 0) {
            json.add("animations", animations);
        }

        JsonArray transitions = new JsonArray();
        for (Map.Entry<String, String> entry : state.transitions.entrySet()) {
            JsonObject transition = new JsonObject();
            transition.addProperty(entry.getKey(), entry.getValue());
            transitions.add(transition);
        }
        if (transitions.size() > 0) {
            json.add("transitions", transitions);
        }
        putStringArray(json, "on_entry", state.onEntry);
        putStringArray(json, "on_exit", state.onExit);
        putStringArray(json, "sound_effects", state.soundEffects);
        if (!state.blendTransitions.isEmpty()) {
            JsonObject blendTransitions = new JsonObject();
            for (Map.Entry<Float, Float> entry : state.blendTransitions.entrySet()) {
                blendTransitions.addProperty(Float.toString(entry.getKey()), entry.getValue());
            }
            json.add("blend_transition", blendTransitions);
        } else if (state.blendTransitionValue > 0f) {
            json.addProperty("blend_transition", state.blendTransitionValue);
        }
        if (state.blendViaShortestPath) {
            json.addProperty("blend_via_shortest_path", true);
        }
        return json;
    }

    private static void putStringArray(JsonObject json, String name, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(new JsonPrimitive(value));
        }
        json.add(name, array);
    }

    /**
     * 将 .molang 函数文件添加到 animations map 中，键使用 MOLANG_MAP_PREFIX 前缀。
     * 这些文件在客户端注册阶段会被解析为 ctrl.<state> → 动画名 的映射。
     */
    private static void putMolangFunctions(Map<String, byte[]> animations, RawYsmModel raw) {
        for (Map.Entry<String, RawYsmModel.RawDataFile> entry : raw.functionFiles.entrySet()) {
            String name = entry.getKey();   // 例如 "@player_ctrl_pre_main"
            RawYsmModel.RawDataFile file = entry.getValue();
            if (file == null || file.data == null || file.data.length == 0) {
                continue;
            }
            String mapKey = YsmControllerResources.molangResourceName(name);
            animations.put(mapKey, file.data);
        }
    }

}
