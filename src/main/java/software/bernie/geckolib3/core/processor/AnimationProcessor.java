package software.bernie.geckolib3.core.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;

import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.keyframe.AnimationPoint;
import software.bernie.geckolib3.core.keyframe.BoneAnimationQueue;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;
import software.bernie.geckolib3.core.snapshot.DirtyTracker;
import software.bernie.geckolib3.core.util.MathUtil;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.model.provider.data.EntityModelData;

public class AnimationProcessor<T extends IAnimatable> {

    public boolean reloadAnimations = false;
    private List<IBone> modelRendererList;
    private Map<Integer, AnimationRenderState> animatedEntities = new HashMap<>();
    private final IAnimatableModel animatedModel;

    /**
     * 每个 {@link GeoModel} 的骨骼登记结果（骨骼表 + 名字索引），按 GeoModel 身份缓存。
     *
     * <p>为什么要缓存：模型预览页有十几个预览各自播不同的模型，而它们共用同一个
     * GeckoLib 模型实例（{@code CustomPlayerRenderer#getGeoModelProvider()}），于是
     * {@code AnimatedGeoModel#getModel()} 里的 {@code model != currentModel} 每次渲染都会成立
     * —— 旧实现每次都 {@code clearModelRendererList()} + 递归重走整棵骨骼树 +
     * {@code saveInitialSnapshot()}（VirtualBone 还会重新分配 BoneSnapshot）。
     * 纯净预览页采样里这条路径 self 约 350-600 ms，随模型数线性增长。
     *
     * <p>用 {@link java.util.WeakHashMap}：GeoModel 由 {@code GeckoLibCache} 持有并且会被
     * 资源生命周期淘汰，处理器不能强引用它们（否则淘汰失效）。
     */
    private final Map<GeoModel, ModelRegistration> registrations = new java.util.WeakHashMap<>();

    /** 当前生效的登记结果；{@link #modelRendererList} 永远指向它的 {@link ModelRegistration#bones}。 */
    private ModelRegistration currentRegistration = new ModelRegistration();

    /** 一次模型登记的产物：骨骼表 + 名字索引。控制器直接复用后者，不再自己重建。 */
    public static final class ModelRegistration {

        private final List<IBone> bones = new ArrayList<>();
        private final Map<String, IBone> byName = new HashMap<>();
        /**
         * {@link #getBone(String)} 用的同名索引，保留**最先**登记的同名骨骼。
         *
         * <p>不能复用 {@link #byName}：那个是 last-wins（控制器按它取骨骼，改语义风险大），
         * 而旧的 {@code getBone} 是 {@code modelRendererList.stream().filter(...).findFirst()}，
         * 即 first-wins。同名骨骼极罕见，但两者行为不同，所以分开维护、各按各的语义。</p>
         */
        private final Map<String, IBone> byNameFirst = new HashMap<>();
        // Scratch follows this registration's lifetime, rather than a second global identity map.
        private TrackerSlot[] trackerSlots = new TrackerSlot[2];
        private long revision;
        private final java.util.Set<AnimationData> consumers = java.util.Collections.newSetFromMap(
            new java.util.WeakHashMap<AnimationData, Boolean>());

        private void clear() {
            java.util.Set<IBone> released = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            released.addAll(bones);
            for (AnimationData data : consumers) {
                data.releaseGeometry(released, byName);
            }
            consumers.clear();
            bones.clear();
            byName.clear();
            byNameFirst.clear();
            trackerSlots = new TrackerSlot[2];
            revision++;
        }
    }

    /** YSMU: 切换当前模型。命中缓存（这个 GeoModel 登记过）就整体换上，
     *  返回 true；未命中时装上空的登记结果并返回 false —— 调用方需要重新走一遍骨骼树。 */
    public boolean selectModel(GeoModel model) {
        ModelRegistration registration = registrations.get(model);
        if (registration == null) {
            registration = new ModelRegistration();
            registrations.put(model, registration);
            this.currentRegistration = registration;
            this.modelRendererList = registration.bones;
            return false;
        }
        this.currentRegistration = registration;
        this.modelRendererList = registration.bones;
        return true;
    }

