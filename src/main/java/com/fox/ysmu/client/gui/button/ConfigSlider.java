package com.fox.ysmu.client.gui.button;

import java.util.Locale;
import java.util.function.DoubleConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Mouse;

/**
 * 可复用的浮点滑条按钮（模组设置页用，与 ConfigCheckBox/FlatColorButton 并列）。
 *
 * 自包含拖拽：1.7.10 没有可靠的 mouseClickMove，因此在 drawButton 内跟踪
 * {@link Mouse#isButtonDown(int)}（与轮盘个人化面板的 range 滑条同一模式）。
 * 任何 GuiScreen 的 buttonList 里加一个即可用，无需父屏处理鼠标事件；
 * 点击轨道任意位置直接跳到该处，拖动过程中实时回调 onChange 写回配置。
 */
public class ConfigSlider extends GuiButton {
    private final double min;
    private final double max;
    private final double step;
    private final String labelKey;
    private final DoubleConsumer onChange;
    private double value;
    private boolean dragging;

    public ConfigSlider(int id, int x, int y, int width, String labelKey,
        double min, double max, double step, double initialValue, DoubleConsumer onChange) {
        super(id, x, y, width, 20, "");
        this.min = min;
        this.max = max;
        this.step = step;
        this.labelKey = labelKey;
        this.onChange = onChange;
        this.value = clamp(initialValue);
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = clamp(value);
    }

    private double clamp(double v) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        boolean hovered = mouseX >= this.xPosition && mouseY >= this.yPosition
            && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;

        int trackX = this.xPosition + 2;
        int trackW = this.width - 4;
        int trackY = this.yPosition + this.height - 8;

        // 自包含拖拽：按下命中时开始拖，松开结束；拖动中按轨道位置更新值
        boolean mouseDown = Mouse.isButtonDown(0);
        if (mouseDown && hovered) {
            dragging = true;
        } else if (!mouseDown) {
            dragging = false;
        }
        if (dragging && mouseDown) {
            double pct = (mouseX - trackX) / (double) trackW;
            pct = Math.max(0.0, Math.min(1.0, pct));
            double raw = min + (max - min) * pct;
            if (step > 0) {
                raw = Math.round(raw / step) * step;
            }
            value = clamp(raw);
            if (onChange != null) {
                onChange.accept(value);
            }
        }

        // 行背景
        drawRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + this.height,
            hovered ? 0x55FFFFFF : 0x33000000);

        // 标签（左）+ 当前值（右）
        String label = I18n.format(labelKey);
        mc.fontRenderer.drawString(label, this.xPosition + 2, this.yPosition + 2, 0xF3EFE0);
        int decimals = step > 0 ? Math.max(0, (int) Math.ceil(-Math.log10(step))) : 2;
        String valStr = String.format(Locale.ROOT, "%." + decimals + "f", value);
        int valW = mc.fontRenderer.getStringWidth(valStr);
        mc.fontRenderer.drawString(valStr, this.xPosition + this.width - valW - 2, this.yPosition + 2,
            hovered ? 0xFFB100 : 0xF3EFE0);

        // 轨道（灰底）+ 填充（黄）
        drawRect(trackX, trackY, trackX + trackW, trackY + 3, 0xAA888888);
        double pct = (value - min) / (max - min);
        int fillW = (int) Math.round(trackW * pct);
        drawRect(trackX, trackY, trackX + fillW, trackY + 3, hovered ? 0xAAFFFF00 : 0xFFFFAA00);
        // 手柄
        int thumbX = trackX + fillW;
        drawRect(thumbX - 3, trackY - 2, thumbX + 3, trackY + 5, 0xFFB100);
    }
}
