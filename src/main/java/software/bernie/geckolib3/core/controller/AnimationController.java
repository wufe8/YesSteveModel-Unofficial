/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */
// TODO AnimationController 尚未检查完
package software.bernie.geckolib3.core.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;

import com.eliotlash.mclib.math.IValue;

import software.bernie.geckolib3.core.AnimationState;
import software.bernie.geckolib3.core.ConstantValue;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;
import software.bernie.geckolib3.core.easing.EasingManager;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;
import software.bernie.geckolib3.core.easing.EasingType;
import software.bernie.geckolib3.core.event.CustomInstructionKeyframeEvent;
import software.bernie.geckolib3.core.event.ParticleKeyFrameEvent;
import software.bernie.geckolib3.core.event.SoundKeyframeEvent;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.keyframe.AnimationPoint;
import software.bernie.geckolib3.core.keyframe.BoneAnimation;
import software.bernie.geckolib3.core.keyframe.BoneAnimationQueue;
import software.bernie.geckolib3.core.keyframe.EventKeyFrame;
import software.bernie.geckolib3.core.keyframe.KeyFrame;
import software.bernie.geckolib3.core.keyframe.KeyFrameLocation;
import software.bernie.geckolib3.core.keyframe.ParticleEventKeyFrame;
import software.bernie.geckolib3.core.keyframe.VectorKeyFrameList;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.expressions.MolangExpression;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;
import software.bernie.geckolib3.core.util.Axis;

/**
 * The type Animation controller.
 *
 * @param <T> the type parameter
 */
public class AnimationController<T extends IAnimatable> {

    static List<ModelFetcher<?>> modelFetchers = new ArrayList<>();
    /**
     * The Entity.
     */
    protected T animatable;
    /**
     * The animation predicate, is tested in every process call (i.e. every frame)
     */
    protected IAnimationPredicate<T> animationPredicate;

    /**
     * The name of the animation controller
     */
    private final String name;

    protected AnimationState animationState = AnimationState.Stopped;

    /**
     * How long it takes to transition between animations
     */
    public double transitionLengthTicks;

    /**
     * The sound listener is called every time a sound keyframe is encountered (i.e.
     * every frame)
     */
    private ISoundListener<T> soundListener;

    /**
     * The particle listener is called every time a particle keyframe is encountered
     * (i.e. every frame)
     */
    private IParticleListener<T> particleListener;

    /**
     * The custom instruction listener is called every time a custom instruction
     * keyframe is encountered (i.e. every frame)
     */
    private ICustomInstructionListener<T> customInstructionListener;
    public List emitters;
    public boolean isJustStarting = false;
    public int particleUpdatesPerSecond = 20;

    public static void addModelFetcher(ModelFetcher<?> fetcher) {
        modelFetchers.add(fetcher);
    }

    public static void removeModelFetcher(ModelFetcher<?> fetcher) {
        Objects.requireNonNull(fetcher);
        modelFetchers.remove(fetcher);
    }

    /**
     * An AnimationPredicate is run every render frame for ever AnimationController.
     * The "test" method is where you should change animations, stop animations,
     * restart, etc.
     */
    @FunctionalInterface
    public interface IAnimationPredicate<P extends IAnimatable> {

        /**
         * An AnimationPredicate is run every render frame for ever AnimationController.
         * The "test" method is where you should change animations, stop animations,
         * restart, etc.
         *
         * @return CONTINUE if the animation should continue, STOP if it should stop.
         */
        PlayState test(AnimationEvent<P> event);
    }

    /**
     * Sound Listeners are run when a sound keyframe is hit. You can either return
     * the SoundEvent and geckolib will play the sound for you, or return null and
     * handle the sounds yourself.
     */
    @FunctionalInterface
    public interface ISoundListener<A extends IAnimatable> {

        /**
         * Sound Listeners are run when a sound keyframe is hit. You can either return
         * the SoundEvent and geckolib will play the sound for you, or return null and
         * handle the sounds yourself.
         */
        void playSound(SoundKeyframeEvent<A> event);
    }

    /**
     * Particle Listeners are run when a sound keyframe is hit. You need to handle
     * the actual playing of the particle yourself.
     */
    @FunctionalInterface
    public interface IParticleListener<A extends IAnimatable> {

        /**
         * Particle Listeners are run when a sound keyframe is hit. You need to handle
         * the actual playing of the particle yourself.
         */
        void summonParticle(ParticleKeyFrameEvent<A> event);
    }

    /**
     * Custom instructions can be added in blockbench by enabling animation effects
     * in Animation - Animate Effects. You can then add custom instruction keyframes
     * and use them as timecodes/events to handle in code.
     */
    @FunctionalInterface
    public interface ICustomInstructionListener<A extends IAnimatable> {

        /**
         * Custom instructions can be added in blockbench by enabling animation effects
         * in Animation - Animate Effects. You can then add custom instruction keyframes
         * and use them as timecodes/events to handle in code.
         */
        void executeInstruction(CustomInstructionKeyframeEvent<A> event);
    }

