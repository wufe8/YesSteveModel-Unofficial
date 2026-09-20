package software.bernie.geckolib3.core.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.geo.raw.pojo.Converter;
import software.bernie.geckolib3.geo.raw.pojo.RawGeoModel;
import software.bernie.geckolib3.geo.raw.tree.RawGeometryTree;
import software.bernie.geckolib3.geo.render.GeoBuilder;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * {@link AnimationProcessor#selectModel} 的骨骼登记缓存。
 *
 * <p>回归点（来自纯净预览页采样）：十几个预览轮流播不同的模型，而它们共用同一个 GeckoLib
 * 模型实例，于是 {@code AnimatedGeoModel#getModel()} 里"模型变了"每次渲染都成立。旧实现
 * 每次都清空骨骼表 + 递归重走整棵骨骼树 + 重存初始快照；现在同一个 GeoModel 第二次起应该
 * 直接命中缓存（连骨骼表对象都复用），切换回来也不能把别的模型的登记结果冲掉。</p>
 */
class AnimationProcessorRegistrationCacheTest {

    private static GeoModel model(String boneName) throws Exception {
        String json = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
            + "\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":64},"
            + "\"bones\":[{\"name\":\"" + boneName + "\",\"pivot\":[0,0,0],"
            + "\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";
        RawGeoModel raw = Converter.fromJsonString(json);
        return GeoBuilder.getGeoBuilder("ysmu")
            .constructGeoModel(RawGeometryTree.parseHierarchy(raw));
    }

    private static AnimationProcessor<IAnimatable> processor() {
        return new AnimationProcessor<>(null);
    }

    private static void register(AnimationProcessor<IAnimatable> processor, GeoModel model) {
        for (GeoBone bone : model.topLevelBones) {
            processor.registerModelRenderer(bone);
        }
    }

    /** 同一个 GeoModel 第二次 selectModel 必须命中缓存：骨骼表还是同一个对象，索引也保留。 */
    @Test
    void theSameModelIsRegisteredOnlyOnce() throws Exception {
        AnimationProcessor<IAnimatable> processor = processor();
        GeoModel model = model("cached_bone");

        assertFalse(processor.selectModel(model), "第一次见到这个模型必须返回\"需要登记\"");
        register(processor, model);
        java.util.List<IBone> registered = processor.getModelRendererList();
        assertEquals(1, registered.size());
        assertEquals("cached_bone", processor.getBoneByNameMap()
            .get("cached_bone")
            .getName());

        assertTrue(processor.selectModel(model), "第二次必须命中缓存");
        assertSame(registered, processor.getModelRendererList(), "缓存命中要整体换引用，不能重建");
        assertEquals(1, processor.getBoneByNameMap()
            .size(), "命中缓存时索引不能被清掉");
    }

    /** 在多个模型之间来回切换：每个模型各留一份登记结果，互不覆盖。 */
    @Test
    void eachModelKeepsItsOwnRegistration() throws Exception {
        AnimationProcessor<IAnimatable> processor = processor();
        GeoModel a = model("bone_a");
        GeoModel b = model("bone_b");

        assertFalse(processor.selectModel(a));
        register(processor, a);
        assertFalse(processor.selectModel(b));
        register(processor, b);
        assertEquals("bone_b", processor.getBoneByNameMap()
            .get("bone_b")
            .getName());
        assertEquals(null, processor.getBoneByNameMap()
            .get("bone_a"), "B 的索引里不能有 A 的骨骼");

        assertTrue(processor.selectModel(a), "切回来的 A 也必须命中缓存");
        assertEquals("bone_a", processor.getBoneByNameMap()
            .get("bone_a")
            .getName());
        assertEquals(1, processor.getModelRendererList()
            .size(), "换模型要整体换骨骼表，不是往里追加");
    }

    /**
     * 资源框架释放几何（{@code DROP_HEAP}）时必须显式让它松手：弱键只保证"键不可达时条目
     * 消失"，而 {@code AnimatedGeoModel.currentModel} 等地方还强引用着这份几何，条目会一直
     * 活着，骨骼表连同 cube 几何也留在堆上。释放后再渲染到它是重新解析出的新对象，
     * 必须走"重新登记"。
     */
    @Test
    void aReleasedModelDropsItsRegistration() throws Exception {
        AnimationProcessor<IAnimatable> processor = processor();
        GeoModel model = model("released_bone");

        assertFalse(processor.selectModel(model));
        register(processor, model);
        assertEquals(1, processor.getBoneByNameMap()
            .size());

        processor.forgetModel(model);

        assertEquals(0, processor.getBoneByNameMap()
            .size(), "释放后处理器不能再钉着这份骨骼表");
        assertEquals(0, processor.getModelRendererList()
            .size());
        assertFalse(processor.selectModel(model), "释放后再见到它必须重新登记（重建骨骼表）");
    }
}