    /** 当前模型的"骨骼名 → IBone"索引。控制器只读，不要改。 */
    public Map<String, IBone> getBoneByNameMap() {
        return currentRegistration.byName;
    }

    /**
     * 资源框架把某个 {@link GeoModel} 释放（{@code ReleaseMode.DROP_HEAP}）时调用：
     * 丢掉它的骨骼登记结果。
     *
     * <p>为什么必须显式丢：弱键只保证"键不可达时条目会消失"，但只要还有别的地方强引用这份
     * 几何（{@link software.bernie.geckolib3.model.AnimatedGeoModel#getCurrentModel()} 就是
     * 一个），条目就一直活着，骨骼表连同它引用的 cube 几何也跟着留在堆上 —— 这一侧的
     * 释放就白做了。释放后若再渲染到它，拿到的必然是重新解析出来的**新** GeoModel 对象，
     * 身份缓存未命中会重建，所以丢掉是安全的。
     */
    public void forgetModel(GeoModel model) {
        if (model == null) {
            return;
        }
        clearAnimatedEntities();
        ModelRegistration removed = registrations.remove(model);
        if (removed != null) {
            removed.clear();
        }
        if (removed != null && removed == currentRegistration) {
            // 控制器还各自持有这份 byName 的引用（它们会在下一次 process() 换成新模型的），
            // 处理器自己至少不能再钉着它。
            currentRegistration = new ModelRegistration();
            modelRendererList = currentRegistration.bones;
        }
    }

    /** YSMU: Clear the per-frame deduplication cache so the next tickAnimation()
     *  call is guaranteed to process animation even for the same entity+seekTime.
     *  Used by GUI preview rendering (ModelButton FBOs) where multiple renders of
     *  the same cached entity must each produce fresh bone values. */
    public void clearAnimatedEntities() {
        animatedEntities.clear();
    }

    public AnimationProcessor(IAnimatableModel animatedModel) {
        this.animatedModel = animatedModel;
        this.modelRendererList = currentRegistration.bones;
    }

