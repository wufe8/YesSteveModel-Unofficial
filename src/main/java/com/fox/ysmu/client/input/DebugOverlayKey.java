package com.fox.ysmu.client.input;

import com.fox.ysmu.client.gui.debug.DebugOverlay;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.relauncher.Side;
import org.lwjgl.input.Keyboard;

/**
 * Ctrl+<Alt+P绑定的键> 切换 Debug Overlay。
 * 从 ExtraPlayerConfigKey 读取基键，与 Alt+P 共用同一绑定按键。
 * 不通过 KeyBinding（避免共用 KeyBinding 的 isPressed() 冲突），
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

        // 未激活或未被消费：检测 Ctrl+<Alt+P绑定的键> 切换
        // 使用 ExtraPlayerConfigKey 的基键，这样用户在 Controls 里改绑 Alt+P 时，
        // Ctrl+新键也能同步跟随，避免写死 KEY_P 导致按键冲突无法调整。
        if (!Keyboard.getEventKeyState()) return;
        int key = Keyboard.getEventKey();
        int boundKey = com.fox.ysmu.client.input.ExtraPlayerConfigKey.EXTRA_PLAYER_RENDER_KEY.getKeyCode();
        boolean isCtrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
        if (key == boundKey && isCtrl) {
            DebugOverlay.toggle();
        }
    }
}
