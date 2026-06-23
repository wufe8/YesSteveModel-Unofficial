package com.fox.ysmu.client.gui;

import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.ExtraWheelData;
import com.fox.ysmu.client.input.ExtraAnimationKey;
import com.fox.ysmu.Config;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SetPlayAnimation;
import com.fox.ysmu.util.ModelIdUtil;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.*;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.util.*;

public class AnimationRouletteScreen extends GuiScreen {
    private static final int ITEMS_PER_PAGE = 8;
    private static final String SUBMENU_PREFIX = "#";
    private static final String RETURN_KEY = "#return";

    private int x, y;
    private int selectId = -1;
    private int currentPage;
    private final Deque<Map<String, String>> navigationStack = new ArrayDeque<>();
    private Map<String, String> currentEntries;

    @Override
    public void initGui() {
        this.x = width / 2;
        this.y = height / 2 - 8;
        if (mc != null && mc.thePlayer != null) {
            ExtendedModelInfo eep = ExtendedModelInfo.get(mc.thePlayer);
            if (eep != null) {
                ResourceLocation modelId = eep.getModelId();
                ResourceLocation mainId = ModelIdUtil.getMainId(modelId);
                ExtraWheelData wheelData = ClientModelManager.EXTRA_WHEEL.get(mainId);
                if (wheelData != null && !wheelData.entries.isEmpty()) {
                    this.currentEntries = wheelData.entries;
                    this.navigationStack.clear();
                    this.currentPage = 0;
                    return;
                }
                String[] names = ClientModelManager.EXTRA_ANIMATION_NAME.get(mainId);
                if (names != null && names.length > 0) {
                    this.currentEntries = flatToEntries(names);
                    this.navigationStack.clear();
                    this.currentPage = 0;
                    return;
                }
            }
        }
        this.currentEntries = Collections.emptyMap();
    }