    private final HashMap<String, BoneAnimationQueue> boneAnimationQueues = new HashMap<>();
    private final List<BoneAnimationQueue> activeBoneAnimationQueues = new ArrayList<>();
        // YSMU perf: Pre-built bone name → IBone map — populated once per process() call,
    // reused by both the transition and running branches to avoid a second
    // HashMap build and to enable lazy BoneAnimationQueue creation.
    private HashMap<String, IBone> boneNameToBone = new HashMap<>();
    // YSMU: tickOffset for animation frame time tracking
    public double tickOffset;
    public Queue<Animation> animationQueue = new LinkedList<>();
    public Animation currentAnimation;
    public AnimationBuilder currentAnimationBuilder = new AnimationBuilder();
    public boolean shouldResetTick = false;
    private final HashMap<String, BoneSnapshot> boneSnapshots = new HashMap<>();
    // YSMU: justStopped/justStartedTransition — fix PLAY_ONCE restart detection
    private boolean justStopped = false;
    protected boolean justStartedTransition = false;
    public Function<Double, Double> customEasingMethod;
    // YSMU: needsAnimationReload — signals rebuild when same builder is re-submitted
    protected boolean needsAnimationReload = false;
    // YSMU: animationSpeed — external control for pause/freeze (used by animation preview screen)
    public double animationSpeed = 1D;
    // YSMU: anim_time_update — Bedrock 风格逐动画自定义时间推进
    /** 上一次计算出的动画时间（tick）；-1 = 未初始化（新动画/重启时重置）。 */
    private double lastAnimTimeTick = -1;
    /** 计算 anim_time_update 时的动画实例（动画切换时重置 lastAnimTimeTick）。 */
    private Animation lastAnimTimeAnimation;
    /** 上一帧 actualTick（用于计算 query.delta_time）。 */
    private double lastActualTick = -1;
    /** anim_time_update 表达式解析缓存（按表达式文本，全局共享）。 */
    private static final ConcurrentHashMap<String, MolangExpression> ANIM_TIME_UPDATE_CACHE = new ConcurrentHashMap<>();
    private final Set<EventKeyFrame<?>> executedKeyFrames = new HashSet<>();

    /**
     * This method sets the current animation with an animation builder. You can run
     * this method every frame, if you pass in the same animation builder every
     * time, it won't restart. Additionally, it smoothly transitions between
     * animation states.
     */
    public void setAnimation(AnimationBuilder builder) {
        // YSMU: Handle same-builder resubmission — restart PLAY_ONCE when Stopped,
        // or restart LOOP when currentAnimation was lost (e.g. after cache clear).
        if (builder != null && !builder.getRawAnimationList()
            .isEmpty()) {
            String animName = builder.getRawAnimationList().get(0).animationName;
            boolean animChanged = !builder.getRawAnimationList()
                .equals(this.currentAnimationBuilder.getRawAnimationList());
            if (builder.getRawAnimationList()
                .equals(this.currentAnimationBuilder.getRawAnimationList()) && !this.needsAnimationReload) {
                if (animationState == AnimationState.Stopped
                    || (builder.getRawAnimationList()
                        .get(builder.getRawAnimationList().size() - 1).loopType
                        == ILoopType.EDefaultLoopTypes.LOOP && currentAnimation == null)) {
                    needsAnimationReload = true;
                }
            }
        }
        // END YSMU
        IAnimatableModel<T> model = getModel(this.animatable);
        if (model != null) {
            if (builder == null || builder.getRawAnimationList()
                .size() == 0) {
                animationState = AnimationState.Stopped;
            } else if (!builder.getRawAnimationList()
                .equals(currentAnimationBuilder.getRawAnimationList()) || needsAnimationReload) {
                    AtomicBoolean encounteredError = new AtomicBoolean(false);
                    // Convert the list of animation names to the actual list, keeping track of the
                    // loop boolean along the way
                    LinkedList<Animation> animations = builder.getRawAnimationList()
                        .stream()
                        .map((rawAnimation) -> {
                            Animation animation = model.getAnimation(rawAnimation.animationName, animatable);
                            // YSMU: Fallback removed — scanning ALL files in GeckoLibCache
                            // leaks per-model custom animations (e.g. rok's attack_1)
                            // into unrelated models, causing bone name mismatches.
                            // Each model must provide its own animations; the default
                            // model's animations are injected by YSMU's AnimationManager
                            // before calling setAnimation.
                            if (animation == null) {
                                System.out
                                    .printf("Could not load animation: %s. Is it missing?", rawAnimation.animationName);
                                encounteredError.set(true);
                            }
                            if (animation != null && rawAnimation.loopType != null) {
                                animation.loop = rawAnimation.loopType;
                            }
                            return animation;
                        })
                        .collect(Collectors.toCollection(LinkedList::new));

                    if (encounteredError.get()) {
                        return;
                    } else {
                        animationQueue = animations;
                    }
                    currentAnimationBuilder = builder;

                    // Always clear stale currentAnimation when a new animation is queued,
                    // so that process() can dequeue it.  Without this, a Running
                    // controller (e.g. playing "idle") keeps the old currentAnimation
                    // when setAnimation queues the next animation, and the Transitioning
                    // branch skips dequeuing because tick != 0, leaving the new
                    // animation stuck in the queue forever.
                    // The one-frame delay before the new animation starts is handled
                    // by the Transitioning→Running transition check in process() which
                    // polls from the queue when currentAnimation is null.
                    this.currentAnimation = null;
                    resetEventKeyFrames();

                    // Reset the adjusted tick to 0 on next animation process call
                    shouldResetTick = true;
                    this.animationState = AnimationState.Transitioning;
                    justStartedTransition = true;
                    needsAnimationReload = false;
                }
        }
    }

