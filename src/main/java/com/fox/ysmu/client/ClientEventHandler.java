package com.fox.ysmu.client;

import com.fox.ysmu.client.gui.ExtraPlayerConfigScreen;
import com.fox.ysmu.client.compat.AngelicaCompat;
import com.fox.ysmu.client.renderer.CustomPlayerRenderer;
import com.fox.ysmu.client.renderer.FirstPersonHandRenderer;
import com.fox.ysmu.client.renderer.HudPreviewCache;
import com.fox.ysmu.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.animation.RemotePlayerAnimationQueries;
import com.fox.ysmu.client.animation.RemotePlayerMotionStates;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.renderer.CustomPlayerRenderer;
import com.fox.ysmu.data.NPCData;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.event.api.SpecialPlayerRenderEvent;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SetPlayAnimation;
import com.fox.ysmu.util.ModelIdUtil;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;

@EventBusSubscriber(side = Side.CLIENT)
public class ClientEventHandler {

    private static boolean EXTRA_PLAYER = false;
    private static boolean pendingModelLoad;
    /** Whether the welcome message has been shown this session. */
    private static boolean welcomeShown = false;

    /** Cached HUD player preview – avoids re-rendering the full GeckoLib pipeline every frame. */
    private static final HudPreviewCache hudPreviewCache = new HudPreviewCache();

    @SubscribeEvent
    public static void onTextureStitchEventPost(TextureStitchEvent.Post event) {
        if (event.map.getTextureType() == 0) {
            pendingModelLoad = true;
        }
    }

    /** Tick counter for periodic lazy-animation unload (~every 100 ticks = 5s). */
    private static int unloadTick = 0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // Periodically unload unused animation data from GeckoLibCache
        if (++unloadTick >= 1200) {
            unloadTick = 0;
            ClientModelManager.unloadUnusedAnimations();
            DirectBufferWatchdog.tick();
        }
        if (!pendingModelLoad) {
            return;
        }
        pendingModelLoad = false;

