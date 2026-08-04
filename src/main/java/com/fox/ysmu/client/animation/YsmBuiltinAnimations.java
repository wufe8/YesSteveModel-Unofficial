package com.fox.ysmu.client.animation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.eliotlash.mclib.math.IValue;

import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.ConstantValue;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;
import software.bernie.geckolib3.core.keyframe.BoneAnimation;
import software.bernie.geckolib3.core.keyframe.KeyFrame;
import software.bernie.geckolib3.core.keyframe.VectorKeyFrameList;

/**
 * YSMU 内置硬编码动画。
 *
 * <p>「empty」是 mod 提供的一个默认空操作动画（不改变任何骨骼），供模型控制器引用
 * 但模型文件未定义该动画时兜底使用（例如 Endfield_Rossi 的 空闲 状态引用 "empty"
 * 但动画文件缺失，导致 setAnimation 加载失败、currentAnimation 为 null，进而触发
 * all_animations_finished 误判为 true 的落地翻滚 bug）。
 *
 * <p>模型禁止修改此内置动画：除非模型自行定义了同名 "empty"（以模型为准），否则统一
 * 走这里。调试开关 {@link #DEBUG_EMPTY_VISIBLE} 打开后可强制所有 "empty" 播放都使用
 * 可见标记，便于肉眼识别模型正在播放空动画。
 */
public final class YsmBuiltinAnimations {

    private YsmBuiltinAnimations() {}

    /** 内置空动画名。 */
    public static final String EMPTY_ANIMATION_NAME = "empty";

    /**
     * 调试开关（硬编码，改代码后重新构建即可）：
     * <ul>
     *   <li>false（默认）：empty 是纯空动画，不产生任何变换；模型自带的 "empty" 优先。</li>
     *   <li>true：所有 "empty" 播放都强制使用内置版本，并给 Root 骨骼加上持续旋转，
     *       哪个模型/控制器在播 empty 会一眼看出（模型持续晃动）。</li>
     * </ul>
     */
    public static final boolean DEBUG_EMPTY_VISIBLE = false;

    private static final Animation EMPTY_ANIMATION = buildEmptyAnimation();

    /** 返回内置 "empty" 动画（单例，调用方不得修改其字段）。 */
    public static Animation getEmptyAnimation() {
        return EMPTY_ANIMATION;
    }

    /** 内置 empty 兜底是否对该调用生效：名字为 "empty"，且（调试开关打开 或 模型未定义）。 */
    public static boolean isBuiltinEmptyUsed(String animationName, Animation modelAnimation) {
        if (!EMPTY_ANIMATION_NAME.equals(animationName)) {
            return false;
        }
        return DEBUG_EMPTY_VISIBLE || modelAnimation == null;
    }

    private static Animation buildEmptyAnimation() {
        Animation anim = new Animation();
        anim.animationName = EMPTY_ANIMATION_NAME;
        anim.animationLength = 20.0d; // 1 秒（tick 数），空动画循环播放
        anim.loop = EDefaultLoopTypes.LOOP;
        anim.soundKeyFrames = new ArrayList<>();
        anim.particleKeyFrames = new ArrayList<>();
        anim.customInstructionKeyframes = new ArrayList<>();
        if (DEBUG_EMPTY_VISIBLE) {
            // ── 调试标记 ──────────────────────────────────────────
            // 需要更明显的标记时改这里：给任意骨骼加变换，模型播放 empty 就会
            // 呈现该变换。YSM 通用骨骼名为 "Root"；若目标模型无此骨，该变换
            // 静默失效（不影响播放）。
            anim.boneAnimations = buildDebugRootWobble();
            ysmu.LOG.warn("[YSMU] DEBUG_EMPTY_VISIBLE=true: built-in '{}' renders a visible Root wobble",
                EMPTY_ANIMATION_NAME);
        } else {
            // 默认：纯空动画（不改变任何骨骼）。
            anim.boneAnimations = new ArrayList<>();
        }
        return anim;
    }

    /** 调试用：Root 骨骼 Y 轴 0→180 度线性旋转（每 2 秒循环一次），肉眼可辨。 */
    private static List<BoneAnimation> buildDebugRootWobble() {
        BoneAnimation root = new BoneAnimation();
        root.boneName = "Root";
        KeyFrame<IValue> ry = new KeyFrame<>(40.0d, new ConstantValue(0.0d), new ConstantValue(180.0d));
        VectorKeyFrameList<KeyFrame<IValue>> rot = new VectorKeyFrameList<>(
            Collections.<KeyFrame<IValue>>emptyList(),
            Collections.<KeyFrame<IValue>>emptyList(),
            new ArrayList<KeyFrame<IValue>>(Arrays.asList(ry)));
        root.rotationKeyFrames = rot;
        root.positionKeyFrames = new VectorKeyFrameList<>();
        root.scaleKeyFrames = new VectorKeyFrameList<>();
        return new ArrayList<BoneAnimation>(Arrays.asList(root));
    }
}
