//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package software.bernie.geckolib3.core.builder;

import java.io.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import software.bernie.geckolib3.core.builder.ILoopType.EDefaultLoopTypes;
import software.bernie.geckolib3.core.keyframe.BoneAnimation;
import software.bernie.geckolib3.core.keyframe.EventKeyFrame;
import software.bernie.geckolib3.core.keyframe.ParticleEventKeyFrame;

public class Animation implements Serializable {

    private static final long serialVersionUID = 42L;
    public String animationName;
    public Double animationLength;
    public ILoopType loop;
    /** YSMU: Bedrock-style per-animation custom time advance (anim_time_update).
     *  Molang expression evaluated each frame that returns the animation time
     *  in seconds (e.g. "query.anim_time + query.delta_time * 2"). Null/empty
     *  = normal time advance. YSM has no native equivalent; the name follows
     *  the Bedrock wiki.
     *  局限：过渡（blend）期间不生效（blend 不走 processCurrentAnimation）；
     *  若与 {@link #animSpeed} 同时设置则优先于 animSpeed；每帧表达式求值；
     *  二进制 .ysm 暂不支持。 */
    public String animTimeUpdate;
    /** YSMU: per-animation playback speed multiplier（数字或 Molang 表达式，返回
     *  播放倍率），映射到 GeckoLib AnimationController.animationSpeed，与防滑步
     *  倍率相乘。仅标量倍率，不支持任意时间曲线——需要自定义时间推进请用
     *  {@link #animTimeUpdate}。二进制 .ysm 暂不支持。 */
    public String animSpeed;
    public List<BoneAnimation> boneAnimations;
    public List<EventKeyFrame<String>> soundKeyFrames;
    public List<ParticleEventKeyFrame> particleKeyFrames;
    public List<EventKeyFrame<String>> customInstructionKeyframes;

    public Animation() {
        this.loop = EDefaultLoopTypes.LOOP;
        this.soundKeyFrames = new ArrayList();
        this.particleKeyFrames = new ArrayList();
        this.customInstructionKeyframes = new ArrayList();
    }

    public static Animation copy(Animation animation) {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(animation);
            objectOutputStream.flush();
            String serialized = Base64.getEncoder()
                .encodeToString(byteArrayOutputStream.toByteArray());

            byte[] data = Base64.getDecoder()
                .decode(serialized);
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
            ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
            return (Animation) objectInputStream.readObject();
        } catch (Exception e) {
            return animation;
        }
    }
}
