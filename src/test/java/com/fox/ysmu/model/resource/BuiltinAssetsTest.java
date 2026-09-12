package com.fox.ysmu.model.resource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.model.resource.pojo.RawYsmModel;

/**
 * 用**随模组发布的内置模型**当作解析层的回归夹具：它们跟着仓库走，不依赖任何下载模型，
 * 因此"手头没有可测试的模型"时也能锁住加载链。
 *
 * <p>覆盖两种格式：{@code builtin/default}（现代 {@code ysm.json} 包）与
 * {@code builtin/misc/*}（旧版扁平格式，正是让 legacy 解析路径必须保留的原因）。</p>
 */
class BuiltinAssetsTest {

    private static final Path BUILTIN = Paths.get("src", "main", "resources", "assets", "ysmu", "builtin");

    @Test
    void modernBuiltinPackParsesAndIsBridgeable() throws Exception {
        Path dir = BUILTIN.resolve("default");
        assertTrue(Files.isDirectory(dir), "内置 default 包不在预期位置: " + dir.toAbsolutePath());

        try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(dir)) {
            RawYsmModel raw = deserializer.deserialize();
            assertNotNull(raw.mainEntity.mainModel, "default 主模型几何缺失");
            assertNotNull(raw.mainEntity.armModel, "default 手臂几何缺失");
            assertFalse(raw.mainEntity.textures.isEmpty(), "default 没有贴图");
            assertFalse(raw.mainEntity.animationFiles.isEmpty(), "default 没有动画文件");
            assertFalse(raw.metadata.name.isEmpty(), "default 缺少 metadata.name");
            assertTrue(RawYsmModelAdapter.isBridgeable(raw), "default 应可桥接成 legacy ModelData");
        }
    }

    @Test
    void legacyBuiltinModelsParseAndAreBridgeable() throws Exception {
        String[] models = { "1_alex", "2_steve", "3_default_boy", "4_default_controllers", "5_qingluka",
            "6_wine_fox" };
        for (String name : models) {
            Path dir = BUILTIN.resolve("misc").resolve(name);
            assertTrue(Files.isDirectory(dir), "内置旧格式模型缺失: " + dir.toAbsolutePath());
            try (YSMFolderDeserializer deserializer = new YSMFolderDeserializer(dir)) {
                RawYsmModel raw = deserializer.deserialize();
                assertNotNull(raw.mainEntity.mainModel, name + " 主模型几何缺失");
                assertNotNull(raw.mainEntity.armModel, name + " 手臂几何缺失");
                assertFalse(raw.mainEntity.textures.isEmpty(), name + " 没有贴图");
                assertTrue(RawYsmModelAdapter.isBridgeable(raw), name + " 应可桥接成 legacy ModelData");
            }
        }
    }
}
