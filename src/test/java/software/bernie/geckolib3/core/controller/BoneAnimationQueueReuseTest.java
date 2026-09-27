package software.bernie.geckolib3.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.eliotlash.mclib.math.IValue;
import com.fox.ysmu.client.animation.VirtualBone;

import software.bernie.geckolib3.core.AnimationState;
import software.bernie.geckolib3.core.ConstantValue;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.easing.EasingType;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.keyframe.AnimationPoint;
import software.bernie.geckolib3.core.keyframe.BoneAnimation;
import software.bernie.geckolib3.core.keyframe.BoneAnimationQueue;
import software.bernie.geckolib3.core.keyframe.KeyFrame;
import software.bernie.geckolib3.core.keyframe.VectorKeyFrameList;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.processor.AnimationProcessor;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.core.util.MathUtil;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;

/**
 * {@link AnimationController#process} 每帧都要用一遍「骨骼 → 动画点队列」，但队列本身
 * （每骨骼 1 个 {@link BoneAnimationQueue} + 9 条 LinkedList）不该每帧重建：
 * 改动前的堆直方图里同时躺着 539,173 个 BoneAnimationQueue 和 4,852,557 条
 * AnimationPointQueue（= 539,173 × 9），全是一次 GC 就该消失的垃圾。
 *
 * <p>复用不能顺手把「清空」一起省掉：队列里上一帧没被 AnimationProcessor {@code poll()}
 * 走的点如果留到下一帧，就会被下一个 {@code poll()} 先读出来 —— 渲染出的是上一帧的姿势。
 * 所以这里同时钉住两件事：<b>同一个骨骼跨帧拿到的是同一个队列对象</b>，
 * 且<b>每帧开始时队列里的旧点已经被丢掉</b>（本测试不替控制器 poll，于是第二帧后
 * 队列里只应有第二帧那 1 个点，而不是 1 + 1 个）。
 */
class BoneAnimationQueueReuseTest {

    private static final String BONE = "root";

    private static final class StubAnimatable implements IAnimatable {

        @Override
        public void registerControllers(AnimationData data) {
            // the controller is constructed by the test
        }

        @Override
        public AnimationFactory getFactory() {
            return null;
        }
    }

    private static final class StubModel implements IAnimatableModel<StubAnimatable> {

        final HashMap<String, Animation> animations = new HashMap<>();

        @Override
        public void setLivingAnimations(StubAnimatable entity, Integer uniqueID, AnimationEvent customPredicate) {
            // no-op
        }

        @Override
        public AnimationProcessor getAnimationProcessor() {
            return null;
        }

        @Override
        public Animation getAnimation(String name, IAnimatable animatable) {
            return animations.get(name);
        }

        @Override
        public void setMolangQueries(IAnimatable animatable, double currentTick) {
            // no-op
        }
    }

    private final StubModel model = new StubModel();
    private final AnimationController.ModelFetcher<StubAnimatable> fetcher =
        new AnimationController.ModelFetcher<StubAnimatable>() {

            @Override
            @SuppressWarnings("unchecked")
            public IAnimatableModel<StubAnimatable> apply(IAnimatable animatable) {
                return (IAnimatableModel<StubAnimatable>) model;
            }
        };
    private final VirtualBone bone = new VirtualBone(BONE);

    @AfterEach
    void removeFetcher() {
        while (AnimationController.modelFetchers.remove(fetcher)) {
            // remove every registration this test class may have added
        }
    }

    private AnimationController<StubAnimatable> controller;

    private AnimationController<StubAnimatable> newController() {
        AnimationController.addModelFetcher(fetcher);
        bone.saveInitialSnapshot();
        AnimationController<StubAnimatable> controller = new AnimationController<>(
            new StubAnimatable(),
            "test_controller",
            0.0f,
            event -> null);
        this.controller = controller;
        return controller;
    }

