package com.fox.ysmu.client.renderer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;
import software.bernie.geckolib3.geo.raw.tree.RawGeometryTree;
import software.bernie.geckolib3.geo.render.GeoBuilder;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * 骨骼的可见性判定：**任意一轴缩放为 0 就不渲染**（不只是三轴全 0）。
 *
 * <p>回归点（实机对照）：某弹射物命中后把箭身写成 {@code Arrow.scale = [0, 1, 1]} —— 作者的
 * 本意是"收掉箭身"（官方客户端看不到它），而旧的"三轴全 0 才隐藏"判定会把它画成一块
 * 0 厚度的面片；那面贴图 alpha 全不透明，所以看起来就是命中后还挂着个实心蓝方块。</p>
 *
 * <p>单轴 0 是模型里通用的"隐藏"写法（眼睑/嘴/眉毛/刀光轨迹都用它），现代管线下这类退化
 * 矩阵本来也画不出来。</p>
 */
class BoneScaleVisibilityTest {

    private static final String GEO_JSON = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},"
        + "\"bones\":[{\"name\":\"b\",\"pivot\":[0,0,0],"
        + "\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";

    private static GeoBone bone() throws Exception {
        RawGeoModel raw = Converter.fromJsonString(GEO_JSON);
        GeoModel model = GeoBuilder.getGeoBuilder("ysmu")
            .constructGeoModel(RawGeometryTree.parseHierarchy(raw));
        return model.topLevelBones.get(0);
    }

    /** 三轴全 0（标量 0 的展开）仍然是隐藏。 */
    @Test
    void allAxesZeroIsHidden() throws Exception {
        GeoBone bone = bone();
        bone.setScaleX(0f);
        bone.setScaleY(0f);
        bone.setScaleZ(0f);
        assertTrue(IGeoRenderer.isScaleInvisible(bone));
    }

    /** 单轴 0 = 同样隐藏（本次修复的核心）。 */
    @Test
    void anySingleZeroAxisIsHidden() throws Exception {
        GeoBone bone = bone();

        bone.setScaleX(0f);
        bone.setScaleY(1f);
        bone.setScaleZ(1f);
        assertTrue(IGeoRenderer.isScaleInvisible(bone), "[0,1,1]（箭身收掉）必须视为隐藏");

        bone.setScaleX(1f);
        bone.setScaleY(0f);
        bone.setScaleZ(1f);
        assertTrue(IGeoRenderer.isScaleInvisible(bone), "[1,0,1]（眼睑/轨迹的隐藏写法）也必须隐藏");

        bone.setScaleX(1f);
        bone.setScaleY(1f);
        bone.setScaleZ(0f);
        assertTrue(IGeoRenderer.isScaleInvisible(bone));
    }

    /** 极薄但不是 0 的压扁仍然渲染（例如外圈光效的 [1,1,0.01]）。 */
    @Test
    void nearZeroButNonZeroScaleStillRenders() throws Exception {
        GeoBone bone = bone();
        bone.setScaleX(1f);
        bone.setScaleY(1f);
        bone.setScaleZ(0.009999999776482582f);
        assertFalse(IGeoRenderer.isScaleInvisible(bone), "0.01 不是 0，必须照常渲染");

        bone.setScaleZ(1f);
        bone.setScaleX(0.8f);
        assertFalse(IGeoRenderer.isScaleInvisible(bone));
    }

    /** null 骨骼按不可见处理（渲染路径的防御性分支）。 */
    @Test
    void nullBoneIsInvisible() {
        assertTrue(IGeoRenderer.isScaleInvisible((IBone) null));
    }
}
