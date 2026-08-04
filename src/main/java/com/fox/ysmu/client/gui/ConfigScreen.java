package com.fox.ysmu.client.gui;

import java.util.List;

import com.fox.ysmu.client.gui.button.ConfigCheckBox;
import com.fox.ysmu.client.gui.button.ConfigSlider;
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
            // 预览刷新：点击循环离散值（0-4），文本置中与其他点击项一致
            this.buttonList.add(new FlatColorButton(9, x + 5, y + 25 + i++ * 22, 400, 20,
                I18n.format("gui.yes_steve_model.config.gui_model_preview_refresh." + Config.GUI_MODEL_PREVIEW_REFRESH)));
        } else if (page == 1) {
            int i = 0;
            addCheckbox(3,  x + 5, y + 25 + i++ * 22, "print_animation_roulette_msg", Config.PRINT_ANIMATION_ROULETTE_MSG);
            addCheckbox(13, x + 5, y + 25 + i++ * 22, "hide_offhand_defoliage_axe", Config.HIDE_OFFHAND_DEFOLIAGE_AXE);
            // 渲染其他模组背部模型：开关紧邻其下的缩放滑条，便于对照调整
            addCheckbox(7,  x + 5, y + 25 + i++ * 22, "render_wearable",         Config.RENDER_WEARABLE);
            // 可穿戴模型缩放：连续值用滑条（比多次点击方便）；范围与 Config 一致（0.1-5.0）
            this.buttonList.add(new ConfigSlider(10, x + 5, y + 25 + i++ * 22, 400,
                "gui.yes_steve_model.config.wearable_render_scale",
                0.1, 5.0, 0.05, Config.WEARABLE_RENDER_SCALE,
                v -> Config.WEARABLE_RENDER_SCALE = v));
            this.buttonList.add(new FlatColorButton(11, x + 5, y + 25 + i++ * 22, 400, 20,
                I18n.format("gui.yes_steve_model.config.texture_target_size." + Config.TEXTURE_TARGET_SIZE)));
            this.buttonList.add(new FlatColorButton(12, x + 5, y + 25 + i++ * 22, 400, 20,
                I18n.format("gui.yes_steve_model.config.texture_vram_budget." + Config.TEXTURE_VRAM_BUDGET_MB)));
            // 防滑步（stride matching）设置：开关 + 基础倍率滑条
            // （平滑响应 AnimationSpeedMatchResponse 只留在 config 文件，不提供滑条）
            addCheckbox(14, x + 5, y + 25 + i++ * 22, "animation_speed_match", Config.ANIMATION_SPEED_MATCH);
            this.buttonList.add(new ConfigSlider(15, x + 5, y + 25 + i++ * 22, 400,
                "gui.yes_steve_model.config.animation_speed_match_base",
                0.25, 4.0, 0.05, Config.ANIMATION_SPEED_MATCH_BASE,
                v -> Config.ANIMATION_SPEED_MATCH_BASE = v));
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
            case 3: case 7: case 13: case 14: case 11: case 12:
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
            case 13:
                Config.HIDE_OFFHAND_DEFOLIAGE_AXE = !Config.HIDE_OFFHAND_DEFOLIAGE_AXE;
                ((ConfigCheckBox) button).doPress();
                break;
            case 14:
                Config.ANIMATION_SPEED_MATCH = !Config.ANIMATION_SPEED_MATCH;
                ((ConfigCheckBox) button).doPress();
                break;
            case 11:
                Config.TEXTURE_TARGET_SIZE = nextTextureTargetSize(Config.TEXTURE_TARGET_SIZE);
                button.displayString = I18n.format("gui.yes_steve_model.config.texture_target_size." + Config.TEXTURE_TARGET_SIZE);
                break;
            case 12:
                Config.TEXTURE_VRAM_BUDGET_MB = nextVramBudget(Config.TEXTURE_VRAM_BUDGET_MB);
                button.displayString = I18n.format("gui.yes_steve_model.config.texture_vram_budget." + Config.TEXTURE_VRAM_BUDGET_MB);
                break;
        }
    }

    /** Cycles the VRAM texture-size target: 0 (off) -> 2048 -> 1024 -> 512 -> 256 -> 0. */
    private static int nextTextureTargetSize(int current) {
        switch (current) {
            case 0: return 2048;
            case 2048: return 1024;
            case 1024: return 512;
            case 512: return 256;
            default: return 0;
        }
    }

    /** Cycles the texture VRAM budget (MB): 0 (off) -> 128 -> 256 -> 512 -> 1024 -> 2048 -> 0. */
    private static int nextVramBudget(int current) {
        switch (current) {
            case 0: return 128;
            case 128: return 256;
            case 256: return 512;
            case 512: return 1024;
            case 1024: return 2048;
            default: return 0;
        }
    }

    @Override
    public void drawScreen(int pMouseX, int pMouseY, float pPartialTick) {
        this.drawDefaultBackground();
        super.drawScreen(pMouseX, pMouseY, pPartialTick);
    }

    /** 鼠标滚轮翻页（等同点击右上角页码按钮）；右键返回（等同左上角返回按钮）。 */
    @Override
    public void handleMouseInput() {
        int dWheel = org.lwjgl.input.Mouse.getDWheel();
        if (dWheel != 0) {
            page = (page + 1) % 2;
            this.initGui();
            return;
        }
        super.handleMouseInput();
    }

    @Override
    protected void mouseClicked(int pMouseX, int pMouseY, int pButton) {
        // 右键：返回模型预览页（等同左上角返回按钮 id 0）
        if (pButton == 1) {
            this.mc.displayGuiScreen(parent);
            return;
        }
        super.mouseClicked(pMouseX, pMouseY, pButton);
    }

    @Override
    public void onGuiClosed() {
        Config.save();
    }
}