    // YSMU: Added crashWhenCantFindBone parameter — when true, missing bones
    // throw RuntimeException instead of silently skipping, useful for debugging
    // model mismatch issues.
    public void tickAnimation(IAnimatable entity, Integer uniqueID, double seekTime, AnimationEvent event,
        MolangParser parser, boolean crashWhenCantFindBone) {
        // 诊断计数：只有 Config.DEBUG_MODEL_RENDER 打开时才真的自增（见 GeoStats）。
        com.fox.ysmu.util.GeoStats.noteAnimTick();
        AnimationRenderState renderState = AnimationRenderState.from(seekTime, event);
        if (renderState.equals(animatedEntities.get(uniqueID))) {
            return;
        }
        animatedEntities.put(uniqueID, renderState);

        // Each animation has it's own collection of animations (called the
        // EntityAnimationManager), which allows for multiple independent animations
        AnimationData manager = entity.getFactory()
            .getOrCreateAnimationData(uniqueID);
        // Keeps track of which bones have had animations applied to them, and
        // eventually sets the ones that don't have an animation to their default values
        currentRegistration.consumers.add(manager);
        HashMap<String, DirtyTracker> modelTracker = createNewDirtyTracker();

        boolean completed = false;
        try {
            // Store the current value of each bone rotation/position/scale
            updateBoneSnapshots(manager.getBoneSnapshotCollection());
            HashMap<String, Pair<IBone, BoneSnapshot>> boneSnapshots = manager.getBoneSnapshotCollection();
            // YSMU: 与本 tick 的 DirtyTracker 表同一个复用槽位（见 pointDataGroupForThisTick）。
            HashMap<String, PointData> pointDataGroup = pointDataGroupForThisTick();
            for (AnimationController<T> controller : manager.getAnimationControllers()
                .values()) {
                if (reloadAnimations) {
                    controller.markNeedsReload();
                    controller.getBoneAnimationQueues()
                        .clear();
                }

                controller.isJustStarting = manager.isFirstTick;

                // Set current controller to animation test event
                event.setController(controller);

                // Process animations and add new values to the point queues
                controller.process(seekTime, event, currentRegistration.byName, boneSnapshots, parser,
                    crashWhenCantFindBone);

                // Loop through every single bone and lerp each property
                for (BoneAnimationQueue boneAnimation : controller.getActiveBoneAnimationQueues()) {
                    IBone bone = boneAnimation.bone;
                    // 骨名一次取出：下面 boneSnapshots / pointDataGroup / modelTracker 三张表都用它。
                    // 旧的写法每个 (骨骼 × 控制器 × tick) 要 getName() 四次、哈希四次。
                    String boneName = bone.getName();
                    BoneSnapshot snapshot = boneSnapshots.get(boneName)
                        .getRight();
                    BoneSnapshot initialSnapshot = bone.getInitialSnapshot();
                    // computeIfAbsent 一次查找拿到 PointData；旧写法 putIfAbsent + get 是两次。
                    PointData pointData = pointDataGroup.computeIfAbsent(boneName, k -> new PointData());

                    AnimationPoint rXPoint = boneAnimation.rotationXQueue.poll();
                    AnimationPoint rYPoint = boneAnimation.rotationYQueue.poll();
                    AnimationPoint rZPoint = boneAnimation.rotationZQueue.poll();

                    AnimationPoint pXPoint = boneAnimation.positionXQueue.poll();
                    AnimationPoint pYPoint = boneAnimation.positionYQueue.poll();
                    AnimationPoint pZPoint = boneAnimation.positionZQueue.poll();

                    AnimationPoint sXPoint = boneAnimation.scaleXQueue.poll();
                    AnimationPoint sYPoint = boneAnimation.scaleYQueue.poll();
                    AnimationPoint sZPoint = boneAnimation.scaleZQueue.poll();

                    // If there's any rotation points for this bone
                    DirtyTracker dirtyTracker = modelTracker.get(boneName);
                    if (dirtyTracker == null) {
                        if (rXPoint != null) rXPoint.recycle();
                        if (rYPoint != null) rYPoint.recycle();
                        if (rZPoint != null) rZPoint.recycle();
                        if (pXPoint != null) pXPoint.recycle();
                        if (pYPoint != null) pYPoint.recycle();
                        if (pZPoint != null) pZPoint.recycle();
                        if (sXPoint != null) sXPoint.recycle();
                        if (sYPoint != null) sYPoint.recycle();
                        if (sZPoint != null) sZPoint.recycle();
                        continue;
                    }
                    if (rXPoint != null && rYPoint != null && rZPoint != null) {
                        float valueX = MathUtil.lerpValues(rXPoint, controller.easingType, controller.customEasingMethod);
                        float valueY = MathUtil.lerpValues(rYPoint, controller.easingType, controller.customEasingMethod);
                        float valueZ = MathUtil.lerpValues(rZPoint, controller.easingType, controller.customEasingMethod);
                        // YSMU: wiki「并行动画」——`parallel` 族的旋转是"特殊的混合"：与低优先级层相加，
                        // 而不是覆盖（"这个混合仅会混合旋转，不会混合位移和缩放"；OpenYSM 对 parallel
                        // 注册 deprecatedMode=true，走 vector3f.add(value)）。position/scale 仍然覆盖。
                        // 不这样做的话，一个"pre_parallel 转轮胎 + parallel 转向"的模型里，后处理的
                        // parallel 会把整条 rotation 向量覆盖掉，轮胎就不转了。
                        boolean additive = controller.isAdditiveRotation();
                        pointData.rotationValueX = combineRotation(pointData.rotationValueX, valueX, additive);
                        pointData.rotationValueY = combineRotation(pointData.rotationValueY, valueY, additive);
                        pointData.rotationValueZ = combineRotation(pointData.rotationValueZ, valueZ, additive);
                        bone.setRotationX(pointData.rotationValueX + initialSnapshot.rotationValueX);
                        bone.setRotationY(pointData.rotationValueY + initialSnapshot.rotationValueY);
                        bone.setRotationZ(pointData.rotationValueZ + initialSnapshot.rotationValueZ);
                        snapshot.rotationValueX = bone.getRotationX();
                        snapshot.rotationValueY = bone.getRotationY();
                        snapshot.rotationValueZ = bone.getRotationZ();
                        snapshot.isCurrentlyRunningRotationAnimation = true;
                        dirtyTracker.hasRotationChanged = true;
                    }

                    // If there's any position points for this bone
                    if (pXPoint != null && pYPoint != null && pZPoint != null) {
                        bone.setPositionX(
                            MathUtil.lerpValues(pXPoint, controller.easingType, controller.customEasingMethod));
                        bone.setPositionY(
                            MathUtil.lerpValues(pYPoint, controller.easingType, controller.customEasingMethod));
                        bone.setPositionZ(
                            MathUtil.lerpValues(pZPoint, controller.easingType, controller.customEasingMethod));
                        snapshot.positionOffsetX = bone.getPositionX();
                        snapshot.positionOffsetY = bone.getPositionY();
                        snapshot.positionOffsetZ = bone.getPositionZ();
                        snapshot.isCurrentlyRunningPositionAnimation = true;

                        dirtyTracker.hasPositionChanged = true;
                    }

                    // If there's any scale points for this bone
                    if (sXPoint != null && sYPoint != null && sZPoint != null) {
                        bone.setScaleX(MathUtil.lerpValues(sXPoint, controller.easingType, controller.customEasingMethod));
                        bone.setScaleY(MathUtil.lerpValues(sYPoint, controller.easingType, controller.customEasingMethod));
                        bone.setScaleZ(MathUtil.lerpValues(sZPoint, controller.easingType, controller.customEasingMethod));
                        snapshot.scaleValueX = bone.getScaleX();
                        snapshot.scaleValueY = bone.getScaleY();
                        snapshot.scaleValueZ = bone.getScaleZ();
                        snapshot.isCurrentlyRunningScaleAnimation = true;

                        dirtyTracker.hasScaleChanged = true;
                    }

                    // Recycle all polled AnimationPoints back to the pool
                    if (rXPoint != null) rXPoint.recycle();
                    if (rYPoint != null) rYPoint.recycle();
                    if (rZPoint != null) rZPoint.recycle();
                    if (pXPoint != null) pXPoint.recycle();
                    if (pYPoint != null) pYPoint.recycle();
                    if (pZPoint != null) pZPoint.recycle();
                    if (sXPoint != null) sXPoint.recycle();
                    if (sYPoint != null) sYPoint.recycle();
                    if (sZPoint != null) sZPoint.recycle();
                }
            }

            this.reloadAnimations = false;

            double resetTickLength = manager.getResetSpeed();
            for (Map.Entry<String, DirtyTracker> tracker : modelTracker.entrySet()) {
                IBone model = tracker.getValue().model;
                BoneSnapshot initialSnapshot = model.getInitialSnapshot();
                BoneSnapshot saveSnapshot = boneSnapshots.get(tracker.getKey())
                    .getRight();
                if (saveSnapshot == null) {
                    if (crashWhenCantFindBone) {
                        throw new RuntimeException(
                            "Could not find save snapshot for bone: " + tracker.getValue().model.getName()
                                + ". Please don't add bones that are used in an animation at runtime.");
                    } else {
                        continue;
                    }
                }

                if (!tracker.getValue().hasRotationChanged) {
                    if (saveSnapshot.isCurrentlyRunningRotationAnimation) {
                        saveSnapshot.mostRecentResetRotationTick = 0; // TODO 原为(float) seekTime，旋转问题相关
                        saveSnapshot.isCurrentlyRunningRotationAnimation = false;
                    }

                    double percentageReset = Math
                        .min((seekTime - saveSnapshot.mostRecentResetRotationTick) / resetTickLength, 1);

                    model.setRotationX(
                        MathUtil.lerpValues(percentageReset, saveSnapshot.rotationValueX, initialSnapshot.rotationValueX));
                    model.setRotationY(
                        MathUtil.lerpValues(percentageReset, saveSnapshot.rotationValueY, initialSnapshot.rotationValueY));
                    model.setRotationZ(
                        MathUtil.lerpValues(percentageReset, saveSnapshot.rotationValueZ, initialSnapshot.rotationValueZ));

                    if (percentageReset >= 1) {
                        saveSnapshot.rotationValueX = model.getRotationX();
                        saveSnapshot.rotationValueY = model.getRotationY();
                        saveSnapshot.rotationValueZ = model.getRotationZ();
                    }
                }
                if (!tracker.getValue().hasPositionChanged) {
                    if (saveSnapshot.isCurrentlyRunningPositionAnimation) {
                        // YSMU fix: start the reset timer at 0 (matching the rotation
                        // reset above) so a bone whose position animation stops
                        // lerps back to its bind-pose offset. Previously this was
                        // set to (float) seekTime, making percentageReset always 0 —
                        // the bone kept its last animated position forever. That
                        // caused pose bleed: e.g. after sneaking_Control (Root
                        // lowered to [0,-7.625,0]) the moving-sneak 行走 pose never
                        // returned the body to standing height.
                        //
                        // YSM 语义依据: YSM 中未被当前动画覆盖的骨骼应回到绑定姿势
                        // (bind pose), 而非停留在上一个动画的最后一帧 position。
                        // 该修复与此语义一致, 且与 rotation reset 逻辑对称。
                        // 注意: 本修复不是"移动潜行显示站立潜行"问题的根因——
                        // 那个根因是 main_controller legacy 潜行动画覆盖了 pre_main
                        // (见 AnimationManager.predicateMain 的 OpenYSM 潜行跳过),
                        // 但 position reset 仍是防御性的正确修复, 防止类似姿势残留。
                        saveSnapshot.mostRecentResetPositionTick = 0;
                        saveSnapshot.isCurrentlyRunningPositionAnimation = false;
                    }

                    double percentageReset = Math
                        .min((seekTime - saveSnapshot.mostRecentResetPositionTick) / resetTickLength, 1);

                    model.setPositionX(
                        MathUtil
                            .lerpValues(percentageReset, saveSnapshot.positionOffsetX, initialSnapshot.positionOffsetX));
                    model.setPositionY(
                        MathUtil
                            .lerpValues(percentageReset, saveSnapshot.positionOffsetY, initialSnapshot.positionOffsetY));
                    model.setPositionZ(
                        MathUtil
                            .lerpValues(percentageReset, saveSnapshot.positionOffsetZ, initialSnapshot.positionOffsetZ));

                    if (percentageReset >= 1) {
                        saveSnapshot.positionOffsetX = model.getPositionX();
                        saveSnapshot.positionOffsetY = model.getPositionY();
                        saveSnapshot.positionOffsetZ = model.getPositionZ();
                    }
                }
                if (!tracker.getValue().hasScaleChanged) {
                    if (saveSnapshot.isCurrentlyRunningScaleAnimation) {
                        saveSnapshot.mostRecentResetScaleTick = (float) seekTime;
                        saveSnapshot.isCurrentlyRunningScaleAnimation = false;
                    }

                    double percentageReset = Math
                        .min((seekTime - saveSnapshot.mostRecentResetScaleTick) / resetTickLength, 1);

                    model.setScaleX(
                        MathUtil.lerpValues(percentageReset, saveSnapshot.scaleValueX, initialSnapshot.scaleValueX));
                    model.setScaleY(
                        MathUtil.lerpValues(percentageReset, saveSnapshot.scaleValueY, initialSnapshot.scaleValueY));
                    model.setScaleZ(
                        MathUtil.lerpValues(percentageReset, saveSnapshot.scaleValueZ, initialSnapshot.scaleValueZ));

                    if (percentageReset >= 1) {
                        saveSnapshot.scaleValueX = model.getScaleX();
                        saveSnapshot.scaleValueY = model.getScaleY();
                        saveSnapshot.scaleValueZ = model.getScaleZ();
                    }
                }
            }
            manager.isFirstTick = false;
            completed = true;
        } finally {
            releaseDirtyTracker();
            // A failed evaluation must remain retryable even at the same entity/time.
            if (!completed) {
                animatedEntities.remove(uniqueID);
            }
        }
    }

