package com.fox.ysmu.client.gui;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientEventHandler;
import com.fox.ysmu.util.RenderUtil;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import org.lwjgl.input.Keyboard;

import java.util.List;

public class ExtraPlayerConfigScreen extends GuiScreen {
    private int posX;
    private int posY;
    private float scale;
    private float yawOffset;
    private boolean isChangePos = false;
    private boolean isChangeScale = false;

    private static final int LEFT_MOUSE_BUTTON = 0;
    private static final int RIGHT_MOUSE_BUTTON = 1;
    private int lastMouseX;

    public ExtraPlayerConfigScreen() {
        this.posX = Config.PLAYER_POS_X;
        this.posY = Config.PLAYER_POS_Y;
        this.scale = (float) Config.PLAYER_SCALE;
        this.yawOffset = (float) Config.PLAYER_YAW_OFFSET;
    }


    @Override
    public void drawScreen(int pMouseX, int pMouseY, float pPartialTick) {
        int startX = this.posX;
        int startY = this.posY;
        int endX = (int) (startX + this.scale * 1);
        int endY = (int) (startY + this.scale * 2);

        this.drawVerticalLine(width / 2 - 1, -2, height + 2, 0x9fffffff);
        this.drawHorizontalLine(-2, width + 2, height / 2 - 1, 0x9fffffff);

        this.drawVerticalLine(10, -2, height + 2, 0x9fffffff);
        this.drawVerticalLine(width - 10, -2, height + 2, 0x9fffffff);
        this.drawHorizontalLine(-2, width + 2, 10, 0x9fffffff);
        this.drawHorizontalLine(-2, width + 2, height - 10, 0x9fffffff);

        this.drawVerticalLine(startX, startY, endY, 0xffff0000);
        this.drawVerticalLine(endX, startY, endY, 0xffff0000);
        this.drawHorizontalLine(startX, endX, startY, 0xffff0000);
        this.drawHorizontalLine(startX, endX, endY, 0xffff0000);

        this.drawGradientRect(startX, startY, endX, endY, 0x4fffffff, 0x4fffffff);

        this.drawGradientRect(startX - 5, startY - 5, startX + 5, startY + 5, 0xFF00FF9F, 0xFF00FF9F);
        this.drawGradientRect(endX - 5, endY - 5, endX + 5, endY + 5, 0xFF00009F, 0xFF00009F);

        String mainText = I18n.format("gui.yes_steve_model.extra_player_render.tips").replace("\\n", "\n");
        List<String> textLines = this.fontRendererObj.listFormattedStringToWidth(mainText, 500);
        int y = 15;
        for (String line : textLines) {
            int w = fontRendererObj.getStringWidth(line);
            this.drawString(fontRendererObj, line, width - 15 - w, y, 0xFFFFFF);
            y += 10;
        }

        // FBO cache toggle hint
        String cacheStatus = I18n.format(Config.GUI_HUD_PREVIEW_CACHE
            ? "gui.yes_steve_model.hud_cache.on"
            : "gui.yes_steve_model.hud_cache.off");
        this.drawString(fontRendererObj, cacheStatus, 15, height - 20, Config.GUI_HUD_PREVIEW_CACHE ? 0x55FF55 : 0xFFAA00);

        // HUD follow mode hint
        String followKey;
        switch (Config.HUD_FOLLOW_MODE) {
            case Config.HUD_FOLLOW_VANILLA_SMOOTH:
                followKey = "gui.yes_steve_model.hud_follow.smooth";
                break;
            case Config.HUD_FOLLOW_YSM:
                followKey = "gui.yes_steve_model.hud_follow.ysm";
                break;
            case Config.HUD_FOLLOW_YSM_SMOOTH:
                followKey = "gui.yes_steve_model.hud_follow.ysm_smooth";
                break;
            default:
                followKey = "gui.yes_steve_model.hud_follow.vanilla";
                break;
        }
        String followStatus = I18n.format(followKey);
        int followColor = Config.HUD_FOLLOW_MODE == Config.HUD_FOLLOW_VANILLA ? 0xFFFFFF : 0x55FF55;
        this.drawString(fontRendererObj, followStatus, 15, height - 10, followColor);

        if (this.mc.thePlayer != null) {
            RenderUtil.renderPlayerEntity(this.mc.thePlayer, this.posX, this.posY, this.scale, this.yawOffset, 50, pPartialTick);
        }
    }


    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        boolean xIn = this.posX - 5 < mouseX && mouseX < this.posX + 5;
        boolean yIn = this.posY - 5 < mouseY && mouseY < this.posY + 5;
        if (button == LEFT_MOUSE_BUTTON && xIn && yIn) {
            this.isChangePos = true;
        }
        int endX = (int) (this.posX + this.scale * 1);
        int endY = (int) (this.posY + this.scale * 2);
        boolean xIn2 = endX - 5 < mouseX && mouseX < endX + 5;
        boolean yIn2 = endY - 5 < mouseY && mouseY < endY + 5;
        if (button == LEFT_MOUSE_BUTTON && xIn2 && yIn2) {
            this.isChangeScale = true;
        }
        if (button == RIGHT_MOUSE_BUTTON) {
            this.lastMouseX = mouseX;
        }
    }


    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        super.mouseMovedOrUp(mouseX, mouseY, state);
        if (state == 0 || state == 1) {
            this.isChangePos = false;
            this.isChangeScale = false;
        }
    }


    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
        if (isChangeScale) {
            double scale1 = mouseX - this.posX;
            double scale2 = (double) (mouseY - this.posY) / 2;
            this.scale = (float) Math.min(scale1, scale2);
        }
        if (isChangePos) {
            this.posX = mouseX;
            this.posY = mouseY;
        }
        float dragX = mouseX - this.lastMouseX;
        if (button == RIGHT_MOUSE_BUTTON) {
            this.yawOffset += (dragX * 2);
        }
        this.lastMouseX = mouseX;
    }


    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // Alt+R key events don't fire when Alt is held (consumed by LWJGL/GLFW).
        // Since this screen has no text input, just use plain R key to reset.
        if (keyCode == org.lwjgl.input.Keyboard.KEY_R) {
            this.posX = 10;
            this.posY = 10;
            this.scale = 40;
            this.yawOffset = 5;
            return; // Don't pass R to super (no text field anyway)
        }
        // Toggle HUD FBO cache
        if (keyCode == org.lwjgl.input.Keyboard.KEY_C) {
            Config.GUI_HUD_PREVIEW_CACHE = !Config.GUI_HUD_PREVIEW_CACHE;
            return;
        }
        // Cycle HUD follow mode (0 vanilla / 1 vanilla smooth / 2 ysm / 3 ysm smooth)
        if (keyCode == org.lwjgl.input.Keyboard.KEY_M) {
            Config.HUD_FOLLOW_MODE = (Config.HUD_FOLLOW_MODE + 1) % 4;
            ClientEventHandler.invalidateHudPreviewCache();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }


    @Override
    public void onGuiClosed() {
        Config.PLAYER_POS_X = this.posX;
        Config.PLAYER_POS_Y = this.posY;
        Config.PLAYER_SCALE = this.scale;
        Config.PLAYER_YAW_OFFSET = this.yawOffset;
        Config.save();
    }
}
