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
 * <p>参数布局对齐参考树 {@code OpenYSM/} 的 {@code SoundFunction}（2.6.5 树，
 * {@code play} 2~5 参 / {@code stop} 1~2 参 / {@code stop_all} 0~1 参）：</p>
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
 *
 * <h3>为什么按名字拆成三个子类</h3>
 * <p>{@link Function} 的构造器**在子类字段赋值之前**就调用 {@code getRequiredArguments()}
 * 校验实参个数（{@code Function:11}）。所以"最少几个参数"只能由**类**决定，不能像原来那样
 * 从构造器里才赋值的 {@code stop}/{@code stopAll} 字段推导 —— 旧实现读到的永远是未初始化的
 * {@code false}，三个名字一律要求 2 个参数，于是 1 参的
 * {@code ysm.stop_sound('id')}（文档允许的写法）和 0 参的 {@code ysm.stop_all_sounds()}
 * 直接抛 "requires at least 2 arguments"，被 mclib 包成
 * {@code MolangException: Couldn't parse '…' expression!}。</p>
 * <p>后果远不止这一条语句：{@code .molang} 脚本是**整份先解析再执行**的
 * （{@link MolangScriptInterpreter}），一条表达式解析失败会让整个文件永不执行 ——
 * 某个模型的 {@code @player_update} 文件里同时放着按键鸣笛、随机鸣笛与轮胎旋转，
 * 一处 1 参 {@code stop_sound} 就把这一整批逻辑全部废掉（实机日志：
 * {@code [YSMU-MOLANG-SCRIPT] … player_update failed to execute}）。</p>
 */
public abstract class YsmSoundFunction extends Function {

    private final boolean stopAll;
    private final boolean stop;

    YsmSoundFunction(IValue[] values, String name, boolean stop, boolean stopAll) throws Exception {
        super(values, name);
        this.stop = stop;
        this.stopAll = stopAll;
    }

    /** {@code ysm.play_sound(id, sound_name, flags?, volume?, pitch?)}：至少 2 个参数。 */
    public static final class Play extends YsmSoundFunction {

        public Play(IValue[] values, String name) throws Exception {
            super(values, name, false, false);
        }

        @Override
        public int getRequiredArguments() {
            return 2;
        }
    }

    /** {@code ysm.stop_sound(id, global?)}：至少 1 个参数。 */
    public static final class Stop extends YsmSoundFunction {

        public Stop(IValue[] values, String name) throws Exception {
            super(values, name, true, false);
        }

        @Override
        public int getRequiredArguments() {
            return 1;
        }
    }

    /** {@code ysm.stop_all_sounds(global?)}：可以 0 个参数。 */
    public static final class StopAll extends YsmSoundFunction {

        public StopAll(IValue[] values, String name) throws Exception {
            super(values, name, false, true);
        }

        @Override
        public int getRequiredArguments() {
            return 0;
        }
    }

    @Override
    public double get() {
        try {
            Entity entity = ParticleEffectUtil.getCurrentEntity();
            if (entity == null) {
                return 0.0d;
            }
            // 归属 = 当前求值的实体（模型持有者）。音效的播放/停止都按归属分层，
            // 否则一个模型脚本的 stop_all_sounds 会把别人的音效一起掐掉
            // （参考实现里 global=0/缺省 的语义就是"只停当前实体上下文"）。
            String ownerSlot = YSMSoundManager.ownerKey(entity instanceof EntityPlayer ? (EntityPlayer) entity : null);
            if (stopAll) {
                // stop_all_sounds(global?)：global 非 0 时停全局，否则只停当前实体的上下文。
                boolean global = args.length > 0 && getArg(0) != 0;
                if (global) {
                    YSMSoundManager.stopAll();
                } else {
                    YSMSoundManager.stopAll(ownerSlot);
                }
                return 1.0d;
            }
            String id = MolangStringPool.get((int) getArg(0));
            if (stop) {
                // 停止按该 id（作为 soundName）播放的活跃音源。global（第二个参数）为真时
                // 不限定归属，与参考实现的 stop_sound(id, global?) 一致。
                if (id != null && !id.isEmpty()) {
                    if (args.length > 1 && getArg(1) != 0) {
                        YSMSoundManager.stopSound(id);
                    } else {
                        YSMSoundManager.stopSound(ownerSlot, id);
                    }
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