    /**
     * YSMU: 一个骨骼的 rotation 通道被多个控制器依次写时的取值规则。
     *
     * <p>wiki「并行动画」：{@code parallel} 族是"特殊的混合动画"——它的旋转与**低优先级层相加**，
     * 而不是覆盖（"这个混合仅会混合旋转，不会混合位移和缩放"）。OpenYSM 的实现同样是相加：
     * parallel 族注册 {@code deprecatedMode=true}，{@code AnimationProcessor} 对它们走
     * {@code vector3f.add(value)}，其余控制器走覆盖。位移/缩放两条通道不走这个函数。</p>
     *
     * @param previous 本帧已由低优先级控制器写下的 rotation 偏移分量
     * @param value    当前控制器这一帧的 rotation 偏移分量
     * @param additive 当前控制器是否属于 {@code parallel} 族
     */
    static float combineRotation(float previous, float value, boolean additive) {
        return additive ? previous + value : value;
    }

    /** Active calls only; inactive scratch is owned by ModelRegistration. Render-thread confined. */
    private TrackerSlot[] activeSlots = new TrackerSlot[4];
    private int trackerDepth;

    private static final class TrackerSlot {
        private final HashMap<String, DirtyTracker> trackers = new HashMap<>();
        private long builtRevision = -1;
        private HashMap<String, PointData> pointData;
    }

