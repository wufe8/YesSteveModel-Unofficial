package com.fox.ysmu.client.gui;

import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.ExtraWheelData;
import com.fox.ysmu.client.input.ExtraAnimationKey;
import com.fox.ysmu.Config;
import com.fox.ysmu.model.resource.pojo.RawYsmModel.ConfigForm;
import com.fox.ysmu.model.resource.pojo.RawYsmModel.ExtraAnimationButton;
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
    private static final int INNER_RING_MIN = 25;
    private static final int INNER_RING_MAX = 50;

    private int x, y;
    private int currentPage;
    private final Deque<Map<String, String>> navigationStack = new ArrayDeque<>();
    private Map<String, String> currentEntries;

    // Config panel state
    private ExtraAnimationButton currentConfigGroup;
    private int configScrollOffset;

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
                    this.currentConfigGroup = null;
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
        if (currentConfigGroup != null) {
            drawConfigPanel(pMouseX, pMouseY);
            return;
        }
        List<Map.Entry<String, String>> pageEntries = getPageEntries();
        drawRoulette(pMouseX, pMouseY, pageEntries);
        drawRouletteText(pageEntries);
    }

    @Override
    public void handleMouseInput() {
        int dWheel = org.lwjgl.input.Mouse.getDWheel();
        if (dWheel != 0 && currentConfigGroup != null) {
            configScrollOffset += dWheel > 0 ? -20 : 20;
            int totalHeight = 0;
            for (int i = 0; i < currentConfigGroup.forms.size(); i++) {
                totalHeight += getFormHeight(currentConfigGroup.forms.get(i)) + 4;
            }
            int maxScroll = Math.max(0, totalHeight - (height - 100));
            configScrollOffset = MathHelper.clamp_int(configScrollOffset, 0, maxScroll);
            return;
        }
        super.handleMouseInput();
    }

    @Override
    protected void mouseClicked(int pMouseX, int pMouseY, int pButton) {
        if (currentConfigGroup != null) {
            handleConfigClick(pMouseX, pMouseY, pButton);
            return;
        }
        List<Map.Entry<String, String>> pageEntries = getPageEntries();
        float distance = MathHelper.sqrt_float((pMouseY - y) * (pMouseY - y) + (pMouseX - x) * (pMouseX - x));
        float theta = (float) Math.atan2(pMouseY - y, pMouseX - x);
        if (theta < 0) theta = (float) (Math.PI * 2 + theta);
        int hoveredIndex = getHoveredIndex(theta, pageEntries.size());

        if (pButton == 0 && hoveredIndex >= 0) {
            Map.Entry<String, String> entry = pageEntries.get(hoveredIndex);
            String key = entry.getKey();
            // Inner ring (25-50): config button or back navigation
            if (distance >= INNER_RING_MIN && distance <= INNER_RING_MAX) {
                if (RETURN_KEY.equals(key)) {
                    navigateBack();
                    return;
                }
                // OpenYSM convention: value (display label) starts with # for config buttons
                if (entry.getValue() != null && entry.getValue().startsWith(SUBMENU_PREFIX)) {
                    String btnId = entry.getValue().substring(SUBMENU_PREFIX.length());
                    ExtraWheelData wd = getWheelData();
                    if (wd != null && wd.configButtons.containsKey(btnId)) {
                        currentConfigGroup = wd.configButtons.get(btnId);
                        configScrollOffset = 0;
                        return;
                    }
                }
            }
            // Outer ring (50-100): normal animation / sub-page
            if (distance > INNER_RING_MAX && distance < 100) {
                if (RETURN_KEY.equals(key)) {
                    navigateBack();
                } else if (key.startsWith(SUBMENU_PREFIX)) {
                    navigateInto(key);
                } else {
                    triggerExtra(key);
                }
            }
        } else if (pButton == 1) {
            if (currentConfigGroup != null) {
                currentConfigGroup = null;
            } else if (!navigationStack.isEmpty()) {
                navigateBack();
            }
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

    private int getHoveredIndex(float theta, int count) {
        if (count == 0) return -1;
        for (int i = 0; i < count; i++) {
            float spacingDeg = (float) (Math.PI / 90);
            float startDeg = (float) ((2 * Math.PI / count) * i + spacingDeg);
            float endDeg = (float) ((2 * Math.PI / count) * (i + 1) - spacingDeg);
            if (startDeg < theta && theta < endDeg) return i;
        }
        return -1;
    }

    private void navigateInto(String key) {
        String classifyId = key.substring(SUBMENU_PREFIX.length());
        ExtraWheelData wheelData = getWheelData();
        if (wheelData != null && wheelData.classifies.containsKey(classifyId)) {
            navigationStack.push(currentEntries);
            this.currentEntries = wheelData.classifies.get(classifyId);
            this.currentPage = 0;
            this.currentConfigGroup = null;
            return;
        }
        if (!navigationStack.isEmpty() && !classifyId.isEmpty()) {
            Map<String, Map<String, String>> cf = getWheelData() != null ? getWheelData().classifies : null;
            if (cf != null && cf.containsKey(classifyId)) {
                navigationStack.push(currentEntries);
                this.currentEntries = cf.get(classifyId);
                this.currentPage = 0;
                this.currentConfigGroup = null;
            }
        }
    }

    private void navigateBack() {
        if (currentConfigGroup != null) {
            currentConfigGroup = null;
            return;
        }
        if (navigationStack.isEmpty()) {
            mc.displayGuiScreen(null);
            return;
        }
        this.currentEntries = navigationStack.pop();
        this.currentPage = 0;
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
            String animName = key.startsWith("extra") ? key : key;
            NetworkHandler.CHANNEL.sendToServer(new SetPlayAnimation(animName));
            if (mc.thePlayer != null && Config.PRINT_ANIMATION_ROULETTE_MSG) {
                mc.thePlayer.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r Play: " + animName));
            }
            mc.displayGuiScreen(null);
        }
    }

    // ── Config Panel ──────────────────────────────────────────────

    /** Returns the pixel height of a single config form. */
    private int getFormHeight(ConfigForm form) {
        if ("checkbox".equals(form.type)) return 40;
        if ("range".equals(form.type)) return 55;
        if ("radio".equals(form.type)) return 24 + form.labels.size() * 14;
        return 40;
    }

    /** Returns the Y offset of form index i relative to content start. */
    private int getFormY(int index) {
        int y = 0;
        for (int i = 0; i < index && i < currentConfigGroup.forms.size(); i++) {
            y += getFormHeight(currentConfigGroup.forms.get(i)) + 4;
        }
        return y;
    }

    private void handleConfigClick(int pMouseX, int pMouseY, int pButton) {
        if (pButton == 1) {
            currentConfigGroup = null;
            return;
        }
        int panelX = width / 2 - 100;
        int panelW = 200;
        int startY = 60 - configScrollOffset;
        for (int i = 0; i < currentConfigGroup.forms.size(); i++) {
            ConfigForm form = currentConfigGroup.forms.get(i);
            int fy = startY + getFormY(i);
            int fh = getFormHeight(form);
            if (fy + fh < 10 || fy > height - 30) continue;
            if ("checkbox".equals(form.type) && pButton == 0) {
                int cbY = fy + 22;
                if (pMouseX >= panelX && pMouseX <= panelX + 14 && pMouseY >= cbY && pMouseY <= cbY + 14) {
                    handleCheckboxChange(i, -1);
                    mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
                }
            } else if ("range".equals(form.type) && pButton == 0) {
                int sliderY = fy + 28;
                if (pMouseY >= sliderY - 6 && pMouseY <= sliderY + 6) {
                    handleRangeChange(i, pMouseX, panelX, panelW);
                    mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
                }
            } else if ("radio".equals(form.type) && pButton == 0) {
                int yOff = fy + 24;
                for (Map.Entry<String, String> label : form.labels.entrySet()) {
                    if (pMouseX >= panelX && pMouseX <= panelX + panelW && pMouseY >= yOff && pMouseY <= yOff + 14) {
                        handleRadioChange(i, label.getValue());
                        mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
                        break;
                    }
                    yOff += 14;
                }
            }
        }
    }

    /**
     * Sets a roaming Molang variable. The animation system reads these from
     * OpenYsmPlayerControllerRuntime.RuntimeState.variables with the "v."
     * prefix stripped (e.g. "roaming.ef" not "v.roaming.ef").
     * Supports "v.name=value" (assignment) and "v.name" (toggle 0↔1).
     */
    private static void setMolangVar(String expression) {
        if (StringUtils.isBlank(expression)) return;
        expression = expression.trim();
        String varName;
        double value = Double.NaN; // NaN means toggle

        int eqIdx = expression.indexOf('=');
        if (eqIdx > 0) {
            varName = expression.substring(0, eqIdx).trim();
            String valStr = expression.substring(eqIdx + 1).trim();
            try {
                value = Double.parseDouble(valStr);
            } catch (NumberFormatException e) {
                value = Double.NaN;
            }
        } else {
            varName = expression;
        }

        // Strip "v." prefix for the roaming variable name
        String roamingName = varName.startsWith("v.") ? varName.substring(2) : varName;
        double oldValue = OpenYsmPlayerControllerRuntime.PENDING_ROAMING
            .getOrDefault(roamingName, 0.0);
        double newValue = Double.isNaN(value) ? (oldValue > 0 ? 0 : 1) : value;
        OpenYsmPlayerControllerRuntime.PENDING_ROAMING.put(roamingName, newValue);
    }

    /** Gets the current value of a roaming Molang variable. */
    private static double getMolangVar(String expression) {
        if (StringUtils.isBlank(expression)) return 0;
        String varName = expression.contains("=") ? expression.substring(0, expression.indexOf('=')).trim() : expression.trim();
        String roamingName = varName.startsWith("v.") ? varName.substring(2) : varName;
        Double v = OpenYsmPlayerControllerRuntime.PENDING_ROAMING.get(roamingName);
        return v != null ? v : 0;
    }

    private void handleRangeChange(int formIndex, int mouseX, int panelX, int panelW) {
        ConfigForm form = currentConfigGroup.forms.get(formIndex);
        float pct = (mouseX - panelX) / (float) panelW;
        pct = Math.max(0, Math.min(1, pct));
        double raw = form.min + (form.max - form.min) * pct;
        double stepped;
        if (form.step > 0) {
            stepped = Math.round(raw / form.step) * form.step;
        } else {
            stepped = raw;
        }
        stepped = Math.max(form.min, Math.min(form.max, stepped));
        setMolangVar(form.defaultValue + "=" + stepped);
    }

    private void handleCheckboxChange(int formIndex, double value) {
        ConfigForm form = currentConfigGroup.forms.get(formIndex);
        if ("checkbox".equals(form.type)) {
            String expr = form.defaultValue;
            String toExec = expr;
            if (value >= 0 && expr.contains("=")) {
                String[] parts = expr.split("=", 2);
                toExec = parts[0] + "=" + String.valueOf((int) value);
            }
            setMolangVar(toExec);
        }
    }

    private void handleRadioChange(int formIndex, String expression) {
        ConfigForm form = currentConfigGroup.forms.get(formIndex);
        if (StringUtils.isNotBlank(expression)) {
            setMolangVar(expression);
        }
    }

    private void drawConfigPanel(int mouseX, int mouseY) {
        drawDefaultBackground();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        int panelX = width / 2 - 100;
        int panelW = 200;
        int panelY = 30;

        drawRect(panelX - 10, panelY - 10, panelX + panelW + 10, height - 50, 0xCC222222);

        String title = StringUtils.isNotBlank(currentConfigGroup.name) ? currentConfigGroup.name : currentConfigGroup.id;
        drawCenteredString(fontRendererObj, "[CFG] " + title, width / 2, panelY, 0xFFB100);

        if (StringUtils.isNotBlank(currentConfigGroup.description)) {
            drawCenteredString(fontRendererObj, currentConfigGroup.description, width / 2, panelY + 12, 0xAAAAAA);
        }

        drawCenteredString(fontRendererObj, "Right-click to close", width / 2, height - 25, 0x666666);

        int startY = panelY + 30 - configScrollOffset;

        for (int i = 0; i < currentConfigGroup.forms.size(); i++) {
            ConfigForm form = currentConfigGroup.forms.get(i);
            int fy = startY + getFormY(i);
            int fh = getFormHeight(form);
            if (fy + fh < panelY || fy > height - 40) continue;

            drawRect(panelX, fy - 2, panelX + panelW, fy, 0x44FFFFFF);
            drawString(fontRendererObj, form.title, panelX, fy, 0xF3EFE0);
            if (StringUtils.isNotBlank(form.description)) {
                drawString(fontRendererObj, form.description, panelX, fy + 10, 0x888888);
            }

            if ("checkbox".equals(form.type)) {
                int cbY = fy + 22;
                boolean hovered = mouseX >= panelX && mouseX <= panelX + 14 && mouseY >= cbY && mouseY <= cbY + 14;
                int boxColor = hovered ? 0xFFFFFF00 : 0xAAFFFFFF;
                drawRect(panelX, cbY, panelX + 14, cbY + 14, boxColor);
                boolean checked = getMolangVar(form.defaultValue) > 0;
                drawString(fontRendererObj, checked ? "[x]" : "[ ]", panelX + 18, cbY, checked ? 0x55FF55 : 0xF3EFE0);
            } else if ("range".equals(form.type)) {
                int sliderY = fy + 28;
                boolean hovered = mouseY >= sliderY - 6 && mouseY <= sliderY + 6;
                drawRect(panelX, sliderY - 2, panelX + panelW, sliderY + 2, hovered ? 0xAAFFFF00 : 0xAA888888);
                double curVal = getMolangVar(form.defaultValue);
                // Clamp to min/max, snap to step
                curVal = Math.max(form.min, Math.min(form.max, curVal));
                if (form.step > 0) {
                    curVal = Math.round(curVal / form.step) * form.step;
                }
                float pct = form.max > form.min ? (float) ((curVal - form.min) / (form.max - form.min)) : 0.5f;
                pct = Math.max(0, Math.min(1, pct));
                int thumbX = panelX + (int) (panelW * pct);
                // Draw thumb indicator: colored triangle and bright bar
                drawCenteredString(fontRendererObj, "^", thumbX, sliderY - 9, 0xFFB100);
                drawRect(thumbX - 4, sliderY - 3, thumbX + 4, sliderY + 3, 0xFFFFAA00);
                // Determine decimal places from step
                int decimals = form.step > 0 ? Math.max(0, (int) Math.ceil(-Math.log10(form.step))) : 2;
                String valStr = String.format("%." + decimals + "f", curVal);
                drawString(fontRendererObj, String.format("%." + decimals + "f", form.min), panelX, sliderY + 6, 0x888888);
                String maxStr = String.format("%." + decimals + "f", form.max);
                drawString(fontRendererObj, maxStr, panelX + panelW - fontRendererObj.getStringWidth(maxStr), sliderY + 6, 0x888888);
                drawCenteredString(fontRendererObj, valStr, panelX + panelW / 2, sliderY + 6, 0xFFFF55);
            } else if ("radio".equals(form.type)) {
                double curVal = getMolangVar(form.defaultValue);
                int yOff = fy + 24;
                for (Map.Entry<String, String> label : form.labels.entrySet()) {
                    boolean hovered = mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= yOff && mouseY <= yOff + 14;
                    int color = hovered ? 0xFFB100 : 0xF3EFE0;
                    // Check if this option matches current value
                    boolean selected = false;
                    String optExpr = label.getValue();
                    if (StringUtils.isNotBlank(optExpr) && optExpr.contains("=")) {
                        String valStr = optExpr.substring(optExpr.indexOf('=') + 1).trim();
                        try {
                            selected = Math.abs(curVal - Double.parseDouble(valStr)) < 0.001;
                        } catch (NumberFormatException e) {
                            // not a numeric comparison
                        }
                    }
                    drawString(fontRendererObj, selected ? "(\u2713) " + label.getKey() : "( ) " + label.getKey(), panelX, yOff, selected ? 0x55FF55 : color);
                    yOff += 14;
                }
            }
        }
        GL11.glDisable(GL11.GL_BLEND);
    }

    // ── Wheel Rendering ───────────────────────────────────────────

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
        int hoveredIndex = getHoveredIndex(theta, count);

        for (int i = 0; i < count; i++) {
            float spacingDeg = (float) (Math.PI / 90);
            float startDeg = (float) ((2 * Math.PI / count) * i + spacingDeg);
            float endDeg = (float) ((2 * Math.PI / count) * (i + 1) - spacingDeg);
            boolean hovered = i == hoveredIndex && distance > INNER_RING_MIN && distance < 100;
            int innerColor = 0x90000000;
            int outerColor = 0x90000000;
            if (hovered) {
                if (distance <= INNER_RING_MAX) {
                    innerColor = 0xf0FFB100; // gold inner ring
                } else {
                    outerColor = 0xf0FFB100; // gold outer ring
                }
            }
            drawFan(tessellator, INNER_RING_MIN, INNER_RING_MAX, startDeg, endDeg, innerColor);
            drawFan(tessellator, INNER_RING_MAX, 105, startDeg, endDeg, outerColor);
        }
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
