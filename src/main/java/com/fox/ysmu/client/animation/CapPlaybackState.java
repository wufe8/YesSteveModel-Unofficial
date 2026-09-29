package com.fox.ysmu.client.animation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「每个演员自己的 CAP 控制器播放簿记」：cap 控制器上是否正在播外部（轮盘/EEP）动画，
 * 以及该演员已经应用过的轮盘版本。
 *
 * <h3>为什么必须按演员分</h3>
 * <p>cap 谓词对每个被渲染的玩家都会跑一遍。原先这两件事各用一个 **static** 变量记在
 * {@code AnimationManager} 上，被所有玩家共用，于是：</p>
 * <ul>
 *   <li>A 在播 EEP 动画时把标志置真 → B（没有任何外部动画）走到末尾停止分支时读到的是
 *       A 的状态，于是清掉标志并停掉 {@code cap_controller} 的音效 —— 停的是 A 那条；
 *       反向也成立：B 的 EEP 请求刚下达（控制器仍处于 Stopped）会被 A 留下的标志判成
 *       "已经播完了"，于是在开播之前就被 {@code stopAnimation()} 掉。</li>
 *   <li>轮盘版本同理：点一次轮盘只让**第一个**被处理的演员重载控制器，其余演员的关键帧
 *       不会重置，音效关键帧也就不再重放。</li>
 * </ul>
 *
 * <p>键是玩家 UUID：模型播放器（{@code CustomPlayerRenderer}）全服共用一个
 * {@code CustomPlayerEntity} 实例并逐玩家切换它的 player 字段，所以动画实体实例不是身份；
 * GeckoLib 侧的每玩家状态本来就是靠 {@code getUniqueID(entity)} 分桶的。</p>
 *
 * <p>当前调用方都在渲染线程上（GeckoLib 控制器求值），容器用并发实现只是为了不让未来的
 * 跨线程读取出问题，判定本身是单线程的顺序逻辑。</p>
 */
final class CapPlaybackState {

    /** 单个演员的两个簿记量。 */
    private static final class ActorState {

        private boolean capPlaying;
        /** 该演员是否已经"见过"某个轮盘版本；未见过时第一次求值只记录、不触发重载。 */
        private boolean wheelVersionKnown;
        private int wheelVersion;
    }

    private final Map<UUID, ActorState> actors = new ConcurrentHashMap<>();

    private ActorState stateOf(UUID actor) {
        return actors.computeIfAbsent(actor, k -> new ActorState());
    }

    /** 标记该演员开始在 cap 控制器上播外部动画；返回 true 表示这是上升沿（用于去重日志）。 */
    boolean begin(UUID actor) {
        ActorState state = stateOf(actor);
        boolean risingEdge = !state.capPlaying;
        state.capPlaying = true;
        return risingEdge;
    }

    /** 该演员当前是否在播外部动画（**只反映它自己**的状态）。 */
    boolean isPlaying(UUID actor) {
        ActorState state = actors.get(actor);
        return state != null && state.capPlaying;
    }

    /** 结束该演员的播放；返回 true 表示此前确实在播（调用方据此清理它自己的音效）。 */
    boolean end(UUID actor) {
        ActorState state = actors.get(actor);
        if (state == null || !state.capPlaying) {
            return false;
        }
        state.capPlaying = false;
        return true;
    }

    /**
     * 该演员是否遇到了一个它还没见过的轮盘版本；是则记下并返回 true，调用方据此重载它自己的控制器。
     * 版本号由 {@code AnimationManager.setCurrentWheelAnimName} 自增。
     *
     * <p><b>首次求值只记录、不触发</b>：旧实现是一个全局 {@code lastWheelAnimVersion}，它的初值
     * 与 {@code wheelAnimVersion} 相同，所以任何人第一次进游戏/第一次被渲染时都不会重置控制器。
     * 按演员分之后必须保留这一点（用 {@code wheelVersionKnown} 显式表达），否则每个演员的**第一帧**
     * 都会把 cap 控制器的动画重置到 tick 0 —— 那是一个新的、可见的行为变化。</p>
     */
    boolean consumeWheelVersion(UUID actor, int version) {
        ActorState state = stateOf(actor);
        if (!state.wheelVersionKnown) {
            state.wheelVersionKnown = true;
            state.wheelVersion = version;
            return false;
        }
        if (state.wheelVersion == version) {
            return false;
        }
        state.wheelVersion = version;
        return true;
    }

    /** 丢弃该演员的簿记（模型切换 / 玩家登出）。其他演员不受影响。 */
    void clear(UUID actor) {
        actors.remove(actor);
    }

    /**
     * 丢弃所有演员的簿记（会话结束时调用）：下次会话的第一次求值重新回到"只记录版本、不重置"。
     * {@link #clear} 是单演员（模型切换 / 登出），这里是整场会话。
     */
    void clearAll() {
        actors.clear();
    }

    /** 当前被跟踪的演员数（诊断/测试）。 */
    int trackedActors() {
        return actors.size();
    }
}
