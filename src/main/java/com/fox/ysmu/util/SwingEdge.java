package com.fox.ysmu.util;

/**
 * 1.7.10 的"是否开始了一次新的挥剑"判定。
 *
 * <p>为什么不能只看 {@code isSwingInProgress} 的布尔上升沿：Minecraft 1.9 才加入攻击冷却，
 * 那之后连点几乎不会落在上一次挥剑动画播放期间；而 **1.7.10 允许"挥到一半再次点击"** ——
 * {@code EntityLivingBase.swingItem()} 在
 * {@code !isSwingInProgress || swingProgressInt >= getArmSwingAnimationEnd()/2 || swingProgressInt < 0}
 * 时会重新触发，并把 {@code swingProgressInt} 重置为 <b>-1</b>，但 {@code isSwingInProgress}
 * 始终为 true（见 {@code EntityLivingBase.updateArmSwingProgress()}：计数单调递增，
 * 只有自然结束才把它清零并置 isSwingInProgress=false）。</p>
 *
 * <p>于是"只看布尔沿"的实现在 1.7.10 下会漏掉打断重挥：模型侧（例如 {@code post_swing}
 * 控制器把 {@code v.swing} 当一次性触发消费掉、靠再次置 1 退回 default 再重进）就收不到新的
 * 触发，表现为<b>连点时挥剑动画不重播，必须等它播完</b>。官方 YSM 面向 1.9+（有攻击冷却），
 * 照抄布尔沿在 1.7.10 下就是错的。</p>
 *
 * <p>本类只做这一件事，供 OpenYSM 控制器路径（{@code v.swing} 协议）与传统回退路径
 * （{@code AnimationManager.markSwingStart}）共用，避免两处判定漂移。</p>
 */
public final class SwingEdge {

    private SwingEdge() {}

    /**
     * @param swinging     当前 {@code isSwingInProgress}
     * @param progress     当前 {@code swingProgressInt}
     * @param lastProgress 上一次采样到的 {@code swingProgressInt}
     * @param wasSwinging  上一次采样时是否在挥剑
     * @return 是否开始了一次新的挥剑（含 1.7.10 的"打断重挥"）
     */
    public static boolean isNewSwing(boolean swinging, int progress, int lastProgress, boolean wasSwinging) {
        if (!swinging) {
            // 自然结束（updateArmSwingProgress 把计数清零并置 isSwingInProgress=false）不算新挥剑。
            return false;
        }
        if (!wasSwinging) {
            return true;
        }
        // 1.7.10 打断重挥：swingItem() 把 swingProgressInt 重置为 -1，计数本来是单调递增的，
        // 所以"变小"只可能是重新触发。注意自然结束那条路径（计数归零）在上面已经被 swinging
        // 过滤掉了。
        return progress < lastProgress;
    }
}