        // TextureStitchEvent.Post fires while TextureManager is still reloading
        // its texture map. Registering model textures there mutates the same
        // map and can make GTNH disable all user resource packs after a CME.
        //
        // Only load the default model here (needed for menu GUI previews).
        // Loading all cached models is redundant — the sync protocol (legacy or
        // OpenYSM) handles that when the player joins a world, and would
        // overwrite everything anyway.
        ClientModelManager.loadDefaultModel();
    }

    @SubscribeEvent
    public static void onClientPlayerJoinWorld(EntityJoinWorldEvent event) {
        if (!event.world.isRemote || !(event.entity instanceof EntityClientPlayerMP)) {
            return;
        }
        RemotePlayerAnimationQueries.clear();
        if (!Config.ENABLE_SYNC_PROTOCOL) {
            ClientModelManager.sendSyncModelMessage();
        }
        // Show welcome/info message once per session
        if (Config.SHOW_WELCOME_MESSAGE && !welcomeShown) {
            welcomeShown = true;
            Minecraft mc = Minecraft.getMinecraft();
            boolean isChinese = mc.getLanguageManager().getCurrentLanguage().getLanguageCode().startsWith("zh");
            mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentTranslation(
                "commands.yes_steve_model.welcome", com.fox.ysmu.Tags.VERSION));
            if (com.fox.ysmu.Config.HIGH_VERSION_GAME_PATH.isEmpty()) {
                mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentTranslation(
                    "commands.yes_steve_model.welcome.sound_hint"));
            }
            // Disable hint with clickable [Click Here] — runs /ysm welcome off
            net.minecraft.util.ChatComponentText disableMsg = new net.minecraft.util.ChatComponentText(
                net.minecraft.util.EnumChatFormatting.GRAY + (isChinese ? "您可以点击 " : "Click ") +
                net.minecraft.util.EnumChatFormatting.GREEN + "" + net.minecraft.util.EnumChatFormatting.UNDERLINE +
                (isChinese ? "[关闭此消息]" : "[Disable]") +
                net.minecraft.util.EnumChatFormatting.RESET + "" + net.minecraft.util.EnumChatFormatting.GRAY +
                (isChinese ? " 关闭欢迎消息，或输入 " : " to disable the welcome message, or type ") +
                net.minecraft.util.EnumChatFormatting.YELLOW + "/ysm welcome off" +
                net.minecraft.util.EnumChatFormatting.GRAY + (isChinese ? "。" : "."));
            disableMsg.getChatStyle().setChatClickEvent(new net.minecraft.event.ClickEvent(
                net.minecraft.event.ClickEvent.Action.RUN_COMMAND, "/ysm welcome off"));
            mc.thePlayer.addChatMessage(disableMsg);
        }
    }

    @SubscribeEvent
    public static void onRenderPlayer(SpecialPlayerRenderEvent event) {
        EntityPlayer player = event.getPlayer();
        CustomPlayerEntity animatable = event.getCustomPlayer();
        if (isVanillaPlayer(event.getModelId()) && player instanceof AbstractClientPlayer clientPlayer) {
            animatable.setPlayer(player);
            animatable.setMainModel(ModelIdUtil.getMainId(event.getModelId()));
            ResourceLocation location = clientPlayer.getLocationSkin();
            animatable.setTexture(location);
        }
    }

    @SubscribeEvent
    public static void onRender(RenderPlayerEvent.Pre event) {
        EntityPlayer player = event.entityPlayer;
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP playerSelf = mc.thePlayer;
        if (player.equals(playerSelf) && Config.DISABLE_SELF_MODEL) {
            return;
        }
        if (!player.equals(playerSelf) && Config.DISABLE_OTHER_MODEL) {
            return;
        }
        event.setCanceled(true);
        CustomPlayerRenderer renderer = ClientProxy.getInstance();
        if ((mc.currentScreen != null || EXTRA_PLAYER) && player.equals(playerSelf)) {
            renderSelfGuiPlayer(renderer, player, event.partialRenderTick);
        } else {
            float partialTicks = event.partialRenderTick;
            double ix = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
            double iy = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks;
            double iz = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
            renderer.doRender(
                player,
                ix - RenderManager.renderPosX,
                iy - RenderManager.renderPosY - player.yOffset,
                iz - RenderManager.renderPosZ,
                player.rotationYaw,
                partialTicks);
        }
    }

    private static void renderSelfGuiPlayer(CustomPlayerRenderer renderer, EntityPlayer player, float partialTicks) {
        PlayerPreviousRotationSnapshot snapshot = PlayerPreviousRotationSnapshot.capture(player);
        try {
            RenderUtil.withGuiEntityLighting(() -> renderer.doRender(
                player,
                0,
                0 - player.yOffset,
                0,
                player.rotationYaw,
                partialTicks));
        } finally {
            snapshot.restore(player);
        }
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        ItemRenderer itemRenderer = mc.entityRenderer.itemRenderer;
        if (AngelicaCompat.usesShaderHandRenderer()) {
            return;
        }
        FirstPersonHandRenderer.tryRender(event, mc, player, itemRenderer);
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (!Config.SHOW_LOADING_PROGRESS) return;
        if (!ClientModelManager.SYNC_IN_PROGRESS) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.fontRenderer == null) return;

        int total = ClientModelManager.SYNC_TOTAL;
        int loaded = ClientModelManager.SYNC_LOADED;
        String currentModel = ClientModelManager.SYNC_CURRENT_MODEL;

        int screenWidth = event.resolution.getScaledWidth();
        int screenHeight = event.resolution.getScaledHeight();

        // Model name text (top line)
        if (!currentModel.isEmpty()) {
            String label = net.minecraft.util.StatCollector.translateToLocal("gui.yes_steve_model.sync_model");
            String modelText = label + currentModel;
            int mx = (screenWidth - mc.fontRenderer.getStringWidth(modelText)) / 2;
            int my = screenHeight - 60;
            mc.fontRenderer.drawStringWithShadow(modelText, mx, my, 0xFFFFFF);
        }

        // Progress bar (determinate when total known, indeterminate otherwise)
        int barWidth = 150;
        int barHeight = 8;
        int barX = (screenWidth - barWidth) / 2;
        int barY = screenHeight - 40;

        net.minecraft.client.gui.Gui.drawRect(barX, barY, barX + barWidth, barY + barHeight, 0xAA222222);
        if (total > 0) {
            int fillWidth = loaded >= total ? barWidth : (int) (barWidth * ((float) loaded / total));
            net.minecraft.client.gui.Gui.drawRect(barX, barY, barX + fillWidth, barY + barHeight, 0xFF44AA44);
        } else {
            // Indeterminate: pulsing highlight (first quarter)
            int pulse = (int) ((System.currentTimeMillis() % 2000L) / 2000.0f * barWidth);
            net.minecraft.client.gui.Gui.drawRect(barX + pulse, barY,
                Math.min(barX + pulse + barWidth / 4, barX + barWidth), barY + barHeight, 0xFF44AA44);
        }

        // Progress text
        String text = total > 0 ? (loaded + " / " + total)
            : net.minecraft.util.StatCollector.translateToLocal("gui.yes_steve_model.sync_waiting");
        int textX = (screenWidth - mc.fontRenderer.getStringWidth(text)) / 2;
        int textY = barY - mc.fontRenderer.FONT_HEIGHT - 2;
        mc.fontRenderer.drawStringWithShadow(text, textX, textY, 0xFFFFFF);
    }

    @SubscribeEvent
    public static void onRenderScreen(RenderGameOverlayEvent.Pre event) {
        if (event.type != RenderGameOverlayEvent.ElementType.HOTBAR) return;
        if (Config.DISABLE_PLAYER_RENDER) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        if (player == null) return;
        if (mc.currentScreen instanceof ExtraPlayerConfigScreen) return;
        double posX = Config.PLAYER_POS_X;
        double posY = Config.PLAYER_POS_Y;
        float scale = (float) Config.PLAYER_SCALE;
        float yawOffset = (float) Config.PLAYER_YAW_OFFSET;
        EXTRA_PLAYER = true;
        // Use the cached HUD preview instead of rendering the full pipeline every frame.
        hudPreviewCache.render(player, posX, posY, scale, yawOffset, event.partialTicks);
        EXTRA_PLAYER = false;
    }

    @SubscribeEvent
    public static void onKeyboardInput(InputEvent.KeyInputEvent event) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (isMoveKey() && player != null) {
            ExtendedModelInfo eep = ExtendedModelInfo.get(player);
            if (eep != null && eep.isPlayAnimation()) {
                NetworkHandler.CHANNEL.sendToServer(SetPlayAnimation.stop());
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        ClientModelManager.clearConnectionState();
        RemotePlayerAnimationQueries.clear();
        RemotePlayerMotionStates.clear();
        NPCData.clear();
    }

    private static boolean isVanillaPlayer(ResourceLocation modelId) {
        String path = modelId.getResourcePath();
        // 直接匹配已知的路径名
        if (path.equals("steve") || path.equals("alex")) return true;
        // 解码编码后的 builtin 路径（如 misc/1_alex → 检查最后一段）
        String decoded = ModelIdUtil.getModelDisplayName(modelId);
        String lastSegment = decoded.substring(decoded.lastIndexOf('/') + 1);
        return lastSegment.equals("steve") || lastSegment.equals("alex")
            || lastSegment.equals("2_steve") || lastSegment.equals("1_alex");
    }

    private static final class PlayerPreviousRotationSnapshot {
        private final float prevRenderYawOffset;
        private final float prevRotationYaw;
        private final float prevRotationPitch;
        private final float prevRotationYawHead;

        private PlayerPreviousRotationSnapshot(EntityPlayer player) {
            this.prevRenderYawOffset = player.prevRenderYawOffset;
            this.prevRotationYaw = player.prevRotationYaw;
            this.prevRotationPitch = player.prevRotationPitch;
            this.prevRotationYawHead = player.prevRotationYawHead;
        }

        private static PlayerPreviousRotationSnapshot capture(EntityPlayer player) {
            return new PlayerPreviousRotationSnapshot(player);
        }

        private void restore(EntityPlayer player) {
            player.prevRenderYawOffset = this.prevRenderYawOffset;
            player.prevRotationYaw = this.prevRotationYaw;
            player.prevRotationPitch = this.prevRotationPitch;
            player.prevRotationYawHead = this.prevRotationYawHead;
        }
    }

    private static boolean isMoveKey() {
        KeyBinding[] keyBindings = Minecraft.getMinecraft().gameSettings.keyBindings;
        for (KeyBinding keyBinding : keyBindings) {
            if ((keyBinding == Minecraft.getMinecraft().gameSettings.keyBindForward
                || keyBinding == Minecraft.getMinecraft().gameSettings.keyBindBack
                || keyBinding == Minecraft.getMinecraft().gameSettings.keyBindLeft
                || keyBinding == Minecraft.getMinecraft().gameSettings.keyBindRight
                || keyBinding == Minecraft.getMinecraft().gameSettings.keyBindJump
                || keyBinding == Minecraft.getMinecraft().gameSettings.keyBindSneak) && keyBinding.isPressed()) {
                return true;
            }
        }
        return false;
    }
}
