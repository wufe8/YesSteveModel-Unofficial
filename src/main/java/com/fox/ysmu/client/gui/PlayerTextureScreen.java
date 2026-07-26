package com.fox.ysmu.client.gui;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.gui.button.FlatColorButton;
import com.fox.ysmu.client.gui.button.TextureButton;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.util.RenderUtil;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 模型动作预览 + 材质贴图选择界面。
 * <p>
 * 三栏布局：
 * 左栏 (90px) — 动画列表，点击选择要预览的动画
 * 中栏 (206px) — 大尺寸 3D 模型预览（LMB拖拽旋转、RMB拖拽平移、滚轮缩放）
 * 右栏 (118px) — 材质贴图缩略图 (2×2)
 */
public class PlayerTextureScreen extends GuiScreen {    // ============================================================
    // SECTION: Constants & Layout
    // ============================================================
    private static final String HIDDEN_PREFIX = "\u2014\u2014"; // "——"
    private static final String MERGED_PREFIX = "__ysm_merged__";

    // 布局常量
    private static final int LEFT_PANEL_X = 5;
    private static final int LEFT_PANEL_W = 85;
    private static final int ANIM_PER_PAGE = 11;
    private static final int ANIM_BTN_H = 17;

    private static final int RIGHT_PANEL_X = 302;
    private static final int RIGHT_PANEL_W = 118;
    private static final int TEXTURES_PER_PAGE = 4;
    private static final int TEXTURE_COLS = 2;
    private static final int TEXTURE_X_STEP = 56;
    private static final int TEXTURE_Y_STEP = 104;

    private static final int CENTER_PANEL_X = 93;
    private static final int CENTER_PANEL_W = 206;

    private static final int GUI_W = 420;
    private static final int GUI_H = 235;

    // ============================================================
    // SECTION: Fields
    // ============================================================

    // 预览交互状态
    private float offsetX;
    private float offsetY;
    private float zoom;
    private float yaw;
    private float pitch;
    private boolean showGround;
    /** 是否暂停动画播放 */
    private boolean paused;

    private final PlayerModelScreen parent;
    private final ResourceLocation modelId;
    private final List<ResourceLocation> textures;
    private final EntityPlayer player;

    // 动画列表
    private final List<String> animationNames;
    private String currentAnimation;

    // 分页
    private int texturePage;
    private int maxTexturePage;
    private int animPage;
    private int maxAnimPage;

    private int guiLeft;
    private int guiTop;

    // 鼠标拖拽追踪
    private int lastMouseX;
    private int lastMouseY;

    public PlayerTextureScreen(PlayerModelScreen parent, ResourceLocation modelId, List<ResourceLocation> textures) {
        this.parent = parent;
        this.modelId = modelId;
        this.textures = textures;
        this.textures.sort(Comparator.comparing(ResourceLocation::toString));
        this.player = parent.player;

        // 初始视角
        this.offsetX = 0.0f;
        this.offsetY = -60.0f;
        this.zoom = 80.0f;
        this.yaw = 165.0f;
        this.pitch = -5.0f;
        this.showGround = true;
        this.paused = false;

        // 从 GeckoLibCache 读取模型动画名列表
        this.animationNames = new ArrayList<>();
        ResourceLocation animId = ModelIdUtil.getMainId(modelId);
        AnimationFile file = GeckoLibCache.getInstance().getAnimations().get(animId);
        if (file != null && file.animations != null) {
            for (String name : file.animations.keySet()) {
                if (!name.startsWith(HIDDEN_PREFIX)
                    && (com.fox.ysmu.Config.DEBUG_MERGED_ANIMATIONS || !name.startsWith(MERGED_PREFIX))) {
                    animationNames.add(name);
                }
            }
        }
        animationNames.sort(String::compareTo);

        // 默认动画：优先 idle（刚进入页面时从静态姿势开始），其次 previewAnimation
        this.currentAnimation = "";
        if (animationNames.contains("idle")) {
            this.currentAnimation = "idle";
        } else {
            String previewAnim = ClientModelManager.PREVIEW_ANIMATION.get(ModelIdUtil.getMainId(modelId));
            if (previewAnim != null && !previewAnim.isEmpty() && animationNames.contains(previewAnim)) {
                this.currentAnimation = previewAnim;
            } else if (!animationNames.isEmpty()) {
                this.currentAnimation = animationNames.get(0);
            }
        }
    }