    /** 一条只在 {@value #BONE} 上有 rotation 关键帧的动画。 */
    private static Animation animation(String name) {
        Animation animation = new Animation();
        animation.animationName = name;
        animation.animationLength = 2.0d;
        animation.loop = ILoopType.EDefaultLoopTypes.LOOP;
        animation.boneAnimations = new ArrayList<>();
        animation.soundKeyFrames = new ArrayList<>();
        animation.particleKeyFrames = new ArrayList<>();
        animation.customInstructionKeyframes = new ArrayList<>();

        BoneAnimation boneAnimation = new BoneAnimation();
        boneAnimation.boneName = BONE;
        List<KeyFrame<IValue>> x = keyFrames();
        boneAnimation.rotationKeyFrames = new VectorKeyFrameList<>(x, keyFrames(), keyFrames());
        boneAnimation.positionKeyFrames = new VectorKeyFrameList<>();
        boneAnimation.scaleKeyFrames = new VectorKeyFrameList<>();
        animation.boneAnimations.add(boneAnimation);
        return animation;
    }

    private static List<KeyFrame<IValue>> keyFrames() {
        KeyFrame<IValue> frame = new KeyFrame<>(2.0d, new ConstantValue(0.0d), new ConstantValue(90.0d));
        return new ArrayList<>(Arrays.asList(frame));
    }

    private final Map<String, IBone> bones = new HashMap<>();
    private final HashMap<String, Pair<IBone, BoneSnapshot>> snapshots = new HashMap<>();

    private void process(double seekTime) {
        AnimationEvent<StubAnimatable> event = new AnimationEvent<>(new StubAnimatable(), 0.0f, 0.0f, 0.0f, false,
            new ArrayList<>());
        event.setController(controller);
        controller.process(seekTime, event, bones, snapshots, new MolangParser(), false);
    }

    @Test
    void theSameBoneKeepsItsQueueAcrossFramesAndLastFramesPointsAreDropped() {
        AnimationController<StubAnimatable> controller = newController();
        // 运行时这个映射由 AnimationProcessor 按模型维护、跨帧是同一个对象（换模型才换引用），
        // 所以这里也必须复用同一个 map，否则测的就是"每帧都被当成换模型"。
        bones.put(BONE, bone);
        snapshots.put(BONE, Pair.of(bone, new BoneSnapshot(bone.getInitialSnapshot())));
        model.animations.put("swing", animation("swing"));
        controller.setAnimation(new AnimationBuilder().addAnimation("swing", ILoopType.EDefaultLoopTypes.LOOP));

        process(0.0d); // transition frame
        process(1.0d); // first running frame
        assertEquals(AnimationState.Running, controller.getAnimationState());
        BoneAnimationQueue afterFirst = controller.getBoneAnimationQueues()
            .get(BONE);
        assertNotNull(afterFirst, "the bone's queue must have been created while the animation runs");
        assertEquals(
            1,
            afterFirst.rotationXQueue.size(),
            "nothing polls the queue in this test, so the frame's own point is still in it");

        process(2.0d); // second running frame
        BoneAnimationQueue afterSecond = controller.getBoneAnimationQueues()
            .get(BONE);
        assertSame(afterFirst, afterSecond, "the queue object must be reused instead of rebuilt every frame");
        assertEquals(
            1,
            afterSecond.rotationXQueue.size(),
            "last frame's unpolled point must be dropped, not left ahead of this frame's point");
        assertEquals(
            1,
            afterSecond.rotationYQueue.size(),
            "every channel of a reused queue must be cleared, not just the one that was checked first");
    }

