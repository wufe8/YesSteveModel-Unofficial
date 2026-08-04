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
    /** (animationName, model-defined animation) -> replacement animation, or null to keep
     *  the model's own. YSMU uses it for the built-in no-op "empty" animation fallback. */
    public static BiFunction<String, Animation, Animation> builtinFallback = null;
    /** Animation names rejected on write (e.g. the built-in debug animation "empty", which
     *  per the YSM wiki is an exceptional debug animation models must not define). */
    public static Predicate<String> rejectedAnimationNames = null;

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
