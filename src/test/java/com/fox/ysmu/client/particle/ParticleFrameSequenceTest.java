package com.fox.ysmu.client.particle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * 序列粒子（逐帧换贴图）的帧选择，以及 {@code bubble_pop} 的帧列表在"没有高版本资产"时的降级。
 *
 * <p>高版本 {@code particles/bubble_pop.json} 声明 {@code bubble_pop_0..4} 五张纹理，
 * {@code BubblePopParticle.setSpriteFromAge} 在 4 tick 内轮播它们。1.7.10 的
 * {@code CustomParticleFX} 默认只绑一张，所以由 {@link ParticleTextureManager#getFrameTextureIds}
 * 把整串 GL id 交给粒子、由粒子按 age 换帧。</p>
 */
class ParticleFrameSequenceTest {

    @Test
    void frameFollowsAgeOverLifetime() {
        // 5 帧 / 4 tick 寿命：0→0、1→1、2→2、3→3、4→4（与 setSpriteFromAge 一致）
        assertEquals(0, CustomParticleFX.frameForAge(0, 4, 5));
        assertEquals(1, CustomParticleFX.frameForAge(1, 4, 5));
        assertEquals(2, CustomParticleFX.frameForAge(2, 4, 5));
        assertEquals(3, CustomParticleFX.frameForAge(3, 4, 5));
        assertEquals(4, CustomParticleFX.frameForAge(4, 4, 5));
    }

    @Test
    void frameIsClampedAndSingleFrameIsAFixedZero() {
        assertEquals(4, CustomParticleFX.frameForAge(100, 4, 5), "超出寿命要钳到最后一帧");
        assertEquals(0, CustomParticleFX.frameForAge(0, 4, 1), "只有一帧时恒为 0");
        assertEquals(0, CustomParticleFX.frameForAge(0, 4, 5));
    }

    /** 单张纹理的粒子不能因为"帧数为 0"崩掉（防御性）。 */
    @Test
    void zeroFrameCountIsTolerated() {
        assertEquals(0, CustomParticleFX.frameForAge(2, 4, 0));
        assertEquals(0, CustomParticleFX.frameForAge(2, 0, 0));
    }

    /**
     * 测试环境没有配置高版本游戏目录：帧列表只能为空（不抛异常、也不返回伪造的 GL id）。
     * 真实纹理与逐帧序列只能在装了高版本资产的客户端里验证。
     */
    @Test
    void frameTextureIdsDegradeToEmptyWithoutHighVersionAssets() {
        int[] frames = ParticleTextureManager.getFrameTextureIds("bubble_pop");
        assertNotNull(frames);
        assertEquals(0, frames.length, "没有高版本资产时不该返回任何帧纹理 id");

        int[] namespaced = ParticleTextureManager.getFrameTextureIds("minecraft:bubble_pop");
        assertNotNull(namespaced);
        assertEquals(0, namespaced.length, "带命名空间的写法也要能安全查询");
    }
}
