package com.fox.ysmu.client.gui.button;

import com.fox.ysmu.Config;
import com.fox.ysmu.ysmu;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.eep.ExtendedStarModels;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.OpenModelGuiMessage;
import com.fox.ysmu.network.message.SetModelAndTexture;
import com.fox.ysmu.network.message.SetNpcModelAndTexture;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.client.renderer.PreviewRefreshPolicy;
import com.fox.ysmu.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.entity.player.EntityPlayer;
import org.apache.commons.lang3.tuple.Pair;
import org.lwjgl.opengl.GL11;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

public class ModelButton extends GuiButton {
    private final static ResourceLocation ICON = new ResourceLocation(ysmu.MODID, "texture/icon.png");
    public final Pair<ResourceLocation, List<ResourceLocation>> modelInfo;
    private final ResourceLocation mainModelId;
    private final int color;
    public final List<IChatComponent> tooltips;
    private final EntityPlayer player;
    private final boolean disablePreviewRotation;

    // Cached DynamicTexture locations for foreground/background
    private ResourceLocation fgTextureLocation;
    private ResourceLocation bgTextureLocation;

    // Off-screen framebuffer cache for model preview — renders once, reuses texture.
    private final com.fox.ysmu.util.FboCache fboCache = new com.fox.ysmu.util.FboCache();
    private boolean modelCacheDirty = true;
    private String modelCacheGuiAnim = "";
    private boolean modelCacheWasHovered = false;
    private int modelCacheFramesUntilRefresh = 0;
    private int modelCacheLastRefreshInterval = -1;
    /** 上一次烘焙用的 FBO 尺寸：尺寸一变说明 FBO 被重建，缓存画面作废、姿态签名也要作废。 */
    private int modelCacheFbW = -1;
    private int modelCacheFbH = -1;
    /** 自动模式的墙钟累加器；只在 {@code Config.GUI_MODEL_PREVIEW_REFRESH < 0} 时使用。 */
    private final PreviewRefreshPolicy.Tracker previewRefresh = new PreviewRefreshPolicy.Tracker();

    // GUI animation state
    private long lastHoverTime = -1;
    private boolean hasHoverAnim;
    private boolean hasHoverFadeoutAnim;
    private boolean hasFocusAnim;
    private double hoverFadeoutDurationMs;
    /** Lazy-animation state: whether we already (re)checked the model's AnimationFile
     *  (loaded on first use) and how many draw attempts we've spent waiting for it. */
    private boolean lazyAnimChecked;
    private int lazyAnimAttempts;

    public ModelButton(int id, int pX, int pY, Pair<ResourceLocation, List<ResourceLocation>> modelInfo,
                       List<IChatComponent> tooltips, EntityPlayer player) {
        super(id, pX, pY, 52, 90, "");
        this.modelInfo = modelInfo;
        this.mainModelId = ModelIdUtil.getMainId(modelInfo.getLeft());
        this.color = 0xFF_434242;
        this.tooltips = tooltips;
        this.player = player;

        // Look up disablePreviewRotation from ClientModelManager
        Boolean dpr = ClientModelManager.DISABLE_PREVIEW_ROTATION.get(mainModelId);
        this.disablePreviewRotation = dpr != null && dpr;

        // Detect GUI animations from the model's animation file
        AnimationFile animFile = GeckoLibCache.getInstance().getAnimations().get(mainModelId);
        if (animFile != null) {
            this.hasHoverAnim = animFile.getAnimation("hover") != null;
            this.hasHoverFadeoutAnim = animFile.getAnimation("hover_fadeout") != null;
            software.bernie.geckolib3.core.builder.Animation focusAnim = animFile.getAnimation("focus");
            this.hasFocusAnim = focusAnim != null && focusAnim.boneAnimations != null && !focusAnim.boneAnimations.isEmpty();
            if (this.hasHoverFadeoutAnim) {
                software.bernie.geckolib3.core.builder.Animation fadeout = animFile.getAnimation("hover_fadeout");
                this.hoverFadeoutDurationMs = fadeout != null ? fadeout.animationLength * 1000.0 : 0;
            }
        }

        this.displayString = ModelIdUtil.getModelDisplayName(modelInfo.getLeft());
    }

