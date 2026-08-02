package com.fox.ysmu.client.gui;

import java.util.List;

import com.fox.ysmu.client.gui.button.ConfigCheckBox;
import com.fox.ysmu.client.gui.button.FlatColorButton;
import com.fox.ysmu.Config;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

public class ConfigScreen extends GuiScreen {
    private final PlayerModelScreen parent;
    private int page = 0;

    public ConfigScreen(PlayerModelScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int x = (width - 420) / 2;
        int y = (height - 235) / 2;

        // Return + page indicator (same row, top)
        this.buttonList.add(new FlatColorButton(0, x + 5, y, 80, 18, I18n.format("gui.yes_steve_model.model.return")));
        this.buttonList.add(new FlatColorButton(99, x + 300, y, 100, 18,
            I18n.format("gui.yes_steve_model.config.page", page + 1)));

        if (page == 0) {
            int i = 0;
            addCheckbox(1,  x + 5, y + 25 + i++ * 22, "disable_self_model",       Config.DISABLE_SELF_MODEL);
            addCheckbox(2,  x + 5, y + 25 + i++ * 22, "disable_other_model",     Config.DISABLE_OTHER_MODEL);
            addCheckbox(4,  x + 5, y + 25 + i++ * 22, "disable_self_hands",      Config.DISABLE_SELF_HANDS);
            addCheckbox(5,  x + 5, y + 25 + i++ * 22, "disable_player_render",   Config.DISABLE_PLAYER_RENDER);
            addCheckbox(6,  x + 5, y + 25 + i++ * 22, "swap_config_sides",       Config.SWAP_CONFIG_SIDES);
            addCheckbox(8,  x + 5, y + 25 + i++ * 22, "gui_enhancements",        Config.GUI_ENHANCEMENTS);
            this.buttonList.add(new FlatColorButton(9, x + 5, y + 25 + i++ * 22, 400, 20,
                I18n.format("gui.yes_steve_model.config.gui_model_preview_refresh." + Config.GUI_MODEL_PREVIEW_REFRESH)));
        } else if (page == 1) {
            int i = 0;
            addCheckbox(3,  x + 5, y + 25 + i++ * 22, "print_animation_roulette_msg", Config.PRINT_ANIMATION_ROULETTE_MSG);
            addCheckbox(7,  x + 5, y + 25 + i++ * 22, "render_wearable",         Config.RENDER_WEARABLE);
            this.buttonList.add(new FlatColorButton(10, x + 5, y + 25 + i++ * 22, 400, 20,
                I18n.format("gui.yes_steve_model.config.wearable_render_scale", Config.WEARABLE_RENDER_SCALE)));
            this.buttonList.add(new FlatColorButton(11, x + 5, y + 25 + i++ * 22, 400, 20,
                I18n.format("gui.yes_steve_model.config.texture_max_size." + Config.TEXTURE_MAX_SIZE)));
        }
    }

    private void addCheckbox(int id, int x, int y, String langKey, boolean value) {
        this.buttonList.add(new ConfigCheckBox(id, x, y, langKey, value));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                this.mc.displayGuiScreen(parent);
                break;
            case 99:
                page = (page + 1) % 2;
                this.initGui();
                break;
            case 1: case 2: case 4: case 5: case 6: case 8: case 9:
                actionPage0(button);
                break;
            case 3: case 7: case 10: case 11:
                actionPage1(button);
                break;
        }
    }

    private void actionPage0(GuiButton button) {
        switch (button.id) {
            case 1:
                Config.DISABLE_SELF_MODEL = !Config.DISABLE_SELF_MODEL;
                ((ConfigCheckBox) button).doPress();
                break;
            case 2:
                Config.DISABLE_OTHER_MODEL = !Config.DISABLE_OTHER_MODEL;
                ((ConfigCheckBox) button).doPress();
                break;
            case 4:
                Config.DISABLE_SELF_HANDS = !Config.DISABLE_SELF_HANDS;
                ((ConfigCheckBox) button).doPress();
                break;
            case 5:
                Config.DISABLE_PLAYER_RENDER = !Config.DISABLE_PLAYER_RENDER;
                ((ConfigCheckBox) button).doPress();
                break;
            case 6:
                Config.SWAP_CONFIG_SIDES = !Config.SWAP_CONFIG_SIDES;
                ((ConfigCheckBox) button).doPress();
                break;
            case 8:
                Config.GUI_ENHANCEMENTS = !Config.GUI_ENHANCEMENTS;
                ((ConfigCheckBox) button).doPress();
                break;
            case 9:
                Config.GUI_MODEL_PREVIEW_REFRESH = (Config.GUI_MODEL_PREVIEW_REFRESH + 1) % 5;
                button.displayString = I18n.format("gui.yes_steve_model.config.gui_model_preview_refresh." + Config.GUI_MODEL_PREVIEW_REFRESH);
                break;
        }
    }

    private void actionPage1(GuiButton button) {
        switch (button.id) {
            case 3:
                Config.PRINT_ANIMATION_ROULETTE_MSG = !Config.PRINT_ANIMATION_ROULETTE_MSG;
                ((ConfigCheckBox) button).doPress();
                break;
            case 7:
                Config.RENDER_WEARABLE = !Config.RENDER_WEARABLE;
                ((ConfigCheckBox) button).doPress();
                break;
            case 10:
                Config.WEARABLE_RENDER_SCALE = Math.round((Config.WEARABLE_RENDER_SCALE + 0.1) * 10) / 10.0;
                if (Config.WEARABLE_RENDER_SCALE > 1.5) Config.WEARABLE_RENDER_SCALE = 0.5;
                button.displayString = I18n.format("gui.yes_steve_model.config.wearable_render_scale", Config.WEARABLE_RENDER_SCALE);
                break;
            case 11:
                Config.TEXTURE_MAX_SIZE = nextTextureMaxSize(Config.TEXTURE_MAX_SIZE);
                button.displayString = I18n.format("gui.yes_steve_model.config.texture_max_size." + Config.TEXTURE_MAX_SIZE);
                break;
        }
    }

    /** Cycles the VRAM texture-size cap: 0 (off) -> 2048 -> 1024 -> 512 -> 256 -> 0. */
    private static int nextTextureMaxSize(int current) {
        switch (current) {
            case 0: return 2048;
            case 2048: return 1024;
            case 1024: return 512;
            case 512: return 256;
            default: return 0;
        }
    }

    @Override
    public void drawScreen(int pMouseX, int pMouseY, float pPartialTick) {
        this.drawDefaultBackground();
        super.drawScreen(pMouseX, pMouseY, pPartialTick);
    }

    @Override
    public void onGuiClosed() {
        Config.save();
    }
}