    public boolean setAnimationPreservingTick(AnimationBuilder builder, double absoluteTick, double elapsedTick) {
        IAnimatableModel<T> model = getModel(this.animatable);
        if (model == null || builder == null || builder.getRawAnimationList()
            .isEmpty()) {
            return false;
        }
        AtomicBoolean encounteredError = new AtomicBoolean(false);
        LinkedList<Animation> animations = builder.getRawAnimationList()
            .stream()
            .map((rawAnimation) -> {
                Animation animation = model.getAnimation(rawAnimation.animationName, animatable);
                // Cross-model fallback removed — see setAnimation() for rationale.
                if (animation == null) {
                    System.out.printf("Could not load animation: %s. Is it missing?", rawAnimation.animationName);
                    encounteredError.set(true);
                }
                if (animation != null && rawAnimation.loopType != null) {
                    animation.loop = rawAnimation.loopType;
                }
                return animation;
            })
            .collect(Collectors.toCollection(LinkedList::new));
        if (encounteredError.get() || animations.isEmpty()) {
            return false;
        }
        this.animationQueue = animations;
        this.currentAnimationBuilder = builder;
        this.currentAnimation = this.animationQueue.poll();
        this.tickOffset = absoluteTick - Math.max(0.0D, elapsedTick);
        this.shouldResetTick = false;
        this.animationState = AnimationState.Running;
        this.justStartedTransition = false;
        this.justStopped = false;
        this.needsAnimationReload = false;
        // Do NOT call resetEventKeyFrames() here — this method preserves the
        // animation tick position when switching between conditional animation
        // variants within the same state (e.g. sword_attack_01 → sword_attack_run1
        // when the player starts/stops running while swinging).  Resetting would
        // clear executedKeyFrames, causing sound keyframes at tick 0.0 to re-fire
        // even though the playback position is already past them.  New Animation
        // objects create distinct EventKeyFrame instances, so old keyframes in the
        // set do not prevent new ones from executing at their appropriate ticks.
        return this.currentAnimation != null;
    }

    /**
     * By default Geckolib uses the easing types of every keyframe. If you want to
     * override that for an entire AnimationController, change this value.
     */
    public EasingType easingType = EasingType.NONE;

    /**
     * Instantiates a new Animation controller. Each animation controller can run
     * one animation at a time. You can have several animation controllers for each
     * entity, i.e. one animation to control the entity's size, one to control
     * movement, attacks, etc.
     *
     * @param animatable            The entity
     * @param name                  Name of the animation controller
     *                              (move_controller, size_controller,
     *                              attack_controller, etc.)
     * @param transitionLengthTicks How long it takes to transition between
     *                              animations (IN TICKS!!)
     */
    public AnimationController(T animatable, String name, float transitionLengthTicks,
        IAnimationPredicate<T> animationPredicate) {
        this.animatable = animatable;
        this.name = name;
        this.transitionLengthTicks = transitionLengthTicks;
        this.animationPredicate = animationPredicate;
        tickOffset = 0.0d;
    }

    /**
     * Instantiates a new Animation controller. Each animation controller can run
     * one animation at a time. You can have several animation controllers for each
     * entity, i.e. one animation to control the entity's size, one to control
     * movement, attacks, etc.
     *
     * @param animatable            The entity
     * @param name                  Name of the animation controller
     *                              (move_controller, size_controller,
     *                              attack_controller, etc.)
     * @param transitionLengthTicks How long it takes to transition between
     *                              animations (IN TICKS!!)
     * @param easingtype            The method of easing to use. The other
     *                              constructor defaults to no easing.
     */
    public AnimationController(T animatable, String name, float transitionLengthTicks, EasingType easingtype,
        IAnimationPredicate<T> animationPredicate) {
        this.animatable = animatable;
        this.name = name;
        this.transitionLengthTicks = transitionLengthTicks;
        this.easingType = easingtype;
        this.animationPredicate = animationPredicate;
        tickOffset = 0.0d;
    }

    /**
     * Instantiates a new Animation controller. Each animation controller can run
     * one animation at a time. You can have several animation controllers for each
     * entity, i.e. one animation to control the entity's size, one to control
     * movement, attacks, etc.
     *
     * @param animatable            The entity
     * @param name                  Name of the animation controller
     *                              (move_controller, size_controller,
     *                              attack_controller, etc.)
     * @param transitionLengthTicks How long it takes to transition between
     *                              animations (IN TICKS!!)
     * @param customEasingMethod    If you want to use an easing method that's not
     *                              included in the EasingType enum, pass your
     *                              method into here. The parameter that's passed in
     *                              will be a number between 0 and 1. Return a
     *                              number also within 0 and 1. Take a look at
     *                              {@link EasingManager}
     */
    public AnimationController(T animatable, String name, float transitionLengthTicks,
        Function<Double, Double> customEasingMethod, IAnimationPredicate<T> animationPredicate) {
        this.animatable = animatable;
        this.name = name;
        this.transitionLengthTicks = transitionLengthTicks;
        this.customEasingMethod = customEasingMethod;
        this.easingType = EasingType.CUSTOM;
        this.animationPredicate = animationPredicate;
        tickOffset = 0.0d;
    }

    /**
     * Gets the controller's name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the current animation. Can be null
     *
     * @return the current animation
     */

    public Animation getCurrentAnimation() {
        return currentAnimation;
    }

    /**
     * Returns the current state of this animation controller.
     */
    public AnimationState getAnimationState() {
        return animationState;
    }

    /**
     * Gets the current animation's bone animation queues.
     *
     * @return the bone animation queues
     */
    public HashMap<String, BoneAnimationQueue> getBoneAnimationQueues() {
        return boneAnimationQueues;
    }

    public List<BoneAnimationQueue> getActiveBoneAnimationQueues() {
        return activeBoneAnimationQueues;
    }

    /**
     * Registers a sound listener.
     */
    public void registerSoundListener(ISoundListener<T> soundListener) {
        this.soundListener = soundListener;
    }

    /**
     * Registers a particle listener.
     */
    public void registerParticleListener(IParticleListener<T> particleListener) {
        this.particleListener = particleListener;
    }

    /**
     * Registers a custom instruction listener.
     */
    public void registerCustomInstructionListener(ICustomInstructionListener<T> customInstructionListener) {
        this.customInstructionListener = customInstructionListener;
    }

