package com.fox.ysmu.client.gui.debug;

/**
 * Molang 变量 Debug Overlay。
 * 通过 Ctrl+P 或 /ysm debug overlay 切换。
 * 渲染在 RenderGameOverlayEvent 上，为半透明全屏表格。
 *
 * TODO: 实现完整的 overlay 渲染 + 过滤 + 滚动
 */
public final class DebugOverlay {

    private static boolean active = false;

    private DebugOverlay() {}

    /** 切换 overlay 显隐 */
    public static void toggle() {
        active = !active;
        // TODO: 激活时注册键盘事件以捕获 Esc/打字/方向键
    }

    public static boolean isActive() {
        return active;
    }
}
