package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.molang.MolangStringPool;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;
import software.bernie.geckolib3.geo.raw.tree.RawGeometryTree;
import software.bernie.geckolib3.geo.render.GeoBuilder;
import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * 弹射物（子模型）骨骼作用域：{@code ysm.bone_pivot_abs(...)} 必须能查到弹射物几何里的骨骼。
 *
 * <p>回归点：弹射物的 GeoModel 不经过玩家的 {@link software.bernie.geckolib3.core.processor.AnimationProcessor}
 * 注册，所以 {@code MolangPhysicsRuntime.bone()} 对它一律返回 null —— 某弹射物时间轴里的
 * {@code ysm.bone_pivot_abs('Arrow')/16*0.7} 恒等于 0，粒子只能落在实体原点，
 * 而这个"0"还不是模型想要的坐标。{@code beginProjectileBones} 负责把这份几何的骨骼按名字登记。</p>
 */
class ProjectileBoneScopeTest {

    /** root(pivot 4,0,0) → child(pivot 0,2,0)，都带一个方块。 */
    private static final String GEO_JSON = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},"
        + "\"bones\":[{\"name\":\"root\",\"pivot\":[4,0,0],\"cubes\":["
        + "{\"origin\":[3,-1,-1],\"size\":[2,2,2],\"uv\":[0,0]}]},"
        + "{\"name\":\"child\",\"parent\":\"root\",\"pivot\":[0,2,0],\"cubes\":["
        + "{\"origin\":[-1,1,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";

    private static GeoModel loadGeo() throws Exception {
        RawGeoModel raw = Converter.fromJsonString(GEO_JSON);
        RawGeometryTree tree = RawGeometryTree.parseHierarchy(raw);
        return GeoBuilder.getGeoBuilder("ysmu").constructGeoModel(tree);
    }

    private static double pivot(String boneName, char axis) {
        return MolangPhysicsRuntime.bonePivot(MolangStringPool.intern(boneName), axis);
    }

    private static double scale(String boneName, char axis) {
        return MolangPhysicsRuntime.boneScale(MolangStringPool.intern(boneName), axis);
    }

    /** 没有作用域时（旧行为）：查不到骨骼 → 0。建立作用域后必须查到几何里的真值。 */
    @Test
    void projectileScopeMakesBonePivotResolvable() throws Exception {
        GeoModel model = loadGeo();
        assertNotNull(model.topLevelBones);

        assertEquals(0.0d, pivot("root", 'x'), 1.0e-6d, "没有作用域时骨骼查不到（旧行为）");
        // 查不到骨骼时 boneScale 返回中性 1.0（这也是粒子公式里 scale 函数的默认值）
        assertEquals(1.0d, scale("root", 'x'), 1.0e-6d);

        Map<String, IBone> previous = MolangPhysicsRuntime.beginProjectileBones(model.topLevelBones);
        try {
            assertNotEquals(0.0d, pivot("root", 'x'), "作用域内必须查到 root 的枢轴");
            assertEquals(4.0d, pivot("root", 'x'), 1.0e-3d, "枢轴 x（渲染帧方向，GeoBuilder 内部取负后取反）");
            assertEquals(0.0d, pivot("root", 'y'), 1.0e-3d);
            assertEquals(1.0d, scale("root", 'x'), 1.0e-6d, "绑定姿势缩放 = 1");
            assertNotEquals(0.0d, pivot("child", 'y'), "子骨骼也要能查到");
            assertEquals(2.0d, pivot("child", 'y'), 1.0e-3d, "子骨骼枢轴沿父链累积");
        } finally {
            MolangPhysicsRuntime.endProjectileBones(previous);
        }

        assertEquals(0.0d, pivot("root", 'x'), 1.0e-6d, "退出作用域后重新查不到（玩家路径不受影响）");
    }

    /** 作用域要能嵌套还原：结束弹射物作用域后，调用前的那一份必须回来。 */
    @Test
    void projectileScopeRestoresPreviousScope() throws Exception {
        GeoModel model = loadGeo();
        Map<String, IBone> previous = MolangPhysicsRuntime.beginProjectileBones(model.topLevelBones);
        Map<String, IBone> nested = MolangPhysicsRuntime.beginProjectileBones(java.util.Collections.emptyList());
        try {
            assertEquals(0.0d, pivot("root", 'x'), 1.0e-6d, "空作用域覆盖期间查不到");
        } finally {
            MolangPhysicsRuntime.endProjectileBones(nested);
        }
        try {
            assertNotEquals(0.0d, pivot("root", 'x'), "恢复后应回到外层作用域（root 可查）");
        } finally {
            MolangPhysicsRuntime.endProjectileBones(previous);
        }
    }
}
