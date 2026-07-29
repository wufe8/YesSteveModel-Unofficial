package com.fox.ysmu.client.input;

import com.fox.ysmu.client.gui.debug.DebugOverlay;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.relauncher.Side;
import org.lwjgl.input.Keyboard;

/**
 * Ctrl+P 切换 Debug Overlay。
 * 不通过 KeyBinding（避免与 Alt+P 共用 KEY_P 的冲突），
 * 直接检测键盘原始事件。
 *
 * 当 overlay 激活时，所有键盘事件先交给 DebugOverlay.handleKeyInput()
 * 处理（过滤/滚动/Esc关闭），未被消费的事件再透传。
 */
@EventBusSubscriber(side = Side.CLIENT)
public class DebugOverlayKey {

    @SubscribeEvent
    public static void onKeyboardInput(InputEvent.KeyInputEvent event) {
        // 如果 overlay 激活，优先交给 overlay 处理
        if (DebugOverlay.isActive()) {
            if (DebugOverlay.handleKeyInput()) {
                return; // 按键被 overlay 消费
            }
        }

        // 未激活或未被消费：检测 Ctrl+P 切换
        if (!Keyboard.getEventKeyState()) return;
        int key = Keyboard.getEventKey();
        boolean isCtrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
        if (key == Keyboard.KEY_P && isCtrl) {
            DebugOverlay.toggle();
        }
    }
}
