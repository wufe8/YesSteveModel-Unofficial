package com.fox.ysmu.client.animation.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.animation.molang.MolangInstructionExecutor;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.keyframe.EventKeyFrame;
import software.bernie.geckolib3.file.AnimationFile;

/**
 * 弹射物（子模型）动画时间轴执行器：把活动动画里的 {@code timeline} 指令派发出去。
 *
 * <p>玩家侧的时间轴由 GeckoLib 控制器每帧回调驱动（{@code AnimationController.processCurrentAnimation}
 * → {@code OpenYsmPlayerControllerRuntime.handleTimelinePlayback}）；弹射物不走控制器播放，
 * 所以这条链路上以前**完全没有**时间轴派发：模型写在 {@code parallel1}/{@code post_ground} 里的
 * {@code ysm.particle(...)}（飞行拖尾、命中水花）一次都不会执行。</p>
 *
 * <p><b>时钟语义（与骨骼采样一致）</b>：每个动画的时间轴从它**所属状态进入的那一刻**开始计时。
 * 活动动画集合变化 = 控制器切状态（{@code default ↔ 箭矢落地}）= 时间轴重新从 0 派发，
 * 因此 {@code post_main}/{@code post_ground} 开头 t=0 的指令一定会在进入状态那一帧发出，
 * 而不是按实体总年龄被跳过（实体总年龄早就越过了这些 0.2 秒级动画的末尾）。
 * 循环动画（如 {@code parallel1}，周期 0.2 tick）仍旧按自身周期反复派发。</p>
 *
 * <p>时钟用"实体年龄的增量"推进：渲染帧可以比 tick 密（同一 tick 内渲染多次时增量为 0，
 * 不会重复派发），被遮挡后重新出现的大跳跃由 {@link TimelineEventScheduler} 的追赶上限兜住。</p>
 */
public final class ProjectileTimelineRuntime {

    /** 每渲染帧的派发上限（与玩家侧一样有界，防止模型的短周期时间轴刷爆日志/粒子系统）。 */
    public static final int MAX_DISPATCHES_PER_FRAME = 1024;

    private static int frameBudget = MAX_DISPATCHES_PER_FRAME;

    /** 每条弹射物动画的调度器状态。 */
    private static final class State {
        TimelineEventScheduler scheduler;
        String programKey = "";
        /** 上一帧的实体年龄；NaN = 还没有基准。 */
        double lastAge = Double.NaN;
        /** 自上次重启以来累计的 tick 数（作为调度器的 position 上报）。 */
        double elapsed;
        /** 活动动画名（原样拼接，顺序敏感）：名字没变就不重建程序，避免每帧分配。 */
        String namesKey = "";
        Program program;
    }

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private static final TimelineEventScheduler.Sink SINK = instructions -> {
        if (frameBudget <= 0) {
            return;
        }
        frameBudget--;
        MolangInstructionExecutor.noteTimelineExecution("projectile", instructions);
        MolangInstructionExecutor.execute(instructions);
    };

    private ProjectileTimelineRuntime() {}

    /** 每渲染帧重置派发预算（由 {@code ClientEventHandler.onRenderTick} 调用）。 */
    public static void beginRenderFrame() {
        frameBudget = MAX_DISPATCHES_PER_FRAME;
    }

    /** 弹射物消失时丢弃状态（调用方：渲染器看到 {@code arrow.isDead}）。 */
    public static void forget(int entityId, ResourceLocation animId) {
        STATES.remove(key(entityId, animId));
    }