    // ============================================================
    // SECTION: initGui — Button Layout
    // ============================================================

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.guiLeft = (width - GUI_W) / 2;
        this.guiTop = (height - GUI_H) / 2;

        // 材质分页
        this.maxTexturePage = textures.isEmpty() ? 0 : (textures.size() - 1) / TEXTURES_PER_PAGE;
        if (this.texturePage > this.maxTexturePage) {
            this.texturePage = 0;
        }

        // 动画分页
        this.maxAnimPage = animationNames.isEmpty() ? 0 : (animationNames.size() - 1) / ANIM_PER_PAGE;
        if (this.animPage > this.maxAnimPage) {
            this.animPage = 0;
        }

        // --- 顶部按钮 ---
        // 返回按钮
        this.buttonList.add(new FlatColorButton(0,
            guiLeft + LEFT_PANEL_X, guiTop + 5, 80, 18,
            I18n.format("gui.yes_steve_model.model.return")));

        // 中间预览区顶部：停止 / 复位 / 地面 按钮（用短标签）
        this.buttonList.add(new FlatColorButton(10,
            guiLeft + CENTER_PANEL_X + 2, guiTop + 2, 18, 18, "S")
            .setTooltips("gui.yes_steve_model.model.stop"));
        this.buttonList.add(new FlatColorButton(11,
            guiLeft + CENTER_PANEL_X + 22, guiTop + 2, 18, 18, "R")
            .setTooltips("gui.yes_steve_model.model.reset"));
        this.buttonList.add(new FlatColorButton(12,
            guiLeft + CENTER_PANEL_X + 42, guiTop + 2, 18, 18, "G")
            .setTooltips("gui.yes_steve_model.model.ground"));

        // 右侧材质区翻页按钮
        this.buttonList.add(new FlatColorButton(1,
            guiLeft + RIGHT_PANEL_X + 20, guiTop + 213, 18, 18, "<"));
        this.buttonList.add(new FlatColorButton(2,
            guiLeft + RIGHT_PANEL_X + 80, guiTop + 213, 18, 18, ">"));

        // 左侧动画区翻页按钮
        this.buttonList.add(new FlatColorButton(3,
            guiLeft + LEFT_PANEL_X + 10, guiTop + 214, 16, 16, "<"));
        this.buttonList.add(new FlatColorButton(4,
            guiLeft + LEFT_PANEL_X + 56, guiTop + 214, 16, 16, ">"));

        // --- 左侧：动画列表按钮 ---
        int btnId = 20;
        for (int slot = 0; slot < ANIM_PER_PAGE; slot++) {
            int index = slot + this.animPage * ANIM_PER_PAGE;
            if (index >= animationNames.size()) break;
            String animName = animationNames.get(index);
            String localizedName = localizeAnimationName(animName);
            int btnX = guiLeft + LEFT_PANEL_X;
            int btnY = guiTop + 27 + ANIM_BTN_H * slot;
            this.buttonList.add(new FlatColorButton(btnId++, btnX, btnY, LEFT_PANEL_W, 16, localizedName));
        }