    /**
     * Lazy-animation support: with lazy animation loading the model's AnimationFile
     * may not be in GeckoLibCache when this button is constructed, so hover/focus
     * detection would be cached as "absent" forever. Trigger the background load once
     * and refresh the flags when the file arrives (equivalent to the brief wait users
     * already accept for idle reloads).
     */
    private void refreshGuiAnimFlags() {
        if (this.lazyAnimChecked) {
            return;
        }
        // 同步进行中不加载完整动画（内存优先：缩略图按需，同步完成后恢复）。
        if (ClientModelManager.SYNC_IN_PROGRESS) {
            return;
        }
        if (++this.lazyAnimAttempts > 600) {
            // Give up after ~10s: model has no (or unresolvable) main animation.
            this.lazyAnimChecked = true;
            return;
        }
        com.fox.ysmu.client.asset.AssetManager.anim(mainModelId).get();
        AnimationFile animFile = GeckoLibCache.getInstance().getAnimations().get(mainModelId);
        if (animFile == null) {
            return;
        }
        this.lazyAnimChecked = true;
        this.hasHoverAnim = animFile.getAnimation("hover") != null;
        this.hasHoverFadeoutAnim = animFile.getAnimation("hover_fadeout") != null;
        software.bernie.geckolib3.core.builder.Animation focusAnim = animFile.getAnimation("focus");
        this.hasFocusAnim = focusAnim != null && focusAnim.boneAnimations != null && !focusAnim.boneAnimations.isEmpty();
        if (this.hasHoverFadeoutAnim) {
            software.bernie.geckolib3.core.builder.Animation fadeout = animFile.getAnimation("hover_fadeout");
            this.hoverFadeoutDurationMs = fadeout != null ? fadeout.animationLength * 1000.0 : 0;
        }
    }

