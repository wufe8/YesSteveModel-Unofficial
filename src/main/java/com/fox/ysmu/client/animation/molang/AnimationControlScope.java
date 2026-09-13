package com.fox.ysmu.client.animation.molang;

import java.util.List;
import java.util.Locale;

/**
 * 动画控制脚本（{@code @player_ctrl_<slot>.molang}）的作用域：包住宿主真正的作用域
 * （游戏侧是 {@code OpenYsmScriptScope}），只截获动画控制相关的名字，其余原样转发。
 *
 * <p>被截获的名字（wiki: molang/script「动画控制」）：</p>
 * <ul>
 *   <li>函数 {@code ctrl.set_animation(名字[, 循环类型])}、{@code ctrl.set_beginning_transition_length(秒)}、
 *       {@code ctrl.indicate_reload}、{@code ctrl.reset}；</li>
 *   <li>谓词变量 {@code ctrl.state_continue} / {@code ctrl.state_pause} / {@code ctrl.state_stop} /
 *       {@code ctrl.state_bypass}；</li>
 *   <li>循环类型常量 {@code ctrl.loop} / {@code ctrl.play_once} / {@code ctrl.hold_on_last_frame}。</li>
 * </ul>
 *
 * <p>{@code ctrl.indicate_reload} 与 {@code ctrl.reset} 在 wiki 里写作无参数调用，
 * 脚本里加不加小括号都可能出现，所以**变量与函数两种形态都支持**。</p>
 *
 * <p>谓词值编码（供 {@link #actionOf(double)} 还原）：continue=1、pause=2、stop=3、bypass=4。
 * 这些名字在宿主作用域里本来是未知变量（求值为 0），所以截获它们不会与任何既有语义冲突。</p>
 */
public final class AnimationControlScope implements MolangScriptInterpreter.MolangScriptScope {

    public static final String CTRL_STATE_CONTINUE = "ctrl.state_continue";
    public static final String CTRL_STATE_PAUSE = "ctrl.state_pause";
    public static final String CTRL_STATE_STOP = "ctrl.state_stop";
    public static final String CTRL_STATE_BYPASS = "ctrl.state_bypass";
    public static final String CTRL_LOOP = "ctrl.loop";
    public static final String CTRL_PLAY_ONCE = "ctrl.play_once";
    public static final String CTRL_HOLD_ON_LAST_FRAME = "ctrl.hold_on_last_frame";
    public static final String CTRL_SET_ANIMATION = "ctrl.set_animation";
    public static final String CTRL_SET_TRANSITION = "ctrl.set_beginning_transition_length";
    public static final String CTRL_INDICATE_RELOAD = "ctrl.indicate_reload";
    public static final String CTRL_RESET = "ctrl.reset";

    /**
     * 谓词编码。刻意用**远离常规数值**的大常数：脚本的求值结果是"最后一条语句的值"，
     * 如果谓词用 1/2/3/4，那么脚本结尾写成 {@code ctrl.set_animation('run')}（返回 1）
     * 或随手留一个 {@code v.x}（值 2）都会被误读成 CONTINUE/PAUSE。用 1e9 量级的编码后，
     * 只有显式写出的 {@code ctrl.state_*} 才可能命中。
     */
    private static final double PREDICATE_BASE = 1.0e9d;
    private static final double PREDICATE_CONTINUE = PREDICATE_BASE + 1;
    private static final double PREDICATE_PAUSE = PREDICATE_BASE + 2;
    private static final double PREDICATE_STOP = PREDICATE_BASE + 3;
    private static final double PREDICATE_BYPASS = PREDICATE_BASE + 4;

    private final MolangScriptInterpreter.MolangScriptScope inner;
    private final AnimationControlResult result = new AnimationControlResult();

    public AnimationControlScope(MolangScriptInterpreter.MolangScriptScope inner) {
        this.inner = inner;
    }

    public AnimationControlResult result() {
        return this.result;
    }

    // ---- 截获的名字 ----

    @Override
    public double variableValue(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        switch (lower) {
            case CTRL_STATE_CONTINUE:
                return PREDICATE_CONTINUE;
            case CTRL_STATE_PAUSE:
                return PREDICATE_PAUSE;
            case CTRL_STATE_STOP:
                return PREDICATE_STOP;
            case CTRL_STATE_BYPASS:
                return PREDICATE_BYPASS;
            case CTRL_LOOP:
                return 1.0d;
            case CTRL_PLAY_ONCE:
                return 2.0d;
            case CTRL_HOLD_ON_LAST_FRAME:
                return 3.0d;
            case CTRL_INDICATE_RELOAD:
                // wiki 写作无参数调用，但不带小括号时是一个变量引用 —— 两种形态都当"执行"。
                this.result.markIndicateReload();
                return 1.0d;
            case CTRL_RESET:
                this.result.markReset();
                return 1.0d;
            default:
                return this.inner.variableValue(name);
        }
    }

    @Override
    public double functionValue(String name, List<MolangScriptInterpreter.Argument> arguments) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        switch (lower) {
            case CTRL_SET_ANIMATION:
                if (arguments != null && !arguments.isEmpty()) {
                    MolangScriptInterpreter.Argument first = arguments.get(0);
                    this.result.setAnimation(first.isString() ? first.asString() : null);
                    if (arguments.size() >= 2) {
                        this.result.setLoopType(loopTypeOf(arguments.get(1)
                            .asNumber()));
                    }
                }
                // 返回 0：脚本结尾忘了写 return 时不能被误读成某个谓词。
                return 0.0d;
            case CTRL_SET_TRANSITION:
                if (arguments != null && !arguments.isEmpty()) {
                    this.result.setTransitionSeconds(arguments.get(0)
                        .asNumber());
                }
                return 0.0d;
            case CTRL_INDICATE_RELOAD:
                this.result.markIndicateReload();
                return 0.0d;
            case CTRL_RESET:
                this.result.markReset();
                return 0.0d;
            default:
                return this.inner.functionValue(name, arguments);
        }
    }

    // ---- 转发 ----

    @Override
    public void setVariableValue(String name, double value) {
        this.inner.setVariableValue(name, value);
    }

    @Override
    public double argument(int index) {
        return this.inner.argument(index);
    }

    @Override
    public int argumentCount() {
        return this.inner.argumentCount();
    }

    @Override
    public String functionScript(String name) {
        return this.inner.functionScript(name);
    }

    /** 谓词数值 → {@link AnimationControlResult.Action}；未知值按 {@code NONE}（= 不动内置逻辑）。 */
    public static AnimationControlResult.Action actionOf(double predicate) {
        if (predicate == PREDICATE_CONTINUE) {
            return AnimationControlResult.Action.CONTINUE;
        }
        if (predicate == PREDICATE_PAUSE) {
            return AnimationControlResult.Action.PAUSE;
        }
        if (predicate == PREDICATE_STOP) {
            return AnimationControlResult.Action.STOP;
        }
        if (predicate == PREDICATE_BYPASS) {
            return AnimationControlResult.Action.BYPASS;
        }
        return AnimationControlResult.Action.NONE;
    }

    private static AnimationControlResult.LoopType loopTypeOf(double value) {
        if (value == 1.0d) {
            return AnimationControlResult.LoopType.LOOP;
        }
        if (value == 2.0d) {
            return AnimationControlResult.LoopType.PLAY_ONCE;
        }
        if (value == 3.0d) {
            return AnimationControlResult.LoopType.HOLD_ON_LAST_FRAME;
        }
        return null;
    }
}
