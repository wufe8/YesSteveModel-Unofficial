package com.fox.ysmu.compat;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Timeless and Classics Guns (TacZ) 模组检测兼容层。
 *
 * <p>TacZ 是 1.20+ 的枪械模组，在 1.7.10 上不存在。
 * 许多 OpenYSM 模型包（如 M200）包含为 TacZ 设计的并行动画控制器，
 * 这些控制器的 transition 条件大量使用 {@code ctrl.tac_*} 控制变量。
 * 在没有 TacZ 的环境下这些变量永远是默认值（0/false），
 * 导致控制器永远卡在 default 状态循环播放带音效关键帧的动画。</p>
 *
 * <p>此兼容层用于检测 TacZ 是否加载，以便在运行时跳过那些完全依赖
 * TacZ 变量的控制器，避免无效动画播放和音效误触发。</p>
 */
@SideOnly(Side.CLIENT)
public final class TacZCompat {

    private static final boolean TACZ_LOADED = Loader.isModLoaded("tacz");

    private TacZCompat() {}

    /**
     * Timeless and Classics Guns (TacZ) 模组是否已加载。
     */
    public static boolean isTacZLoaded() {
        return TACZ_LOADED;
    }
}