    /**
     * 派发这一帧的时间轴指令。
     *
     * @param entityId    弹射物实体 id（状态隔离）
     * @param animId      弹射物动画 id（{@code ysm.json} 的 {@code files.projectiles} 条目）
     * @param file        该动画的 {@link AnimationFile}
     * @param activeAnims 本帧活动动画名（顺序无关，只影响派发顺序）
     * @param ageInTicks  实体总年龄（含 partial tick）
     */
    public static void dispatch(int entityId, ResourceLocation animId, AnimationFile file,
        List<String> activeAnims, double ageInTicks) {
        if (animId == null || file == null || activeAnims == null || activeAnims.isEmpty()) {
            return;
        }
        String stateKey = key(entityId, animId);
        State state = STATES.computeIfAbsent(stateKey, ignored -> new State());

        String namesKey = String.join(",", activeAnims);
        Program program = namesKey.equals(state.namesKey) && state.program != null
            ? state.program
            : buildProgram(file, activeAnims);
        state.namesKey = namesKey;
        state.program = program;
        if (program.contributors.isEmpty()) {
            state.scheduler = null;
            state.programKey = "";
            state.lastAge = ageInTicks;
            return;
        }

        // 活动集合变化 = 控制器切状态：时间轴时钟从 0 重新开始（这也是 post_main/post_ground
        // 这类"进入状态才播一次"的动画能发出 t=0 指令的前提）。
        if (!program.key.equals(state.programKey) || state.scheduler == null) {
            state.scheduler = new TimelineEventScheduler();
            state.scheduler.configure(program.contributors, true);
            state.programKey = program.key;
            state.elapsed = 0.0d;
            state.lastAge = ageInTicks;
        }

        double delta = Double.isNaN(state.lastAge) ? 0.0d : ageInTicks - state.lastAge;
        state.lastAge = ageInTicks;
        if (!(delta > 0.0d)) {
            // 同一 tick 内的第二遍渲染（增量为 0）：位置不变，调度器按"停摆"处理，不重复派发。
            delta = 0.0d;
        }
        state.elapsed += delta;
        state.scheduler.advanceFrame(state.elapsed, delta, SINK);
    }

    private static String key(int entityId, ResourceLocation animId) {
        return entityId + "|" + animId;
    }

    /** 构建好的调度器程序 + 内容键（集合/事件变化都会改变键）。 */
    private static final class Program {
        final List<TimelineEventScheduler.Contributor> contributors;
        final String key;

        Program(List<TimelineEventScheduler.Contributor> contributors, String key) {
            this.contributors = contributors;
            this.key = key;
        }
    }

    private static Program buildProgram(AnimationFile file, List<String> activeAnims) {
        List<TimelineEventScheduler.Contributor> out = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        double longest = 0.0d;
        for (String name : activeAnims) {
            Animation animation = file.animations.get(name);
            if (animation == null || animation.customInstructionKeyframes == null
                || animation.customInstructionKeyframes.isEmpty()) {
                continue;
            }
            double length = OpenYsmPlayerControllerRuntime.playbackLengthTicks(animation);
            longest = Math.max(longest, length);
        }
        for (String name : activeAnims) {
            Animation animation = file.animations.get(name);
            if (animation == null || animation.customInstructionKeyframes == null
                || animation.customInstructionKeyframes.isEmpty()) {
                continue;
            }
            double period = OpenYsmPlayerControllerRuntime.playbackLengthTicks(animation);
            if (!(period > 0.0d) && longest > 0.0d) {
                // 周期算不出来（anim_time_update 之类）时挂到最长周期上，否则事件只会响一次。
                period = longest;
            }
            boolean loops = animation.loop == ILoopType.EDefaultLoopTypes.LOOP;
            List<TimelineEventScheduler.Event> events = new ArrayList<>();
            for (EventKeyFrame<String> keyFrame : animation.customInstructionKeyframes) {
                if (keyFrame == null) {
                    continue;
                }
                Double tick = keyFrame.getStartTick();
                events.add(new TimelineEventScheduler.Event(tick == null ? 0.0d : tick, keyFrame.getEventData()));
                key.append(name).append('@').append(tick == null ? 0.0d : tick).append(';');
            }
            key.append(name).append('#').append(period).append('#').append(loops).append('|');
            out.add(TimelineEventScheduler.contributor(name, period, loops, events));
        }
        return new Program(out, key.toString());
    }
}
