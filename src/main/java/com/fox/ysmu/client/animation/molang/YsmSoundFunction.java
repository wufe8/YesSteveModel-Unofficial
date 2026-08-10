package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.audio.YSMSoundManager;
import com.fox.ysmu.client.particle.ParticleEffectUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code ysm.play_sound / ysm.stop_sound / ysm.stop_all_sounds} 的 mclib 实现
 * （动画关键帧 / {@code .molang} 指令路径）。
 *
 * <p>参数布局对齐 OpenYSM 2.5.3：</p>
 * <pre>
 *   ysm.play_sound('id', 'sound_name', flags?, volume?, pitch?)   // 2~5 参数
 *   ysm.stop_sound('id', global?)                                 // 1~2 参数
 *   ysm.stop_all_sounds(global?)                                  // 0~1 参数
 * </pre>
 * <p>{@code id} 是本次播放的逻辑标识；{@code sound_name} 写法与声音关键帧一致
 * （模型音效名 / {@code namespace:path} / 本地高版本资产路径）。{@code flags}
 * 位标志（1=强制替换、2=全局、4=循环）在 1.7.10 里部分不适用：{@link YSMSoundManager}
 * 的播放本身先停同名再播（天然替换），循环音效与全局上下文无对应能力，故忽略；
 * {@code volume}/{@code pitch} 缺省 1.0。</p>
 *
 * <p>字符串参数经 {@link MolangStringPool} 池化为整数 id，求值时刻还原；
 * 实体上下文由 {@link ParticleEffectUtil#setCurrentEntity} 每帧写入，
 * 当前模型 id 由 {@link MolangPhysicsRuntime#getCurrentModelId} 提供。</p>
 */
public class YsmSoundFunction extends Function {

    private final boolean stopAll;
    private final boolean stop;

    public YsmSoundFunction(IValue[] values, String name) throws Exception {
        super(values, name);
        this.stopAll = name != null && name.contains("stop_all");
        this.stop = !stopAll && name != null && name.contains("stop_sound");
    }

    @Override
    public int getRequiredArguments() {
        if (stopAll) {
            return 0;
        }
        return stop ? 1 : 2;
    }

    @Override
    public double get() {
        try {
            Entity entity = ParticleEffectUtil.getCurrentEntity();
            if (entity == null) {
                return 0.0d;
            }
            if (stopAll) {
                YSMSoundManager.stopAll();
                return 1.0d;
            }
            String id = MolangStringPool.get((int) getArg(0));
            if (stop) {
                // 停止按该 id（作为 soundName）播放的活跃音源。
                if (id != null && !id.isEmpty()) {
                    YSMSoundManager.stopSound(id);
                }
                return 1.0d;
            }
            // play
            if (id == null || id.isEmpty()) {
                return 0.0d;
            }
            String soundName = MolangStringPool.get((int) getArg(1));
            if (soundName == null || soundName.isEmpty()) {
                return 0.0d;
            }
            float volume = args.length > 3 ? (float) getArg(3) : 1.0f;
            float pitch = args.length > 4 ? (float) getArg(4) : 1.0f;
            ResourceLocation modelId = MolangPhysicsRuntime.getCurrentModelId();
            EntityPlayer player = entity instanceof EntityPlayer ? (EntityPlayer) entity : null;
            if (player == null) {
                return 0.0d;
            }
            if (Config.DEBUG_SOUND) {
                ysmu.LOG.info("[YSMU-SOUND] molang play_sound: id='{}' name='{}' vol={} pitch={} model={}",
                    id, soundName, volume, pitch, modelId);
            }
            YSMSoundManager.playSound(player, soundName, modelId, volume, pitch);
            return 1.0d;
        } catch (Exception e) {
            if (Config.DEBUG_SOUND) {
                ysmu.LOG.warn("[YSMU-SOUND] molang sound function error: {}", e.toString());
            }
            return 0.0d;
        }
    }
}