    private TrackerSlot acquireTrackerSlot() {
        TrackerSlot[] slots = currentRegistration.trackerSlots;
        if (trackerDepth >= slots.length) {
            slots = java.util.Arrays.copyOf(slots, trackerDepth * 2 + 1);
            currentRegistration.trackerSlots = slots;
        }
        TrackerSlot slot = slots[trackerDepth];
        if (slot == null) {
            slot = new TrackerSlot();
            slots[trackerDepth] = slot;
        }
        if (trackerDepth == activeSlots.length) {
            activeSlots = java.util.Arrays.copyOf(activeSlots, trackerDepth * 2);
        }
        activeSlots[trackerDepth++] = slot;
        return slot;
    }

    HashMap<String, DirtyTracker> createNewDirtyTracker() {
        TrackerSlot slot = acquireTrackerSlot();
        HashMap<String, DirtyTracker> tracker = slot.trackers;
        if (slot.builtRevision != currentRegistration.revision) {
            // Registration identity alone is insufficient: virtual bones may arrive after the first tick.
            tracker.clear();
            for (IBone bone : modelRendererList) {
                tracker.put(bone.getName(), new DirtyTracker(false, false, false, bone));
            }
            if (slot.pointData != null) {
                slot.pointData.clear();
            }
            slot.builtRevision = currentRegistration.revision;
            return tracker;
        }
        // 同一个模型的第二次及以后：键和 DirtyTracker 对象都留着，只复位三个标记。
        for (DirtyTracker tracked : tracker.values()) {
            tracked.hasRotationChanged = false;
            tracked.hasPositionChanged = false;
            tracked.hasScaleChanged = false;
        }
        return tracker;
    }