    /**
     * This method is called every frame in order to populate the animation point
     * queues, and process animation state logic.
     *
     * @param tick                   The current tick + partial tick
     * @param event                  The animation test event
     * @param modelRendererList      The list of all AnimatedModelRender's
     * @param boneSnapshotCollection The bone snapshot collection
     */
    public void process(double tick, AnimationEvent<T> event, List<IBone> modelRendererList,
        HashMap<String, Pair<IBone, BoneSnapshot>> boneSnapshotCollection, MolangParser parser,
        boolean crashWhenCantFindBone) {
        parser.setValue("query.life_time", tick / 20);
        if (currentAnimation != null) {
            IAnimatableModel<T> model = getModel(this.animatable);
            if (model != null) {
                Animation animation = model.getAnimation(currentAnimation.animationName, this.animatable);
                // Cross-model fallback removed — see setAnimation() for rationale.
                if (animation != null) {
                    ILoopType loop = currentAnimation.loop;
                    currentAnimation = animation;
                    currentAnimation.loop = loop;
                }
            }
        }

        double actualTick = tick;
        boolean tickWasReset = false;
        double afterReset = adjustTick(tick);
        if (afterReset == 0.0D && tick != 0.0D) {
            tickWasReset = true;
        }
        tick = afterReset;
        // Transition period has ended, reset the tick and set the animation to running.
        // NOTE: this must compare the ADJUSTED tick (elapsed since the transition
        // started), not the raw `tick` argument.  The raw value is a global seek
        // counter (manager.tick + renderPartialTicks) that is almost always
        // >= transitionLengthTicks after the first few ticks of play, so the old
        // pre-adjustTick check fired on the very first transition frame and flipped
        // straight to Running — the Transitioning blend branch never ran and no
        // transition was ever visible.  adjustTick() resets tickOffset to the
        // current raw tick on the first transition frame (shouldResetTick=true)
        // and returns the elapsed time on later frames, which is exactly how long
        // the current transition has been running.
        if (animationState == AnimationState.Transitioning && tick >= transitionLengthTicks) {
            this.shouldResetTick = true;
            animationState = AnimationState.Running;
            // Ensure currentAnimation is set from the queue if not already
            if (this.currentAnimation == null && this.animationQueue.size() != 0) {
                this.currentAnimation = this.animationQueue.poll();
            }
        }
        if (animationState == AnimationState.Running) {
            tick = adjustTick(actualTick);
            // If the first adjustTick performed a reset (shouldResetTick was true),
            // the second adjustTick should not undo it — it returned the original
            // actualTick because shouldResetTick was already cleared.  In that case,
            // honour the reset and keep tick at 0 so the animation starts from the
            // beginning (e.g. after a setAnimation triggered by a Stopped→Running
            // transition).
            if (tickWasReset) {
                tick = 0.0D;
            }
        }

        assert tick >= 0 : "GeckoLib: Tick was less than zero";

        // This tests the animation predicate
        PlayState playState = this.testAnimationPredicate(event);
        if (playState == PlayState.STOP || (currentAnimation == null && animationQueue.size() == 0)) {
            // The animation should transition to the model's initial state
            animationState = AnimationState.Stopped;
            justStopped = true;
            // YSMU: Clear bone animation queues when the controller stops.
            // Without this, stale queues from the last non-STOP frame persist
            // and their animation points continue to override bones set by
            // other controllers (e.g. cap_controller's "hover" pose lingers
            // after the hover ends, corrupting the main controller's preview
            // animation).
            this.boneAnimationQueues.clear();
            this.activeBoneAnimationQueues.clear();
            return;
        }

        // Defer queue creation until we know the controller is active (not STOP).
        // This saves 13%+ overhead for idle controllers that return STOP from
        // their predicate (e.g. OpenYSM slot controllers with no matching definition).
        createInitialQueues(modelRendererList);
        if (justStartedTransition && (shouldResetTick || justStopped)) {
            justStopped = false;
            tick = adjustTick(actualTick);
        } else if (currentAnimation == null && this.animationQueue.size() != 0) {
            this.shouldResetTick = true;
            this.animationState = AnimationState.Transitioning;
            justStartedTransition = true;
            needsAnimationReload = false;
            tick = adjustTick(actualTick);
        } else {
            if (animationState != AnimationState.Transitioning) {
                animationState = AnimationState.Running;
            }
        }

        // Handle transitioning to a different animation (or just starting one)
        if (animationState == AnimationState.Transitioning) {
            // Just started transitioning, so set the current animation to the first one
            if (tick == 0 || isJustStarting) {
                justStartedTransition = false;
                this.currentAnimation = animationQueue.poll();
                resetEventKeyFrames();
                saveSnapshotsForAnimation(currentAnimation, boneSnapshotCollection);
            }
            if (currentAnimation != null) {
                setAnimTime(parser, 0);
                // Reuse boneNameToBone map built by createInitialQueues instead
                // of building a second HashMap from modelRendererList.
                for (BoneAnimation boneAnimation : currentAnimation.boneAnimations) {
                    BoneAnimationQueue boneAnimationQueue = getOrCreateQueue(boneAnimation.boneName);
                    BoneSnapshot boneSnapshot = this.boneSnapshots.get(boneAnimation.boneName);
                    IBone first = this.boneNameToBone.get(boneAnimation.boneName);
                    if (first == null) {
                        if (crashWhenCantFindBone) {
                            throw new RuntimeException("Could not find bone: " + boneAnimation.boneName);
                        } else {
                            continue;
                        }
                    }
                    markActiveBoneAnimationQueue(boneAnimationQueue);
                    BoneSnapshot initialSnapshot = first.getInitialSnapshot();
                    assert boneSnapshot != null : "Bone snapshot was null";

                    VectorKeyFrameList<KeyFrame<IValue>> rotationKeyFrames = boneAnimation.rotationKeyFrames;
                    VectorKeyFrameList<KeyFrame<IValue>> positionKeyFrames = boneAnimation.positionKeyFrames;
                    VectorKeyFrameList<KeyFrame<IValue>> scaleKeyFrames = boneAnimation.scaleKeyFrames;

                    // Adding the initial positions of the upcoming animation, so the model
                    // transitions to the initial state of the new animation
                    if (!rotationKeyFrames.xKeyFrames.isEmpty()) {
                        AnimationPoint xPoint = getAnimationPointAtTick(rotationKeyFrames.xKeyFrames, 0, true, Axis.X);
                        AnimationPoint yPoint = getAnimationPointAtTick(rotationKeyFrames.yKeyFrames, 0, true, Axis.Y);
                        AnimationPoint zPoint = getAnimationPointAtTick(rotationKeyFrames.zKeyFrames, 0, true, Axis.Z);
                        boneAnimationQueue.rotationXQueue.add(
                            AnimationPoint.obtain(
                                null,
                                tick,
                                transitionLengthTicks,
                                boneSnapshot.rotationValueX - initialSnapshot.rotationValueX,
                                xPoint.animationStartValue));
                        boneAnimationQueue.rotationYQueue.add(
                            AnimationPoint.obtain(
                                null,
                                tick,
                                transitionLengthTicks,
                                boneSnapshot.rotationValueY - initialSnapshot.rotationValueY,
                                yPoint.animationStartValue));
                        boneAnimationQueue.rotationZQueue.add(
                            AnimationPoint.obtain(
                                null,
                                tick,
                                transitionLengthTicks,
                                boneSnapshot.rotationValueZ - initialSnapshot.rotationValueZ,
                                zPoint.animationStartValue));
                        xPoint.recycle();
                        yPoint.recycle();
                        zPoint.recycle();
                    }

                    if (!positionKeyFrames.xKeyFrames.isEmpty()) {
                        AnimationPoint xPoint = getAnimationPointAtTick(positionKeyFrames.xKeyFrames, 0, false, Axis.X);
                        AnimationPoint yPoint = getAnimationPointAtTick(positionKeyFrames.yKeyFrames, 0, false, Axis.Y);
                        AnimationPoint zPoint = getAnimationPointAtTick(positionKeyFrames.zKeyFrames, 0, false, Axis.Z);
                        boneAnimationQueue.positionXQueue.add(
                            AnimationPoint.obtain(
                                null,
                                tick,
                                transitionLengthTicks,
                                boneSnapshot.positionOffsetX,
                                xPoint.animationStartValue));
                        boneAnimationQueue.positionYQueue.add(
                            AnimationPoint.obtain(
                                null,
                                tick,
                                transitionLengthTicks,
                                boneSnapshot.positionOffsetY,
                                yPoint.animationStartValue));
                        boneAnimationQueue.positionZQueue.add(
                            AnimationPoint.obtain(
                                null,
                                tick,
                                transitionLengthTicks,
                                boneSnapshot.positionOffsetZ,
                                zPoint.animationStartValue));
                        xPoint.recycle();
                        yPoint.recycle();
                        zPoint.recycle();
                    }

                    if (!scaleKeyFrames.xKeyFrames.isEmpty()) {
                        AnimationPoint xPoint = getAnimationPointAtTick(scaleKeyFrames.xKeyFrames, 0, false, Axis.X);
                        AnimationPoint yPoint = getAnimationPointAtTick(scaleKeyFrames.yKeyFrames, 0, false, Axis.Y);
                        AnimationPoint zPoint = getAnimationPointAtTick(scaleKeyFrames.zKeyFrames, 0, false, Axis.Z);
                        boneAnimationQueue.scaleXQueue.add(
                            AnimationPoint.obtain(
                                null,
                                tick,
                                transitionLengthTicks,
                                boneSnapshot.scaleValueX,
                                xPoint.animationStartValue));
                        boneAnimationQueue.scaleYQueue.add(
                            AnimationPoint.obtain(
                                null,
                                tick,
                                transitionLengthTicks,
                                boneSnapshot.scaleValueY,
                                yPoint.animationStartValue));
                        boneAnimationQueue.scaleZQueue.add(
                            AnimationPoint.obtain(
                                null,
                                tick,
                                transitionLengthTicks,
                                boneSnapshot.scaleValueZ,
                                zPoint.animationStartValue));
                        xPoint.recycle();
                        yPoint.recycle();
                        zPoint.recycle();
                    }
                }
            }
        } else if (getAnimationState() == AnimationState.Running) {
            // Actually run the animation
            processCurrentAnimation(tick, actualTick, parser, crashWhenCantFindBone);
        }
    }

