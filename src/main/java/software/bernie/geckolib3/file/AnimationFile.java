package software.bernie.geckolib3.file;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;

import software.bernie.geckolib3.core.builder.Animation;

public class AnimationFile implements Serializable {

    private static final long serialVersionUID = 42L;
    public HashMap<String, Animation> animations = new HashMap<>();

    public Animation getAnimation(String name) {
        Animation anim = animations.get(name);
        // YSMU: 内置 "empty" 空动画兜底。模型控制器引用 "empty" 但模型文件未定义时
        // （如 Endfield_Rossi 的 空闲 状态）返回硬编码的内置空动画（见
        // YsmBuiltinAnimations），避免 setAnimation 加载失败导致 currentAnimation
        // 为 null，进而触发 all_animations_finished 误判等连锁问题。
        // 模型自行定义了 "empty" 时以模型为准（除非调试开关 DEBUG_EMPTY_VISIBLE 打开）。
        if (com.fox.ysmu.client.animation.YsmBuiltinAnimations.isBuiltinEmptyUsed(name, anim)) {
            return com.fox.ysmu.client.animation.YsmBuiltinAnimations.getEmptyAnimation();
        }
        return anim;
    }

    public Collection<Animation> getAllAnimations() {
        return this.animations.values();
    }

    public void putAnimation(String name, Animation animation) {
        this.animations.put(name, animation);
    }
}
