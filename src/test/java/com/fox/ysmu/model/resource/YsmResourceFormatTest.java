package com.fox.ysmu.model.resource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import io.netty.buffer.Unpooled;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fox.ysmu.data.EncryptTools;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import rip.ysm.security.YSMByteBuf;

class YsmResourceFormatTest {

    private static final byte[] PNG_1X1 = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=");

    @TempDir
    Path tempDir;

    @Test
    void folderDeserializerReadsOpenYsmFolderAndBridgeablePlayerFiles() throws Exception {
        Path modelDir = tempDir.resolve("Fancy Model");
        Files.createDirectories(modelDir.resolve("models"));
        Files.createDirectories(modelDir.resolve("textures"));
        Files.createDirectories(modelDir.resolve("animations"));
        Files.createDirectories(modelDir.resolve("controller"));
        Files.createDirectories(modelDir.resolve("lang"));
        Files.write(modelDir.resolve("ysm.json"), ysmJson().getBytes(StandardCharsets.UTF_8));
        Files.write(modelDir.resolve("models/main.json"), geometryJson("geometry.test.main").getBytes(StandardCharsets.UTF_8));
        Files.write(modelDir.resolve("models/arm.json"), geometryJson("geometry.test.arm").getBytes(StandardCharsets.UTF_8));
        Files.write(modelDir.resolve("textures/default.png"), PNG_1X1);
        Files.write(modelDir.resolve("animations/main.animation.json"), animationJson().getBytes(StandardCharsets.UTF_8));
        Files.write(modelDir.resolve("controller/main_controllers.json"), controllerJson().getBytes(StandardCharsets.UTF_8));
        Files.write(
            modelDir.resolve("lang/en_us.json"),
            "{\"model.ysm.test\":\"Test Model\",\"properties.extra_animation.extra0\":\"Wave\"}"
                .getBytes(StandardCharsets.UTF_8));

        RawYsmModel raw;
        try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(modelDir)) {
            raw = deserializer.deserialize();
            assertEquals(32, deserializer.getFolderHash().length());
        }

        assertEquals("Sample", raw.metadata.name);
        assertEquals("default", raw.properties.defaultTexture);
        assertNotNull(raw.mainEntity.mainModel.sourceJson);
        assertNotNull(raw.mainEntity.armModel.sourceJson);
        assertTrue(raw.mainEntity.textures.containsKey("default"));
        assertTrue(raw.languageFiles.containsKey("en_us"));
        assertEquals(1, raw.mainEntity.animationControllerFiles.size());
        assertTrue(RawYsmModelAdapter.isBridgeable(raw));