    private void setAnimTime(MolangParser parser, double tick) {
        parser.setValue("query.anim_time", tick / 20);
    }

    private IAnimatableModel<T> getModel(T animatable) {
        for (ModelFetcher<?> modelFetcher : modelFetchers) {
            IAnimatableModel<T> model = (IAnimatableModel<T>) modelFetcher.apply(animatable);
            if (model != null) {
                return model;
            }
        }
        System.out.printf(
            "Could not find suitable model for animatable of type %s. Did you register a Model Fetcher?%n",
            animatable.getClass());
        return null;
    }

    protected PlayState testAnimationPredicate(AnimationEvent<T> event) {
        return this.animationPredicate.test(event);
    }

    // At the beginning of a new transition, save a snapshot of the model's
    // rotation, position, and scale values as the initial value to lerp from
    private void saveSnapshotsForAnimation(Animation animation,
        HashMap<String, Pair<IBone, BoneSnapshot>> boneSnapshotCollection) {
        // Pre-build a set of bone animation names to avoid stream().anyMatch() per snapshot
        java.util.Set<String> animBoneNames = java.util.Collections.emptySet();
        if (animation != null && animation.boneAnimations != null) {
            animBoneNames = new java.util.HashSet<>();
            for (software.bernie.geckolib3.core.keyframe.BoneAnimation ba : animation.boneAnimations) {
                animBoneNames.add(ba.boneName);
            }
        }
        for (Pair<IBone, BoneSnapshot> snapshot : boneSnapshotCollection.values()) {
            if (!animBoneNames.isEmpty() && animBoneNames.contains(snapshot.getLeft().getName())) {
                    this.boneSnapshots.put(
                        snapshot.getLeft()
                            .getName(),
                        new BoneSnapshot(snapshot.getRight()));
                }
            }
        }

