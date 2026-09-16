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
     * 备用池大小的**默认值**。wiki 只定义数字槽位，但官方对非数字后缀也发控制器；池子固定是为了
     * 不受"模型切换而 {@code registerControllers} 只跑一次"的影响。
     * <p>
     * 实际生效的值是 {@code Config.NAMED_PARALLEL_EXTRA_SLOTS}（配置项 {@code NamedParallelExtraSlots}，
     * 本常量只提供默认值）；路由见 {@code OpenYsmPlayerControllerRuntime.routeNamedParallel()}。
     */
    public static final int DEFAULT_NAMED_PARALLEL_EXTRA_SLOTS = 8;

    /** 配置允许的备用池大小上限：池是**每个实体**注册的控制器，不能无限制地加。
     *  16 = 每族 16 个（两族共 32 个）已经是"具名槽位极端多"的模型才需要的规模。 */
    public static final int MAX_NAMED_PARALLEL_EXTRA_SLOTS = 16;
}
