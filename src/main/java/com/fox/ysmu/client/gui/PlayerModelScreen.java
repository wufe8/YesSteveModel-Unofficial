package com.fox.ysmu.client.gui;

import com.fox.ysmu.Tags;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.eep.ExtendedStarModels;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.gui.button.*;
import com.fox.ysmu.util.ModelIdUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.*;
import net.minecraft.entity.player.EntityPlayer;
import org.apache.commons.lang3.tuple.Pair;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

import com.fox.ysmu.ysmu;

public class PlayerModelScreen extends GuiScreen {
    protected final EntityPlayer player;
    private Map<ResourceLocation, List<ResourceLocation>> models = Maps.newHashMap();
    private List<ResourceLocation> modelOrderList;
    private int maxPage;
    private GuiTextField textField;
    private Category category;
    private int page;
    private int x;
    private int y;
    private int lastClientModelCount = -1;
    private boolean requestedModelSync;
    /** 当前选中的模型包名称。null = 不限制/包列表模式。 */
    private String selectedPack;
    /** 是否正在显示模型包列表（而不是模型列表）。 */
    private boolean showingPacks;

    public PlayerModelScreen() {
        this.category = Category.ALL;
        this.player = Minecraft.getMinecraft().thePlayer;
    }

    public PlayerModelScreen(EntityPlayer player) {
        this.category = Category.ALL;
        this.player = player;
    }

    private void calculateModelList() {
        models = Maps.newHashMap();
        Map<ResourceLocation, List<ResourceLocation>> source;
        if (this.selectedPack != null) {
            // 包模式：只显示选中包内的模型
            List<ResourceLocation> packModels = ClientModelManager.MODEL_PACKS.get(this.selectedPack);
            source = Maps.newHashMap();
            if (packModels != null) {
                for (ResourceLocation id : packModels) {
                    List<ResourceLocation> tex = ClientModelManager.MODELS.get(id);
                    if (tex != null) source.put(id, tex);
                }
            }
        } else {
            source = ClientModelManager.MODELS;
        }

        if (this.category == Category.ALL) {
            if (this.selectedPack == null) {
                // 全部模型模式：排除属于包内的模型
                for (Map.Entry<ResourceLocation, List<ResourceLocation>> entry : source.entrySet()) {
                    String pack = ClientModelManager.MODEL_PACK_OF.get(entry.getKey());
                    if (pack == null) {
                        this.models.put(entry.getKey(), entry.getValue());
                    }
                }
            } else {
                this.models.putAll(source);
            }
        }
        if (this.category == Category.STAR) {
            ExtendedStarModels eep = ExtendedStarModels.get(this.player);
            if (eep != null) {
                for (ResourceLocation modelId : source.keySet()) {
                    if (eep.containModel(modelId)) {
                        List<ResourceLocation> tex = source.get(modelId);
                        if (tex != null) this.models.put(modelId, tex);
                    }
                }
            }
        }
        if (textField != null && !showingPacks) {
            String search = this.textField.getText().toLowerCase(Locale.US);
            models.entrySet()
                .removeIf(next -> !ModelIdUtil.getModelDisplayName(next.getKey())
                    .toLowerCase(Locale.US)
                    .contains(search));
        }
        this.modelOrderList = Lists.newArrayList(models.keySet());
        this.modelOrderList.sort(
            Comparator.comparing(modelId -> ModelIdUtil.getModelDisplayName(modelId).toLowerCase(Locale.US)));
        this.maxPage = (models.size() - 1) / 10;
    }

