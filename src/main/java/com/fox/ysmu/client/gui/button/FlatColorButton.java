package com.fox.ysmu.client.gui.button;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import java.util.Collections;
import java.util.List;

public class FlatColorButton extends GuiButton {
    private boolean isSelect = false;
    private boolean leftAligned = false;
    private int leftPadding = 28;
    public List<String> tooltips;

    public FlatColorButton(int id, int pX, int pY, int pWidth, int pHeight, String pMessage) {
        super(id, pX, pY, pWidth, pHeight, pMessage);
    }

    /** 文本改为左对齐（与 ConfigCheckBox 标签对齐），padding 为左侧留白。 */
    public FlatColorButton setLeftAligned(int padding) {
        this.leftAligned = true;
        this.leftPadding = padding;
        return this;
    }

    public FlatColorButton setTooltips(String key) {
        tooltips = Collections.singletonList(I18n.format(key));
        return this;
    }

    public FlatColorButton setTooltips(List<String> tooltips) {
        this.tooltips = tooltips;
        return this;
    }

    // renderWidget -> drawButton
    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        FontRenderer font = mc.fontRenderer;
        this.field_146123_n = mouseX >= this.xPosition && mouseY >= this.yPosition && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
        int backgroundColor = isSelect ? 0xff_1E90FF : 0xff_434242;
        this.drawGradientRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + this.height, backgroundColor, backgroundColor);

        if (this.field_146123_n) {
            this.drawGradientRect(this.xPosition, this.yPosition + 1, this.xPosition + 1, this.yPosition + this.height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.xPosition + this.width - 1, this.yPosition + 1, this.xPosition + this.width, this.yPosition + this.height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.xPosition, this.yPosition + this.height - 1, this.xPosition + this.width, this.yPosition + this.height, 0xff_F3EFE0, 0xff_F3EFE0);
        }
        //GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        if (leftAligned) {
            font.drawString(this.displayString, this.xPosition + this.leftPadding,
                this.yPosition + (this.height - 8) / 2, 0xF3EFE0);
        } else {
            this.drawCenteredString(font, this.displayString, this.xPosition + this.width / 2,
                this.yPosition + (this.height - 8) / 2, 0xF3EFE0);
        }
    }

    public void setSelect(boolean select) {
        isSelect = select;
    }
}
