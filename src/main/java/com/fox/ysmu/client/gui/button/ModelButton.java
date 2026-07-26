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

    // GUI animation state
    private long lastHoverTime = -1;
    private boolean hasHoverAnim;
    private boolean hasHoverFadeoutAnim;
    private boolean hasFocusAnim;
    private double hoverFadeoutDurationMs;

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

    public void doPress() {
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep != null) {
            eep.setModelAndTexture(modelInfo.getLeft(), modelInfo.getRight().get(0));
        }
        if (player.equals(Minecraft.getMinecraft().thePlayer)) {
            NetworkHandler.CHANNEL.sendToServer(new SetModelAndTexture(modelInfo.getLeft(), modelInfo.getRight().get(0)));
        } else {
            NetworkHandler.CHANNEL.sendToServer(new SetNpcModelAndTexture(modelInfo.getLeft(), modelInfo.getRight().get(0), OpenModelGuiMessage.CURRENT_NPC_ID));
        }
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
        FontRenderer font = mc.fontRenderer;
        // Hover状态
        this.field_146123_n = mouseX >= this.xPosition && mouseY >= this.yPosition
            && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;

        boolean guiEnhancements = Config.GUI_ENHANCEMENTS;

        // Determine cap-controller overlay animation (hover / hover_fadeout / focus).
        // The base preview_animation is now played by predicateMain, so the cap
        // controller only handles temporary overlays — they blend naturally.
        String guiAnimName = "";
        if (guiEnhancements) {
            if (this.field_146123_n) {
                // Hovering: play "hover" animation
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
            // If nothing from hover/fadeout and this model is selected, play "focus" as overlay
            if (guiAnimName.isEmpty()) {
                ExtendedModelInfo eep = ExtendedModelInfo.get(player);
                if (eep != null && hasFocusAnim && eep.getModelId() != null
                    && mainModelId.equals(ModelIdUtil.getMainId(eep.getModelId()))) {
                    guiAnimName = "focus";
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
        // Use configured refresh interval; fall back to 0 when GUI_ENHANCEMENTS
        // is disabled (fully static, no periodic refresh).
        int refreshInterval = guiEnhancements ? Config.GUI_MODEL_PREVIEW_REFRESH : 0;
        // Re-sync counter when user changes the config value.
        if (refreshInterval != modelCacheLastRefreshInterval) {
            modelCacheFramesUntilRefresh = 0;
            modelCacheLastRefreshInterval = refreshInterval;
        }
        boolean hoverChanged = this.field_146123_n != modelCacheWasHovered;
        boolean animChanged = !guiAnimName.equals(modelCacheGuiAnim);
        boolean timeToRefresh = refreshInterval > 0 && --modelCacheFramesUntilRefresh <= 0;
        if (hoverChanged || animChanged || modelCacheDirty || timeToRefresh) {
            modelCacheWasHovered = this.field_146123_n;
            modelCacheGuiAnim = guiAnimName;
            modelCacheDirty = false;
            modelCacheFramesUntilRefresh = refreshInterval;

            int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
            int fbW = this.width * scale;
            int fbH = (this.height - 20) * scale;

            // Ensure FBO exists with correct size (the outer if already determined
            // that a re-render is needed — don't gate on checkAndResize's return
            // value, which is false when refreshInterval=0 on subsequent passes).
            fboCache.checkAndResize(fbW, fbH, 0);
            fboCache.bind();
                GL11.glViewport(0, 0, fbW, fbH);
                GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

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
                    RenderUtil.renderEntityInInventory(
                        this.xPosition + this.width / 2, this.yPosition + this.height / 2 + 20, 30,
                        mc.thePlayer, modelInfo.getLeft(), modelInfo.getRight().get(0),
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
                        disablePreviewRotation);
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
