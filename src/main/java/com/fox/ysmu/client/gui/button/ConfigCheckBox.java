package com.fox.ysmu.client.gui.button;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

public class ConfigCheckBox extends GuiButton {
    private boolean isChecked;
    private final String Key;

    public ConfigCheckBox(int id, int pX, int pY, String key, boolean isChecked) {
        super(id, pX, pY, 400, 20, "");
        this.Key = key;
        this.isChecked = isChecked;
        this.displayString = I18n.format("gui.yes_steve_model.config." + key);
    }

    public void doPress() {
        this.isChecked = !this.isChecked;
    }

    @Override
    public void drawButton(net.minecraft.client.Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) return;
        boolean hovered = mouseX >= this.xPosition && mouseY >= this.yPosition
            && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
        int bgColor = hovered ? 0x55FFFFFF : 0x33000000;
        drawRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + this.height, bgColor);
        String checkMark = isChecked ? "[X]" : "[ ]";
        int checkColor = hovered ? 0xFFB100 : 0xF3EFE0;
        mc.fontRenderer.drawString(checkMark, this.xPosition + 2, this.yPosition + (this.height - 8) / 2, checkColor);
        int textColor = this.enabled ? (hovered ? 0xFFB100 : 0xF3EFE0) : 0x666666;
        mc.fontRenderer.drawString(this.displayString, this.xPosition + 28, this.yPosition + (this.height - 8) / 2, textColor);
    }
}
