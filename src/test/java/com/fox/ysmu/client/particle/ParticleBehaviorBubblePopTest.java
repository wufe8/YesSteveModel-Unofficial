package com.fox.ysmu.client.particle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * {@code minecraft:bubble_pop} 的行为表条目。
 *
 * <p>为什么需要它：{@code ParticleEffectUtil.resolve()} 只在 {@code ParticleBehaviors.get(name) != null}
 * 时才去找高版本纹理（1.7.10 没有"按 id 生成任意粒子"的通用 API，自定义粒子必须由行为表近似物理）。
 * 某弹射物的飞行拖尾与命中水花用的就是 {@code minecraft:bubble_pop}，所以它必须进表，
 * 否则整条链路会退化成 1.7.10 内置的 {@code bubble}（外观/寿命都不是官方的样子）。</p>
 *
 * <p>参数取自 1.21.11 {@code BubblePopParticle}：{@code lifetime = 4}、{@code gravity = 0.008}、
 * 保留调用方速度、默认尺寸、无渐隐、不因落地消失。</p>
 */
class ParticleBehaviorBubblePopTest {

    @Test
    void bubblePopIsRegisteredWithTheHighVersionBehavior() {
        ParticleBehaviors.Behavior behavior = ParticleBehaviors.get("bubble_pop");
        assertNotNull(behavior, "bubble_pop 必须进行为表，否则不会启用高版本纹理");

        assertEquals(4, behavior.fixedLifetime, "高版本 BubblePopParticle 寿命是硬编码 4 tick");
        assertEquals(0.008f, behavior.gravity, 1.0e-6f);
        assertFalse(behavior.ignoreVelocity, "BubblePopParticle 保留调用方速度");
        assertFalse(behavior.fadeOut);
        assertFalse(behavior.dieOnGround);
        assertEquals(1.0f, behavior.scale, 1.0e-6f, "默认 quad 0.1 格 = CustomParticleFX 的 scale 1.0");
    }

    /** 固定寿命必须覆盖调用方传的 lifetime（模型传的是 30~50）。 */
    @Test
    void fixedLifetimeOverridesTheModelsRequest() {
        ParticleBehaviors.Behavior bubblePop = ParticleBehaviors.get("bubble_pop");
        assertEquals(4, ParticleEffectUtil.effectiveLifetime(bubblePop, 45));
        assertEquals(4, ParticleEffectUtil.effectiveLifetime(bubblePop, 1));
    }

    /** 没有声明固定寿命的行为仍沿用调用方/默认寿命（别把现有粒子一起改了）。 */
    @Test
    void behavioursWithoutFixedLifetimeKeepTheirOwnLifetime() {
        ParticleBehaviors.Behavior splash = ParticleBehaviors.get("splash");
        assertNotNull(splash);
        assertEquals(0, splash.fixedLifetime, "splash 用 defaultLifetime + 随机，不是固定值");
        assertEquals(45, ParticleEffectUtil.effectiveLifetime(splash, 45));
        assertEquals(45, ParticleEffectUtil.effectiveLifetime(null, 45), "无行为 = vanilla 路径，原样传下去");
    }

}
