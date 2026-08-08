package com.fox.ysmu.mixin;

import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.fox.ysmu.client.particle.CustomParticleManager;

/**
 * 渲染自定义粒子（独立于 vanilla 的 layer 0/1/2 批处理）。
 *
 * <p>1.7.10 的 {@link EffectRenderer#renderParticles} 只渲染 layer 0/1/2
 * （atlas / 区块 / 物品纹理）。自定义粒子由 {@link CustomParticleManager}
 * 独立管理，本 Mixin 只在 {@code renderParticles} 尾部调用其渲染器——
 * 不访问 {@code EffectRenderer} 内部字段（避免 SRG 环境 {@code @Shadow}
 * 字段映射不可靠的问题），只做方法注入（方法映射正常）。</p>
 */
@Mixin(value = EffectRenderer.class, priority = 900)
public abstract class MixinEffectRenderer {

    @Inject(method = "renderParticles", at = @At("TAIL"))
    private void ysmu$renderCustomParticles(Entity entity, float partialTicks, CallbackInfo ci) {
        CustomParticleManager.render(entity, partialTicks);
    }
}