    private void processCurrentAnimation(double tick, double actualTick, MolangParser parser,
        boolean crashWhenCantFindBone) {
        assert currentAnimation != null;
        // YSMU: anim_time_update — 逐动画自定义时间推进（Bedrock 风格）。
        // 表达式每帧求值，返回动画时间（秒）；query.anim_time = 上一帧时间，
        // query.delta_time = 本帧时间增量（秒）。未提供该字段时走默认时间推进。
        boolean customTime = currentAnimation.animTimeUpdate != null
            && !currentAnimation.animTimeUpdate.isEmpty();
        if (customTime) {
            if (this.lastAnimTimeAnimation != currentAnimation) {
                this.lastAnimTimeAnimation = currentAnimation;
                this.lastAnimTimeTick = -1;
            }
            double prevSeconds = this.lastAnimTimeTick >= 0 ? this.lastAnimTimeTick / 20.0 : 0.0;
            double deltaSeconds = actualTick - this.lastActualTick;
            if (deltaSeconds <= 0.0 || deltaSeconds > 1.0) {
                deltaSeconds = 1.0 / 20.0; // 兜底：一 tick
            }
            this.lastActualTick = actualTick;
            parser.setValue("query.anim_time", prevSeconds);
            parser.setValue("query.delta_time", deltaSeconds);
            try {
                MolangExpression expr = ANIM_TIME_UPDATE_CACHE.computeIfAbsent(
                    currentAnimation.animTimeUpdate,
                    s -> {
                        try {
                            return parser.parseExpression(s);
                        } catch (Exception e) {
                            return null;
                        }
                    });
                if (expr != null) {
                    double newSeconds = expr.get();
                    if (newSeconds < 0.0) {
                        newSeconds = 0.0;
                    }
                    tick = newSeconds * 20.0;
                }
            } catch (Exception ignored) {
                // 表达式出错：回退到默认时间推进
            }
        }
        // Animation has ended
        if (tick >= currentAnimation.animationLength) {
            if (currentAnimation.loop == EDefaultLoopTypes.HOLD_ON_LAST_FRAME) {
                tick = Math.max(0.0D, currentAnimation.animationLength);
            } else if (!currentAnimation.loop.isRepeatingAfterEnd()) {
                processKeyFrameEvents(currentAnimation.animationLength);
                resetEventKeyFrames();
                // Pull the next animation from the queue
                Animation peek = animationQueue.peek();
                if (peek == null) {
                    // No more animations left, stop the animation controller
                    this.animationState = AnimationState.Stopped;
                    return;
                } else {
                    // Otherwise, set the state to transitioning and start transitioning to the next
                    // animation next frame
                    this.animationState = AnimationState.Transitioning;
                    shouldResetTick = true;
                    currentAnimation = this.animationQueue.peek();
                }
            } else {
                processKeyFrameEvents(currentAnimation.animationLength);
                resetEventKeyFrames();
                tick = wrapLoopTick(actualTick, tick, currentAnimation.animationLength);
            }
        }
        if (customTime) {
            this.lastAnimTimeTick = tick;
        }
        setAnimTime(parser, tick);
        processKeyFrameEvents(tick);

        // Loop through every boneanimation in the current animation and process the
        // values
        List<BoneAnimation> boneAnimations = currentAnimation.boneAnimations;
        for (BoneAnimation boneAnimation : boneAnimations) {
            BoneAnimationQueue boneAnimationQueue = getOrCreateQueue(boneAnimation.boneName);
            if (boneAnimationQueue == null) {
                if (crashWhenCantFindBone) {
                    throw new RuntimeException("Could not find bone: " + boneAnimation.boneName);
                } else {
                    continue;
                }
            }
            markActiveBoneAnimationQueue(boneAnimationQueue);

            VectorKeyFrameList<KeyFrame<IValue>> rotationKeyFrames = boneAnimation.rotationKeyFrames;
            VectorKeyFrameList<KeyFrame<IValue>> positionKeyFrames = boneAnimation.positionKeyFrames;
            VectorKeyFrameList<KeyFrame<IValue>> scaleKeyFrames = boneAnimation.scaleKeyFrames;

            if (!rotationKeyFrames.xKeyFrames.isEmpty()) {
                boneAnimationQueue.rotationXQueue
                    .add(getAnimationPointAtTick(rotationKeyFrames.xKeyFrames, tick, true, Axis.X));
                boneAnimationQueue.rotationYQueue
                    .add(getAnimationPointAtTick(rotationKeyFrames.yKeyFrames, tick, true, Axis.Y));
                boneAnimationQueue.rotationZQueue
                    .add(getAnimationPointAtTick(rotationKeyFrames.zKeyFrames, tick, true, Axis.Z));
            }

            if (!positionKeyFrames.xKeyFrames.isEmpty()) {
                boneAnimationQueue.positionXQueue
                    .add(getAnimationPointAtTick(positionKeyFrames.xKeyFrames, tick, false, Axis.X));
                boneAnimationQueue.positionYQueue
                    .add(getAnimationPointAtTick(positionKeyFrames.yKeyFrames, tick, false, Axis.Y));
                boneAnimationQueue.positionZQueue
                    .add(getAnimationPointAtTick(positionKeyFrames.zKeyFrames, tick, false, Axis.Z));
            }

            if (!scaleKeyFrames.xKeyFrames.isEmpty()) {
                boneAnimationQueue.scaleXQueue
                    .add(getAnimationPointAtTick(scaleKeyFrames.xKeyFrames, tick, false, Axis.X));
                boneAnimationQueue.scaleYQueue
                    .add(getAnimationPointAtTick(scaleKeyFrames.yKeyFrames, tick, false, Axis.Y));
                boneAnimationQueue.scaleZQueue
                    .add(getAnimationPointAtTick(scaleKeyFrames.zKeyFrames, tick, false, Axis.Z));
            }
        }
        if (this.transitionLengthTicks == 0 && shouldResetTick && this.animationState == AnimationState.Transitioning) {
            this.currentAnimation = animationQueue.poll();
        }
    }

