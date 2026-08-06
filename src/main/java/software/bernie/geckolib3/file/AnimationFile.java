package software.bernie.geckolib3.file;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import software.bernie.geckolib3.core.builder.Animation;

public class AnimationFile implements Serializable {

    private static final long serialVersionUID = 42L;
    public HashMap<String, Animation> animations = new HashMap<>();

    // YSMU: host-mod-injected hooks. Typed with plain JDK functional interfaces so this
    // vendored file has NO compile-time dependency back on mod code (inverted control).
    // volatile: 跨线程可见性（可能由 ClientProxy.init 的注册线程写入、渲染线程读取）。
    // 约定：仅由 YsmBuiltinAnimations.registerHooks() 在 ClientProxy.init 设置一次（幂等），
    // 运行时只读；null 时 getAnimation/putAnimation 保持原行为（优雅降级）。
    /** (animationName, model-defined animation) -> replacement animation, or null to keep
     *  the model's own. YSMU uses it for the built-in no-op "empty" animation fallback. */
    public static volatile BiFunction<String, Animation, Animation> builtinFallback = null;
    /** Animation names rejected on write (e.g. the built-in debug animation "empty", which
     *  per the YSM wiki is an exceptional debug animation models must not define). */
    public static volatile Predicate<String> rejectedAnimationNames = null;

    public Animation getAnimation(String name) {
        Animation anim = animations.get(name);
        if (builtinFallback != null) {
            Animation fallback = builtinFallback.apply(name, anim);
            if (fallback != null) {
                return fallback;
            }
        }
        return anim;
    }

    public Collection<Animation> getAllAnimations() {
        return this.animations.values();
    }

    public void putAnimation(String name, Animation animation) {
        if (rejectedAnimationNames != null && rejectedAnimationNames.test(name)) {
            return;
        }
        this.animations.put(name, animation);
    }
}