    public void doPress() {
        // Use the model's default_texture (falling back to the first texture),
        // matching official YSM behavior — the first texture is not necessarily
        // the intended default.
        ResourceLocation defaultTex = ClientModelManager.resolveDefaultTexture(mainModelId, modelInfo.getRight());
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep != null) {
            eep.setModelAndTexture(modelInfo.getLeft(), defaultTex);
        }
        if (player.equals(Minecraft.getMinecraft().thePlayer)) {
            NetworkHandler.CHANNEL.sendToServer(new SetModelAndTexture(modelInfo.getLeft(), defaultTex));
        } else {
            NetworkHandler.CHANNEL.sendToServer(new SetNpcModelAndTexture(modelInfo.getLeft(), defaultTex, OpenModelGuiMessage.CURRENT_NPC_ID));
        }
    }

    /** Releases the off-screen FBO and any GUI DynamicTextures. Must be called
     *  when this button is removed from the screen (page flip / screen close)
     *  to avoid VRAM leaks. */
    public void dispose() {
        fboCache.delete();
        previewRefresh.reset();
        // Free the DynamicTextures created for the foreground/background images.
        // Without this, browsing a large library uploads a permanent GL texture
        // per model button that has GUI images (VRAM growth that never shrinks).
        Minecraft mc = Minecraft.getMinecraft();
        if (fgTextureLocation != null) {
            mc.getTextureManager().deleteTexture(fgTextureLocation);
            fgTextureLocation = null;
        }
        if (bgTextureLocation != null) {
            mc.getTextureManager().deleteTexture(bgTextureLocation);
            bgTextureLocation = null;
        }
        modelCacheDirty = true;
    }

    /**
     * Creates a DynamicTexture from a RawImage and registers it with the texture manager.
     * The caller should cache the returned ResourceLocation.
     */
    private ResourceLocation createGuiTexture(Minecraft mc, RawYsmModel.RawImage rawImage, String suffix) {
        if (rawImage == null || rawImage.data == null) return null;
        String key = "ysmu_gui_" + mainModelId.toString().replace(':', '_').replace('/', '_') + "_" + suffix;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(rawImage.data));
            if (img != null) {
                DynamicTexture dynTex = new DynamicTexture(img);
                return mc.getTextureManager().getDynamicTextureLocation(key, dynTex);
            }
        } catch (IOException e) {
            ysmu.LOG.warn("Failed to load GUI texture {} for model {}", suffix, mainModelId);
        }
        return null;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        refreshGuiAnimFlags();
        // 可见按钮每帧标记自身模型「使用中」：浏览中的页面保持常驻（FBO 重渲染不空白），
        // 关闭 GUI 后 ~5s 由快速卸载扫描回收。
        ClientModelManager.markModelInUse(mainModelId);
        FontRenderer font = mc.fontRenderer;
        // Hover状态
        this.field_146123_n = mouseX >= this.xPosition && mouseY >= this.yPosition
            && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;

        boolean guiEnhancements = Config.GUI_ENHANCEMENTS;

        // Determine cap-controller overlay animation (hover / hover_fadeout / focus).
        // The base preview_animation is now played by predicateMain, so the cap
        // controller only handles temporary overlays — they blend naturally.
        String guiAnimName = "";
        boolean isSelected = false;
        if (guiEnhancements) {
            ExtendedModelInfo eep = ExtendedModelInfo.get(player);
            isSelected = eep != null && eep.getModelId() != null
                && mainModelId.equals(ModelIdUtil.getMainId(eep.getModelId()));

            if (isSelected) {
                // Selected model: always play focus (per wiki: "当选中该模型按钮时播放")
                if (!hasFocusAnim) {
                    AnimationFile af = GeckoLibCache.getInstance().getAnimations().get(mainModelId);
                    if (af != null) {
                        software.bernie.geckolib3.core.builder.Animation fa = af.getAnimation("focus");
                        hasFocusAnim = fa != null && fa.boneAnimations != null && !fa.boneAnimations.isEmpty();
                    }
                }
                if (hasFocusAnim) {
                    guiAnimName = "focus";
                }
            } else if (this.field_146123_n) {
                // Hovering over a non-selected model: play "hover"
                this.lastHoverTime = System.currentTimeMillis();
                guiAnimName = hasHoverAnim ? "hover" : "";
            } else if (this.lastHoverTime >= 0) {
                long elapsed = System.currentTimeMillis() - this.lastHoverTime;
                if (hasHoverFadeoutAnim && elapsed < hoverFadeoutDurationMs) {
                    guiAnimName = "hover_fadeout";
                } else {
                    this.lastHoverTime = -1;
                }
            }
        }

        // Draw solid background
        this.drawGradientRect(this.xPosition, this.yPosition,
            this.xPosition + this.width, this.yPosition + this.height, this.color, this.color);

        // Draw GUI background texture (behind model, full button area)
        if (guiEnhancements) {
            RawYsmModel.RawImage bgRaw = ClientModelManager.GUI_BACKGROUND_IMAGE.get(mainModelId);
            if (bgRaw != null) {
                if (bgTextureLocation == null) {
                    bgTextureLocation = createGuiTexture(mc, bgRaw, "bg");
                }
                if (bgTextureLocation != null) {
                    drawGuiTexture(mc, bgTextureLocation, this.xPosition, this.yPosition, this.width, this.height);
                }
            }
        }

        // Off-screen framebuffer caching for the model preview.
        // 刷新策略：-1 = 自动（整页共享预算，见 PreviewRefreshPolicy）；0 = 静态（仅交互时重烘焙）；
        // 1-4 = 每 N 帧。GUI_ENHANCEMENTS 关闭时退化成静态。
        int refreshMode = guiEnhancements ? Config.GUI_MODEL_PREVIEW_REFRESH : 0;
        // Re-sync counter when user changes the config value.
        if (refreshMode != modelCacheLastRefreshInterval) {
            modelCacheFramesUntilRefresh = 0;
            modelCacheLastRefreshInterval = refreshMode;
            previewRefresh.reset();
        }
        boolean hoverChanged = this.field_146123_n != modelCacheWasHovered;
        boolean animChanged = !guiAnimName.equals(modelCacheGuiAnim);
        boolean periodicDue;
        if (refreshMode < 0) {
            PreviewRefreshPolicy.noteVisible();
            periodicDue = previewRefresh.due(this.id, this.field_146123_n || isSelected);
        } else {
            periodicDue = refreshMode > 0 && --modelCacheFramesUntilRefresh <= 0;
        }
        // 同步进行中不重建 FBO 缩略图：完整 geo/anim 留给同步完成后按需加载（压峰值内存），
        // 期间显示已缓存的 FBO（首次为空白占位）。
        if (!ClientModelManager.SYNC_IN_PROGRESS
            && (hoverChanged || animChanged || modelCacheDirty || periodicDue)) {
            modelCacheWasHovered = this.field_146123_n;
            modelCacheGuiAnim = guiAnimName;
            modelCacheDirty = false;
            modelCacheFramesUntilRefresh = refreshMode;

            int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
            int fbW = this.width * scale;
            int fbH = (this.height - 20) * scale;
            // FBO 尺寸变了（窗口缩放 / 换了 GUI scale）→ 里面的画面作废，必须重画一次：
            // 姿态签名可能没变（模型静止），只有这里知道"缓存内容已经没了"。
            if (fbW != modelCacheFbW || fbH != modelCacheFbH) {
                modelCacheFbW = fbW;
                modelCacheFbH = fbH;
                RenderUtil.invalidatePreviewPose(mainModelId);
            }
            // 悬停/焦点动画切换、翻页或模型被标记为脏：这些变化不一定体现在骨骼姿态里
            // （可能是换了动画、换了贴图），所以一律作废签名强制重绘。
            if (hoverChanged || animChanged || modelCacheDirty) {
                RenderUtil.invalidatePreviewPose(mainModelId);
            }

            // Ensure FBO exists with correct size (the outer if already determined
            // that a re-render is needed — don't gate on checkAndResize's return
            // value, which is false when refreshInterval=0 on subsequent passes).
            fboCache.checkAndResize(fbW, fbH, 0);
            fboCache.bind();
                GL11.glViewport(0, 0, fbW, fbH);
                // 注意：这里**不**清 FBO。清除动作作为回调传给 RenderUtil，只有真的决定要画时
                // 才执行 —— 姿态没变会被跳过，先清就会留下空白缩略图。

                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPushMatrix();
                GL11.glLoadIdentity();
                GL11.glOrtho(this.xPosition, this.xPosition + this.width,
                    this.yPosition + this.height - 20, this.yPosition,
                    1000.0, 3000.0);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);

                try {
                    final String finalGuiAnimName = guiAnimName;
                    final String baseAnim = ClientModelManager.PREVIEW_ANIMATION.get(mainModelId);
                    // Preview uses the model's default_texture, not the first texture.
                    final ResourceLocation previewTex = ClientModelManager.resolveDefaultTexture(mainModelId, modelInfo.getRight());
                    long bakeStart = System.nanoTime();
                    boolean poseRendered = RenderUtil.renderEntityInInventory(
                        this.xPosition + this.width / 2, this.yPosition + this.height / 2 + 20, 30,
                        mc.thePlayer, modelInfo.getLeft(), previewTex,
                        entity -> {
                            if (guiEnhancements) {
                                entity.setGuiAnimationsEnabled(true);
                                if (baseAnim != null && !baseAnim.isEmpty()) {
                                    entity.setGuiBaseAnimation(baseAnim);
                                }
                                entity.setPreviewAnimation(finalGuiAnimName);
                            } else {
                                entity.setGuiAnimationsEnabled(false);
                                entity.setGuiBaseAnimation("");
                                entity.setPreviewAnimation("");
                            }
                        },
                        disablePreviewRotation,
                        () -> {
                            GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
                            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                        });
                    // 自动模式的花费换算：整页共享预算 ÷ 可见数量 ÷ 这个单次成本。
                    // 姿态没变时跳过了几何提交（只剩动画 tick），**不能**把那点耗时喂给预算：
                    // 否则估计成本会塌到零、频率被推到上限，而每次 tick 的开销反而成倍增加。
                    if (poseRendered) {
                        PreviewRefreshPolicy.noteBake((System.nanoTime() - bakeStart) / 1.0e6F);
                    }
                } finally {
                    GL11.glMatrixMode(GL11.GL_PROJECTION);
                    GL11.glPopMatrix();
                    GL11.glMatrixMode(GL11.GL_MODELVIEW);
                    fboCache.unbind(mc);
                }
        }

        // Draw the cached FBO texture stretched to the model area of the button.
        fboCache.draw(this.xPosition, this.yPosition, this.width, this.height - 20);

        // Draw GUI foreground texture (over model, full button area)
        if (guiEnhancements) {
            RawYsmModel.RawImage fgRaw = ClientModelManager.GUI_FOREGROUND_IMAGE.get(mainModelId);
            if (fgRaw != null) {
                if (fgTextureLocation == null) {
                    fgTextureLocation = createGuiTexture(mc, fgRaw, "fg");
                }
                if (fgTextureLocation != null) {
                    drawGuiTexture(mc, fgTextureLocation, this.xPosition, this.yPosition, this.width, this.height);
                }
            }
        }

        // Render text
        List<String> split = font.listFormattedStringToWidth(this.displayString, 45);
        if (split.size() > 1) {
            this.drawCenteredString(font, split.get(0), this.xPosition + this.width / 2,
                this.yPosition + this.height - 19, 0xF3EFE0);
            this.drawCenteredString(font, split.get(1), this.xPosition + this.width / 2,
                this.yPosition + this.height - 10, 0xF3EFE0);
        } else {
            this.drawCenteredString(font, this.displayString, this.xPosition + this.width / 2,
                this.yPosition + this.height - 15, 0xF3EFE0);
        }

        // Hover highlight border
        if (this.field_146123_n) {
            this.drawGradientRect(this.xPosition, this.yPosition + 1, this.xPosition + 1,
                this.yPosition + this.height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.xPosition, this.yPosition, this.xPosition + this.width,
                this.yPosition + 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.xPosition + this.width - 1, this.yPosition + 1,
                this.xPosition + this.width, this.yPosition + this.height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.xPosition, this.yPosition + this.height - 1,
                this.xPosition + this.width, this.yPosition + this.height, 0xff_F3EFE0, 0xff_F3EFE0);
        }

        // Star/favorite icon
        ExtendedStarModels starEep = ExtendedStarModels.get(player);
        if (starEep != null && starEep.containModel(modelInfo.getLeft())) {
            mc.getTextureManager().bindTexture(ICON);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            this.drawTexturedModalRect(this.xPosition + this.width - 14, this.yPosition, 16, 0, 16, 16);
        }
    }

    /**
     * Draws a custom-sized GUI texture (foreground/background) stretched to fill (x,y,w,h).
     * Uses Tessellator for correct UV mapping with DynamicTextures.
     */
    /**
     * Draws a custom-sized GUI texture (foreground/background) stretched to fill (x,y,w,h).
     * Resets critical GL state first to ensure correct rendering after 3D entity drawing.
     */
    private static void drawGuiTexture(Minecraft mc, ResourceLocation tex, int x, int y, int w, int h) {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_COLOR_MATERIAL);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        // Reset texture matrix to identity (GeckoLib may leave a transform)
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        mc.getTextureManager().bindTexture(tex);
        Tessellator tessellator = Tessellator.instance;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(x,   y + h, 0, 0, 1);
        tessellator.addVertexWithUV(x + w, y + h, 0, 1, 1);
        tessellator.addVertexWithUV(x + w, y,     0, 1, 0);
        tessellator.addVertexWithUV(x,   y,     0, 0, 0);
        tessellator.draw();
        GL11.glDisable(GL11.GL_BLEND);
    }
}
