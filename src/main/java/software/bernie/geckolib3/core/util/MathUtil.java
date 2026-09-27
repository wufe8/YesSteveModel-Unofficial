package software.bernie.geckolib3.core.util;

import java.util.function.Function;

import software.bernie.geckolib3.core.easing.EasingManager;
import software.bernie.geckolib3.core.easing.EasingType;
import software.bernie.geckolib3.core.keyframe.AnimationPoint;

public class MathUtil {

    /**
     * Lerps an AnimationPoint
     *
     * @param animationPoint The animation point
     * @return the resulting lerped value
     */
    public static float lerpValues(AnimationPoint animationPoint, EasingType easingType,
        Function<Double, Double> customEasingMethod) {
        // AnimationPoint 的四个字段现在是基本类型（见 AnimationPoint 的注释），所以不再有
        // "池里拿出来的点还没填值"这种 null 情况：一个没被 obtain 过的点是全 0，
        // 下面第一个判断就会把它送到 animationEndValue（同样是 0），与旧的 null 兜底等价。
        if (animationPoint.currentTick >= animationPoint.animationEndTick) {
            return (float) animationPoint.animationEndValue;
        }

        if (easingType == EasingType.CUSTOM && customEasingMethod != null) {
            return lerpValues(
                customEasingMethod.apply(animationPoint.currentTick / animationPoint.animationEndTick),
                animationPoint.animationStartValue,
                animationPoint.animationEndValue);
        } else if (easingType == EasingType.NONE && animationPoint.keyframe != null) {
            easingType = animationPoint.keyframe.easingType;
        }
        double percent = animationPoint.currentTick / animationPoint.animationEndTick;
        // YSMU perf: Linear 是 KeyFrame 的默认缓动（绝大多数关键帧都是它），NONE 也一样 ——
        // EasingManager.getEasingFuncImpl 对这两者走 default 分支，返回的 in(linear) 里
        // in() 原样返回、linear(t)=t，所以缓动结果就是 percent 本身。直接算掉，省掉每次调用
        // 一次 new EasingFunctionArgs + 两次 Double 装箱（Function<Double,Double> 的参数与返回值）
        // + 一次 Memoizer 查表（它的 key 还会被缓存长期留住：堆快照里 106,851 个）。
        if (easingType == EasingType.Linear || easingType == EasingType.NONE) {
            return lerpValues(percent, animationPoint.animationStartValue, animationPoint.animationEndValue);
        }
        double ease = EasingManager.ease(
            percent,
            easingType,
            animationPoint.keyframe == null ? null : animationPoint.keyframe.easingArgs);
        return lerpValues(ease, animationPoint.animationStartValue, animationPoint.animationEndValue);
    }

    /**
     * This is the actual function that smoothly interpolates (lerp) between
     * keyframes
     *
     * @param startValue The animation's start value
     * @param endValue   The animation's end value
     * @return The interpolated value
     */
    public static float lerpValues(double percentCompleted, double startValue, double endValue) {
        // current tick / position should be between 0 and 1 and represent the
        // percentage of the lerping that has completed
        return (float) lerp(percentCompleted, startValue, endValue);
    }

    public static double lerp(double pct, double start, double end) {
        return start + pct * (end - start);
    }
}