    /**
     * 本 tick 的 PointData 表，复用 {@link #createNewDirtyTracker()} 刚刚取到的那一层
     * 槽位（所以必须在它之后、{@link #releaseDirtyTracker()} 之前调用）。
     *
     * <p>旧实现每 tick {@code new HashMap<>(capacityForBones(n))} + 每骨骼
     * {@code computeIfAbsent(…, k -> new PointData())}：堆快照里 493,170 个 PointData
     * 与 268,149 个 HashMap$Node[] 里的一部分就是它。PointData 携带的只是"本 tick 各控制器
     * 累加出来的旋转分量"，所以键集与对象都能跨 tick 留着，只把分量清零 ——
     * {@code parallel} 族的旋转是**相加**的，必须从 0 开始，不能沿用上一 tick 的值。
     */
    HashMap<String, PointData> pointDataGroupForThisTick() {
        TrackerSlot slot = activeSlots[trackerDepth - 1];
        if (slot.pointData == null) {
            slot.pointData = new HashMap<>(capacityForBones(modelRendererList.size()));
        }
        // 槽位是按模型索引的，键集天然只属于这个模型；把上一 tick 的累加值清零即可。
        HashMap<String, PointData> group = slot.pointData;
        for (PointData pointData : group.values()) {
            pointData.reset();
        }
        return group;
    }