    @Override
    public void initGui() {
        // clearWidgets() -> buttonList.clear()
        this.buttonList.clear();
        if (ClientModelManager.MODELS.isEmpty() && !this.requestedModelSync) {
            this.requestedModelSync = true;
            ClientModelManager.sendSyncModelMessage();
        }

        // 决定显示模式：有包且未选择包时显示包列表，否则显示模型列表
        boolean hasPacks = !ClientModelManager.MODEL_PACKS.isEmpty();
        this.showingPacks = hasPacks && this.selectedPack == null && this.category == Category.ALL;

        this.calculateModelList();
        this.lastClientModelCount = ClientModelManager.MODELS.size();

        this.x = (width - 420) / 2;
        this.y = (height - 235) / 2;

        String perText = "";
        boolean focus = false;
        if (textField != null && !showingPacks) {
            perText = textField.getText();
            focus = textField.isFocused();
        }
        textField = new GuiTextField(this.fontRendererObj, x + 144, y + 6, 158, 16);
        if (!showingPacks) {
            textField.setText(perText);
            textField.setFocused(focus);
            textField.setCursorPositionEnd();
        }
        textField.setTextColor(0xF3EFE0);

        // 按钮创建和点击逻辑分离 使用唯一的 ID 来标识按钮
        // addRenderableWidget -> this.buttonList.add
        this.buttonList.add(new TextureCountButton(0, x + 5, y + 5));
        this.buttonList.add(new FlatIconButton(1, x + 28, y + 5, 79, 20, 32, 16).setTooltips("gui.yes_steve_model.model.texture"));
        this.buttonList.add(new StarButton(2, x + 110, y + 5));
        // 返回按钮：位于收藏按钮下方，选择包时可见，用返回箭头图标(48,0)
        FlatIconButton backBtn = new FlatIconButton(4, x + 88, y + 27, 42, 20, 0, 32);
        backBtn.setTooltips("gui.yes_steve_model.model.return");
        backBtn.visible = selectedPack != null;
        this.buttonList.add(backBtn);
        this.buttonList.add(new FlatIconButton(3, x + 328, y + 5, 18, 18, 32, 0).setTooltips("gui.yes_steve_model.all_models"));
        this.buttonList.add(new FlatIconButton(5, x + 308, y + 5, 18, 18, 0, 0).setTooltips("gui.yes_steve_model.star_models"));
        this.buttonList.add(new FlatIconButton(6, x + 397, y + 5, 18, 18, 16, 16).setTooltips("gui.yes_steve_model.config"));
        this.buttonList.add(new FlatIconButton(8, x + 377, y + 5, 18, 18, 80, 0).setTooltips("gui.yes_steve_model.open_model_folder.open"));
        this.buttonList.add(new FlatColorButton(9, x + 198, y + 215, 52, 14, I18n.format("gui.yes_steve_model.pre_page")));
        this.buttonList.add(new FlatColorButton(10, x + 308, y + 215, 52, 14, I18n.format("gui.yes_steve_model.next_page")));

        // 收集包文件夹和模型列表，混合显示
        java.util.List<Object> allItems = new java.util.ArrayList<>();
        if (showingPacks) {
            // 先显示所有包文件夹
            allItems.addAll(ClientModelManager.MODEL_PACKS.entrySet());
            // 再显示非包模型
            for (ResourceLocation id : modelOrderList) {
                allItems.add(id);
            }
        } else {
            allItems.addAll(modelOrderList);
        }
        this.maxPage = Math.max(0, (allItems.size() - 1) / 10);
        if (this.page > this.maxPage) this.page = 0;

        int buttonId = 11;
        int packBtnId = 101;
        int startIdx = this.page * 10;
        int endIdx = Math.min(startIdx + 10, allItems.size());
        for (int i = startIdx; i < endIdx; i++) {
            int gridIdx = i - startIdx;
            int xStart = x + 143 + 55 * (gridIdx % 5);
            int yStart = y + 28 + 93 * (gridIdx / 5);
            Object item = allItems.get(i);
            if (item instanceof Map.Entry) {
                @SuppressWarnings("unchecked")
                Map.Entry<String, List<ResourceLocation>> packEntry = (Map.Entry<String, List<ResourceLocation>>) item;
                // Find ClientPackData for this pack by matching display name
                ClientModelManager.ClientPackData cpd = null;
                for (ClientModelManager.ClientPackData p : ClientModelManager.CLIENT_PACKS.values()) {
                    if (p.getDisplayName().equals(packEntry.getKey())) {
                        cpd = p;
                        break;
                    }
                }
                this.buttonList.add(new PackFolderButton(packBtnId++, xStart, yStart,
                    packEntry.getKey(), packEntry.getValue().size(), cpd));
            } else if (item instanceof ResourceLocation) {
                ResourceLocation id = (ResourceLocation) item;
                ModelButton mb = new ModelButton(buttonId++, xStart, yStart,
                    Pair.of(id, ClientModelManager.MODELS.get(id)),
                    ClientModelManager.EXTRA_INFO.get(ModelIdUtil.getMainId(id)), player);
                mb.displayString = getModelDisplayText(id);
                this.buttonList.add(mb);
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                break;
            case 1:
                ExtendedModelInfo eep = ExtendedModelInfo.get(player);
                if (eep != null) {
                    List<ResourceLocation> textures = ClientModelManager.MODELS.get(eep.getModelId());
                    if (textures != null) {
                        // setScreen -> displayGuiScreen
                        this.mc.displayGuiScreen(new PlayerTextureScreen(this, eep.getModelId(), textures));
                    }
                }
                break;
            case 2:
                if (button instanceof StarButton) {
                    ((StarButton) button).doPress();
                }
                break;
            case 4:
                // 返回：从包浏览回到包列表，或从包列表回到所有模型
                if (selectedPack != null) {
                    selectedPack = null;
                    page = 0;
                    initGui();
                }
                break;
            case 3:
                if (this.category != Category.ALL || this.selectedPack != null || this.showingPacks) {
                    this.category = Category.ALL;
                    this.selectedPack = null;
                    this.page = 0;
                    this.initGui();
                }
                break;
            case 5:
                if (this.category != Category.STAR || this.selectedPack != null) {
                    this.category = Category.STAR;
                    this.selectedPack = null;
                    this.page = 0;
                    this.initGui();
                }
                break;
            case 6:
                this.mc.displayGuiScreen(new ConfigScreen(this));
                break;
            case 8:
                this.mc.displayGuiScreen(new OpenModelFolderScreen(this));
                break;
            case 9:
                if (this.page > 0) {
                    this.page--;
                    this.initGui();
                }
                break;
            case 10:
                if (this.page < this.maxPage) {
                    this.page++;
                    this.initGui();
                }
                break;
            default:
                if (button instanceof ModelButton) {
                    ((ModelButton) button).doPress();
                } else if (button instanceof PackFolderButton) {
                    this.selectedPack = ((PackFolderButton) button).packName;
                    this.page = 0;
                    this.initGui();
                }
                break;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // renderBackground(graphics) -> drawDefaultBackground()
        this.drawDefaultBackground();

        this.drawGradientRect(x, y, x + 135, y + 235, 0xff_222222, 0xff_222222);
        this.drawGradientRect(x + 138, y, x + 420, y + 235, 0xff_222222, 0xff_222222);
        this.drawGradientRect(x + 351, y + 7, x + 352, y + 21, 0xFF_F3EFE0, 0xFF_F3EFE0);
        // textField.render -> textField.drawTextBox
        textField.drawTextBox();

        int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        int scissorX = (this.x + 5) * scale;
        int scissorY = mc.displayHeight - ((this.y + 200) * scale);
        int scissorW = 125 * scale;
        int scissorH = 171 * scale;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(scissorX, scissorY, scissorW, scissorH);
        // func_147046_a(x,y,scale,toMouseX,toMouseY,entity)
        GuiInventory.func_147046_a(x + 67, y + 190, 70, x + 67 - mouseX, y + 180 - 95 - mouseY, player);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep != null) {
            String modelName = getModelDisplayText(eep.getModelId());
            // font -> fontRendererObj
            List<String> modelNameSplit = fontRendererObj.listFormattedStringToWidth(modelName, 125);
            int lineY = y + 205;
            for (String line : modelNameSplit) {
                int nameWidth = fontRendererObj.getStringWidth(line);
                this.drawString(fontRendererObj, line, x + (135 - nameWidth) / 2, lineY, 0xF3EFE0);
                lineY += 10;
            }
        }

        if (selectedPack != null) {
            // 在搜索框位置显示当前包名称
            String packLabel = I18n.format("gui.yes_steve_model.model_manage.type.folder") + ": " + selectedPack;
            this.drawString(fontRendererObj, packLabel, x + 144, y + 8, 0x55FFFF);
        } else if (textField.getText().isEmpty() && !textField.isFocused()) {
            this.drawString(fontRendererObj, EnumChatFormatting.ITALIC + I18n.format("gui.yes_steve_model.search"), x + 148, y + 10, 0x777777);
        }

        String pageInfo = String.format("%d/%d", page + 1, this.maxPage + 1);
        this.drawString(fontRendererObj, pageInfo, x + 138 + (282 - fontRendererObj.getStringWidth(pageInfo)) / 2, y + 223 - fontRendererObj.FONT_HEIGHT / 2, 0xF3EFE0);

        String debugInfo = String.format("%s-%s", "1.7.10", Tags.VERSION);
        this.drawString(fontRendererObj, debugInfo, x + 2, y + 226, 0x555555);
        // super.render -> super.drawScreen, 这会绘制所有按钮
        super.drawScreen(mouseX, mouseY, partialTicks);
        // Render tooltips
        for (Object button : this.buttonList) {
            if (button instanceof FlatIconButton f) {
                if (f.func_146115_a() && f.tooltips != null && !f.tooltips.isEmpty()) {
                    this.func_146283_a(f.tooltips, mouseX, mouseY);
                }
            }
            if (button instanceof ModelButton m) {
                if (m.func_146115_a() && m.tooltips != null && !m.tooltips.isEmpty()) {
                    List<String> tooltipStrings = m.tooltips.stream().map(IChatComponent::getFormattedText).collect(Collectors.toList());
                    // Append model stats (bones, faces, animations) to tooltip
                    ResourceLocation mainId = ModelIdUtil.getMainId(m.modelInfo.getLeft());
                    int[] stats = ClientModelManager.MODEL_STATS.get(mainId);
                    if (stats != null) {
                        tooltipStrings.add(I18n.format("gui.yes_steve_model.model.stats", stats[0], stats[1], stats[2]));
                    }
                    this.func_146283_a(tooltipStrings, mouseX, mouseY);
                }
            }
        }
    }

    // tick -> updateScreen
    @Override
    public void updateScreen() {
        this.textField.updateCursorCounter();
        int currentModelCount = ClientModelManager.MODELS.size();
        if (currentModelCount != this.lastClientModelCount) {
            this.initGui();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        // Right-click anywhere goes back (same as pressing the back button).
        if (button == 1) {
            if (selectedPack != null) {
                selectedPack = null;
                page = 0;
                initGui();
            } else if (showingPacks) {
                showingPacks = false;
                category = Category.ALL;
                selectedPack = null;
                page = 0;
                initGui();
            }
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
        this.textField.mouseClicked(mouseX, mouseY, button);
    }

    // charTyped and keyPressed -> keyTyped
    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        String perText = this.textField.getText();
        if (this.textField.textboxKeyTyped(typedChar, keyCode)) {
            if (!Objects.equals(perText, this.textField.getText())) {
                this.page = 0;
                this.initGui();
            }
        } else {
            if (this.textField.isFocused() && keyCode != 1) {
                return; // 阻止其他按键（如E键）关闭GUI
            }
            super.keyTyped(typedChar, keyCode);
        }
    }

    // mouseScrolled -> handleMouseInput
    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int dWheel = Mouse.getDWheel();
        if (dWheel != 0) {
            int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
            int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            if (inRange(mouseX, mouseY)) {
                scrollPage(dWheel);
            }
        }
    }

    private boolean inRange(int mouseX, int mouseY) {
        boolean isInWidthRange = (x + 143) < mouseX && mouseX < (x + 430);
        boolean isInHeightRange = (y + 25) < mouseY && mouseY < (y + 235);
        return isInWidthRange && isInHeightRange;
    }

    private void scrollPage(int delta) {
        if (delta > 0 && this.page > 0) {
            this.page--;
            this.mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
            this.initGui();
        }
        if (delta < 0 && this.page < this.maxPage) {
            this.page++;
            this.mc.getSoundHandler().playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
            this.initGui();
        }
    }

    /** Returns the best display name for a model: ysm.json metadata.name if available, else decoded path. */
    private static String getModelDisplayText(ResourceLocation modelId) {
        // MODEL_DISPLAY_NAMES is keyed by main ID (with /main suffix)
        ResourceLocation mainId = ModelIdUtil.getMainId(modelId);
        String name = ClientModelManager.MODEL_DISPLAY_NAMES.get(mainId);
        if (name != null) return name;
        // Fallback: strip pack prefix if present
        String display = ModelIdUtil.getModelDisplayName(modelId);
        int slash = display.indexOf('/');
        return slash >= 0 ? display.substring(slash + 1) : display;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** 模型包文件夹按钮，点击后进入该包查看其下的模型。 */
    private static class PackFolderButton extends GuiButton {
        final String packName;
        final int modelCount;
        @Nullable
        private final ClientModelManager.ClientPackData packData;
        @Nullable
        private ResourceLocation iconLocation;

        PackFolderButton(int id, int pX, int pY, String packName, int modelCount,
            @Nullable ClientModelManager.ClientPackData packData) {
            super(id, pX, pY, 52, 90, packName);
            this.packName = packName;
            this.modelCount = modelCount;
            this.packData = packData;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!this.visible) return;
            this.field_146123_n = mouseX >= this.xPosition && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;

            // 文件夹背景
            this.drawGradientRect(this.xPosition, this.yPosition,
                this.xPosition + this.width, this.yPosition + this.height,
                0xFF_3A3A3A, 0xFF_3A3A3A);

            int cx = this.xPosition + this.width / 2;
            int iconX = this.xPosition;
            int iconY = this.yPosition;
            int iconDrawW = this.width;
            int iconDrawH = this.height;
            boolean drewIcon = false;

            // 尝试绘制 ysm-pack.png 图标
            if (packData != null && packData.iconData != null && packData.iconData.length > 0) {
                if (iconLocation == null) {
                    java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(packData.iconData);
                    try {
                        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(bis);
                        if (img != null) {
                            iconLocation = mc.getTextureManager().getDynamicTextureLocation(
                                "ysmu_pack_" + packName,
                                new net.minecraft.client.renderer.texture.DynamicTexture(img));
                        }
                    } catch (java.io.IOException ignored) {}
                }
                if (iconLocation != null) {
                    drawIcon(mc, iconLocation, iconX, iconY, iconDrawW, iconDrawH);
                    drewIcon = true;
                }
            }
            // Fallback: try loading ysm-pack.png directly from the pack folder
            if (!drewIcon) {
                try {
                    java.io.File packDir = new java.io.File(
                        net.minecraft.client.Minecraft.getMinecraft().mcDataDir,
                        "config/ysmu/custom/" + packName);
                    java.io.File iconFile = new java.io.File(packDir, "ysm-pack.png");
                    if (iconFile.isFile()) {
                        if (iconLocation == null) {
                            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(iconFile);
                            if (img != null) {
                                iconLocation = mc.getTextureManager().getDynamicTextureLocation(
                                    "ysmu_pack_fs_" + packName,
                                    new net.minecraft.client.renderer.texture.DynamicTexture(img));
                            }
                        }
                        if (iconLocation != null) {
                            drawIcon(mc, iconLocation, iconX, iconY, iconDrawW, iconDrawH);
                            drewIcon = true;
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (!drewIcon) {
                // Fallback: try built-in default pack icon (OpenYSM-compatible)
                try {
                    ResourceLocation defaultIcon = new ResourceLocation(ysmu.MODID, "texture/default_pack_icon.png");
                    drawIcon(mc, defaultIcon, iconX, iconY, iconDrawW, iconDrawH);
                    drewIcon = true;
                } catch (Exception ignored) {}
            }

            if (!drewIcon) {
                // 文件夹图标 - 绘制一个简单的文件夹形状
                iconY = this.yPosition + 15;
                // 文件夹主体
                drawRect(cx - 12, iconY - 4, cx + 12, iconY + 20, 0xFF_F3EFE0);
                drawRect(cx - 12, iconY - 4, cx - 4, iconY + 2, 0xFF_F3EFE0);
                // 文件夹标签
                drawRect(cx - 4, iconY - 2, cx + 12, iconY + 2, 0xFF_F3EFE0);
                // 半透明覆盖
                drawGradientRect(this.xPosition, this.yPosition,
                    this.xPosition + this.width, this.yPosition + this.height,
                    0x40_000000, 0x60_000000);
            }

            // 包名
            FontRenderer font = mc.fontRenderer;
            List<String> split = font.listFormattedStringToWidth(this.displayString, 45);
            int textY = this.yPosition + this.height - 28;
            if (split.size() > 1) {
                this.drawCenteredString(font, split.get(0), cx, textY, 0xF3EFE0);
                this.drawCenteredString(font, split.get(1), cx, textY + 10, 0xF3EFE0);
            } else {
                this.drawCenteredString(font, this.displayString, cx, textY + 5, 0xF3EFE0);
            }

            // 模型数量
            String countStr = I18n.format("gui.yes_steve_model.model_count", modelCount);
            this.drawCenteredString(font, countStr, cx, this.yPosition + this.height - 12, 0x888888);

            // 悬停边框
            if (this.field_146123_n) {
                this.drawGradientRect(this.xPosition, this.yPosition + 1,
                    this.xPosition + 1, this.yPosition + this.height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
                this.drawGradientRect(this.xPosition, this.yPosition,
                    this.xPosition + this.width, this.yPosition + 1, 0xff_F3EFE0, 0xff_F3EFE0);
                this.drawGradientRect(this.xPosition + this.width - 1, this.yPosition + 1,
                    this.xPosition + this.width, this.yPosition + this.height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
                this.drawGradientRect(this.xPosition, this.yPosition + this.height - 1,
                    this.xPosition + this.width, this.yPosition + this.height, 0xff_F3EFE0, 0xff_F3EFE0);
            }
        }

        /** Draw a textured rectangle with UV 0-1 (correct for any texture size). */
        private static void drawIcon(Minecraft mc, ResourceLocation tex, int x, int y, int w, int h) {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            mc.getTextureManager().bindTexture(tex);
            Tessellator tessellator = Tessellator.instance;
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            tessellator.startDrawingQuads();
            tessellator.addVertexWithUV(x,       y + h, 0, 0, 1);
            tessellator.addVertexWithUV(x + w,   y + h, 0, 1, 1);
            tessellator.addVertexWithUV(x + w,   y,     0, 1, 0);
            tessellator.addVertexWithUV(x,       y,     0, 0, 0);
            tessellator.draw();
            GL11.glDisable(GL11.GL_BLEND);
        }
    }

    private enum Category {
        /**
         * 不同页面类别
         */
        ALL, STAR
    }
}
