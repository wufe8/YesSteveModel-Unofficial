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

    /**
     * 八个 OpenYSM **槽位**控制器（{@code player.<slot>}）。
     * <p>
     * 官方对同一槽位下**带后缀**的名字（{@code player.<slot>_<后缀>}）也会发独立控制器：
     * OpenYSM {@code ControllerSlotBinder} 的 {@code controllerNameMatcher} 是
     * {@code ^player\.<slot>(_.+){0,1}$}，收集到的每个名字都注册一个控制器；wiki「动画控制器」
     * 2.6.3 也写明"同一组内的控制器按名称字母序排序后依次加载，位置越靠后优先级越高"，
     * 分组用的就是 {@code player.pre_main_*} 这种写法。
     * <p>
     * 于是同一个槽位可以同时挂 {@code @player_ctrl_<slot>.molang} 控制脚本和若干 JSON 控制器
     * （如 {@code player.pre_main} 与 {@code player.pre_main_mecha_mgr}）。YSMU 的 GeckoLib
     * 控制器是**按名字固定**注册的，一个槽位只有一个，所以用下面的共享备用池承载后缀控制器。
     */
    public static final java.util.List<String> OPENYSM_SLOTS = java.util.Collections.unmodifiableList(
        java.util.Arrays.asList(
            "pre_main",
            "post_main",
            "pre_hold",
            "post_hold",
            "pre_swing",
            "post_swing",
            "pre_use",
            "post_use"));

    /** 槽位后缀控制器备用池的控制器名前缀（{@code openysm_slot_extra_<i>_controller}）。
     *  名字里没有模型信息：第 i 个池控制器承载当前模型第 i 个 {@code player.<slot>_<后缀>}。 */
    public static final String SLOT_EXTRA_CONTROLLER_PREFIX = "openysm_slot_extra_";

    /**
     * 槽位后缀备用池大小的**默认值**（跨全部八个槽位共享，不是每槽位）。
     * <p>
     * 实测最坏的一份模型同时有 5 个后缀控制器（4 个 {@code player.post_main_car_*} +
     * 1 个 {@code player.pre_main_mecha_mgr}），默认 8 留了余量。
     * 实际生效的值是 {@code Config.SLOT_EXTRA_CONTROLLERS}。
     */
    public static final int DEFAULT_SLOT_EXTRA_CONTROLLERS = 8;

    /** 配置允许的槽位后缀备用池大小上限（理由同 {@link #MAX_NAMED_PARALLEL_EXTRA_SLOTS}）。 */
    public static final int MAX_SLOT_EXTRA_CONTROLLERS = 16;

    /** 第 {@code index} 个槽位后缀池控制器的 GeckoLib 控制器名。 */
    public static String slotExtraControllerName(int index) {
        return SLOT_EXTRA_CONTROLLER_PREFIX + index + "_controller";
    }
}