        // --- 右侧：材质按钮 ---
        int texBtnId = 40;
        for (int slot = 0; slot < TEXTURES_PER_PAGE; slot++) {
            int index = slot + this.texturePage * TEXTURES_PER_PAGE;
            if (index >= textures.size()) break;
            int btnX = guiLeft + RIGHT_PANEL_X + TEXTURE_X_STEP * (slot % TEXTURE_COLS);
            int btnY = guiTop + 5 + TEXTURE_Y_STEP * (slot / TEXTURE_COLS);
            this.buttonList.add(new TextureButton(texBtnId++, btnX, btnY, modelId, textures.get(index), player));
        }
    }

    // ============================================================
    // SECTION: Action Handling
    // ============================================================

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0: // 返回
                this.mc.displayGuiScreen(parent);
                break;
            case 1: // 材质上一页
                if (this.texturePage > 0) {
                    this.texturePage--;
                    this.initGui();
                }
                break;
            case 2: // 材质下一页
                if (this.texturePage < this.maxTexturePage) {
                    this.texturePage++;
                    this.initGui();
                }
                break;
            case 3: // 动画上一页
                if (this.animPage > 0) {
                    this.animPage--;
                    this.initGui();
                }
                break;
            case 4: // 动画下一页
                if (this.animPage < this.maxAnimPage) {
                    this.animPage++;
                    this.initGui();
                }
                break;
            case 10: // 暂停/继续动画
                this.paused = !this.paused;
                break;
            case 11: // 复位视角
                this.offsetX = 0.0f;
                this.offsetY = -60.0f;
                this.zoom = 80.0f;
                this.yaw = 165.0f;
                this.pitch = -5.0f;
                break;
            case 12: // 切换地面
                this.showGround = !this.showGround;
                break;
            default:
                // 动画按钮 (ID 20 ~ 20+ANIM_PER_PAGE)
                if (button.id >= 20 && button.id < 20 + ANIM_PER_PAGE) {
                    int idx = (button.id - 20) + this.animPage * ANIM_PER_PAGE;
                    if (idx >= 0 && idx < animationNames.size()) {
                        this.currentAnimation = animationNames.get(idx);
                    }
                    return;
                }
                // 材质按钮 (ID 40+)
                if (button instanceof TextureButton) {
                    ((TextureButton) button).doPress();
                }
                break;
        }
    }

    // ============================================================
    // SECTION: Draw — Main Render
    // ============================================================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        this.drawDefaultBackground();

        // 整体背景
        this.drawGradientRect(guiLeft, guiTop, guiLeft + GUI_W, guiTop + GUI_H, 0xff_222222, 0xff_222222);

        // 三栏分隔背景
        this.drawGradientRect(guiLeft + LEFT_PANEL_X, guiTop + 27,
            guiLeft + LEFT_PANEL_X + LEFT_PANEL_W, guiTop + GUI_H, 0xff_2B2B2B, 0xff_2B2B2B);
        this.drawGradientRect(guiLeft + CENTER_PANEL_X, guiTop,
            guiLeft + CENTER_PANEL_X + CENTER_PANEL_W, guiTop + GUI_H, 0xff_2B2B2B, 0xff_2B2B2B);
        this.drawGradientRect(guiLeft + RIGHT_PANEL_X, guiTop,
            guiLeft + RIGHT_PANEL_X + RIGHT_PANEL_W, guiTop + GUI_H, 0xff_2B2B2B, 0xff_2B2B2B);

        // 高亮当前选中的动画按钮（在 super.drawScreen 之前绘制背景色）
        for (Object obj : this.buttonList) {
            if (obj instanceof GuiButton) {
                GuiButton btn = (GuiButton) obj;
                if (btn.id >= 20 && btn.id < 20 + ANIM_PER_PAGE) {
                    int idx = (btn.id - 20) + this.animPage * ANIM_PER_PAGE;
                    if (idx >= 0 && idx < animationNames.size()) {
                        String animName = animationNames.get(idx);
                        if (animName.equals(this.currentAnimation)) {
                            this.drawGradientRect(btn.xPosition, btn.yPosition,
                                btn.xPosition + btn.width, btn.yPosition + btn.height,
                                0xff_3A6B3A, 0xff_3A6B3A);
                        }
                    }
                }
            }
        }

        // === 中间：3D 模型预览（在按钮和文字之前渲染，让 GUI 元素覆盖在模型上方） ===
        renderCenterPreview(mouseX, mouseY, partialTick);

        super.drawScreen(mouseX, mouseY, partialTick);

        // === 分页信息文字 ===
        // 材质分页
        String texPageInfo = String.format("%d/%d", texturePage + 1, maxTexturePage + 1);
        int texPageX = guiLeft + RIGHT_PANEL_X + (RIGHT_PANEL_W - fontRendererObj.getStringWidth(texPageInfo)) / 2;
        this.drawString(fontRendererObj, texPageInfo, texPageX, guiTop + 223, 0xF3EFE0);

        // 动画分页
        String animPageInfo = String.format("%d/%d", animPage + 1, maxAnimPage + 1);
        int animPageX = guiLeft + LEFT_PANEL_X + (LEFT_PANEL_W - fontRendererObj.getStringWidth(animPageInfo)) / 2;
        this.drawString(fontRendererObj, animPageInfo, animPageX, guiTop + 218, 0xF3EFE0);

        // 暂停提示
        if (this.paused) {
            String pauseLabel = I18n.format("gui.yes_steve_model.texture.paused");
            int pauseX = guiLeft + CENTER_PANEL_X + (CENTER_PANEL_W - fontRendererObj.getStringWidth(pauseLabel)) / 2;
            this.drawString(fontRendererObj, pauseLabel, pauseX, guiTop + GUI_H - 24, 0xFF5555);
        }

        // 当前动画名提示（底部居中）
        if (this.currentAnimation != null && !this.currentAnimation.isEmpty()) {
            // I18n.format returns the key itself if no translation exists
            String animLabel = I18n.format(
                "gui.yes_steve_model.texture.button." + this.currentAnimation.replaceAll(":", "."),
                this.currentAnimation);
            // If the translation IS the key (no translation found), just show the raw name
            if (animLabel.startsWith("gui.yes_steve_model.texture.button.")) {
                animLabel = this.currentAnimation;
            }
            int labelX = guiLeft + CENTER_PANEL_X + (CENTER_PANEL_W - fontRendererObj.getStringWidth(animLabel)) / 2;
            this.drawString(fontRendererObj, animLabel, labelX, guiTop + GUI_H - 12, 0xFFAA00);
        }

        // Tooltips for S/R/G buttons and animation buttons
        for (Object obj : this.buttonList) {
            if (obj instanceof GuiButton) {
                GuiButton btn = (GuiButton) obj;
                if (btn.func_146115_a()) {
                    String tip = null;
                    if (btn.id >= 10 && btn.id <= 12) {
                        switch (btn.id) {
                            case 10:
                                tip = paused ? I18n.format("gui.yes_steve_model.texture.resume") : I18n.format("gui.yes_steve_model.model.stop");
                                break;
                            case 11:
                                tip = I18n.format("gui.yes_steve_model.model.reset");
                                break;
                            case 12:
                                tip = I18n.format("gui.yes_steve_model.model.ground");
                                break;
                        }
                    } else if (btn.id >= 20 && btn.id < 20 + ANIM_PER_PAGE) {
                        int idx = (btn.id - 20) + this.animPage * ANIM_PER_PAGE;
                        if (idx >= 0 && idx < animationNames.size()) {
                            String rawId = animationNames.get(idx);
                            String localized = localizeAnimationName(rawId);
                            if (!localized.equals(rawId)) {
                                tip = rawId;
                            }
                        }
                    }
                    if (tip != null) {
                        java.util.List<String> lines = java.util.Collections.singletonList(tip);
                        this.drawHoveringText(lines, mouseX, mouseY + 12, fontRendererObj);
                    }
                }
            }
        }
    }

    // ============================================================
    // SECTION: Center Preview Rendering
    // ============================================================

    /** 渲染中间预览区域的 3D 模型 */
    private void renderCenterPreview(int mouseX, int mouseY, float partialTick) {
        if (player == null) return;

        // 裁剪到中间预览区域
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        int sX = (guiLeft + CENTER_PANEL_X) * scale;
        int sY = mc.displayHeight - (guiTop + GUI_H) * scale;
        GL11.glScissor(sX, sY, CENTER_PANEL_W * scale, GUI_H * scale);
        // Clear depth buffer in the scissored area — the model may fail the
        // depth test against stale values from the previous frame's world/HUD
        // rendering otherwise, making body and head invisible while hair/eyes
        // (rendered slightly in front) still pass.
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

        // 获取当前玩家选中的材质
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        ResourceLocation previewTex = (eep != null && eep.getSelectTexture() != null)
            ? eep.getSelectTexture()
            : (textures.isEmpty() ? null : textures.get(0));

        if (previewTex != null) {
            // Match OpenYSM position: slightly left-of-center so the model faces into the frame
            float centerX = guiLeft + 189.5f + offsetX;
            float centerY = guiTop + 197.5f + offsetY;

            RenderUtil.SHOW_GROUND = showGround;
            RenderUtil.renderTextureScreenEntity(
                centerX, centerY, zoom, pitch, yaw,
                player, modelId, previewTex,
                entity -> {
                    entity.setGuiAnimationsEnabled(true);
                    if (currentAnimation != null && !currentAnimation.isEmpty()) {
                        entity.setGuiBaseAnimation(currentAnimation);
                    }
                    // Freeze/resume via GeckoLib controller speed
                    double speed = paused ? 0.0 : 1.0;
                    entity.getFactory()
                        .getOrCreateAnimationData(entity.hashCode())
                        .getAnimationControllers()
                        .values()
                        .forEach(c -> c.setAnimationSpeed(speed));
                }
            );
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    // ============================================================
    // SECTION: Mouse Interaction
    // ============================================================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (isInPreviewArea(mouseX, mouseY)) {
            float dx = (float) (mouseX - lastMouseX);
            float dy = (float) (mouseY - lastMouseY);
            if (clickedMouseButton == 0) {
                // LMB: 旋转
                this.yaw += dx * 1.5f;
                adjustPitch(-dy);
            } else if (clickedMouseButton == 1) {
                // RMB: 平移
                this.offsetX += dx;
                this.offsetY += dy;
            }
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int dWheel = Mouse.getDWheel();
        if (dWheel != 0) {
            int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
            int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            if (isInPreviewArea(mouseX, mouseY)) {
                adjustZoom((float) dWheel);
            } else if (isInTextureArea(mouseX, mouseY)) {
                scrollTexturePage(dWheel);
            } else if (isInAnimationArea(mouseX, mouseY)) {
                scrollAnimationPage(dWheel);
            }
        }
    }

    private void scrollTexturePage(int delta) {
        if (delta > 0 && this.texturePage > 0) {
            this.texturePage--;
            this.mc.getSoundHandler().playSound(
                PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
            this.initGui();
        } else if (delta < 0 && this.texturePage < this.maxTexturePage) {
            this.texturePage++;
            this.mc.getSoundHandler().playSound(
                PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
            this.initGui();
        }
    }

    private void scrollAnimationPage(int delta) {
        if (delta > 0 && this.animPage > 0) {
            this.animPage--;
            this.mc.getSoundHandler().playSound(
                PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
            this.initGui();
        } else if (delta < 0 && this.animPage < this.maxAnimPage) {
            this.animPage++;
            this.mc.getSoundHandler().playSound(
                PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
            this.initGui();
        }
    }

    // ============================================================
    // SECTION: Hit Testing & Utilities
    // ============================================================

    private boolean isInPreviewArea(double mouseX, double mouseY) {
        return mouseX >= guiLeft + CENTER_PANEL_X && mouseX < guiLeft + CENTER_PANEL_X + CENTER_PANEL_W
            && mouseY >= guiTop && mouseY < guiTop + GUI_H;
    }

    private boolean isInAnimationArea(double mouseX, double mouseY) {
        return mouseX >= guiLeft + LEFT_PANEL_X && mouseX < guiLeft + LEFT_PANEL_X + LEFT_PANEL_W
            && mouseY >= guiTop + 27 && mouseY < guiTop + GUI_H;
    }

    private boolean isInTextureArea(double mouseX, double mouseY) {
        return mouseX >= guiLeft + RIGHT_PANEL_X && mouseX < guiLeft + RIGHT_PANEL_X + RIGHT_PANEL_W
            && mouseY >= guiTop && mouseY < guiTop + GUI_H;
    }

    /** Translate animation name via lang keys (e.g. extra1 → 轮盘动画1), fallback to raw name. */
    private static String localizeAnimationName(String animName) {
        String key = "gui.yes_steve_model.texture.button." + animName.replaceAll(":", ".");
        String formatted = I18n.format(key, animName);
        if (formatted.equals(key)) {
            return animName; // no translation found
        }
        return formatted;
    }

    private void adjustPitch(float deltaY) {
        this.pitch = Math.max(-90.0f, Math.min(90.0f, this.pitch + deltaY));
    }

    private void adjustZoom(float wheelDelta) {
        this.zoom = Math.max(18.0f, Math.min(360.0f, this.zoom + wheelDelta * this.zoom * 0.028f));
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
