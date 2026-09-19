package com.fox.ysmu.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 1.7.10 的"新挥剑"判定：只有布尔上升沿是不够的。
 *
 * <p>回归点（用户实测的遗留问题）：快速连点左键时挥剑动画不重播，必须等上一次播完。
 * 原因是 1.7.10 允许"挥到一半再次点击"——{@code EntityLivingBase.swingItem()} 会把
 * {@code swingProgressInt} 重置为 <b>-1</b>，而 {@code isSwingInProgress} 一直是 true
 * （{@code updateArmSwingProgress()} 里计数单调递增，只有自然结束才清零并置 false）。
 * 只看布尔沿就漏掉了这次打断重挥，模型侧（把 {@code v.swing} 当一次性触发、靠再置 1 退回
 * default 再重进）收不到新触发。</p>
 */
class SwingEdgeTest {

    /** 从静止开始挥：布尔上升沿。 */
    @Test
    void risingEdgeIsANewSwing() {
        assertTrue(SwingEdge.isNewSwing(true, 0, 0, false));
    }

    /** 挥剑进行中、计数继续增长：不是新挥剑（否则每帧都会重启动画）。 */
    @Test
    void monotonicProgressIsNotANewSwing() {
        assertFalse(SwingEdge.isNewSwing(true, 3, 2, true));
        assertFalse(SwingEdge.isNewSwing(true, 0, 0, true));
    }

    /** 1.7.10 的打断重挥：swingProgressInt 被重置为 -1（或紧随其后的 0）。 */
    @Test
    void midSwingRestartIsANewSwing() {
        assertTrue(SwingEdge.isNewSwing(true, -1, 3, true), "挥到一半再点击：计数重置为 -1");
        assertTrue(SwingEdge.isNewSwing(true, 0, 5, true), "采样落在重置后的第一个 tick");
    }

    /**
     * 自然结束不算新挥剑：{@code updateArmSwingProgress()} 把计数清零**并且**把
     * {@code isSwingInProgress} 置 false，所以这里必须被 swinging 过滤掉 —— 否则每次
     * 挥完都会假触发一次。
     */
    @Test
    void naturalEndIsNotANewSwing() {
        assertFalse(SwingEdge.isNewSwing(false, 0, 5, true));
        assertFalse(SwingEdge.isNewSwing(false, 0, 0, true));
    }

    /** 没在挥剑时任何计数都不算（防御性）。 */
    @Test
    void idleIsNeverANewSwing() {
        assertFalse(SwingEdge.isNewSwing(false, -1, 0, false));
    }
}