    private static Map<String, String> flatToEntries(String[] names) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            if (StringUtils.isNotBlank(names[i])) {
                entries.put("extra" + i, names[i]);
            }
        }
        return entries;
    }

    @Override
    public void drawScreen(int pMouseX, int pMouseY, float pPartialTick) {
        List<Map.Entry<String, String>> pageEntries = getPageEntries();
        drawRoulette(pMouseX, pMouseY, pageEntries);
        drawRouletteText(pageEntries);
    }

    @Override
    protected void mouseClicked(int pMouseX, int pMouseY, int pButton) {
        List<Map.Entry<String, String>> pageEntries = getPageEntries();
        if (pButton == 0 && selectId >= 0 && selectId < pageEntries.size()) {
            Map.Entry<String, String> entry = pageEntries.get(selectId);
            String key = entry.getKey();
            if (RETURN_KEY.equals(key)) {
                navigateBack();
            } else if (key.startsWith(SUBMENU_PREFIX)) {
                navigateInto(key);
            } else {
                triggerExtra(key);
            }
        } else if (pButton == 1 && !navigationStack.isEmpty()) {
            navigateBack();
        }
        super.mouseClicked(pMouseX, pMouseY, pButton);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private List<Map.Entry<String, String>> getPageEntries() {
        List<Map.Entry<String, String>> all = new ArrayList<>(currentEntries.entrySet());
        int start = currentPage * ITEMS_PER_PAGE;
        if (start >= all.size()) return Collections.emptyList();
        int end = Math.min(start + ITEMS_PER_PAGE, all.size());
        return all.subList(start, end);
    }

    private void navigateInto(String key) {
        String classifyId = key.substring(SUBMENU_PREFIX.length());
        ExtraWheelData wheelData = getWheelData();
        if (wheelData != null && wheelData.classifies.containsKey(classifyId)) {
            navigationStack.push(currentEntries);
            this.currentEntries = wheelData.classifies.get(classifyId);
            this.currentPage = 0;
            this.selectId = -1;
            return;
        }
        if (!navigationStack.isEmpty() && !classifyId.isEmpty()) {
            Map<String, Map<String, String>> cf = getWheelData() != null ? getWheelData().classifies : null;
            if (cf != null && cf.containsKey(classifyId)) {
                navigationStack.push(currentEntries);
                this.currentEntries = cf.get(classifyId);
                this.currentPage = 0;
                this.selectId = -1;
            }
        }
    }

    private void navigateBack() {
        if (navigationStack.isEmpty()) {
            mc.displayGuiScreen(null);
            return;
        }
        this.currentEntries = navigationStack.pop();
        this.currentPage = 0;
        this.selectId = -1;
    }

    private ExtraWheelData getWheelData() {
        if (mc == null || mc.thePlayer == null) return null;
        ExtendedModelInfo eep = ExtendedModelInfo.get(mc.thePlayer);
        if (eep == null) return null;
        return ClientModelManager.EXTRA_WHEEL.get(ModelIdUtil.getMainId(eep.getModelId()));
    }

    private void triggerExtra(String key) {
        if (mc != null) {
            mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
            // 对于 extraN 格式，发送"extra" + N；否则发送原始 key
            String animName = key.startsWith("extra") ? key : key;
            NetworkHandler.CHANNEL.sendToServer(new SetPlayAnimation(animName));
            if (mc.thePlayer != null && Config.PRINT_ANIMATION_ROULETTE_MSG) {
                mc.thePlayer.addChatMessage(new ChatComponentText("Play: " + animName));
            }
            mc.displayGuiScreen(null);
        }
    }

    private void drawRouletteText(List<Map.Entry<String, String>> pageEntries) {
        int count = pageEntries.size();
        if (count == 0) return;
        for (int i = 0; i < count; i++) {
            Map.Entry<String, String> entry = pageEntries.get(i);
            String key = entry.getKey();
            String label = entry.getValue();
            float angle = (float) (Math.PI / count + 2 * Math.PI * i / count);
            int r = 65;
            ChatComponentText keyText = new ChatComponentText("[ ");
            keyText.getChatStyle().setColor(EnumChatFormatting.YELLOW);
            if (ExtraAnimationKey.EXTRA_ANIMATION_KEYS.size() > i) {
                KeyBinding kb = ExtraAnimationKey.EXTRA_ANIMATION_KEYS.get(i);
                if (kb.getKeyCode() == Keyboard.KEY_NONE) {
                    keyText.appendSibling(new ChatComponentTranslation("key.yes_steve_model.extra_animation.none"));
                } else {
                    keyText.appendSibling(new ChatComponentText(Keyboard.getKeyName(kb.getKeyCode())));
                }
            }
            keyText.appendSibling(new ChatComponentText(" ]"));
            int textX = (int) (x + r * MathHelper.cos(angle));
            int textY = (int) (y + r * MathHelper.sin(angle) - (float) fontRendererObj.FONT_HEIGHT / 2);
            String display = key.startsWith(SUBMENU_PREFIX) ? "[>] " + (StringUtils.isNotBlank(label) ? label : key) : (StringUtils.isNotBlank(label) ? label : key);
            if (StringUtils.isBlank(display)) display = key;
            this.drawCenteredString(fontRendererObj, display, textX, textY - 8, 0xF3EFE0);
            this.drawCenteredString(fontRendererObj, keyText.getFormattedText(), textX, textY + 4, 0xF3EFE0);
        }
    }

    private void drawRoulette(int mouseX, int mouseY, List<Map.Entry<String, String>> pageEntries) {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Tessellator tessellator = Tessellator.instance;
        int count = pageEntries.size();
        if (count == 0) {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_BLEND);
            return;
        }
        float theta = (float) Math.atan2(mouseY - y, mouseX - x);
        if (theta < 0) theta = (float) (Math.PI * 2 + theta);
        float distance = MathHelper.sqrt_float((mouseY - y) * (mouseY - y) + (mouseX - x) * (mouseX - x));
        boolean isSelected = false;
        for (int i = 0; i < count; i++) {
            float spacingDeg = (float) (Math.PI / 90);
            float startDeg = (float) ((2 * Math.PI / count) * i + spacingDeg);
            float endDeg = (float) ((2 * Math.PI / count) * (i + 1) - spacingDeg);
            if (startDeg < theta && theta < endDeg && 50 < distance && distance < 100) {
                drawFan(tessellator, 25, 105, startDeg, endDeg, 0xf0FFB100);
                isSelected = true;
                this.selectId = i;
            } else {
                drawFan(tessellator, 25, 105, startDeg, endDeg, 0x90000000);
            }
        }
        if (!isSelected) this.selectId = -1;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
    }

    private void drawFan(Tessellator tessellator, float rIn, float rOut, float startDeg, float endDeg, int color) {
        float alpha = (color >> 24 & 255) / 255.0F;
        float red = (color >> 16 & 255) / 255.0F;
        float green = (color >> 8 & 255) / 255.0F;
        float blue = (color & 255) / 255.0F;
        tessellator.startDrawing(GL11.GL_QUADS);
        tessellator.setColorRGBA_F(red, green, blue, alpha);
        tessellator.addVertex(x + rOut * MathHelper.cos(startDeg), y + rOut * MathHelper.sin(startDeg), 0);
        tessellator.addVertex(x + rIn * MathHelper.cos(startDeg), y + rIn * MathHelper.sin(startDeg), 0);
        tessellator.addVertex(x + rIn * MathHelper.cos(endDeg), y + rIn * MathHelper.sin(endDeg), 0);
        tessellator.addVertex(x + rOut * MathHelper.cos(endDeg), y + rOut * MathHelper.sin(endDeg), 0);
        tessellator.draw();
    }
}
