package com.fox.ysmu.util;

public final class ControllerUtils {

    public static final String MAIN_CONTROLLER = "main_controller";
    public static final String HOLD_MAINHAND_CONTROLLER = "hold_mainhand_controller";
    public static final String HOLD_OFFHAND_CONTROLLER = "hold_offhand_controller";
    public static final String USE_CONTROLLER = "use_controller";
    public static final String CAP_CONTROLLER = "cap_controller";
    public static final String SWING_CONTROLLER = "swing_controller";
    public static final String OPENYSM_PRE_MAIN_CONTROLLER = "player.pre_main";
    public static final String OPENYSM_POST_MAIN_CONTROLLER = "player.post_main";
    public static final String OPENYSM_PRE_HOLD_CONTROLLER = "player.pre_hold";
    public static final String OPENYSM_POST_HOLD_CONTROLLER = "player.post_hold";
    public static final String OPENYSM_PRE_SWING_CONTROLLER = "player.pre_swing";
    public static final String OPENYSM_POST_SWING_CONTROLLER = "player.post_swing";
    public static final String OPENYSM_PRE_USE_CONTROLLER = "player.pre_use";
    public static final String OPENYSM_POST_USE_CONTROLLER = "player.post_use";

    /**
     * 具名并行槽位（{@code player.pre_parallel_<非数字后缀>} / {@code player.parallel_<...>}）
     * 的备用池大小。wiki 只定义数字槽位，但官方对非数字后缀也发控制器；池子固定是为了不受
     * "模型切换而 {@code registerControllers} 只跑一次"的影响，路由见
     * {@code OpenYsmPlayerControllerRuntime.resolveControllers()}。
     */
    public static final int NAMED_PARALLEL_EXTRA_SLOTS = 4;
}
