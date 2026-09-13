package com.fox.ysmu.util;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code ysm.on_ground_time} 的逐箭计时器。
 *
 * <p>YSM-wiki: molang/ref（ysm 弹射物）—— {@code ysm.on_ground_time} 是"箭矢掉在地上的时长
 * （单位：刻）"，落地 1200 刻（60 秒）后箭矢消失，且"如果在此期间箭矢被移动则重置为 0"。
 * 模型里的用法是短窗口判断（例如 {@code ysm.on_ground_time <= 2} 表示"刚落地"）。</p>
 *
 * <p>1.7.10 的 {@code EntityArrow} 把该计数放在**私有**字段 {@code ticksInGround} 里，只在
 * NBT（{@code "life"}）里出现，渲染期读不到。所以这里按实体 id 自己累计：
 * 每过一个**实体刻**且仍在地里就 +1，一旦判定离地立刻归零。按实体刻而不是按渲染帧推进，
 * 是为了让 60 FPS 和 200 FPS 下得到同一个"刻"数。</p>
 *
 * <p>纯逻辑、无 Minecraft 依赖，便于单测。</p>
 */
public final class ProjectileGroundTracker {

    /** 单个箭矢的计时状态。 */
    private static final class State {

        private int ticks;
        private int lastEntityTicks = Integer.MIN_VALUE;
    }

    /** 上限：只在箭矢可见时调用，超过就整体清空，避免实体 id 无限累积。 */
    private static final int MAX_TRACKED = 512;

    private final Map<Integer, State> states = new HashMap<>();

    /**
     * 推进一帧并返回该实体当前的落地刻数。
     *
     * @param entityId    实体 id
     * @param inGround    本帧是否判定为"插在地上"
     * @param entityTicks 实体的 {@code ticksExisted}
     * @return 连续插在地上的刻数；未落地时为 0
     */
    public int update(int entityId, boolean inGround, int entityTicks) {
        if (!inGround) {
            // 离地（或被移动）立刻归零，与 wiki 的"被移动则重置为 0"一致。
            this.states.remove(entityId);
            return 0;
        }
        if (this.states.size() > MAX_TRACKED) {
            this.states.clear();
        }
        State state = this.states.get(entityId);
        if (state == null) {
            state = new State();
            this.states.put(entityId, state);
        }
        if (entityTicks != state.lastEntityTicks) {
            state.lastEntityTicks = entityTicks;
            state.ticks++;
        }
        return state.ticks;
    }

    /** 箭矢消失时清掉状态。 */
    public void forget(int entityId) {
        this.states.remove(entityId);
    }

    /** 已跟踪的实体数（测试/诊断用）。 */
    int tracked() {
        return this.states.size();
    }
}