    /** 见 {@link #createNewDirtyTracker()}：一层 tick 结束，弹出这一层的槽位。 */
    void releaseDirtyTracker() {
        if (trackerDepth > 0) {
            activeSlots[--trackerDepth] = null;
        }
    }

    /** 让 {@code n} 个键一次装进 HashMap 而不触发 resize 的初始容量。 */
    private static int capacityForBones(int n) {
        return Math.max(16, (int) (n / 0.75F) + 1);
    }

    void updateBoneSnapshots(HashMap<String, Pair<IBone, BoneSnapshot>> boneSnapshotCollection) {
        for (IBone bone : modelRendererList) {
            // 一次查找替代旧的 containsKey + put 两次（骨骼数 × tick）。
            // 这里不能用 computeIfAbsent：它的 lambda 捕获了 bone，于是**每骨骼每 tick**
            // 都会分配一个 lambda 实例（堆快照里 765,772 个 AnimationProcessor$$Lambda）。
            String name = bone.getName();
            if (boneSnapshotCollection.get(name) == null) {
                boneSnapshotCollection.put(name, Pair.of(bone, new BoneSnapshot(bone.getInitialSnapshot())));
            }
        }
    }

    /**
     * Gets a bone by name.
     *
     * <p>走 {@link ModelRegistration#byNameFirst}（first-wins，与旧的 stream+findFirst 一致）。
     * 以前这里是 {@code modelRendererList.stream().filter(...).findFirst()} —— 每次调用都线性
     * 扫过整张骨骼表并逐个比较字符串；Molang 的骨骼查询函数（如 {@code ysm.bone_rotation}）
     * 每次关键帧求值都会调它，实测独占客户端线程 2.0%。</p>
     *
     * @param boneName The bone name
     * @return the bone, or null when this model has no such bone
     */
    public IBone getBone(String boneName) {
        return currentRegistration.byNameFirst.get(boneName);
    }

    /**
     * Register model renderer. Each AnimatedModelRenderer (group in blockbench)
     * NEEDS to be registered via this method.
     *
     * @param modelRenderer The model renderer
     */
    public void registerModelRenderer(IBone modelRenderer) {
        modelRenderer.saveInitialSnapshot();
        modelRendererList.add(modelRenderer);
        currentRegistration.byName.put(modelRenderer.getName(), modelRenderer);
        currentRegistration.byNameFirst.putIfAbsent(modelRenderer.getName(), modelRenderer);
        currentRegistration.revision++;
        clearAnimatedEntities();
    }

