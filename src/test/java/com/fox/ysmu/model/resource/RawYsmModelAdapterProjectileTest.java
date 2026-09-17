package com.fox.ysmu.model.resource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.data.ModelData;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;

import software.bernie.geckolib3.file.AnimationFile;

/**
 * 二进制 {@code .ysm} / ysm.json 文件夹模型的弹射物（files.projectiles）必须把**动画和控制器**
 * 一起桥接成 legacy ModelData。
 *
 * <p>回归点：{@code toLegacyModelData} 原来只桥接弹射物的几何和贴图，动画/控制器被一句
 * "they will be registered separately" 的注释丢掉（服务端同步路径
 * {@code OpenYsmModelSyncClient} 有处理，本地模型没有）。缺动画的后果不是"少播一个动画"：
 * {@code ArrowProjectileRenderer} 找不到 AnimationFile 就直接 return，几何停在**绑定姿势**，
 * 于是模型里靠骨骼缩放隐藏的所有子模型（弓、弩、各种光效盒）同时显示在弹射物实体上。</p>
 */
class RawYsmModelAdapterProjectileTest {

    private static final int PNG_FORMAT = 2;
    private static final String GEO = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},"
        + "\"bones\":[{\"name\":\"b\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";
    private static final String PROJ_ANIM = "{\"format_version\":\"1.19.0\",\"animations\":{"
        + "\"air\":{\"loop\":true,\"bones\":{\"b\":{\"scale\":1.0}}},"
        + "\"parallel0\":{\"loop\":true,\"bones\":{\"b\":{\"scale\":0.0}}}}}";
    private static final String PROJ_CTRL = "{\"format_version\":\"1.19.0\",\"animation_controllers\":{"
        + "\"projectile.post_main\":{\"initial_state\":\"default\",\"states\":{"
        + "\"default\":{\"animations\":[\"air\"]}}}}}";

    private static RawYsmModel.RawGeometry geometry() {
        RawYsmModel.RawGeometry geo = new RawYsmModel.RawGeometry();
        geo.identifier = "geometry.test";
        geo.sourceJson = GEO.getBytes(StandardCharsets.UTF_8);
        return geo;
    }

    private static RawYsmModel.RawTexture pngTexture(String name) {
        RawYsmModel.RawTexture texture = new RawYsmModel.RawTexture();
        texture.name = name;
        texture.sourceFileName = name;
        texture.imageFormat = PNG_FORMAT;
        texture.data = new byte[] { (byte) 0x89, 'P', 'N', 'G' };
        return texture;
    }

    private static RawYsmModel.RawSubEntity projectile(String identifier, byte[] animation, byte[] controller) {
        RawYsmModel.RawSubEntity sub = new RawYsmModel.RawSubEntity();
        sub.identifier = identifier;
        sub.model = geometry();
        sub.textures.put("arrow.png", pngTexture("arrow.png"));
        if (animation != null) {
            RawYsmModel.RawAnimationFile file = new RawYsmModel.RawAnimationFile();
            file.sourceJson = animation;
            file.fileHash = "hash";
            sub.animationFiles.put("arrow.animation", file);
        }
        if (controller != null) {
            RawYsmModel.RawAnimationControllerFile file = new RawYsmModel.RawAnimationControllerFile();
            file.name = "arrow.controller";
            file.sourceJson = controller;
            sub.animationControllerFiles.add(file);
        }
        return sub;
    }

    private static RawYsmModel bridgeableModel() {
        RawYsmModel raw = new RawYsmModel();
        raw.modelId = "_test_projectile_bridge";
        raw.mainEntity.mainModel = geometry();
        raw.mainEntity.armModel = geometry();
        raw.mainEntity.textures.put("texture.png", pngTexture("texture.png"));
        return raw;
    }

    @Test
    void projectileAnimationAndControllerAreBridged() throws Exception {
        RawYsmModel raw = bridgeableModel();
        raw.projectiles.put("minecraft:arrow",
            projectile("minecraft:arrow", PROJ_ANIM.getBytes(StandardCharsets.UTF_8),
                PROJ_CTRL.getBytes(StandardCharsets.UTF_8)));

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);

        assertTrue(data.getModel().containsKey("projectile_minecraft:arrow"), data.getModel().keySet().toString());
        assertTrue(data.getAnimation().containsKey("projectile_minecraft:arrow"),
            "弹射物动画必须桥接: " + data.getAnimation().keySet());
        assertTrue(data.getAnimation().containsKey("projectile_ctrl_minecraft:arrow"),
            "弹射物控制器必须桥接: " + data.getAnimation().keySet());
    }

    /** 桥接出来的动画必须是渲染器能解析的 AnimationFile。 */
    @Test
    void bridgedAnimationIsParseableByTheRenderer() throws Exception {
        RawYsmModel raw = bridgeableModel();
        raw.projectiles.put("#arrow",
            projectile("#arrow", PROJ_ANIM.getBytes(StandardCharsets.UTF_8), null));

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);
        String json = new String(data.getAnimation().get("projectile_#arrow"), StandardCharsets.UTF_8);
        AnimationFile parsed = ClientModelManager.parseAnimationFileFromJson(json);

        assertNotNull(parsed);
        assertTrue(parsed.animations.containsKey("parallel0"), parsed.animations.keySet().toString());
        assertTrue(parsed.animations.containsKey("air"), parsed.animations.keySet().toString());
    }

    /** match 列表里的每个 id 都要有自己的一份（同一几何可以匹配多个实体）。 */
    @Test
    void everyMatchIdGetsItsOwnEntry() throws Exception {
        RawYsmModel raw = bridgeableModel();
        RawYsmModel.RawSubEntity sub = projectile("#arrow", PROJ_ANIM.getBytes(StandardCharsets.UTF_8),
            PROJ_CTRL.getBytes(StandardCharsets.UTF_8));
        sub.matchIds = new String[] { "minecraft:arrow", "minecraft:spectral_arrow" };
        raw.projectiles.put("#arrow", sub);

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);
        for (String id : new String[] { "minecraft:arrow", "minecraft:spectral_arrow" }) {
            assertTrue(data.getAnimation().containsKey("projectile_" + id), data.getAnimation().keySet().toString());
            assertTrue(data.getAnimation().containsKey("projectile_ctrl_" + id), data.getAnimation().keySet().toString());
            assertTrue(data.getModel().containsKey("projectile_" + id), data.getModel().keySet().toString());
        }
    }

    /** 没有动画数据的弹射物不能凭空产生 key，也不能抛异常。 */
    @Test
    void projectileWithoutAnimationDataAddsNoAnimationKey() throws Exception {
        RawYsmModel raw = bridgeableModel();
        raw.projectiles.put("#arrow", projectile("#arrow", null, null));

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);
        assertTrue(data.getModel().containsKey("projectile_#arrow"), data.getModel().keySet().toString());
        assertFalse(data.getAnimation().containsKey("projectile_#arrow"), data.getAnimation().keySet().toString());
        assertFalse(data.getAnimation().containsKey("projectile_ctrl_#arrow"), data.getAnimation().keySet().toString());
    }

    /** 主实体动画的既有行为不能被改动。 */
    @Test
    void mainEntityAnimationsStillPresent() throws Exception {
        RawYsmModel raw = bridgeableModel();
        RawYsmModel.RawAnimationFile main = new RawYsmModel.RawAnimationFile();
        main.sourceJson = "{\"format_version\":\"1.19.0\",\"animations\":{\"idle\":{\"loop\":true}}}"
            .getBytes(StandardCharsets.UTF_8);
        raw.mainEntity.animationFiles.put("main", main);
        raw.projectiles.put("#arrow", projectile("#arrow", PROJ_ANIM.getBytes(StandardCharsets.UTF_8), null));

        ModelData data = RawYsmModelAdapter.toLegacyModelData(raw, raw.modelId);
        assertTrue(data.getAnimation().containsKey("main"), data.getAnimation().keySet().toString());
        assertTrue(data.getAnimation().containsKey("projectile_#arrow"), data.getAnimation().keySet().toString());
        // arm / extra 没有数据时会回退到内置默认动画，所以只断言 key 存在且都有内容。
        for (Map.Entry<String, byte[]> entry : data.getAnimation().entrySet()) {
            assertNotNull(entry.getValue(), entry.getKey());
        }
    }
}
