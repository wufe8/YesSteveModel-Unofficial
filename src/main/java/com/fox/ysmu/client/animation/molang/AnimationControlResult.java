package com.fox.ysmu.client.animation.molang;

/**
 * 动画控制脚本（{@code @player_ctrl_<slot>.molang}）一次求值的结果。
 *
 * <p>YSM-wiki: molang/script「动画控制」—— 脚本每帧执行一次，用 {@code ctrl.set_animation}
 * 指定要播的动画，并用 {@code return} 返回一个**谓词**告诉控制器这一帧怎么处理当前动画：</p>
 *
 * <table>
 * <tr><td>{@code ctrl.state_continue}</td><td>正常播放</td></tr>
 * <tr><td>{@code ctrl.state_pause}</td><td>暂停播放（不暂停时间轴）</td></tr>
 * <tr><td>{@code ctrl.state_stop}</td><td>平滑停止</td></tr>
 * <tr><td>{@code ctrl.state_bypass}</td><td>本帧不动，交回内置控制逻辑</td></tr>
 * </table>
 *
 * <p>{@code set_animation(name, loop)} 的第二个参数可选，取值 {@code ctrl.loop} /
 * {@code ctrl.play_once} / {@code ctrl.hold_on_last_frame}。</p>
 */
public final class AnimationControlResult {

    /** 脚本返回的谓词。{@link #BYPASS} 表示"交回内置逻辑"。 */
    public enum Action {
        /** 脚本没有 return（或 return 了 0/未知值）—— 按 bypass 处理，不动内置逻辑。 */
        NONE,
        /** {@code ctrl.state_continue}。 */
        CONTINUE,
        /** {@code ctrl.state_pause}。 */
        PAUSE,
        /** {@code ctrl.state_stop}。 */
        STOP,
        /** {@code ctrl.state_bypass}。 */
        BYPASS
    }

    /** 脚本返回的谓词；默认 {@link Action#NONE}（= 不动内置逻辑）。 */
    private Action action = Action.NONE;
    /** {@code ctrl.set_animation(name)} 指定的动画名；没有调用则为 null。 */
    private String animationName;
    /** {@code ctrl.set_animation(name, loop)} 的循环类型；未指定为 null（用动画自带的）。 */
    private LoopType loopType;
    /** {@code ctrl.set_beginning_transition_length(秒)}；未指定为 null。 */
    private Double transitionSeconds;
    /** 脚本里出现过 {@code ctrl.indicate_reload}：即使同名动画也要重载。 */
    private boolean indicateReload;
    /** 脚本里出现过 {@code ctrl.reset}：立刻重置控制器（含 indicate_reload 的作用）。 */
    private boolean reset;

    public enum LoopType {
        /** {@code ctrl.loop}。 */
        LOOP,
        /** {@code ctrl.play_once}。 */
        PLAY_ONCE,
        /** {@code ctrl.hold_on_last_frame}。 */
        HOLD_ON_LAST_FRAME
    }

    public String animationName() {
        return this.animationName;
    }

    public Action action() {
        return this.action;
    }

    /** 脚本没有给出可用请求时为 true（调用方应保持内置逻辑）。 */
    public boolean isNoOp() {
        return this.action == Action.NONE || this.action == Action.BYPASS;
    }

    public LoopType loopType() {
        return this.loopType;
    }

    public Double transitionSeconds() {
        return this.transitionSeconds;
    }

    public boolean indicateReload() {
        return this.indicateReload;
    }

    public boolean reset() {
        return this.reset;
    }

    // ---- 由 AnimationControlScope 在求值过程中写入 ----

    void setAction(Action action) {
        if (action != null) {
            this.action = action;
        }
    }

    void setAnimation(String name) {
        if (name != null && !name.isEmpty()) {
            this.animationName = name;
        }
    }

    void setLoopType(LoopType loopType) {
        this.loopType = loopType;
    }

    void setTransitionSeconds(double seconds) {
        if (seconds >= 0.0d && !Double.isNaN(seconds)) {
            this.transitionSeconds = seconds;
        }
    }

    void markIndicateReload() {
        this.indicateReload = true;
    }

    void markReset() {
        this.reset = true;
    }

    @Override
    public String toString() {
        return "AnimationControlResult{action=" + this.action
            + ", animation=" + this.animationName
            + ", loop=" + this.loopType
            + ", transition=" + this.transitionSeconds
            + ", reload=" + this.indicateReload
            + ", reset=" + this.reset + "}";
    }
}
