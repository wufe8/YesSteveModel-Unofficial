package software.bernie.geckolib3.core.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;

import com.google.common.collect.Maps;

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
    private List<IBone> modelRendererList = new ArrayList();
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
        ModelRegistration removed = registrations.remove(model);
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
    }

    // YSMU: Added crashWhenCantFindBone parameter — when true, missing bones
    // throw RuntimeException instead of silently skipping, useful for debugging
    // model mismatch issues.
    public void tickAnimation(IAnimatable entity, Integer uniqueID, double seekTime, AnimationEvent event,
        MolangParser parser, boolean crashWhenCantFindBone) {
        // TEMP probe: 见 com.fox.ysmu.util.GeoStats
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
        HashMap<String, DirtyTracker> modelTracker = createNewDirtyTracker();

        // Store the current value of each bone rotation/position/scale
        updateBoneSnapshots(manager.getBoneSnapshotCollection());
        HashMap<String, Pair<IBone, BoneSnapshot>> boneSnapshots = manager.getBoneSnapshotCollection();
        HashMap<String, PointData> pointDataGroup = Maps.newHashMap();
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
                BoneSnapshot snapshot = boneSnapshots.get(bone.getName())
                    .getRight();
                BoneSnapshot initialSnapshot = bone.getInitialSnapshot();
                pointDataGroup.putIfAbsent(bone.getName(), new PointData());
                PointData pointData = pointDataGroup.get(bone.getName());

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
                DirtyTracker dirtyTracker = modelTracker.get(bone.getName());
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

    private HashMap<String, DirtyTracker> createNewDirtyTracker() {
        HashMap<String, DirtyTracker> tracker = new HashMap<>();
        for (IBone bone : modelRendererList) {
            tracker.put(bone.getName(), new DirtyTracker(false, false, false, bone));
        }
        return tracker;
    }

    private void updateBoneSnapshots(HashMap<String, Pair<IBone, BoneSnapshot>> boneSnapshotCollection) {
        for (IBone bone : modelRendererList) {
            if (!boneSnapshotCollection.containsKey(bone.getName())) {
                boneSnapshotCollection.put(bone.getName(), Pair.of(bone, new BoneSnapshot(bone.getInitialSnapshot())));
            }
        }
    }

    /**
     * Gets a bone by name.
     *
     * @param boneName The bone name
     * @return the bone
     */
    public IBone getBone(String boneName) {
        return modelRendererList.stream()
            .filter(
                x -> x.getName()
                    .equals(boneName))
            .findFirst()
            .orElse(null);
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
    }

    public void clearModelRendererList() {
        this.modelRendererList.clear();
        this.currentRegistration.byName.clear();
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
