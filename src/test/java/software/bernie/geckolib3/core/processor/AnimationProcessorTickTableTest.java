package software.bernie.geckolib3.core.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;
import software.bernie.geckolib3.core.snapshot.DirtyTracker;
import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * {@link AnimationProcessor} 每个 tick 都要重建的两张表：{@code 骨骼名 → DirtyTracker} 与
 * {@code 骨骼名 → BoneSnapshot}。
 *
 * <p>回归点（来自纯净预览页采样）：十几个预览各自推进动画，每个 tick、每个模型都要把这两张表
 * 按骨骼名重建一遍。旧写法有三个浪费：HashMap 从默认容量 16 开始、骨骼一多每 tick 扩容重哈希
 * （{@code HashMap.resize} 独占客户端线程 1.3%）；{@code putIfAbsent + get} 与
 * {@code containsKey + put} 都是"同一个键查两次"。这里钉住重构后语义不变。</p>
 */
class AnimationProcessorTickTableTest {

    private static GeoModel model(String boneA, String boneB) throws Exception {
        return AnimationProcessorRegistrationCacheTest.modelWithBones(boneA, boneB);
    }

    private static AnimationProcessor<IAnimatable> registered(String boneA, String boneB) throws Exception {
        AnimationProcessor<IAnimatable> processor = new AnimationProcessor<>(null);
        GeoModel model = model(boneA, boneB);
        assertFalse(processor.selectModel(model));
        AnimationProcessorRegistrationCacheTest.register(processor, model);
        return processor;
    }

    /**
     * DirtyTracker 表：每个骨骼一条，且每次 tick 都从"三个标记全 false"开始。
     *
     * <p>回归点（VisualVM）：以前每 tick 都要 {@code new} 一张表 + 每根骨骼
     * {@code new DirtyTracker(...)}，{@code createNewDirtyTracker} 独占客户端线程
     * 18.5ms/s。现在按嵌套深度复用表与 tracker 对象，只把标记复位 —— 所以这里断言的是
     * **语义**（键齐全、标记从 false 开始、上一个 tick 的 true 不会漏进来），
     * 而不是"每次都是新对象"。</p>
     */
    @Test
    void dirtyTrackerTableStartsCleanEveryTick() throws Exception {
        AnimationProcessor<IAnimatable> processor = registered("bone_a", "bone_b");

        HashMap<String, DirtyTracker> first = processor.createNewDirtyTracker();
        assertEquals(2, first.size(), "每个骨骼一条 DirtyTracker");
        assertEquals(
            "bone_a",
            first.get("bone_a")
                .model.getName());
        assertFalse(
            first.get("bone_a").hasRotationChanged,
            "新表里所有标记都从 false 开始");
        // 模拟这一帧里骨骼真的动过
        first.get("bone_a").hasRotationChanged = true;
        first.get("bone_b").hasPositionChanged = true;
        first.get("bone_b").hasScaleChanged = true;
        processor.releaseDirtyTracker();

        HashMap<String, DirtyTracker> second = processor.createNewDirtyTracker();
        assertEquals(2, second.size(), "键集必须仍然齐全");
        assertFalse(second.get("bone_a").hasRotationChanged, "上一个 tick 的 true 不能漏进来");
        assertFalse(second.get("bone_b").hasPositionChanged, "上一个 tick 的 true 不能漏进来");
        assertFalse(second.get("bone_b").hasScaleChanged, "上一个 tick 的 true 不能漏进来");
        processor.releaseDirtyTracker();
    }

    /** 换模型（骨骼表换了引用）时必须整表重建，不能留下上一个模型的骨骼。 */
    @Test
    void dirtyTrackerTableRebuildsWhenTheModelChanges() throws Exception {
        AnimationProcessor<IAnimatable> processor = registered("bone_a", "bone_b");
        HashMap<String, DirtyTracker> before = processor.createNewDirtyTracker();
        assertEquals(2, before.size());
        processor.releaseDirtyTracker();

        // 换一份骨骼表：仍用同一个 processor，但 selectModel 到另一个 GeoModel
        GeoModel other = AnimationProcessorRegistrationCacheTest.modelWithBones("bone_c");
        assertFalse(processor.selectModel(other));
        AnimationProcessorRegistrationCacheTest.register(processor, other);

        HashMap<String, DirtyTracker> after = processor.createNewDirtyTracker();
        assertEquals(1, after.size(), "只应有新模型的骨骼");
        assertNotNull(after.get("bone_c"), "新模型的骨骼必须在表里");
        assertNull(after.get("bone_a"), "上一个模型的骨骼不能残留");
        processor.releaseDirtyTracker();
    }