    private double wrapLoopTick(double actualTick, double tick, double animationLength) {
        if (animationLength <= 0.0D) {
            this.tickOffset = actualTick;
            this.shouldResetTick = false;
            return 0.0D;
        }
        double wrappedTick = tick % animationLength;
        if (Double.isNaN(wrappedTick) || Double.isInfinite(wrappedTick)) {
            wrappedTick = 0.0D;
        }
        this.tickOffset = this.animationSpeed == 0.0D ? actualTick : actualTick - wrappedTick / this.animationSpeed;
        this.shouldResetTick = false;
        return wrappedTick;
    }

    private void processKeyFrameEvents(double tick) {
        if (soundListener != null) {
            for (EventKeyFrame<String> soundKeyFrame : currentAnimation.soundKeyFrames) {
                if (!this.executedKeyFrames.contains(soundKeyFrame) && tick >= soundKeyFrame.getStartTick()) {
                    SoundKeyframeEvent<T> event = new SoundKeyframeEvent<>(
                        this.animatable,
                        tick,
                        soundKeyFrame.getEventData(),
                        this);
                    soundListener.playSound(event);

                    this.executedKeyFrames.add(soundKeyFrame);
                }
            }
        }

        if (particleListener != null) {
            for (ParticleEventKeyFrame particleEventKeyFrame : currentAnimation.particleKeyFrames) {
                if (!this.executedKeyFrames.contains(particleEventKeyFrame)
                    && tick >= particleEventKeyFrame.getStartTick()) {
                    ParticleKeyFrameEvent<T> event = new ParticleKeyFrameEvent<>(
                        this.animatable,
                        tick,
                        particleEventKeyFrame.effect,
                        particleEventKeyFrame.locator,
                        particleEventKeyFrame.script,
                        this);
                    particleListener.summonParticle(event);

                    this.executedKeyFrames.add(particleEventKeyFrame);
                }
            }
        }

        if (customInstructionListener != null) {
            for (EventKeyFrame<String> customInstructionKeyFrame : currentAnimation.customInstructionKeyframes) {
                if (!this.executedKeyFrames.contains(customInstructionKeyFrame)
                    && tick >= customInstructionKeyFrame.getStartTick()) {
                    CustomInstructionKeyframeEvent<T> event = new CustomInstructionKeyframeEvent<>(
                        this.animatable,
                        tick,
                        customInstructionKeyFrame.getEventData(),
                        this);
                    customInstructionListener.executeInstruction(event);

                    this.executedKeyFrames.add(customInstructionKeyFrame);
                }
            }
        }
    }

    // Helper method to populate all the initial animation point queues
    private void createInitialQueues(List<IBone> modelRendererList) {
        boneAnimationQueues.clear();
        activeBoneAnimationQueues.clear();
        // Build name→IBone map once; BoneAnimationQueue objects are created
        // lazily in the transition/running branches only for bones that are
        // actually animated, avoiding ~900 allocations for unanimated bones.
        boneNameToBone.clear();
        for (IBone modelRenderer : modelRendererList) {
            boneNameToBone.put(modelRenderer.getName(), modelRenderer);
        }
    }

    /** Ensures a BoneAnimationQueue exists for the given bone name, creating
     *  one lazily if needed.  Returns the queue, or null if the bone name is
     *  not in the current model renderer list. */
    private BoneAnimationQueue getOrCreateQueue(String boneName) {
        BoneAnimationQueue q = boneAnimationQueues.get(boneName);
        if (q == null) {
            IBone bone = boneNameToBone.get(boneName);
            if (bone != null) {
                q = new BoneAnimationQueue(bone);
                boneAnimationQueues.put(boneName, q);
            }
        }
        return q;
    }

    private void markActiveBoneAnimationQueue(BoneAnimationQueue boneAnimationQueue) {
        if (boneAnimationQueue != null && !activeBoneAnimationQueues.contains(boneAnimationQueue)) {
            activeBoneAnimationQueues.add(boneAnimationQueue);
        }
    }

    // Used to reset the "tick" everytime a new animation starts, a transition
    // starts, or something else of importance happens
    public double adjustTick(double tick) {
        if (shouldResetTick) {
            if (getAnimationState() == AnimationState.Transitioning) {
                this.tickOffset = tick;
            } else if (getAnimationState() == AnimationState.Running) {
                this.tickOffset = tick;
            }
            shouldResetTick = false;
            return 0;
        } else {
            // assert tick - this.tickOffset >= 0;
            return animationSpeed * Math.max(tick - tickOffset, 0.0D);
        }
    }

