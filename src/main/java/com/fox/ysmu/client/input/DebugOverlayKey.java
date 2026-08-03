package com.fox.ysmu.client.input;

import com.fox.ysmu.client.gui.debug.DebugOverlay;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
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
        // 搜索模式下：吞掉所有 KeyBinding 的按下计数（isPressed() 会递减 pressTime），
        // 让 vanilla 在 runTick 里后续的 keyBindInventory / keyBindDrop / keyBindChat /
        // 快捷栏等 isPressed() 检查全部落空，避免搜索打字时误触发物品栏/丢物品/聊天。
        // InputEvent.KeyInputEvent 由 fireKeyInput() 在 vanilla 按键处理之前触发，
        // 这里拦截正好来得及（与 GUI 打开时行为一致）。
        if (DebugOverlay.isSearching()) {
            KeyBinding[] keyBindings = Minecraft.getMinecraft().gameSettings.keyBindings;
            if (keyBindings != null) {
                for (KeyBinding keyBinding : keyBindings) {
                    keyBinding.isPressed();
                }
            }
        }

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