    public void clearModelRendererList() {
        currentRegistration.clear();
        clearAnimatedEntities();
    }

    public List<IBone> getModelRendererList() {
        return modelRendererList;
    }

    public void preAnimationSetup(IAnimatable animatable, double seekTime) {
        this.animatedModel.setMolangQueries(animatable, seekTime);
    }

    private static final class AnimationRenderState {
        private final double seekTime;
        private final float limbSwing;
        private final float limbSwingAmount;
        private final float partialTick;
        private final boolean moving;
        private final boolean hasEntityModelData;
        private final int entityModelDataIdentity;
        private final boolean sitting;
        private final boolean child;
        private final float netHeadYaw;
        private final float headPitch;

        private AnimationRenderState(double seekTime, AnimationEvent event, EntityModelData entityModelData) {
            this.seekTime = seekTime;
            this.limbSwing = event.getLimbSwing();
            this.limbSwingAmount = event.getLimbSwingAmount();
            this.partialTick = event.getPartialTick();
            this.moving = event.isMoving();
            this.hasEntityModelData = entityModelData != null;
            // EntityModelData is recreated per render call; its identity separates GUI and world poses at the same tick.
            this.entityModelDataIdentity = entityModelData == null ? 0 : System.identityHashCode(entityModelData);
            this.sitting = entityModelData != null && entityModelData.isSitting;
            this.child = entityModelData != null && entityModelData.isChild;
            this.netHeadYaw = entityModelData == null ? 0.0F : entityModelData.netHeadYaw;
            this.headPitch = entityModelData == null ? 0.0F : entityModelData.headPitch;
        }

        private static AnimationRenderState from(double seekTime, AnimationEvent event) {
            return new AnimationRenderState(seekTime, event, getEntityModelData(event));
        }

        private static EntityModelData getEntityModelData(AnimationEvent event) {
            for (Object data : event.getExtraData()) {
                if (data instanceof EntityModelData) {
                    return (EntityModelData) data;
                }
            }
            return null;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof AnimationRenderState)) {
                return false;
            }
            AnimationRenderState other = (AnimationRenderState) obj;
            return Double.doubleToLongBits(this.seekTime) == Double.doubleToLongBits(other.seekTime)
                && Float.floatToIntBits(this.limbSwing) == Float.floatToIntBits(other.limbSwing)
                && Float.floatToIntBits(this.limbSwingAmount) == Float.floatToIntBits(other.limbSwingAmount)
                && Float.floatToIntBits(this.partialTick) == Float.floatToIntBits(other.partialTick)
                && this.moving == other.moving
                && this.hasEntityModelData == other.hasEntityModelData
                && this.entityModelDataIdentity == other.entityModelDataIdentity
                && this.sitting == other.sitting
                && this.child == other.child
                && Float.floatToIntBits(this.netHeadYaw) == Float.floatToIntBits(other.netHeadYaw)
                && Float.floatToIntBits(this.headPitch) == Float.floatToIntBits(other.headPitch);
        }

        @Override
        public int hashCode() {
            int result = (int) (Double.doubleToLongBits(this.seekTime) ^ (Double.doubleToLongBits(this.seekTime) >>> 32));
            result = 31 * result + Float.floatToIntBits(this.limbSwing);
            result = 31 * result + Float.floatToIntBits(this.limbSwingAmount);
            result = 31 * result + Float.floatToIntBits(this.partialTick);
            result = 31 * result + (this.moving ? 1 : 0);
            result = 31 * result + (this.hasEntityModelData ? 1 : 0);
            result = 31 * result + this.entityModelDataIdentity;
            result = 31 * result + (this.sitting ? 1 : 0);
            result = 31 * result + (this.child ? 1 : 0);
            result = 31 * result + Float.floatToIntBits(this.netHeadYaw);
            result = 31 * result + Float.floatToIntBits(this.headPitch);
            return result;
        }
    }
}