    /**
     * 嵌套推进（同一个 processor 在一次 tickAnimation 还没跑完时又被推进）：内层必须拿到
     * **独立**的表，否则会把外层正在读的标记复位掉。用深度索引池就是为了这个。
     */
    @Test
    void nestedTicksGetIndependentTables() throws Exception {
        AnimationProcessor<IAnimatable> processor = registered("bone_a", "bone_b");

        HashMap<String, DirtyTracker> outer = processor.createNewDirtyTracker();
        outer.get("bone_a").hasRotationChanged = true;

        HashMap<String, DirtyTracker> inner = processor.createNewDirtyTracker();
        assertFalse(inner == outer, "嵌套的两层不能共用同一张表");
        assertFalse(inner.get("bone_a").hasRotationChanged, "内层表必须是干净的");
        assertTrue(outer.get("bone_a").hasRotationChanged, "外层表的标记不能被内层复位");

        processor.releaseDirtyTracker();
        processor.releaseDirtyTracker();
    }

    /**
     * PointData 表：和 DirtyTracker 表同一个复用槽位，键与对象跨 tick 留着，
     * 但每个分量必须从 0 开始 —— {@code parallel} 族的旋转是**相加**的
     * （见 AnimationProcessor.combineRotation），沿用上一个 tick 的值会逐帧累积。
     *
     * <p>回归点（VisualVM 直方图）：旧实现每 tick {@code new HashMap<>(…)} + 每骨骼
     * {@code computeIfAbsent(…, k -> new PointData())}，堆里同时有 493,170 个 PointData。
     */
    @Test
    void pointDataTableIsReusedButEveryComponentStartsAtZero() throws Exception {
        AnimationProcessor<IAnimatable> processor = registered("bone_a", "bone_b");

        processor.createNewDirtyTracker();
        HashMap<String, PointData> first = processor.pointDataGroupForThisTick();
        PointData accumulated = new PointData();
        accumulated.rotationValueX = 5f;
        accumulated.rotationValueY = 6f;
        accumulated.rotationValueZ = 7f;
        first.put("bone_a", accumulated);
        processor.releaseDirtyTracker();

        processor.createNewDirtyTracker();
        HashMap<String, PointData> second = processor.pointDataGroupForThisTick();
        assertSame(first, second, "PointData 表必须跨 tick 复用");
        assertSame(accumulated, second.get("bone_a"), "PointData 对象也要复用");
        assertEquals(0f, accumulated.rotationValueX, "上一 tick 的累加值不能漏进来");
        assertEquals(0f, accumulated.rotationValueY, "上一 tick 的累加值不能漏进来");
        assertEquals(0f, accumulated.rotationValueZ, "上一 tick 的累加值不能漏进来");
        processor.releaseDirtyTracker();
    }

    /** 换模型（骨骼表换了引用）时，PointData 表也必须丢掉上一个模型的键。 */
    @Test
    void pointDataTableRebuildsWhenTheModelChanges() throws Exception {
        AnimationProcessor<IAnimatable> processor = registered("bone_a", "bone_b");
        processor.createNewDirtyTracker();
        HashMap<String, PointData> before = processor.pointDataGroupForThisTick();
        before.put("bone_a", new PointData());
        processor.releaseDirtyTracker();

        GeoModel other = AnimationProcessorRegistrationCacheTest.modelWithBones("bone_c");
        assertFalse(processor.selectModel(other));
        AnimationProcessorRegistrationCacheTest.register(processor, other);

        processor.createNewDirtyTracker();
        HashMap<String, PointData> after = processor.pointDataGroupForThisTick();
        assertNull(after.get("bone_a"), "上一个模型的骨骼不能残留");
        processor.releaseDirtyTracker();
    }

    /**
     * BoneSnapshot 表：只补缺失的键 —— 已有的条目（连同它保存的快照值）不能被覆盖。
     * 这是 {@code containsKey + put} 改成 {@code computeIfAbsent} 时必须保持不变的行为。
     */
    @Test
    void boneSnapshotTableOnlyFillsMissingKeys() throws Exception {
        AnimationProcessor<IAnimatable> processor = registered("bone_a", "bone_b");

        HashMap<String, Pair<IBone, BoneSnapshot>> snapshots = new HashMap<>();
        processor.updateBoneSnapshots(snapshots);
        assertEquals(2, snapshots.size());

        Pair<IBone, BoneSnapshot> kept = snapshots.get("bone_a");
        processor.updateBoneSnapshots(snapshots);

        assertSame(kept, snapshots.get("bone_a"), "已有的快照条目不能被覆盖");
        assertEquals(2, snapshots.size(), "重复调用不能长出新键");
    }
}
