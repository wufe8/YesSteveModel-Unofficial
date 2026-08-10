package com.fox.ysmu.util;

import net.minecraft.util.MathHelper;

/**
 * 防快速转圈限制器（方向锁定）：
 * 输入每帧的镜头偏航 V 与身体朝向 B（两者都必须是**连续累积**角度，即不经 wrap180 的
 * 线性插值，如 rotationYaw / renderYawOffset 的连续插值），把差值限制到 ±maxOffset，
 * 并消除快速旋转跨 ±180° 时 wrap180 的翻转跳变（头部不会反向猛甩到另一侧）。
 *
 * <p>输出：限制后的目标差值 target = clamp(norm, ±maxOffset)；方向锁定时钳制在锁定侧
 * （跨 ±180° 保持锁）。「阈值内归中」（target → offsetSmooth 的低通）由调用方完成，
 * 本类只负责把原始差值变成不溢出、不翻转的限制值，并记录锁定状态。
 *
 * <p>机制要点：
 * <ul>
 *   <li>连续差值 signedDiff = 锚定差值 + Σ(dV − dBody)：V 与 B 都是连续累积角度，
 *       增量直接相减无 ±180° 歧义，跨 ±180°、跨整圈、单帧快速甩动以及回中都正确；
 *   <li>方向锁定：差值出阈值（上升沿）锁定当前侧；跨 ±180° 翻转（norm 与锁定方向
 *       反向）保持锁；回阈值（下降沿）且与锁定方向同向才解锁——防快速转头反向跳变；
 *   <li>阈值迟滞：锁定用 maxOffset（85°），解锁用 maxOffset − 迟滞（75°），防快速
 *       转头时 norm 在阈值附近抖动导致「解锁-重锁反方向」；
 *   <li>释放规则：玩家反向转头（signedDiff 跨 0 且 dV 反向运动）或视角静止多帧后
 *       norm 回中（spawn/身体收敛）才解除锁定；
 *   <li>方向/阈值判断基于真实相对角 norm = wrap180(signedDiff)，不基于 signedDiff
 *       符号（两者可能差 360° 整数倍）。
 * </ul>
 */
public final class FollowYawLimiter {
    /** 视角增量低于该值（°/帧）视为视角静止（spawn/挂机），用于区分玩家反向与 B 追过头。 */
    private static final float VIEW_STILL_DEG = 2.0F;
    /** 方向锁定阈值迟滞（°）：未锁定时出 maxOffset 才锁定；锁定时回 maxOffset − 迟滞才解锁。 */
    private static final float HYST_DEG = 10.0F;
    /** 视角静止判定所需连续帧数。 */
    private static final int VIEW_STILL_FRAMES = 6;

    private final float maxOffset;
    /** 连续差值（锚定值 + 逐帧增量，跨 ±180°/整圈/甩动无歧义）。 */
    private float signedDiff;
    /** 上一帧连续差值，用于检测跨 0（玩家反向转头）。 */
    private float lastSignedDiff;
    /** 上一帧镜头偏航 V（连续累积）。 */
    private float lastRawViewYaw;
    /** 上一帧身体朝向 body（连续累积）。 */
    private float lastBodySmooth;
    /** 方向锁定：0 = 未锁定；+1 = 右侧（正方向）锁定；−1 = 左侧锁定。 */
    private int direction;
    /** 上一帧差值是否在阈值内，用于检测上升沿/下降沿。 */
    private boolean lastInside;
    /** 视角连续静止帧数（|dV| < 阈值累计）。 */
    private int viewStillFrames;

    public FollowYawLimiter(float maxOffset) {
        this.maxOffset = maxOffset;
    }

    /** 重置全部状态（模式切换/重新初始化时）。 */
    public void reset() {
        signedDiff = 0.0F;
        lastSignedDiff = 0.0F;
        lastRawViewYaw = 0.0F;
        lastBodySmooth = 0.0F;
        direction = 0;
        lastInside = true;
        viewStillFrames = 0;
    }

    /** 锚定到当前差值（初始化/长时间暂停重同步后首帧），返回当前限制值。 */
    public float anchor(float rawViewYaw, float body) {
        reset();
        // 直接锚定到当前差值（wrap180 消除 V/body 可能的整圈参考差）
        signedDiff = MathHelper.wrapAngleTo180_float(rawViewYaw - body);
        lastRawViewYaw = rawViewYaw;
        lastBodySmooth = body;
        lastSignedDiff = signedDiff;
        return limit(rawViewYaw, body);
    }