    // Helper method to transform a KeyFrameLocation to an AnimationPoint
    private AnimationPoint getAnimationPointAtTick(List<KeyFrame<IValue>> frames, double tick, boolean isRotation,
        Axis axis) {
        KeyFrameLocation<KeyFrame<IValue>> location = getCurrentKeyFrameLocation(frames, tick);
        KeyFrame<IValue> currentFrame = location.currentFrame;
        double startValue = currentFrame.getStartValueDouble();
        double endValue = currentFrame.getEndValueDouble();

        if (isRotation) {
            // Primitive-inlined values were pre-converted by JsonKeyFrameUtils;
            // Molang-expression values need runtime conversion.
            if (!currentFrame.isStartPrimitive()) {
                startValue = Math.toRadians(startValue);
                if (axis == Axis.X || axis == Axis.Y) {
                    startValue *= -1;
                }
            }
            if (!currentFrame.isEndPrimitive()) {
                endValue = Math.toRadians(endValue);
                if (axis == Axis.X || axis == Axis.Y) {
                    endValue *= -1;
                }
            }
        }

        return AnimationPoint.obtain(currentFrame, location.currentTick, currentFrame.getLengthPrimitive(), startValue, endValue);
    }

    /** Cache entry for {@link #getCurrentKeyFrameLocation} — remembers the last
     *  returned keyframe index and its cumulative end time so that subsequent
     *  calls (with monotonically increasing tick) can skip the linear scan from
     *  index 0 and start from the cached position instead. */
    private static final class KfCacheEntry {
        int index;
        double cumulativeTime;
        double ageInTicks;
        KfCacheEntry() {}
        KfCacheEntry(int index, double cumulativeTime, double ageInTicks) {
            set(index, cumulativeTime, ageInTicks);
        }
        void set(int index, double cumulativeTime, double ageInTicks) {
            this.index = index;
            this.cumulativeTime = cumulativeTime;
            this.ageInTicks = ageInTicks;
        }
    }
    private java.util.IdentityHashMap<List<KeyFrame<IValue>>, KfCacheEntry> kfCache;

    /**
     * Returns the current keyframe object, plus how long the previous keyframes
     * have taken (aka elapsed animation time).
     * Uses a per-list index cache to avoid re-scanning from index 0 every call
     * — tick increases monotonically within a running animation, so we can
     * start from the last known position and only scan forward.
     **/
    private KeyFrameLocation<KeyFrame<IValue>> getCurrentKeyFrameLocation(List<KeyFrame<IValue>> frames,
        double ageInTicks) {
        if (kfCache != null) {
            KfCacheEntry cached = kfCache.get(frames);
            if (cached != null) {
                if (ageInTicks < cached.ageInTicks) {
                    // tick went backwards (animation looped) — clear this entry
                    kfCache.remove(frames);
                } else if (ageInTicks < cached.cumulativeTime) {
                    // Same keyframe as last call — return immediately
                    KeyFrame<IValue> frame = frames.get(cached.index);
                    double prevTotal = cached.cumulativeTime - frame.getLengthPrimitive();
                    double tick = ageInTicks - prevTotal;
                    kfCache.computeIfAbsent(frames, k -> new KfCacheEntry()).set(cached.index, cached.cumulativeTime, ageInTicks);
                    return new KeyFrameLocation<>(frame, tick);
                } else {
                    // Moved to a later keyframe — scan from cached index + 1
                    double totalTimeTracker = cached.cumulativeTime;
                    for (int i = cached.index + 1; i < frames.size(); i++) {
                        KeyFrame<IValue> frame = frames.get(i);
                        double newTotal = totalTimeTracker + frame.getLengthPrimitive();
                        if (newTotal > ageInTicks) {
                            double tick = ageInTicks - totalTimeTracker;
                            kfCache.computeIfAbsent(frames, k -> new KfCacheEntry()).set(i, newTotal, ageInTicks);
                            return new KeyFrameLocation<>(frame, tick);
                        }
                        totalTimeTracker = newTotal;
                    }
                    // Past all frames — return last
                    int last = frames.size() - 1;
                    kfCache.computeIfAbsent(frames, k -> new KfCacheEntry()).set(last, totalTimeTracker, ageInTicks);
                    return new KeyFrameLocation<>(frames.get(last), ageInTicks);
                }
            }
        }

        // Full scan from beginning (first call, or after loop)
        double totalTimeTracker = 0;
        for (int i = 0; i < frames.size(); i++) {
            KeyFrame<IValue> frame = frames.get(i);
            totalTimeTracker += frame.getLengthPrimitive();
            if (totalTimeTracker > ageInTicks) {
                double tick = ageInTicks - (totalTimeTracker - frame.getLengthPrimitive());
                if (kfCache == null) {
                    kfCache = new java.util.IdentityHashMap<>();
                }
                kfCache.computeIfAbsent(frames, k -> new KfCacheEntry()).set(i, totalTimeTracker, ageInTicks);
                return new KeyFrameLocation<>(frame, tick);
            }
        }
        int last = frames.size() - 1;
        if (kfCache == null) {
            kfCache = new java.util.IdentityHashMap<>();
        }
        kfCache.computeIfAbsent(frames, k -> new KfCacheEntry()).set(last, totalTimeTracker, ageInTicks);
        return new KeyFrameLocation<>(frames.get(last), ageInTicks);
    }

    private void resetEventKeyFrames() {
        this.executedKeyFrames.clear();
    }

    public void markNeedsReload() {
        this.needsAnimationReload = true;
    }

    public void clearAnimationCache() {
        this.currentAnimationBuilder = new AnimationBuilder();
    }

    public double getAnimationSpeed() {
        return animationSpeed;
    }

    public void setAnimationSpeed(double animationSpeed) {
        this.animationSpeed = animationSpeed;
    }

    @FunctionalInterface
    public interface ModelFetcher<T> extends Function<IAnimatable, IAnimatableModel<T>> {
    }
}
