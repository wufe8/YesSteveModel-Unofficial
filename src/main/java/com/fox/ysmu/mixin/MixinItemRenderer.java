package com.fox.ysmu.mixin;

import com.fox.ysmu.client.compat.AngelicaCompat;
import com.fox.ysmu.client.renderer.FirstPersonHandRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ItemRenderer.class, priority = 900)
public abstract class MixinItemRenderer {

    /**
     * 主手拿物品时 YSM 不接管第一人称渲染（{@code shouldRenderCustomHand} 要求
     * {@code itemRenderer.itemToRender == null}），副手由 Backhand 在其 RETURN 注入里自己画 ——
     * 隐藏名单会被绕过。这里在方法开头（Backhand 的副手绘制之前）清掉 Backhand 副手渲染器的
     * pending 物品，使那一笔绘制无物可画；该字段每 tick 会被 Backhand 重填，不存在丢物品风险。
     * 详见 {@code BackhandCompat.suppressHiddenOffhandRender}。
     */
    @Inject(method = "renderItemInFirstPerson", at = @At("HEAD"))
    private void ysmu$suppressHiddenOffhand(float partialTicks, CallbackInfo ci) {
        com.fox.ysmu.compat.BackhandCompat.suppressHiddenOffhandRender();
    }

    @Inject(method = "renderItemInFirstPerson", at = @At("HEAD"), cancellable = true)
    private void ysmu$renderAngelicaCustomHand(float partialTicks, CallbackInfo ci) {
        if (!AngelicaCompat.isRenderingSolidHandPass()) {
            return;
        }

        ItemRenderer itemRenderer = (ItemRenderer) (Object) this;
        if (FirstPersonHandRenderer.tryRenderInActiveFirstPersonPass(
            Minecraft.getMinecraft(),
            itemRenderer,
            partialTicks,
            AngelicaCompat.shouldRenderOffhandInCurrentHandPass())) {
            AngelicaCompat.resetFirstPersonItemId();
            ci.cancel();
        }
    }
}
