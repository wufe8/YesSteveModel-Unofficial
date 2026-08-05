package com.fox.ysmu.client.gui.button;

import com.fox.ysmu.Config;
import com.fox.ysmu.eep.ExtendedModelInfo;
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
import net.minecraft.util.ResourceLocation;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.GL11;

import java.util.List;

public class TextureButton extends GuiButton {
    private final ResourceLocation modelId;
    private final ResourceLocation textureId;
    private final String name;
    private final EntityPlayer player;

    // Off-screen framebuffer cache for texture preview.
    private final com.fox.ysmu.util.FboCache fboCache = new com.fox.ysmu.util.FboCache();
    private boolean modelCacheDirty = true;
    private int modelCacheFramesUntilRefresh = 0;
    private int modelCacheLastRefreshInterval = -1;

    public TextureButton(int id, int pX, int pY, ResourceLocation modelId, ResourceLocation textureId, EntityPlayer player) {
        super(id, pX, pY, 54, 102, "");
        this.modelId = modelId;
        this.textureId = textureId;
        this.name = ModelIdUtil.getSubNameFromId(textureId);
        this.player = player;
    }

    public void doPress() {
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep != null) {
            eep.setModelAndTexture(modelId, textureId);
        }
        if (player.equals(Minecraft.getMinecraft().thePlayer)) {
            NetworkHandler.CHANNEL.sendToServer(new SetModelAndTexture(modelId, textureId));
        } else {
            NetworkHandler.CHANNEL.sendToServer(new SetNpcModelAndTexture(modelId, textureId, OpenModelGuiMessage.CURRENT_NPC_ID));
        }
    }

    /** Releases the off-screen FBO. Must be called when this button is removed
     *  from the screen (page flip / screen close) to avoid VRAM leaks. */
    public void dispose() {
        fboCache.delete();
        modelCacheDirty = true;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        FontRenderer font = mc.fontRenderer;
        // 可见按钮每帧标记自身模型「使用中」：浏览中的页面保持常驻，关闭 GUI 后由
        // 快速卸载扫描（~5s）回收。
        com.fox.ysmu.client.ClientModelManager.markModelInUse(modelId);
        this.field_146123_n = mouseX >= this.xPosition && mouseY >= this.yPosition && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
        this.drawGradientRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + this.height, 0xFF_434242, 0xFF_434242);

        // Off-screen framebuffer caching for the texture preview.
        // Use configured refresh interval.
        int refreshInterval = Config.GUI_MODEL_PREVIEW_REFRESH;
        if (refreshInterval != modelCacheLastRefreshInterval) {
            modelCacheFramesUntilRefresh = 0;
            modelCacheLastRefreshInterval = refreshInterval;
        }
        boolean timeToRefresh = refreshInterval > 0 && --modelCacheFramesUntilRefresh <= 0;
        if (modelCacheDirty || timeToRefresh) {
            modelCacheDirty = false;
            modelCacheFramesUntilRefresh = refreshInterval;

            int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
            int fbW = this.width * scale;
            int fbH = (this.height - 20) * scale;

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
                    RenderUtil.renderEntityInInventory(this.xPosition + this.width / 2, this.yPosition + this.height / 2 + 24,
                        35, mc.thePlayer, modelId, textureId);
                } finally {
                    GL11.glMatrixMode(GL11.GL_PROJECTION);
                    GL11.glPopMatrix();
                    GL11.glMatrixMode(GL11.GL_MODELVIEW);
                    fboCache.unbind(mc);
                }
        }

        // Draw the cached FBO texture.
        fboCache.draw(this.xPosition, this.yPosition, this.width, this.height - 20);

        List<String> split = font.listFormattedStringToWidth(name, 50);
        if (split.size() > 1) {
            this.drawCenteredString(font, split.get(0), this.xPosition + this.width / 2, this.yPosition + this.height - 19, 0xF3EFE0);
            this.drawCenteredString(font, split.get(1), this.xPosition + this.width / 2, this.yPosition + this.height - 10, 0xF3EFE0);
        } else {
            this.drawCenteredString(font, name, this.xPosition + this.width / 2, this.yPosition + this.height - 15, 0xF3EFE0);
        }
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        boolean selected = eep != null && textureId.equals(eep.getSelectTexture());
        if (selected || this.field_146123_n) {
            drawBorder(selected ? 0xff_82C56A : 0xff_F3EFE0);
        }
    }

    private void drawBorder(int color) {
        this.drawGradientRect(this.xPosition, this.yPosition + 1, this.xPosition + 1, this.yPosition + this.height - 1, color, color);
        this.drawGradientRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + 1, color, color);
        this.drawGradientRect(this.xPosition + this.width - 1, this.yPosition + 1, this.xPosition + this.width, this.yPosition + this.height - 1, color, color);
        this.drawGradientRect(this.xPosition, this.yPosition + this.height - 1, this.xPosition + this.width, this.yPosition + this.height, color, color);
    }
}