    /**
     * 关键帧定位现在写进复用字段（{@link AnimationController#findCurrentKeyFrame}），
     * 不再是每通道每帧 new 一个 KeyFrameLocation。复用字段最怕的是"没更新就沿用上一帧"，
     * 所以这里逐帧验算值：单个 0°→90°/2 tick 的关键帧，tick 0.5 应是 22.5°、tick 1.5 应是 67.5°。
     */
    @Test
    void theInterpolatedValueFollowsTheFrameAndNotAStaleLookup() {
        AnimationController<StubAnimatable> controller = newController();
        bones.put(BONE, bone);
        snapshots.put(BONE, Pair.of(bone, new BoneSnapshot(bone.getInitialSnapshot())));
        model.animations.put("swing", animation("swing"));
        controller.setAnimation(new AnimationBuilder().addAnimation("swing", ILoopType.EDefaultLoopTypes.LOOP));

        process(0.0d);
        assertEquals(0.0f, currentRotationAndRecycle(controller), 1.0e-4f);
        process(0.5d);
        assertEquals(22.5f, currentRotationAndRecycle(controller), 1.0e-4f);
        process(1.5d);
        assertEquals(67.5f, currentRotationAndRecycle(controller), 1.0e-4f);
    }

    private static float currentRotationAndRecycle(AnimationController<StubAnimatable> controller) {
        AnimationPoint point = controller.getBoneAnimationQueues()
            .get(BONE).rotationXQueue.poll();
        assertNotNull(point, "the running animation must have queued a rotation point for the bone");
        float value = MathUtil.lerpValues(point, EasingType.NONE, null);
        point.recycle();
        return value;
    }

    /**
     * 池化只复用对象、不再置 null 字段（字段现在是基本类型），所以"还回来的点"必须靠
     * {@code obtain} 逐个赋值覆盖干净。池是静态的，取回来的可能是任意一个实例（甚至新建），
     * 但绝不能带上一个点的值。
     */
    @Test
    void aRecycledPointCarriesTheNewValuesAndNotTheOnesFromThePreviousFrame() {
        AnimationPoint previous = AnimationPoint.obtain(null, 1.0d, 2.0d, 10.0d, 20.0d);
        previous.recycle();
        AnimationPoint reused = AnimationPoint.obtain(null, 0.25d, 0.5d, 30.0d, 40.0d);

        assertEquals(0.25d, reused.currentTick, 1.0e-9d);
        assertEquals(0.5d, reused.animationEndTick, 1.0e-9d);
        assertEquals(30.0d, reused.animationStartValue, 1.0e-9d);
        assertEquals(40.0d, reused.animationEndValue, 1.0e-9d);
        assertNull(reused.keyframe, "an obtained point with no keyframe must not keep the previous one");
        reused.recycle();
    }

    /**
     * Linear 与 NONE 的缓动在 EasingManager 里都是恒等函数，现在被直接短路掉了；
     * 这个用例把"短路 == 走缓动表"钉住，并确认别的缓动没有被一起短路。
     */
    @Test
    void linearEasingIsShortCircuitedWhileOtherEasingsStillGoThroughTheEasingTable() {
        // 0°→90° / 2 tick 的关键帧，tick 0.5 => percent 0.25
        AnimationPoint point = AnimationPoint.obtain(
            keyFrame(EasingType.Linear), 0.5d, 2.0d, 0.0d, 90.0d);
        assertEquals(22.5f, MathUtil.lerpValues(point, EasingType.NONE, null), 1.0e-4f,
            "Linear(=NONE 解析后的默认缓动) 必须是普通线性插值");

        point.keyframe = keyFrame(EasingType.EaseInCubic);
        assertEquals(1.40625f, MathUtil.lerpValues(point, EasingType.NONE, null), 1.0e-4f,
            "cubic(0.25) = 0.015625，非线性的缓动仍要走 EasingManager");

        point.keyframe = null;
        assertEquals(22.5f, MathUtil.lerpValues(point, EasingType.NONE, null), 1.0e-4f,
            "没有关键帧的过渡点按 NONE 处理，同样是线性");
        point.recycle();
    }

    private static KeyFrame<IValue> keyFrame(EasingType easingType) {
        return new KeyFrame<>(2.0d, new ConstantValue(0.0d), new ConstantValue(90.0d), easingType);
    }
}
