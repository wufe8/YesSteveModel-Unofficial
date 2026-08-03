package com.fox.ysmu.mixin;

import com.fox.ysmu.client.gui.debug.DebugOverlay;
import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class, priority = 900)
public abstract class MixinMinecraft {

    /**
     * 搜索模式下按 Esc 只退出搜索，不打开暂停菜单（与 GUI 行为一致）。
     * 原版在 runTick 的键盘循环里直接检查 key==1 并调用 displayInGameMenu，
     * 该检查发生在 InputEvent.KeyInputEvent 之前，事件处理器来不及拦截，
     * 因此在这里取消暂停菜单的打开。
     */
    @Inject(method = "displayInGameMenu", at = @At("HEAD"), cancellable = true)
    private void ysmu$blockPauseMenuWhileSearching(CallbackInfo ci) {
        if (DebugOverlay.isSearching()) {
            ci.cancel();
        }
    }
}
