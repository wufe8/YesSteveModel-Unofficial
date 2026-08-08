package com.fox.ysmu.client.particle;

import java.util.HashMap;
import java.util.Map;

/**
 * 高版本粒子行为表（1.7.10 落地层的物理/外观近似）。
 *
 * <p>新版 Minecraft 的粒子行为（初始速度、重力、尺寸、颜色、寿命）硬编码在
 * 对应粒子类里（如 {@code FallingDripstoneParticle}、{@code FlameParticle}），
 * particle JSON 只声明纹理。1.7.10 的 {@code CustomParticleFX} 需要手动近似这些
 * 行为，本表为常用高版本粒子提供参数：</p>
 * <ul>
 *   <li>{@code ignoreVelocity}：是否忽略调用方传入速度。水滴/雪花类粒子在高版本
 *       里无视外部速度（DripParticle 构造里 xd=yd=zd=0），用自身运动。</li>
 *   <li>{@code initVx/Y/Z}：忽略速度时使用的初始速度。</li>
 *   <li>{@code gravity}：1.7.10 {@code particleGravity}（负值 = 上升，如火焰）。</li>
 *   <li>{@code scale}：粒子尺寸（renderParticle 里 0.1 * scale 格）。</li>
 *   <li>{@code tintR/G/B}：颜色乘数。高版本水滴纹理本身是白色，靠粒子类上色。</li>
 *   <li>{@code defaultLifetime}：count&gt;0 时使用的寿命（OpenYSM 不 setLifetime，
 *       用粒子类型默认寿命）。</li>
 *   <li>{@code fadeOut}：寿命末期是否渐隐（水花/雪花类）。</li>
 * </ul>
 *
 * <p>只有本表覆盖的粒子才启用自定义纹理；其余（heart/note/portal/flame 等有
 * 1.7.10 内置对应的）一律走 vanilla {@code spawnParticle}，避免行为崩坏。</p>
 */
public final class ParticleBehaviors {

    /** 单个粒子的行为参数。 */
    public static final class Behavior {
        public final boolean ignoreVelocity;
        public final double initVx;
        public final double initVy;
        public final double initVz;
        public final float gravity;
        public final float scale;
        public final float tintR;
        public final float tintG;
        public final float tintB;
        public final int defaultLifetime;
        public final boolean fadeOut;
        /** 外部速度缩放（1.0 = 原样）。水花类模型常传很大 speed（如 3 格/tick），
         *  1.7.10 粒子会瞬间飞出/撞地消失看不见；缩小后可留在生成位置附近四溅。 */
        public final float velocityScale;
        /** 寿命随机范围：实际寿命 = defaultLifetime + [0, lifetimeVariance] 随机。
         *  对齐高版本 SplashParticle（寿命 8~40 tick 随机）。0 = 固定 defaultLifetime。 */
        public final int lifetimeVariance;
        /** 撞到地面或进入液体时立即消失（对齐高版本 SplashParticle；否则 1.7.10
         *  EntityFX 会落地后继续滑行，看起来像向外飞行而不是短暂水花）。 */
        public final boolean dieOnGround;

        Behavior(boolean ignoreVelocity, double initVx, double initVy, double initVz,
                float gravity, float scale, float tintR, float tintG, float tintB,
                int defaultLifetime, boolean fadeOut, float velocityScale,
                int lifetimeVariance, boolean dieOnGround) {
            this.ignoreVelocity = ignoreVelocity;
            this.initVx = initVx;
            this.initVy = initVy;
            this.initVz = initVz;
            this.gravity = gravity;
            this.scale = scale;
            this.tintR = tintR;
            this.tintG = tintG;
            this.tintB = tintB;
            this.defaultLifetime = defaultLifetime;
            this.fadeOut = fadeOut;
            this.velocityScale = velocityScale;
            this.lifetimeVariance = lifetimeVariance;
            this.dieOnGround = dieOnGround;
        }
    }

    /**
     * 水滴：悬挂/下落，忽略外部速度，小尺寸。
     * 颜色对齐 1.20.1 vanilla DrippingWaterParticle（rCol=0.2, gCol=0.3, bCol=1.0 深水色，
     * 纹理本身是白色，靠 tint 上色）——与雨/水方块颜色接近，而非浅蓝。
     */
    private static final Behavior DRIP = new Behavior(
        true, 0.0, -0.1, 0.0,
        0.6F, 0.7F, 0.2F, 0.3F, 1.0F,
        60, false, 1.0F, 0, false);

    /**
     * 水花/雨水：对齐 1.20.1 vanilla SplashParticle（保留调用方速度四溅，gravity=0.04，
     * lifetime 20~100 tick，白色水花，quadSize 0.1 格 ≈ 1.7.10 scale 1.0）。
     * gravity 太大会让水花很快落地、lifetime 太短则没飞开就消失（视觉范围小），
     * scale 太小则看不清（会让人误以为只有湿身水滴）。
     */
    /**
     * 水花/雨水：对齐 ysm2.6.5 观察效果——水粒子在生成位置（pos 周围 σ=delta 散布）
     * 凭空出现，**垂直小弹跳一下、水平基本不动**，随后快速消失（<1 秒）。
     * 依据：vanilla SplashParticle 的 vy==0 分支（xd=zd=0, yd=0.1）+ wiki 确认的
     * "寿命 8~40 tick 随机、落在地面或落入液体立即消失"。不用调用方随机速度
     * （那会往随机方向飞行），改用固定垂直小初速 + 够大的重力让它弹起后落回。
     */
    private static final Behavior SPLASH = new Behavior(
        true, 0.0, 0.1, 0.0,
        0.4F, 0.9F, 1.0F, 1.0F, 1.0F,
        8, true, 1.0F, 32, true);

    /** 火焰：保留调用方速度，浮力上升（负重力），纹理自带色。 */
    private static final Behavior FLAME = new Behavior(
        false, 0.0, 0.0, 0.0,
        -0.05F, 0.6F, 1.0F, 1.0F, 1.0F,
        40, false, 1.0F, 0, false);

    /** 雪花：缓慢下落，忽略外部速度，短寿命渐隐。 */
    private static final Behavior SNOWFLAKE = new Behavior(
        true, 0.0, -0.04, 0.0,
        0.2F, 0.55F, 1.0F, 1.0F, 1.0F,
        50, true, 1.0F, 0, false);

    private static final Map<String, Behavior> TABLE = new HashMap<>();

    static {
        // 滴水/水滴（高版本独有纹理，vanilla 无对应）
        TABLE.put("falling_dripstone_water", DRIP);
        TABLE.put("dripping_dripstone_water", DRIP);
        TABLE.put("dripping_water", DRIP);
        TABLE.put("falling_water", DRIP);
        // 水花 / 雨水
        TABLE.put("splash", SPLASH);
        TABLE.put("rain", SPLASH);
        // 火焰（高版本纹理）
        TABLE.put("flame", FLAME);
        TABLE.put("small_flame", FLAME);
        TABLE.put("copper_fire_flame", FLAME);
        TABLE.put("soul_fire_flame", FLAME);
        // 雪花
        TABLE.put("snowflake", SNOWFLAKE);
        TABLE.put("snow", SNOWFLAKE);
    }

    private ParticleBehaviors() {}

    /**
     * 查询粒子行为；未覆盖返回 {@code null}（此时粒子走 vanilla spawnParticle，
     * 不启用自定义纹理）。
     */
    public static Behavior get(String particleName) {
        return TABLE.get(particleName);
    }
}