    /**
     * 每帧输入镜头偏航 V 与身体朝向 B，返回限制后的目标差值：
     * clamp(wrap180(signedDiff), ±maxOffset)，方向锁定时钳制在锁定侧，跨 ±180° 不翻转。
     */
    public float limit(float rawViewYaw, float body) {
        // ── 连续差值维护 ──────────────────────────────────────────────
        // V 与 body 都连续累积，增量直接相减无歧义
        float dV = rawViewYaw - lastRawViewYaw;
        signedDiff += dV - (body - lastBodySmooth);
        lastRawViewYaw = rawViewYaw;
        lastBodySmooth = body;
        // 未锁定时防累积漂移：与当前差值的 wrap180 偏差过大则重新锚定
        if (direction == 0) {
            float staticDiff = MathHelper.wrapAngleTo180_float(rawViewYaw - body);
            if (Math.abs(MathHelper.wrapAngleTo180_float(signedDiff - staticDiff)) > 30.0F) {
                signedDiff = staticDiff;
            }
        }
        // ── 视角运动统计 ─────────────────────────────────────────────
        // |dV| 连续小于阈值 → 静止帧计数；|dV| 大 → 清零。用于区分「spawn（一直没动视角）」
        // 与「快速转头后停住（刚动过）」，也用于区分「玩家反向转头」与「B 追过头导致的
        // signedDiff 跨 0」。
        if (Math.abs(dV) < VIEW_STILL_DEG) {
            viewStillFrames++;
        } else {
            viewStillFrames = 0;
        }
        boolean viewStill = viewStillFrames >= VIEW_STILL_FRAMES;
        // ── 释放规则 ①：玩家反向转头 ─────────────────────────────────
        // signedDiff 跨过 0（连续差值符号翻转）且 V 增量与跨零方向一致（视角真的反向运动）
        // → 解除锁定，让 norm 按新方向重新判断。排除「B 追过头」（视角静止/未反向）导致的
        // signedDiff 跨 0——那种 norm 翻转是身体旋转的物理结果，方向锁定应保持，否则会锁到
        // 反方向、头部转半圈。
        if (direction != 0
            && ((lastSignedDiff > 0.0F && signedDiff < 0.0F && dV < -VIEW_STILL_DEG)
                || (lastSignedDiff < 0.0F && signedDiff > 0.0F && dV > VIEW_STILL_DEG))) {
            direction = 0;
        }
        lastSignedDiff = signedDiff;
        // ── 真实相对角 + 阈值迟滞 ────────────────────────────────────
        // 归一化到 [-180,180]：signedDiff 是连续值，与真实差可能差 360° 整数倍，
        // 方向/阈值判断必须基于真实头部差 wrap180(signedDiff)。
        float norm = MathHelper.wrapAngleTo180_float(signedDiff);
        // 阈值迟滞：未锁定时出 maxOffset 才锁定；锁定时回 maxOffset − 迟滞才解锁。
        boolean inside = Math.abs(norm) < (direction == 0 ? maxOffset : maxOffset - HYST_DEG);
        // ── 释放规则 ②：视角静止回中 ────────────────────────────────
        // 视角静止多帧（出生 renderYawOffset 收敛、B 追上 V 时玩家没转鼠标）且 norm 已回中
        // （阈值内）——norm 翻转到反向是「身体旋转导致真实相对角回中」的物理结果，方向锁定
        // 让位（解锁），让 norm 驱动 target 使头部回到视角方向。快速转头后停住（刚动过视角、
        // 静止帧数不足）不触发：等 B 追完、norm 回中后再解锁，避免 B 追过头期间半圈。
        if (direction != 0 && inside && viewStill) {
            direction = 0;
        }
        // ── 方向锁定边沿 ────────────────────────────────────────────
        // 上升沿（阈值内→阈值外）/下降沿（阈值外→阈值内）都必须满足 norm 与锁定方向「同向」
        // 才更新/解锁——快速旋转跨 ±180° 时 wrap180 把差值翻转到另一侧（norm 与 direction
        // 反向），此时保持锁定（钳制在锁定方向）而不是翻转或解锁；只有真正回中（norm 回到
        // 阈值内且与方向同向）才解锁。
        if (!inside && lastInside) {
            if (direction == 0 || (direction == 1 && norm > 0.0F) || (direction == -1 && norm < 0.0F)) {
                direction = norm > 0.0F ? 1 : -1;
            }
        } else if (inside && !lastInside) {
            if (direction == 0 || (direction == 1 && norm >= 0.0F) || (direction == -1 && norm <= 0.0F)) {
                direction = 0;
            }
        }
        lastInside = inside;
        // ── 输出限制值 ───────────────────────────────────────────────
        float target = MathHelper.clamp_float(norm, -maxOffset, maxOffset);
        // 方向锁定钳制：norm 与锁定方向反向（跨 ±180° 翻转）时钳回锁定方向
        if (direction == 1 && norm < 0.0F) {
            target = maxOffset;
        } else if (direction == -1 && norm > 0.0F) {
            target = -maxOffset;
        }
        return target;
    }
}