        RawYsmModel.RawTexture jpegTexture = new RawYsmModel.RawTexture();
        jpegTexture.name = "preview";
        jpegTexture.sourceFileName = "preview.jpg";
        jpegTexture.imageFormat = 3;
        jpegTexture.data = new byte[] { (byte) 0xFF, (byte) 0xD8, 0x00 };
        raw.mainEntity.textures.put(jpegTexture.name, jpegTexture);

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, "fancy_model");
        JsonObject description = getDescription(data.getModel().get("main"));
        assertEquals(0.9d, description.get("ysm_height_scale").getAsDouble(), 0.0001d);
        assertEquals(0.8d, description.get("ysm_width_scale").getAsDouble(), 0.0001d);
        JsonObject extraInfo = description.getAsJsonObject("ysm_extra_info");
        assertEquals("Sample", extraInfo.get("name").getAsString());
        assertEquals("Bridge metadata", extraInfo.get("tips").getAsString());
        assertEquals("CC0", extraInfo.get("license").getAsString());
        assertEquals("Tester (author)", extraInfo.getAsJsonArray("authors").get(0).getAsString());
        assertEquals("Wave", extraInfo.getAsJsonArray("extra_animation_names").get(0).getAsString());
        assertArrayEquals(PNG_1X1, data.getTexture().get("default.png"));
        assertFalse(data.getTexture().containsKey("preview.jpg"));
        assertTrue(data.getAnimation().containsKey("main"));
        assertTrue(data.getAnimation().containsKey("arm"));
        assertTrue(data.getAnimation().containsKey("extra"));
        assertTrue(data.getAnimation().containsKey(YsmControllerResources.ANIMATION_MAP_PREFIX + "main_controllers"));
    }

    /**
     * 文件夹动画写了 {@code "loop": "hold_on_last_frame"} 时，这个语义必须活着走完
     * "文件夹 → RawYsmModel → 二进制缓存/同步 → 客户端动画 JSON" 全程。
     *
     * <p>回归点：{@code parseLoopMode} 曾把 hold_on_last_frame 编成 2，而
     * {@code putLoopMode} 只认 3，于是 2 在写回客户端 JSON 时被整段丢掉、动画退回 PLAY_ONCE
     * —— 控制器状态里"播完停在最后一帧"就变成"播完回 idle"。条件动画名路径（如
     * {@code use_mainhand:sword}）由 {@code AnimationManager} 显式传 HOLD_ON_LAST_FRAME，
     * 恰好掩盖了这个 bug，所以只有控制器的状态动画会暴露它。</p>
     */
    @Test
    void holdOnLastFrameLoopSurvivesFolderSyncRoundTrip() throws Exception {
        Path modelDir = tempDir.resolve("Hold Loop");
        Files.createDirectories(modelDir.resolve("models"));
        Files.createDirectories(modelDir.resolve("textures"));
        Files.createDirectories(modelDir.resolve("animations"));
        Files.write(modelDir.resolve("ysm.json"), ysmJson().getBytes(StandardCharsets.UTF_8));
        Files.write(modelDir.resolve("models/main.json"), geometryJson("geometry.hold.main").getBytes(StandardCharsets.UTF_8));
        Files.write(modelDir.resolve("models/arm.json"), geometryJson("geometry.hold.arm").getBytes(StandardCharsets.UTF_8));
        Files.write(modelDir.resolve("textures/default.png"), PNG_1X1);
        Files.write(
            modelDir.resolve("animations/main.animation.json"),
            holdLoopAnimationJson().getBytes(StandardCharsets.UTF_8));

        RawYsmModel raw;
        try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(modelDir)) {
            raw = deserializer.deserialize();
        }
        assertEquals(3, animation(raw, "ctrl_block_pose").loopMode, "hold_on_last_frame 必须编码成上游的 3，不能是 2");

        // 客户端适配器写回的动画 JSON：loop 字段必须在，且是 hold_on_last_frame
        // （二进制往返后的纹理是加密态、isBridgeable 不成立，所以这一步用文件夹解析出的
        // 那份 raw；loopMode 的保留由下面的二进制往返单独断言。）
        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, "hold_loop");
        byte[] animationJson = data.getAnimation().get("main");
        assertNotNull(animationJson, "客户端适配器没有产出 main 动画文件");
        JsonObject animations = new JsonParser().parse(new String(animationJson, StandardCharsets.UTF_8))
            .getAsJsonObject()
            .getAsJsonObject("animations");
        assertEquals(
            "hold_on_last_frame",
            animations.getAsJsonObject("ctrl_block_pose")
                .get("loop")
                .getAsString(),
            "客户端动画 JSON 丢了 loop 字段，动画会退回 PLAY_ONCE");

        // 二进制缓存/同步往返
        RawYsmModel afterBinary;
        try (YSMByteBuf serialized = YSMBinarySerializer.serialize(raw, 32, false)) {
            try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(serialized.toArray(), 32)) {
                afterBinary = deserializer.deserialize();
            }
        }
        assertEquals(3, animation(afterBinary, "ctrl_block_pose").loopMode, "二进制缓存往返丢失了 loopMode");

        // 编码表：未声明(2) 不写字段（缺省在 GeckoLib 里就是 PLAY_ONCE），显式 false(0) 写 false
        JsonObject absent = new JsonObject();
        RawYsmModelAdapter.putLoopMode(absent, 2);
        assertFalse(absent.has("loop"), "未声明 loop 不能补默认值，否则所有没写 loop 的动画都会停在最后一帧");
        JsonObject explicitOnce = new JsonObject();
        RawYsmModelAdapter.putLoopMode(explicitOnce, 0);
        assertFalse(explicitOnce.get("loop").getAsBoolean());
    }

    private static RawYsmModel.RawAnimation animation(RawYsmModel model, String name) {
        RawYsmModel.RawAnimationFile file = model.mainEntity.animationFiles.get("main");
        assertNotNull(file, "main 动画文件缺失");
        RawYsmModel.RawAnimation anim = file.animations.get(name);
        assertNotNull(anim, name + " 动画缺失");
        return anim;
    }

    @Test
    void rawGeometryWithoutSourceJsonCanGenerateLegacyGeometryJson() throws Exception {
        RawYsmModel source = new RawYsmModel();
        source.metadata.name = "Generated";
        source.properties.widthScale = 1.2f;
        source.properties.heightScale = 1.1f;
        source.mainEntity.mainModel = geometryWithFlatCube(1, "geometry.generated.main");
        source.mainEntity.armModel = geometryWithFlatCube(2, "geometry.generated.arm");
        RawYsmModel.RawTexture texture = new RawYsmModel.RawTexture();
        texture.name = "default";
        texture.sourceFileName = "default.png";
        texture.imageFormat = 2;
        texture.data = PNG_1X1;
        source.mainEntity.textures.put(texture.name, texture);

        assertTrue(RawYsmModelAdapter.isBridgeable(source));

        ModelData data = RawYsmModelAdapter.toLegacyModelData(source, "generated_model");
        JsonObject description = getDescription(data.getModel().get("main"));
        assertEquals("geometry.generated.main", description.get("identifier").getAsString());
        assertEquals(1.1d, description.get("ysm_height_scale").getAsDouble(), 0.0001d);
        JsonObject geometry = new JsonParser().parse(new String(data.getModel().get("main"), StandardCharsets.UTF_8))
            .getAsJsonObject()
            .getAsJsonArray("minecraft:geometry")
            .get(0)
            .getAsJsonObject();
        JsonObject boneObj = geometry.getAsJsonArray("bones")
            .get(0)
            .getAsJsonObject();
        // The test geometry has a single flat face with vertex winding opposite to
        // its stored normal → detected as negative volume → goes to __ysm_neg_mesh.
        JsonObject polyMesh = boneObj.getAsJsonObject("__ysm_neg_mesh");
        assertNotNull(polyMesh, "Expected __ysm_neg_mesh for negative-volume cube");
        assertEquals("quad_list", polyMesh.get("polys").getAsString());
        assertTrue(polyMesh.get("normalized_uvs").getAsBoolean());
        assertEquals(12, polyMesh.getAsJsonArray("positions").size());
        assertEquals(8, polyMesh.getAsJsonArray("uvs").size());
    }

    @Test
    void legacyEncryptedModelPreservesMultiTextureByteOrder() throws Exception {
        Map<String, byte[]> model = new LinkedHashMap<>();
        model.put("main", geometryJson("geometry.test.main").getBytes(StandardCharsets.UTF_8));
        model.put("arm", geometryJson("geometry.test.arm").getBytes(StandardCharsets.UTF_8));

        byte[] defaultTexture = new byte[] { 0, 1, 2, 3, 4, 5, 6 };
        byte[] blueTexture = new byte[] { 10, 11, 12 };
        Map<String, byte[]> textures = new LinkedHashMap<>();
        textures.put("default.png", defaultTexture);
        textures.put("blue.png", blueTexture);

        Map<String, byte[]> animations = new LinkedHashMap<>();
        animations.put("main", animationJson().getBytes(StandardCharsets.UTF_8));

        EncryptTools.createRandomPassword();
        byte[] rawPassword = EncryptTools.writePassword();
        byte[] playerKey = new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 };
        byte[] encryptedPassword = EncryptTools.encryptPassword(playerKey, rawPassword);
        byte[] encryptedModel = EncryptTools.assembleEncryptModels(
            new ModelData("ordered_textures", Type.FOLDER, model, textures, animations));

        ModelData decoded = EncryptTools.decryptModel(playerKey, encryptedPassword, encryptedModel);

        assertNotNull(decoded);
        assertArrayEquals(defaultTexture, decoded.getTexture().get("default.png"));
        assertArrayEquals(blueTexture, decoded.getTexture().get("blue.png"));
    }

    @Test
    void binarySerializerRoundTripsFormat32RawModel() throws Exception {
        RawYsmModel source = new RawYsmModel();
        source.formatVersion = 32;
        source.properties.sha256 = "0123456789abcdef";
        source.properties.defaultTexture = "default";
        source.metadata.name = "Binary Sample";
        source.mainEntity.mainModel = geometry(1, "geometry.main");
        source.mainEntity.armModel = geometry(2, "geometry.arm");
        RawYsmModel.RawTexture texture = new RawYsmModel.RawTexture();
        texture.name = "default";
        texture.sourceFileName = "default.png";
        texture.hash = "texture-hash";
        texture.width = 1;
        texture.height = 1;
        texture.imageFormat = 2;
        texture.unknownFlag = 1;
        texture.data = PNG_1X1;
        source.mainEntity.textures.put(texture.name, texture);

        byte[] bytes;
        try (YSMByteBuf serialized = YSMBinarySerializer.serialize(source, 32, false)) {
            bytes = serialized.toArray();
        }

        RawYsmModel decoded;
        try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(bytes, 32)) {
            decoded = deserializer.deserialize();
        }

        assertEquals(32, decoded.formatVersion);
        assertEquals("Binary Sample", decoded.metadata.name);
        assertEquals("default", decoded.properties.defaultTexture);
        assertNotNull(decoded.mainEntity.mainModel);
        assertNotNull(decoded.mainEntity.armModel);
        assertTrue(decoded.mainEntity.textures.containsKey("default"));
    }

    @Test
    void binaryDeserializerKeepsOpenYsmExtensionAnimationTypesSeparate() throws Exception {
        RawYsmModel source = new RawYsmModel();
        source.formatVersion = 32;
        source.properties.sha256 = "0123456789abcdef";
        source.properties.defaultTexture = "default";
        source.metadata.name = "Extension Animations";
        source.mainEntity.mainModel = geometry(1, "geometry.main");
        source.mainEntity.armModel = geometry(2, "geometry.arm");
        RawYsmModel.RawTexture texture = new RawYsmModel.RawTexture();
        texture.name = "default";
        texture.sourceFileName = "default.png";
        texture.hash = "texture-hash";
        texture.width = 1;
        texture.height = 1;
        texture.imageFormat = 2;
        texture.unknownFlag = 1;
        texture.data = PNG_1X1;
        source.mainEntity.textures.put(texture.name, texture);
        source.mainEntity.animationFiles.put(
            "slashblade",
            animationFile(9, "slashblade-hash", "hold_mainhand:slashblade"));
        source.mainEntity.animationFiles.put(
            "tac",
            animationFile(4, "tac-hash", "tac:aim$tacz:minigun"));

        byte[] bytes;
        try (YSMByteBuf serialized = YSMBinarySerializer.serialize(source, 32, false)) {
            bytes = serialized.toArray();
        }

        RawYsmModel decoded;
        try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(bytes, 32)) {
            decoded = deserializer.deserialize();
        }

        assertEquals("slashblade", YSMFolderDeserializer.getAnimKeyFromType(9));
        assertEquals("tac", YSMFolderDeserializer.getAnimKeyFromType(4));
        assertEquals("carryon", YSMFolderDeserializer.getAnimKeyFromType(6));
        assertEquals("parcool", YSMFolderDeserializer.getAnimKeyFromType(7));
        assertEquals("tlm", YSMFolderDeserializer.getAnimKeyFromType(10));
        assertEquals("immersive_melodies", YSMFolderDeserializer.getAnimKeyFromType(12));
        assertEquals("irons_spell_books", YSMFolderDeserializer.getAnimKeyFromType(13));
        assertEquals("unknown_99", YSMFolderDeserializer.getAnimKeyFromType(99));
        assertEquals(9, YSMFolderDeserializer.getAnimTypeFromKey("slashblade"));
        assertEquals(4, YSMFolderDeserializer.getAnimTypeFromKey("tac"));
        assertEquals(6, YSMFolderDeserializer.getAnimTypeFromKey("carryon"));
        assertEquals(7, YSMFolderDeserializer.getAnimTypeFromKey("parcool"));
        assertEquals(10, YSMFolderDeserializer.getAnimTypeFromKey("tlm"));
        assertEquals(12, YSMFolderDeserializer.getAnimTypeFromKey("immersive_melodies"));
        assertEquals(13, YSMFolderDeserializer.getAnimTypeFromKey("irons_spell_books"));
        assertTrue(decoded.mainEntity.animationFiles.containsKey("slashblade"));
        assertTrue(decoded.mainEntity.animationFiles.containsKey("tac"));
        assertFalse(decoded.mainEntity.animationFiles.containsKey("unknown"));
        assertTrue(decoded.mainEntity.animationFiles.get("slashblade")
            .animations
            .containsKey("hold_mainhand:slashblade"));
        assertTrue(decoded.mainEntity.animationFiles.get("tac").animations.containsKey("tac:aim$tacz:minigun"));
    }

    @Test
    void binaryAnimationsWithoutSourceJsonGenerateLegacyAnimationJson() throws Exception {
        RawYsmModel source = new RawYsmModel();
        source.formatVersion = 32;
        source.properties.sha256 = "0123456789abcdef";
        source.properties.defaultTexture = "default";
        source.mainEntity.mainModel = geometryWithFlatCube(1, "geometry.main");
        source.mainEntity.armModel = geometryWithFlatCube(2, "geometry.arm");
        RawYsmModel.RawTexture texture = new RawYsmModel.RawTexture();
        texture.name = "default";
        texture.sourceFileName = "default.png";
        texture.hash = "texture-hash";
        texture.width = 1;
        texture.height = 1;
        texture.imageFormat = 2;
        texture.unknownFlag = 1;
        texture.data = PNG_1X1;
        source.mainEntity.textures.put(texture.name, texture);

        RawYsmModel.RawAnimationFile animationFile = new RawYsmModel.RawAnimationFile();
        animationFile.animType = 1;
        RawYsmModel.RawAnimation idle = new RawYsmModel.RawAnimation();
        idle.name = "idle";
        idle.length = 1.0f;
        idle.loopMode = 1;
        RawYsmModel.RawBoneAnimation sceneBone = new RawYsmModel.RawBoneAnimation();
        sceneBone.boneName = "SceneRoot";
        RawYsmModel.RawKeyframe hiddenScale = new RawYsmModel.RawKeyframe();
        hiddenScale.timestamp = 0.0f;
        hiddenScale.setPostData(new Object[] { 0f, 0f, 0f });
        sceneBone.scale.add(hiddenScale);
        idle.boneAnimations.add(sceneBone);
        RawYsmModel.RawBoneAnimation tailBone = new RawYsmModel.RawBoneAnimation();
        tailBone.boneName = "Tail";
        RawYsmModel.RawKeyframe tailStart = new RawYsmModel.RawKeyframe();
        tailStart.timestamp = 0.0f;
        tailStart.interpolationMode = 2;
        tailStart.setPostData(new Object[] { 0f, "math.sin(query.anim_time*360)*30", 0f });
        tailBone.rotation.add(tailStart);
        idle.boneAnimations.add(tailBone);
        RawYsmModel.RawBoneAnimation blendedBone = new RawYsmModel.RawBoneAnimation();
        blendedBone.boneName = "Blended";
        RawYsmModel.RawKeyframe blendStart = new RawYsmModel.RawKeyframe();
        blendStart.timestamp = 0.0f;
        blendStart.interpolationMode = 2;
        blendStart.setPostData(new Object[] { 0f, 0f, 0f });
        blendedBone.rotation.add(blendStart);
        RawYsmModel.RawKeyframe blendEnd = new RawYsmModel.RawKeyframe();
        blendEnd.timestamp = 0.5f;
        blendEnd.interpolationMode = 2;
        blendEnd.setPostData(new Object[] { 10f, 0f, 0f });
        blendedBone.rotation.add(blendEnd);
        idle.boneAnimations.add(blendedBone);
        animationFile.animations.put(idle.name, idle);
        source.mainEntity.animationFiles.put("main", animationFile);

        byte[] bytes;
        try (YSMByteBuf serialized = YSMBinarySerializer.serialize(source, 32, false)) {
            bytes = serialized.toArray();
        }

        RawYsmModel decoded;
        try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(bytes, 32)) {
            decoded = deserializer.deserialize();
        }

        assertNull(decoded.mainEntity.animationFiles.get("main").sourceJson);

        ModelData data = RawYsmModelAdapter.toLegacyModelData(decoded, "binary_anim");
        JsonObject root = new JsonParser().parse(new String(data.getAnimation().get("main"), StandardCharsets.UTF_8))
            .getAsJsonObject();
        JsonObject idleJson = root.getAsJsonObject("animations").getAsJsonObject("idle");
        assertTrue(idleJson.get("loop").getAsBoolean());
        JsonArray scale = idleJson.getAsJsonObject("bones")
            .getAsJsonObject("SceneRoot")
            .getAsJsonArray("scale");
        assertEquals(0.0d, scale.get(0).getAsDouble(), 0.0001d);
        assertEquals(0.0d, scale.get(1).getAsDouble(), 0.0001d);
        assertEquals(0.0d, scale.get(2).getAsDouble(), 0.0001d);
        JsonArray tailRotation = idleJson.getAsJsonObject("bones")
            .getAsJsonObject("Tail")
            .getAsJsonArray("rotation");
        assertEquals("math.sin(query.anim_time*360)*30", tailRotation.get(1).getAsString());
        JsonObject blendedRotation = idleJson.getAsJsonObject("bones")
            .getAsJsonObject("Blended")
            .getAsJsonObject("rotation");
        assertFalse(blendedRotation.getAsJsonObject("0.0").has("vector"));
        assertTrue(blendedRotation.getAsJsonObject("0.0").has("post"));
        assertEquals("catmullrom", blendedRotation.getAsJsonObject("0.5").get("lerp_mode").getAsString());
    }

    /**
     * 紧凑表示的通道语义本身：数值 / 表达式 / 缺省三种类型能分别写读，互不串味。
     *
     * <p>缺省通道在这里只做表示层断言：二进制的写方本来就表达不了"缺省"
     * （旧表示是数组里的 null，{@code writeMolangValue} 会直接 NPE），
     * 只有读到未知 datatype 时才会产生，所以它不该被"补成 0"。
     */
    @Test
    void compactKeyframeChannelsKeepNumberExpressionAndAbsentApart() {
        RawYsmModel.RawKeyframe keyframe = new RawYsmModel.RawKeyframe();
        keyframe.setPostData(new Object[] { 1.5f, "v.qh + 1", null });
        assertEquals(RawYsmModel.RawKeyframe.CHANNEL_NUMBER, keyframe.kind(false, 0));
        assertEquals(1.5f, keyframe.number(false, 0), 1.0e-6f);
        assertEquals(RawYsmModel.RawKeyframe.CHANNEL_EXPRESSION, keyframe.kind(false, 1));
        assertEquals("v.qh + 1", keyframe.expression(false, 1));
        assertEquals(RawYsmModel.RawKeyframe.CHANNEL_ABSENT, keyframe.kind(false, 2));
        assertNull(keyframe.expression(false, 2));

        // pre 侧独立编码，不受 post 影响
        assertEquals(RawYsmModel.RawKeyframe.CHANNEL_ABSENT, keyframe.kind(true, 0));
        keyframe.setExpression(true, 2, "math.random(-30, 30)");
        keyframe.setNumber(true, 0, 8f);
        assertEquals(RawYsmModel.RawKeyframe.CHANNEL_NUMBER, keyframe.kind(true, 0));
        assertEquals(8f, keyframe.number(true, 0), 1.0e-6f);
        assertEquals(RawYsmModel.RawKeyframe.CHANNEL_EXPRESSION, keyframe.kind(true, 2));
        assertEquals("math.random(-30, 30)", keyframe.expression(true, 2));
        assertEquals(RawYsmModel.RawKeyframe.CHANNEL_ABSENT, keyframe.kind(true, 1));
        // 表达式改成数值（同一个通道不能同时是两种类型）
        keyframe.setNumber(true, 2, 2f);
        assertEquals(RawYsmModel.RawKeyframe.CHANNEL_NUMBER, keyframe.kind(true, 2));
        assertEquals(2f, keyframe.number(true, 2), 1.0e-6f);
    }

    /**
     * RawKeyframe 的紧凑表示（数值内联 + 每通道 2bit 类型）必须在
     * "序列化 → 反序列化 → 适配成 legacy JSON" 这条链上与旧的 Object[] 表示逐值等价：
     * 数值 / 表达式 / 缺省三种通道、以及带 pre / 不带 pre 的关键帧都要覆盖。
     */
    @Test
    void compactKeyframeChannelsSurviveTheBinaryRoundTripAndTheLegacyConversion() throws Exception {
        RawYsmModel source = new RawYsmModel();
        source.formatVersion = 32;
        source.properties.sha256 = "compact-channels";
        source.properties.defaultTexture = "default";
        source.mainEntity.mainModel = geometryWithFlatCube(1, "geometry.main");
        source.mainEntity.armModel = geometryWithFlatCube(2, "geometry.arm");
        RawYsmModel.RawTexture texture = new RawYsmModel.RawTexture();
        texture.name = "default";
        texture.sourceFileName = "default.png";
        texture.hash = "texture-hash";
        texture.width = 1;
        texture.height = 1;
        texture.imageFormat = 2;
        texture.unknownFlag = 1;
        texture.data = PNG_1X1;
        source.mainEntity.textures.put(texture.name, texture);

        RawYsmModel.RawAnimationFile animationFile = new RawYsmModel.RawAnimationFile();
        animationFile.animType = 1;
        RawYsmModel.RawAnimation mixed = new RawYsmModel.RawAnimation();
        mixed.name = "mixed";
        mixed.length = 1.0f;
        mixed.loopMode = 1;

        RawYsmModel.RawBoneAnimation mixedBone = new RawYsmModel.RawBoneAnimation();
        mixedBone.boneName = "Mixed";
        // 数值 / 表达式 / 缺省 三种通道混在一个关键帧里
        RawYsmModel.RawKeyframe numberAndExpression = new RawYsmModel.RawKeyframe();
        numberAndExpression.timestamp = 0.0f;
        numberAndExpression.setPostData(new Object[] { 1.5f, "math.sin(q.anim_time)*2", 0.25f });
        mixedBone.rotation.add(numberAndExpression);
        // 带 pre 的关键帧（两个数组都要过一遍二进制读写）
        RawYsmModel.RawKeyframe withPre = new RawYsmModel.RawKeyframe();
        withPre.timestamp = 0.5f;
        withPre.interpolationMode = 2;
        withPre.hasPreData = true;
        withPre.setPreData(new Object[] { 0f, 0f, 0f });
        withPre.setPostData(new Object[] { 2f, 3f, 4f });
        mixedBone.rotation.add(withPre);
        // 单关键帧 + t=0 + 无 pre：走 putChannel 的快捷分支
        RawYsmModel.RawKeyframe singleNumber = new RawYsmModel.RawKeyframe();
        singleNumber.timestamp = 0.0f;
        singleNumber.setPostData(new Object[] { 0f, 0f, 0f });
        mixedBone.scale.add(singleNumber);
        mixed.boneAnimations.add(mixedBone);
        animationFile.animations.put(mixed.name, mixed);
        source.mainEntity.animationFiles.put("main", animationFile);

        byte[] bytes;
        try (YSMByteBuf serialized = YSMBinarySerializer.serialize(source, 32, false)) {
            bytes = serialized.toArray();
        }

        RawYsmModel decoded;
        try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(bytes, 32)) {
            decoded = deserializer.deserialize();
        }

        RawYsmModel.RawKeyframe decodedMixed = decoded.mainEntity.animationFiles.get("main")
            .animations.get("mixed").boneAnimations.get(0).rotation.get(0);
        assertEquals(RawYsmModel.RawKeyframe.CHANNEL_NUMBER, decodedMixed.kind(false, 0));
        assertEquals(1.5f, decodedMixed.number(false, 0), 1.0e-6f);
        assertEquals(RawYsmModel.RawKeyframe.CHANNEL_EXPRESSION, decodedMixed.kind(false, 1));
        assertEquals("math.sin(q.anim_time)*2", decodedMixed.expression(false, 1));
        assertEquals(RawYsmModel.RawKeyframe.CHANNEL_NUMBER, decodedMixed.kind(false, 2));
        assertEquals(0.25f, decodedMixed.number(false, 2), 1.0e-6f);
        assertFalse(decodedMixed.hasPreData);

        RawYsmModel.RawKeyframe decodedPre = decoded.mainEntity.animationFiles.get("main")
            .animations.get("mixed").boneAnimations.get(0).rotation.get(1);
        assertTrue(decodedPre.hasPreData);
        assertEquals(0f, decodedPre.number(true, 0), 1.0e-6f);
        assertEquals(RawYsmModel.RawKeyframe.CHANNEL_NUMBER, decodedPre.kind(true, 2));
        assertEquals(4f, decodedPre.number(false, 2), 1.0e-6f);

        ModelData data = RawYsmModelAdapter.toLegacyModelData(decoded, "compact_anim");
        JsonObject root = new JsonParser().parse(new String(data.getAnimation().get("main"), StandardCharsets.UTF_8))
            .getAsJsonObject();
        JsonObject bones = root.getAsJsonObject("animations").getAsJsonObject("mixed").getAsJsonObject("bones");
        JsonObject mixedNode = bones.getAsJsonObject("Mixed");
        // 两个关键帧 → rotation 是按时间索引的对象；单关键帧 + t=0 的 scale 走快捷路径 → 数组
        JsonObject rotationByTime = mixedNode.getAsJsonObject("rotation");
        JsonArray firstRotation = rotationByTime.getAsJsonArray("0.0");
        assertEquals(1.5d, firstRotation.get(0).getAsDouble(), 1.0e-6d);
        assertEquals("math.sin(q.anim_time)*2", firstRotation.get(1).getAsString());
        assertEquals(0.25d, firstRotation.get(2).getAsDouble(), 1.0e-6d);
        JsonObject preFrame = rotationByTime.getAsJsonObject("0.5");
        assertEquals(0d, preFrame.getAsJsonArray("pre").get(0).getAsDouble(), 1.0e-6d);
        assertEquals(3d, preFrame.getAsJsonArray("post").get(1).getAsDouble(), 1.0e-6d);
        assertEquals("catmullrom", preFrame.get("lerp_mode").getAsString());
        JsonArray singleScale = mixedNode.getAsJsonArray("scale");
        assertEquals(0d, singleScale.get(0).getAsDouble(), 1.0e-6d);
    }

    @Test
    void binaryDeserializerAcceptsAbbreviatedSyncFooter() throws Exception {
        RawYsmModel source = new RawYsmModel();
        source.formatVersion = 32;
        source.properties.sha256 = "0123456789abcdef";
        source.properties.defaultTexture = "default";
        source.metadata.name = "Footer Sample";
        source.mainEntity.mainModel = geometry(1, "geometry.main");
        source.mainEntity.armModel = geometry(2, "geometry.arm");
        RawYsmModel.RawTexture texture = new RawYsmModel.RawTexture();
        texture.name = "default";
        texture.sourceFileName = "default.png";
        texture.hash = "texture-hash";
        texture.width = 1;
        texture.height = 1;
        texture.imageFormat = 2;
        texture.unknownFlag = 1;
        texture.data = PNG_1X1;
        source.mainEntity.textures.put(texture.name, texture);

        byte[] bytes;
        try (YSMByteBuf serialized = YSMBinarySerializer.serialize(source, 32, true)) {
            bytes = serialized.toArray();
        }

        try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(bytes, 32)) {
            RawYsmModel decoded = deserializer.deserializeKeepOpen();
            deserializer.parseYSMFooter(decoded);

            assertEquals(65535, decoded.footer.version);
            assertEquals(0, decoded.footer.unkInt1);
            assertEquals(0L, decoded.footer.time);
        }
    }

    @Test
    void binaryDeserializerReadsFormat15AndBridgeConvertsRgbaTexture() throws Exception {
        byte[] rgbaRedPixel = new byte[] { (byte) 0xFF, 0, 0, (byte) 0xFF };

        RawYsmModel decoded;
        try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(format15Binary(rgbaRedPixel))) {
            decoded = deserializer.deserialize();
        }

        assertEquals(15, decoded.formatVersion);
        assertEquals("Legacy Binary", decoded.metadata.name);
        assertTrue(RawYsmModelAdapter.isBridgeable(decoded));

        ModelData data = RawYsmModelAdapter.toLegacyModelData(decoded, "_name_e58aa8e58a9be88782");
        byte[] texture = data.getTexture().get("texture.png");
        assertNotNull(texture);
        assertEquals((byte) 0x89, texture[0]);
        assertEquals((byte) 0x50, texture[1]);
        assertEquals((byte) 0x4E, texture[2]);
        assertEquals((byte) 0x47, texture[3]);
        assertEquals("Legacy Binary", getDescription(data.getModel().get("main"))
            .getAsJsonObject("ysm_extra_info")
            .get("name")
            .getAsString());
    }

    private static RawYsmModel.RawGeometry geometry(int type, String identifier) {
        RawYsmModel.RawGeometry geometry = new RawYsmModel.RawGeometry();
        geometry.modelType = type;
        geometry.identifier = identifier;
        geometry.sha256 = identifier + "-hash";
        geometry.textureWidth = 64f;
        geometry.textureHeight = 64f;
        geometry.visibleBoundsOffset = new float[] { 0f, 1.5f, 0f };
        return geometry;
    }

    private static byte[] format15Binary(byte[] rgbaTexture) {
        try (YSMByteBuf buf = new YSMByteBuf(Unpooled.buffer())) {
            buf.writeDword(15);
            buf.writeVarInt(0);

            buf.writeVarInt(2);
            writeFormat15GeometryEntry(buf, 1, "geometry.legacy.main");
            writeFormat15GeometryEntry(buf, 2, "geometry.legacy.arm");

            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);

            buf.writeVarInt(1);
            buf.writeString("texture");
            buf.writeByteArray(rgbaTexture);
            buf.writeVarInt(1);
            buf.writeVarInt(1);
            buf.writeVarInt(0);

            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);

            buf.writeString("format15-sha");
            buf.writeVarInt(1);
            buf.writeVarInt(0);
            buf.writeString("Legacy Binary");
            buf.writeString("Format 15 sample");
            buf.writeString("CC0");
            buf.writeString("");
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeFloat(0.8f);
            buf.writeFloat(0.9f);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeString("texture");
            buf.writeString("");
            buf.writeVarInt(1);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);

            return buf.toArray();
        }
    }

    private static void writeFormat15GeometryEntry(YSMByteBuf buf, int modelType, String identifier) {
        buf.writeVarInt(modelType);
        buf.writeVarInt(1);
        writeFormat15Geometry(buf, identifier);
    }

    private static void writeFormat15Geometry(YSMByteBuf buf, String identifier) {
        buf.writeVarInt(1);
        buf.writeString("");
        buf.writeVarInt(1);
        buf.writeVarInt(1);
        writeVector(buf, 0f, 0f, -1f);
        writeVertex(buf, -0.5f, 0f, 0f, 0f, 0f);
        writeVertex(buf, 0.5f, 0f, 0f, 1f, 0f);
        writeVertex(buf, 0.5f, 1f, 0f, 1f, 1f);
        writeVertex(buf, -0.5f, 1f, 0f, 0f, 1f);
        buf.writeVarInt(0);
        buf.writeVarInt(0);
        buf.writeVarInt(0);
        buf.writeString("root");
        buf.writeVarInt(0);
        buf.writeVarInt(0);
        buf.writeVarInt(0);
        buf.writeVarInt(0);
        buf.writeVarInt(0);
        writeVector(buf, 0f, 0f, 0f);
        writeVector(buf, 0f, 0f, 0f);

        buf.writeString(identifier);
        buf.writeFloat(64f);
        buf.writeFloat(64f);
        buf.writeFloat(3f);
        buf.writeFloat(2f);
        buf.writeVarInt(3);
        buf.writeFloat(0f);
        buf.writeFloat(1.5f);
        buf.writeFloat(0f);
        buf.writeFloat(0f);
        buf.writeFloat(0f);
        buf.writeVarInt(0);
        buf.writeVarInt(0);
        buf.writeVarInt(0);
        buf.writeVarInt(0);
    }

    private static void writeVertex(YSMByteBuf buf, float x, float y, float z, float u, float v) {
        writeVector(buf, x, y, z);
        buf.writeFloat(u);
        buf.writeFloat(v);
    }

    private static void writeVector(YSMByteBuf buf, float x, float y, float z) {
        buf.writeFloat(x);
        buf.writeFloat(y);
        buf.writeFloat(z);
    }

    private static RawYsmModel.RawGeometry geometryWithFlatCube(int type, String identifier) {
        RawYsmModel.RawGeometry geometry = geometry(type, identifier);
        RawYsmModel.RawBone bone = new RawYsmModel.RawBone();
        bone.name = "root";
        RawYsmModel.RawCube cube = new RawYsmModel.RawCube();
        RawYsmModel.RawFace face = new RawYsmModel.RawFace();
        face.normal = new float[] { 0f, 0f, -1f };
        face.positions = new float[][] {
            { -0.5f, 0f, 0f },
            { 0.5f, 0f, 0f },
            { 0.5f, 1f, 0f },
            { -0.5f, 1f, 0f } };
        face.u = new float[] { 0f, 1f, 1f, 0f };
        face.v = new float[] { 0f, 0f, 1f, 1f };
        cube.faces.add(face);
        bone.cubes.add(cube);
        geometry.bones.add(bone);
        return geometry;
    }

    private static RawYsmModel.RawAnimationFile animationFile(int type, String hash, String animationName) {
        RawYsmModel.RawAnimationFile animationFile = new RawYsmModel.RawAnimationFile();
        animationFile.animType = type;
        animationFile.fileHash = hash;
        RawYsmModel.RawAnimation animation = new RawYsmModel.RawAnimation();
        animation.name = animationName;
        animation.length = 1.0f;
        animation.loopMode = 1;
        animationFile.animations.put(animation.name, animation);
        return animationFile;
    }

    /**
     * {@code properties.render_layers_first} 整条链路（不含真正画）：解析 → 注入 geometry 的
     * description → 走游戏同一条 Jackson 解析路径 → 二进制同步缓存往返。
     * 视口里的绘制顺序无法单测，但"标志有没有活着到达渲染器读的那份 geometry"可以。
     */
    @Test
    void renderLayersFirstSurvivesInjectionAndGeometryParse() throws Exception {
        RawYsmModel source = new RawYsmModel();
        source.formatVersion = 32;
        source.properties.sha256 = "0123456789abcdef";
        source.properties.defaultTexture = "default";
        source.properties.renderLayersFirst = true;
        source.metadata.name = "Layers First";
        source.mainEntity.mainModel = geometryWithFlatCube(1, "geometry.layers.main");
        source.mainEntity.armModel = geometryWithFlatCube(2, "geometry.layers.arm");
        RawYsmModel.RawTexture texture = new RawYsmModel.RawTexture();
        texture.name = "default";
        texture.sourceFileName = "default.png";
        texture.hash = "texture-hash";
        texture.width = 1;
        texture.height = 1;
        texture.imageFormat = 2;
        texture.unknownFlag = 1;
        texture.data = PNG_1X1;
        source.mainEntity.textures.put(texture.name, texture);

        ModelData data = RawYsmModelAdapter.toLegacyModelData(source, "layers_first");
        JsonObject description = getDescription(data.getModel().get("main"));
        assertTrue(
            description.get("ysm_render_layers_first")
                .getAsBoolean(),
            "render_layers_first 没有注入 geometry 的 description");

        software.bernie.geckolib3.geo.raw.pojo.RawGeoModel parsed = software.bernie.geckolib3.geo.raw.pojo.Converter
            .fromJsonString(new String(data.getModel().get("main"), StandardCharsets.UTF_8));
        assertTrue(
            software.bernie.geckolib3.geo.raw.tree.RawGeometryTree.parseHierarchy(parsed).properties
                .isRenderLayersFirst(),
            "geometry JSON 解析成 ModelProperties 后 render_layers_first 丢失");

        byte[] bytes;
        try (YSMByteBuf serialized = YSMBinarySerializer.serialize(source, 32, false)) {
            bytes = serialized.toArray();
        }
        try (YSMBinaryDeserializer deserializer = new YSMBinaryDeserializer(bytes, 32)) {
            assertTrue(deserializer.deserialize().properties.renderLayersFirst, "二进制缓存往返丢失了该属性");
        }
    }

    private static JsonObject getDescription(byte[] geometryJson) {
        return new JsonParser().parse(new String(geometryJson, StandardCharsets.UTF_8))
            .getAsJsonObject()
            .getAsJsonArray("minecraft:geometry")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("description");
    }

    private static String ysmJson() {
        return "{"
            + "\"metadata\":{\"name\":\"Sample\",\"tips\":\"Bridge metadata\",\"license\":{\"type\":\"CC0\"},"
            + "\"authors\":[{\"name\":\"Tester\",\"role\":\"author\"}]},"
            + "\"properties\":{\"default_texture\":\"default\",\"width_scale\":0.8,\"height_scale\":0.9,"
            + "\"extra_animation\":{\"extra0\":\"\",\"extra1\":\"Spin\"}},"
            + "\"files\":{\"player\":{"
            + "\"model\":{\"main\":\"models/main.json\",\"arm\":\"models/arm.json\"},"
            + "\"texture\":[\"textures/default.png\"],"
            + "\"animation\":{\"main\":\"animations/main.animation.json\"},"
            + "\"animation_controllers\":[\"controller/main_controllers.json\"]"
            + "}}"
            + "}";
    }

    private static String geometryJson(String identifier) {
        return "{"
            + "\"format_version\":\"1.12.0\","
            + "\"minecraft:geometry\":[{\"description\":{"
            + "\"identifier\":\"" + identifier + "\","
            + "\"texture_width\":64,\"texture_height\":64,"
            + "\"visible_bounds_width\":2,\"visible_bounds_height\":3,"
            + "\"visible_bounds_offset\":[0,1.5,0]"
            + "},\"bones\":[{\"name\":\"root\",\"pivot\":[0,0,0]}]}]"
            + "}";
    }

    private static String animationJson() {
        return "{"
            + "\"format_version\":\"1.8.0\","
            + "\"animations\":{\"idle\":{\"loop\":true,\"animation_length\":1.0,\"bones\":{\"root\":{\"rotation\":[0,0,0]}}}}"
            + "}";
    }

    private static String holdLoopAnimationJson() {
        return "{"
            + "\"format_version\":\"1.8.0\","
            + "\"animations\":{\"ctrl_block_pose\":{\"loop\":\"hold_on_last_frame\",\"animation_length\":1.0,"
            + "\"bones\":{\"root\":{\"rotation\":{\"0.0\":[0,0,0],\"0.25\":[0,0,160]}}}}}"
            + "}";
    }

    private static String controllerJson() {
        return "{"
            + "\"format_version\":\"1.19.0\","
            + "\"animation_controllers\":{\"player.main\":{\"initial_state\":\"default\",\"states\":{"
            + "\"default\":{\"animations\":[\"idle\"],\"transitions\":[{\"walk\":\"q.ground_speed>0.05\"}]},"
            + "\"walk\":{\"animations\":[\"walk\"],\"transitions\":[{\"default\":\"q.ground_speed<=0.05\"}],"
            + "\"blend_transition\":0.15,\"on_entry\":[\"v.entered_walk=1;\"]}"
            + "}}}}";
    }
}
