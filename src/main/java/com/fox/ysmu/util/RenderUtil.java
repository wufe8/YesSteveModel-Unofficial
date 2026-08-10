package com.fox.ysmu.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientProxy;
import net.geckominecraft.client.renderer.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import org.joml.Quaternionf;
import org.lwjgl.util.vector.Quaternion;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.fox.ysmu.client.audio.YSMSoundManager;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.renderer.CustomPlayerRenderer;
import com.fox.ysmu.compat.Axis;
import com.fox.ysmu.compat.BackhandCompat;
import com.fox.ysmu.compat.Utils;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.model.AnimatedGeoModel;

@SuppressWarnings("all")
public final class RenderUtil {

    /** Set by PlayerTextureScreen before renderTextureScreenEntity to control ground visibility. */
    public static boolean SHOW_GROUND = true;
    /** True while rendering inside an inventory/GUI screen (model selection, texture picker). */
    public static boolean RENDERING_IN_INVENTORY = false;
    /** True while rendering the paperdoll/HUD overlay player icon. */
    public static boolean RENDERING_IN_PAPERDOLL = false;

    private static final float GUI_LIGHTMAP_BRIGHTNESS = 240.0F;
    /** Direct buffer for boosted GL light model ambient (1.275x = 0.51). */
    private static final FloatBuffer LIGHT_AMBIENT_BOOST;
    static {
        LIGHT_AMBIENT_BOOST = ByteBuffer
            .allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer();
        LIGHT_AMBIENT_BOOST.put(new float[]{0.6F, 0.6F, 0.6F, 1.0F});
        LIGHT_AMBIENT_BOOST.flip();
    }

    public static void withGuiEntityLighting(Runnable renderAction) {
        GuiEntityLightingState state = GuiEntityLightingState.capture();
        try {
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            GL11.glEnable(GL11.GL_COLOR_MATERIAL);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            setLightmapTextureEnabled(true);
            OpenGlHelper.setLightmapTextureCoords(
                OpenGlHelper.lightmapTexUnit,
                GUI_LIGHTMAP_BRIGHTNESS,
                GUI_LIGHTMAP_BRIGHTNESS);
            renderAction.run();
        } finally {
            OpenGlHelper.setLightmapTextureCoords(
                OpenGlHelper.lightmapTexUnit,
                state.brightnessX,
                state.brightnessY);
            setLightmapTextureEnabled(state.lightmapTextureEnabled);
            setEnabled(GL12.GL_RESCALE_NORMAL, state.rescaleNormalEnabled);
            setEnabled(GL11.GL_COLOR_MATERIAL, state.colorMaterialEnabled);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private static void setLightmapTextureEnabled(boolean enabled) {
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        setEnabled(GL11.GL_TEXTURE_2D, enabled);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }

    private static final class GuiEntityLightingState {

        private final float brightnessX;
        private final float brightnessY;
        private final boolean lightmapTextureEnabled;
        private final boolean rescaleNormalEnabled;
        private final boolean colorMaterialEnabled;

        private GuiEntityLightingState(float brightnessX, float brightnessY, boolean lightmapTextureEnabled,
            boolean rescaleNormalEnabled, boolean colorMaterialEnabled) {
            this.brightnessX = brightnessX;
            this.brightnessY = brightnessY;
            this.lightmapTextureEnabled = lightmapTextureEnabled;
            this.rescaleNormalEnabled = rescaleNormalEnabled;
            this.colorMaterialEnabled = colorMaterialEnabled;
        }

        private static GuiEntityLightingState capture() {
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            boolean lightmapTextureEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            return new GuiEntityLightingState(
                OpenGlHelper.lastBrightnessX,
                OpenGlHelper.lastBrightnessY,
                lightmapTextureEnabled,
                GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL),
                GL11.glIsEnabled(GL11.GL_COLOR_MATERIAL));
        }
    }

    public static void renderTextureScreenEntity(float pPosX, float pPosY, float pScale, float pitch, float yaw,
        EntityPlayer player, ResourceLocation modelId, ResourceLocation textureId,
        Consumer<CustomPlayerEntity> consumer) {
        if (player == null) {
            return;
        }
        IAnimatable animatable;
        try {
            animatable = AnimatableCacheUtil.TEXTURE_GUI_CACHE.get(modelId, CustomPlayerEntity::new);
            if (animatable instanceof CustomPlayerEntity entity) {
                entity.setPlayer(null);
                consumer.accept(entity);

                ResourceLocation mainModelId = ModelIdUtil.getMainId(modelId);
                entity.setMainModel(mainModelId);
                entity.setTexture(textureId);
                // Ensure textures, geo, and animations are loaded for GUI preview (bypasses doRender())
                com.fox.ysmu.client.ClientModelManager.ensureTexturesLoaded(mainModelId);
                com.fox.ysmu.client.ClientModelManager.ensureGeoModelLoaded(mainModelId);
                com.fox.ysmu.client.ClientModelManager.ensureAnimationsLoaded(mainModelId);

                GlStateManager.pushMatrix();
                GlStateManager.matrixMode(GL11.GL_MODELVIEW);
                GlStateManager.translate(pPosX, pPosY, 1050.0D);
                GlStateManager.scale(1.0F, 1.0F, -1.0F);

                GlStateManager.pushMatrix();
                GlStateManager.translate(0.0D, 0.0D, 1000.0D);
                GlStateManager.scale(pScale, pScale, pScale);
                GlStateManager.translate(0, 0.8, 0);
                // Rotation order matching renderModel: Z(180°) → X(-10+pitch) → Y(175+yaw)
                // Yaw is applied in the matrix because CustomPlayerEntity (non-LivingEntity)
                // does not have its body rotation read by GeckoLib.
                GlStateManager.rotate(j2l(Axis.ZP.rotationDegrees(180.0F)));
                GlStateManager.rotate(j2l(Axis.XP.rotationDegrees(-10 + pitch)));
                GlStateManager.rotate(j2l(Axis.YP.rotationDegrees(175 + yaw)));

                // 保存玩家原始状态
                float yBodyRot = player.renderYawOffset;
                float yRot = player.rotationYaw;
                float xRot = player.rotationPitch;
                float yHeadRotO = player.prevRotationYawHead;
                float yHeadRot = player.rotationYawHead;
                //Pose pose = player.getPose();

                // 修改玩家状态用于渲染
                player.renderYawOffset = -yaw;
                player.rotationYaw = 180; // setYRot
                player.rotationPitch = 0; // setXRot
                player.rotationYawHead = player.rotationYaw;
                player.prevRotationYawHead = player.rotationYaw;

                try {
                    RenderHelper.enableGUIStandardItemLighting();
                    RenderManager dispatcher = RenderManager.instance;

                    //dispatcher.overrideCameraOrientation(xp);
                    //dispatcher.setRenderShadow(false);

                    withGuiEntityLighting(() -> {
                        GlStateManager.pushMatrix();
                        try {
                            if (entity.hasPreviewAnimation("sleep")) {
                                GlStateManager.rotate(j2l(Axis.YP.rotationDegrees(yaw - 90)));
                                GlStateManager.translate(0.5, 0.5625, 0);
                                // TODO sleep和sneak要处理下
                                // player.setPose(Pose.SLEEPING);
                            }
                            if (entity.hasPreviewAnimation("swim") || entity.hasPreviewAnimation("swim_stand")) {
                                // player.setPose(Pose.SWIMMING);
                            }
                            if (entity.hasPreviewAnimation("sneak") || entity.hasPreviewAnimation("sneaking")) {
                                // player.setPose(Pose.CROUCHING);
                            }
                            if (entity.hasPreviewAnimation("sit")) {
                                GlStateManager.translate(0, -0.5, 0);
                            }
                            if (entity.hasPreviewAnimation("ride")) {
                                GlStateManager.translate(0, 0.85, 0);
                            }
                            if (entity.hasPreviewAnimation("ride_pig")) {
                                GlStateManager.translate(0, 0.3125, 0);
                            }
                            if (entity.hasPreviewAnimation("boat")) {
                                GlStateManager.translate(0, -0.45, 0);
                            }
                            // Enable depth test and color material (renderModel does this too)
                            GL11.glEnable(GL11.GL_DEPTH_TEST);
                            GL11.glEnable(GL11.GL_COLOR_MATERIAL);
                            // Explicitly disable face culling — GlStateManager.scale(1,1,-1) above
                            // flips triangle winding, and IGeoRenderer.render() re-enables culling
                            // on exit.  Without this, the model body's front faces are culled away.
                            GL11.glDisable(GL11.GL_CULL_FACE);
                            // Render the GeckoLib model
                            CustomPlayerRenderer renderer = ClientProxy.getInstance();
                            AnimatedGeoModel provider = renderer.getGeoModelProvider();
                            ResourceLocation modelLocation = provider.getModelLocation(entity);
                            GeoModel model = provider.getModel(modelLocation);
                            AnimationEvent<CustomPlayerEntity> predicate = new AnimationEvent<>(
                                entity, 0, 0, 0, false, Collections.emptyList());
                            if (renderer.getGeoModelProvider() instanceof IAnimatableModel) {
                                ((IAnimatableModel<CustomPlayerEntity>) renderer.getGeoModelProvider())
                                    .setLivingAnimations(entity, entity.hashCode(), predicate);
                            }
                            Minecraft.getMinecraft().getTextureManager()
                                .bindTexture(provider.getTextureLocation(entity));
                            renderer.render(model, entity, 0, 1.0f, 1.0f, 1.0f, 1.0f);
                            // renderer.render() re-enables culling — turn it back off for
                            // subsequent renders (extra entity, ground) in the Z-flipped matrix.
                            GL11.glDisable(GL11.GL_CULL_FACE);
                            // Render ground INSIDE the animation-adjustment matrix so it inherits
                            // animation-specific offsets (sit -0.5, ride +0.85, etc.) and stays
                            // at the model's feet level regardless of animation type.
                            if (SHOW_GROUND) {
                                renderSceneGround(pScale);
                            }
                        } finally {
                            GlStateManager.popMatrix(); // 弹出动画位移矩阵
                        }
                    });
                } finally {
                    // 恢复玩家状态
                    player.renderYawOffset = yBodyRot;
                    player.rotationYaw = yRot;
                    player.rotationPitch = xRot;
                    player.prevRotationYawHead = yHeadRotO;
                    player.rotationYawHead = yHeadRot;
                    // player.setPose(pose);

                    GlStateManager.popMatrix(); // 弹出模型变换矩阵
                    GlStateManager.popMatrix(); // 弹出视图变换矩阵
                    RenderHelper.disableStandardItemLighting();
                }
            }
        } catch (Exception e) {
            suppressRenderError("renderTextureScreenEntity", e);
        }
    }

    /**
     * Suppressed render errors, keyed by tag|exceptionClass|message. A GUI preview
     * or GUI model render must never crash the game: before this, an exception from
     * GeckoLib rendering (molang error, bone NPE, GeoModelException from a geo that
     * disappeared mid-frame) propagated to Minecraft's crash handler, which rebuilt
     * the crash report every frame — logging "Negative index in crash report
     * handler" repeatedly and adding lag. We swallow it here and log each unique
     * error once (with its stack) so the real bug stays diagnosable.
     */
    private static final java.util.Set<String> SUPPRESSED_RENDER_ERRORS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void suppressRenderError(String tag, Exception e) {
        String key = tag + '|' + e.getClass().getName() + '|' + e.getMessage();
        if (SUPPRESSED_RENDER_ERRORS.add(key)) {
            ysmu.LOG.warn("[YSMU-RENDER] {} suppressed ({}): {}",
                tag, e.getClass().getSimpleName(), String.valueOf(e.getMessage()), e);
        }
    }

    // 创建一个全局的RenderBlocks实例以提高效率
    private static final RenderBlocks renderBlocks = new RenderBlocks();

    /** Render ground blocks inside the current matrix (inherits animation adjustments).
     *  Called from renderTextureScreenEntity's animation-adjustment block.
     *  Each block uses an explicit push/pop + absolute position so that the
     *  layout is correct regardless of whether Angelica's GlStateManager is
     *  present (vanilla 1.7.10 RenderBlocks may leave the matrix in an
     *  unexpected state after renderBlockAsItem, which would corrupt the
     *  cumulative-translate approach used previously). */
    // 动画预览页面的地板 默认是3x3草方块+草和玫瑰 可以自行修改 方向分别是左右, 上下, 前后, 正负均需互换
    private static void renderSceneGround(float pScale) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        // 3×3 grass blocks — each wrapped in push/pop with explicit position
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                GlStateManager.pushMatrix();
                GlStateManager.translate(-1.0f + i, -0.5f, -1.5f + j);
                renderBlocks.renderBlockAsItem(Blocks.grass, 0, 1.0F);
                GlStateManager.popMatrix();
            }
        }
        // Tall grass at the front-right corner of the grid
        GlStateManager.pushMatrix();
        GlStateManager.translate(1.0f, 0.5f, -1.5f);
        renderBlocks.renderBlockAsItem(Blocks.tallgrass, 1, 1.0F);
        GlStateManager.popMatrix();
        // Rose at the front-left corner of the grid
        GlStateManager.pushMatrix();
        GlStateManager.translate(-1.0f, 0.5f, -0.5f);
        renderBlocks.renderBlockAsItem(Blocks.red_flower, 0, 1.0F);
        GlStateManager.popMatrix();
    }

    public static void renderEntityInInventory(int pPosX, int pPosY, int pScale, EntityPlayer player,
        ResourceLocation modelId, ResourceLocation textureId, Consumer<CustomPlayerEntity> consumer) {
        if (player == null) {
            return;
        }
        RENDERING_IN_INVENTORY = true;
        try {
            CustomPlayerRenderer renderer = ClientProxy.getInstance();
            IAnimatable animatable = AnimatableCacheUtil.ANIMATABLE_CACHE.get(modelId, CustomPlayerEntity::new);
            if (animatable instanceof CustomPlayerEntity entity) {
                // Clear player reference — the cached entity may have been
                // contaminated by FirstPersonHandRenderer.setPlayer().
                // predicateMain/predicateCap check player==null for GUI path.
                entity.setPlayer(null);
                consumer.accept(entity);
                renderModel((double) pPosX, (double) pPosY, (float) pScale, player, modelId, textureId, renderer, entity);
            }
        } catch (Exception e) {
            suppressRenderError("renderEntityInInventory[" + modelId + "]", e);
        } finally {
            RENDERING_IN_INVENTORY = false;
        }
    }

    public static void renderEntityInInventory(int pPosX, int pPosY, int pScale, EntityPlayer player,
        ResourceLocation modelId, ResourceLocation textureId) {
        renderEntityInInventory(pPosX, pPosY, pScale, player, modelId, textureId, entity -> {
            if (entity.hasPreviewAnimation()) {
                entity.clearPreviewAnimation();
            }
        });
    }

    /** 带预览旋转开关的完整版：disablePreviewRotation=true 时固定正面视角
     *  （renderModel 内通过 yOffset/不旋转实现），ModelButton 预览页使用。 */
    public static void renderEntityInInventory(int pPosX, int pPosY, int pScale, EntityPlayer player,
        ResourceLocation modelId, ResourceLocation textureId, Consumer<CustomPlayerEntity> consumer,
        boolean disablePreviewRotation) {
        if (player == null) {
            return;
        }
        RENDERING_IN_INVENTORY = true;
        try {
            CustomPlayerRenderer renderer = ClientProxy.getInstance();
            IAnimatable animatable = AnimatableCacheUtil.ANIMATABLE_CACHE.get(modelId, CustomPlayerEntity::new);
            if (animatable instanceof CustomPlayerEntity entity) {
                entity.setPlayer(null);
                // Clear per-frame dedup cache so every GUI preview FBO render
                // gets fresh animation processing.  Without this,
                // AnimationProcessor.tickAnimation() skips identical entities
                // within the same frame, causing subsequent copies of the same
                // model to re-use stale bone values from the first render's
                // transitioning phase instead of showing the correct animation.
                renderer.getGeoModelProvider().getAnimationProcessor().clearAnimatedEntities();
                consumer.accept(entity);
                // Keep the previewed model's geo/anim warm in the asset lifecycle
                // framework. GUI preview renders via RenderUtil (not
                // CustomPlayerRenderer.doRender), so without this the model being
                // browsed could be idle-evicted mid-browse — leaving its geo present
                // but animation missing, which would otherwise fall back to a
                // mismatched default skeleton (see CustomPlayerEntity.getAnimation)
                // and NPE. Trigger background reload as well so a freshly shown
                // model animates as soon as it is ready.
                ResourceLocation previewMainId = ModelIdUtil.getMainId(modelId);
                // 标记为「正在使用」（GUI 预览即使用）：刷新时间戳并首次主动预暖，
                // 让预览立即渲染而不是先空白 ~1s；关闭 GUI 后 ~5s 由快速卸载回收。
                com.fox.ysmu.client.ClientModelManager.markModelInUse(previewMainId);
                // Trigger geo reload when the previewed model was idle-evicted:
                // get() reloads in the background on ABSENT, and on READY it
                // refreshes the idle timestamp exactly like touch(). Using only
                // touch() here was a bug — it is a no-op on an evicted entry, so a
                // previewed model's geo was never reloaded and its preview stayed
                // invisible forever after idle unload. (Textures need no warm-up
                // here: selecting the model makes CustomPlayerRenderer call
                // ensureTexturesLoaded before its first in-world render.)
                com.fox.ysmu.client.asset.AssetManager.geo(previewMainId).get();
                com.fox.ysmu.client.asset.AssetManager.anim(previewMainId).get();
                // If the previewed model's geo is still absent (idle-evicted and
                // reloading in the background, or genuinely missing), skip this
                // frame — GeckoLib's getModel() throws GeoModelException when the
                // geo isn't in GeckoLibCache, which would crash the preview GUI.
                // The background reload is applied on the main thread and the next
                // frame renders normally.
                if (software.bernie.geckolib3.resource.GeckoLibCache.getInstance()
                    .getGeoModels()
                    .get(previewMainId) == null) {
                    return;
                }
                renderModel((double) pPosX, (double) pPosY, (float) pScale, player, modelId, textureId, renderer, entity, disablePreviewRotation);
            }
        } catch (Exception e) {
            suppressRenderError("renderEntityInInventory(" + modelId + ",disablePreviewRotation)", e);
        } finally {
            RENDERING_IN_INVENTORY = false;
        }
    }

    private static void renderModel(double pPosX, double pPosY, float pScale, EntityPlayer player,
        ResourceLocation modelId, ResourceLocation textureId, GeoReplacedEntityRenderer renderer,
        CustomPlayerEntity entity) {
        renderModel(pPosX, pPosY, pScale, player, modelId, textureId, renderer, entity, false);
    }

    private static void renderModel(double pPosX, double pPosY, float pScale, EntityPlayer player,
        ResourceLocation modelId, ResourceLocation textureId, GeoReplacedEntityRenderer renderer,
        CustomPlayerEntity entity, boolean disablePreviewRotation) {
        // Suppress sound playback during GUI preview rendering — GeckoLib animation
        // keyframes would otherwise play sounds when just hovering over a model button.
        YSMSoundManager.setPreviewRendering(true);
        try {
        ResourceLocation mainModelId = ModelIdUtil.getMainId(modelId);
        entity.setMainModel(mainModelId);
        entity.setTexture(textureId);
        // Ensure textures, geo, and animations are loaded for GUI preview (bypasses doRender())
        com.fox.ysmu.client.ClientModelManager.ensureTexturesLoaded(mainModelId);
        com.fox.ysmu.client.ClientModelManager.ensureGeoModelLoaded(mainModelId);
        com.fox.ysmu.client.ClientModelManager.ensureAnimationsLoaded(mainModelId);

        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glPushMatrix();
        float yOffset = disablePreviewRotation ? 5.5F : 0.0F;
        GL11.glTranslatef((float) pPosX, (float) pPosY + yOffset, 100.0F);
        GL11.glScalef(pScale, pScale, -pScale);
        GL11.glRotatef(180.0F, 0.0F, 0.0F, 1.0F); // 将模型从倒置状态翻转过来
        if (!disablePreviewRotation) {
            GL11.glRotatef(-10.0F, 1.0F, 0.0F, 0.0F);
            GL11.glRotatef(-20.0F, 0.0F, 1.0F, 0.0F);
            // DEMO:
            // GL11.glRotatef(45.0F, 1.0F, 0.0F, 0.0F);
            // 关闭preview时 模型沿-x轴(屏幕向左) 右手螺旋顺时针45度(模型向屏幕上方看)
            // GL11.glRotatef(45.0F, 0.0F, 1.0F, 0.0F);
            // 模型沿y轴(屏幕向上) 右手螺旋顺时针45度(模型向屏幕右侧看)
            // GL11.glRotatef(45.0F, 0.0F, 0.0F, 1.0F);
            // 模型沿z轴(屏幕向内) 右手螺旋顺时针45度(模型在屏幕平面上顺时针旋转)
        }

        // 保存玩家状态
        float yBodyRot = player.renderYawOffset;
        float yRot = player.rotationYaw;
        float xRot = player.rotationPitch;
        float yHeadRotO = player.prevRotationYawHead;
        float yHeadRot = player.rotationYawHead;

        // 0-3 是盔甲
        ItemStack[] itemStacks = new ItemStack[6];
        itemStacks[0] = player.inventory.armorItemInSlot(3); // 头盔
        itemStacks[1] = player.inventory.armorItemInSlot(2);
        itemStacks[2] = player.inventory.armorItemInSlot(1);
        itemStacks[3] = player.inventory.armorItemInSlot(0);
        itemStacks[4] = player.inventory.getCurrentItem();
        itemStacks[5] = BackhandCompat.getOffhandItem(player);
        // 清空玩家物品以避免在模型上渲染
        player.inventory.mainInventory[player.inventory.currentItem] = null;
        BackhandCompat.setOffhandItem(player, null);
        for (int i = 0; i < 4; i++) {
            player.inventory.armorInventory[i] = null;
        }

        // Match OpenYSM: both yBodyRot and yRot set to same value
        if (disablePreviewRotation) {
            player.renderYawOffset = 180;
            player.rotationYaw = 180;
        } else {
            player.renderYawOffset = 200;
            player.rotationYaw = 200;
        }
        player.rotationPitch = 0;
        player.rotationYawHead = player.rotationYaw;
        player.prevRotationYawHead = player.rotationYaw;

        GL11.glRotatef(135.0F, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        // Boost lighting brightness by 1.275x to match gui_no_lighting appearance
        GL11.glLightModel(GL11.GL_LIGHT_MODEL_AMBIENT, LIGHT_AMBIENT_BOOST);
        GL11.glRotatef(-135.0F, 0.0F, 1.0F, 0.0F);

        // Check if this model has gui_no_lighting enabled
        boolean guiNoLighting = false;
        ResourceLocation mainId = ModelIdUtil.getMainId(modelId);
        Boolean gnl = com.fox.ysmu.client.ClientModelManager.GUI_NO_LIGHTING.get(mainId);
        if (gnl != null && gnl) {
            guiNoLighting = true;
        }
        if (!guiNoLighting) {
            gnl = com.fox.ysmu.client.ClientModelManager.GUI_NO_LIGHTING.get(modelId);
            if (gnl != null && gnl) {
                guiNoLighting = true;
            }
        }

        if (guiNoLighting) {
            // gui_no_lighting: disable directional lights, use full-bright lightmap
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            GL11.glDisable(GL11.GL_COLOR_MATERIAL);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            setLightmapTextureEnabled(true);
            OpenGlHelper.setLightmapTextureCoords(
                OpenGlHelper.lightmapTexUnit,
                GUI_LIGHTMAP_BRIGHTNESS,
                GUI_LIGHTMAP_BRIGHTNESS);
        }

        try {
            if (guiNoLighting) {
                AnimatedGeoModel provider = renderer.getGeoModelProvider();
                ResourceLocation modelLocation = provider.getModelLocation(entity);
                GeoModel model = provider.getModel(modelLocation);
                AnimationEvent<CustomPlayerEntity> predicate = new AnimationEvent<>(entity, 0, 0, 0, false, Collections.emptyList());
                if (renderer.getGeoModelProvider() instanceof IAnimatableModel) {
                    ((IAnimatableModel<CustomPlayerEntity>) renderer.getGeoModelProvider()).setLivingAnimations(entity, entity.hashCode(), predicate);
                }
                Minecraft.getMinecraft().getTextureManager().bindTexture(provider.getTextureLocation(entity));
                renderer.render(model, entity, 0, 1.0f, 1.0f, 1.0f, 1.0f);
            } else {
                withGuiEntityLighting(() -> {
                    AnimatedGeoModel provider = renderer.getGeoModelProvider();
                    ResourceLocation modelLocation = provider.getModelLocation(entity);
                    GeoModel model = provider.getModel(modelLocation);
                    AnimationEvent<CustomPlayerEntity> predicate = new AnimationEvent<>(entity, 0, 0, 0, false, Collections.emptyList());
                    if (renderer.getGeoModelProvider() instanceof IAnimatableModel) {
                        ((IAnimatableModel<CustomPlayerEntity>) renderer.getGeoModelProvider()).setLivingAnimations(entity, entity.hashCode(), predicate);
                    }
                    Minecraft.getMinecraft().getTextureManager().bindTexture(provider.getTextureLocation(entity));
                    renderer.render(model, entity, 0, 1.0f, 1.0f, 1.0f, 1.0f);
                });
            }
        } finally {
            RenderHelper.disableStandardItemLighting();
            GL11.glPopMatrix();
            GL11.glDisable(GL11.GL_DEPTH_TEST);

            // 恢复状态
            player.renderYawOffset = yBodyRot;
            player.rotationYaw = yRot;
            player.rotationPitch = xRot;
            player.prevRotationYawHead = yHeadRotO;
            player.rotationYawHead = yHeadRot;

            player.inventory.armorInventory[3] = itemStacks[0];
            player.inventory.armorInventory[2] = itemStacks[1];
            player.inventory.armorInventory[1] = itemStacks[2];
            player.inventory.armorInventory[0] = itemStacks[3];
            player.inventory.mainInventory[player.inventory.currentItem] = itemStacks[4];
            BackhandCompat.setOffhandItem(player, itemStacks[5]);
        }
        } finally {
            YSMSoundManager.setPreviewRendering(false);
        }
    }

    /**
     * Renders the player model on the HUD overlay (selfie model).
     * Uses default partialTicks = 1.0F (no frame interpolation).
     * Prefer {@link #renderPlayerEntity(EntityPlayer, double, double, float, float, double, float)}
     * which accepts partialTicks for smooth animation.
     */
    public static void renderPlayerEntity(EntityPlayer player, double posX, double posY, float scale, float yawOffset, double z) {
        renderPlayerEntity(player, posX, posY, scale, yawOffset, z, 1.0F);
    }

    /**
     * Renders the player model on the HUD overlay with frame interpolation.
     *
     * @param partialTicks 帧间插值系数 (0.0~1.0)，来自渲染事件，用于平滑旋转和动画更新
     */
    public static void renderPlayerEntity(EntityPlayer player, double posX, double posY, float scale, float yawOffset, double z, float partialTicks) {
        if (player != Minecraft.getMinecraft().thePlayer) return;
        RENDERING_IN_PAPERDOLL = true;
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glPushMatrix();

        // ── HUD follow mode: capture real rotation fields; restore in finally ──
        float savePrevBody = player.prevRenderYawOffset;
        float saveBody = player.renderYawOffset;
        float savePrevHead = player.prevRotationYawHead;
        float saveHead = player.rotationYawHead;
        try {
            // 对 rotationYaw 做帧间插值，避免每 tick 刷新的卡顿
            float interpolatedYaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
            // 身体偏航（renderYawOffset）的帧间插值：与 rotationYaw 一样用连续线性插值
            // （不经过 lerpYaw 的 wrap180），保持与视角同参考系，供 follow 连续差值跟踪
            float bodyYaw = player.prevRenderYawOffset + (player.renderYawOffset - player.prevRenderYawOffset) * partialTicks;
            float outerYaw = interpolatedYaw;

            int mode = Config.HUD_FOLLOW_MODE;
            ensureFollowMode(mode);
            if (mode == Config.HUD_FOLLOW_VANILLA_SMOOTH) {
                // 阈值区间内平滑：offset 单状态自适应低通（低速强平滑消除 20Hz 锯齿，
                // 高速近直通保持原版手感）。外层 = realB + offset，内层 = 180 - realB 精确抵消
                // → 屏幕身体 = 180+yawOffset+offset（平滑）。
                // 头部：只覆写 H = B + offset（不动 B）。头骨 = B - H = -offset（平滑），
                // 屏幕头部 = 180+yawOffset 完全固定（同 vanilla），query.head_y_rotation = offset
                // 连续无 20Hz 抖动；query.body_y_rotation 仍为真实 B，不破坏身体动画过渡（v2/v3 教训）。
                float offset;
                if (!Float.isNaN(lastPolledFollow)
                    && Minecraft.getSystemTime() - lastPolledFollowTime < 100L) {
                    // pollHudDisplayBodyYaw 已在本帧推进过状态机，直接消费其结果
                    offset = lastPolledFollow;
                    lastPolledFollow = Float.NaN;
                } else {
                    // FBO 缓存关闭或配置界面直接渲染：在这里推进状态机
                    offset = hudFollowState.update(interpolatedYaw, bodyYaw);
                }
                outerYaw = MathHelper.wrapAngleTo180_float(bodyYaw + offset);
                float headDisplay = MathHelper.wrapAngleTo180_float(bodyYaw + offset);
                player.prevRotationYawHead = headDisplay;
                player.rotationYawHead = headDisplay;
            } else if (mode == Config.HUD_FOLLOW_YSM) {
                // 外层改用身体偏航：外层 (B) 与内层 (180-B) 精确抵消 → 身体锁死朝向屏幕。
                // 头部字段覆写为 B - clamp(V-B)：管线内取反后头骨旋转 = clamp(V-B)，
                // 即头部随视角 1:1 转动（限制 ±60°，同 YSM 2.6.5 纸娃娃），query.head_y_rotation 语义也恢复正确。
                outerYaw = bodyYaw;
                float headYaw = MathHelper.clamp_float(
                    MathHelper.wrapAngleTo180_float(interpolatedYaw - bodyYaw),
                    -HUD_YSM_HEAD_MAX_OFFSET, HUD_YSM_HEAD_MAX_OFFSET);
                float headDisplay = MathHelper.wrapAngleTo180_float(bodyYaw - headYaw);
                player.prevRotationYawHead = headDisplay;
                player.rotationYawHead = headDisplay;
            } else if (mode == Config.HUD_FOLLOW_YSM_SMOOTH) {
                // ysm 平滑：身体锁死朝向屏幕（同 ysm），头部参照真实身体 B——连续差值
                // V−B 直接 clamp ±60（不经 wrap180 → 永不翻转，无需方向锁）。不自动归中：
                // 站立时头部停在限位；前进时原版 renderYawOffset 追赶镜头方向，头部随之归中。
                float headYaw;
                if (!Float.isNaN(lastPolledFollow)
                    && Minecraft.getSystemTime() - lastPolledFollowTime < 100L) {
                    // pollHudDisplayBodyYaw 已在本帧推进过状态机，直接消费其结果
                    headYaw = lastPolledFollow;
                    lastPolledFollow = Float.NaN;
                } else {
                    // FBO 缓存关闭或配置界面直接渲染：在这里推进状态机
                    headYaw = ysmHeadState.update(interpolatedYaw, bodyYaw);
                }
                outerYaw = bodyYaw;
                float headDisplay = MathHelper.wrapAngleTo180_float(bodyYaw - headYaw);
                player.prevRotationYawHead = headDisplay;
                player.rotationYawHead = headDisplay;
            }

            GL11.glTranslatef((float) (posX + scale * 0.5), (float) (posY + scale * 2), (float) z);
            GL11.glScalef(-scale, scale, scale);
            GL11.glRotatef(180.0F, 0.0F, 0.0F, 1.0F);
            GL11.glRotatef(outerYaw + yawOffset, 0.0F, 1.0F, 0.0F);

            GL11.glRotatef(135.0F, 0.0F, 1.0F, 0.0F);
            RenderHelper.enableStandardItemLighting();
            GL11.glRotatef(-135.0F, 0.0F, 1.0F, 0.0F);

            GL11.glTranslatef(0.0F, player.yOffset, 0.0F);
            withGuiEntityLighting(() -> RenderManager.instance.renderEntityWithPosYaw(player, 0.0D, 0.0D, 0.0D, 0.0F, partialTicks));
        } finally {
            player.prevRenderYawOffset = savePrevBody;
            player.renderYawOffset = saveBody;
            player.prevRotationYawHead = savePrevHead;
            player.rotationYawHead = saveHead;
            GL11.glPopMatrix();
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            RENDERING_IN_PAPERDOLL = false;
        }
    }

    // ── HUD paperdoll follow-mode helpers ──

    /** 原版平滑模式的 offset 最大幅值（°），沿用 vanilla 的 ±75° 阈值
     *  （renderYawOffset 始终被 vanilla 约束在 rotationYaw±75° 内）。 */
    private static final float HUD_SMOOTH_MAX_OFFSET = 75.0F;
    /** ysm 平滑模式的头部随视角最大幅值（°）。YSM 2.6.5 纸娃娃已从 ±85 改为 ±60
     *  （±85 在大幅转向时屏幕旋转过大）。 */
    private static final float HUD_YSM_HEAD_MAX_OFFSET = 60.0F;
    /** 原版平滑模式对 offset 的基础低通时间常数（s），消除 20Hz 锯齿。 */
    private static final float HUD_SMOOTH_OFFSET_TC = 0.12F;
    /** 对身体偏航 B 的低通时间常数（s）：B 只在 tick 更新（20Hz 步进），先低通 B 再算
     *  target = V - Bs，从源头消除 offset 的 20Hz 锯齿；渲染层仍用真实 B 与内层 180-B
     *  精确抵消（屏幕身体 = offset），因此不会引入「两个独立低通信号相减」导致的振荡。 */
    private static final float HUD_SMOOTH_BODY_TC = 0.08F;
    /** B 偏离 bodySmooth 超过该值（°）视为「身体实际在转动」（侧移/转向/走位），此时改用
     *  HUD_SMOOTH_BODY_FAST_TC 快速跟踪，避免纸娃娃身体转向明显迟滞；仅 tick 步进抖动
     * （偏离小）时仍走 HUD_SMOOTH_BODY_TC 强平滑消除 20Hz 锯齿。 */
    private static final float HUD_SMOOTH_BODY_FAST_DEG = 2.0F;
    /** 身体快速转动时 bodySmooth 的低通时间常数（s）。 */
    private static final float HUD_SMOOTH_BODY_FAST_TC = 0.03F;
    /** 单帧推进的 dt 上限（s）：超过视为中等帧卡顿，本帧跳过推进（卡顿期间 B 也未推进，
     *  无需追赶），避免 bodySmooth/offsetSmooth 一帧瞬移造成的 5-9° 跳变。 */
    private static final float HUD_SMOOTH_MAX_DT = 0.1F;
    /** dt 超过该值（s）视为长时间暂停（切窗口/菜单/Alt-Tab）：此时重同步到当前值，
     *  用户不在看，瞬移无感；防止暂停期间服务器 tick 推进 B 后状态永久失配。 */
    private static final float HUD_SMOOTH_RESYNC_DT = 1.0F;
    /** offset 偏差达到该值（°）时低通时间常数减半（高速时接近直通，保持原版手感）。 */
    private static final float HUD_FAST_DEVIATION_DEG = 20.0F;
    /** target 与 offsetSmooth 偏差超过该值（°）视为「大幅反向跳变」：快速反向甩动使
     *  target 从一侧限位跳到另一侧限位（如 +60 → -60）。此时改用慢追时间常数，让头部平滑转回，
     *  避免一帧快追 120°+（屏幕上身体锁死时即头部猛甩的「闪」）。 */
    private static final float HUD_SMOOTH_SLOW_FLIP_DEG = 90.0F;
    /** 大幅反向跳变时的慢追时间常数（s）：正常快速转头（偏差 <90°）仍用 HUD_SMOOTH_OFFSET_TC
     *  快追保持跟手手感；跨侧跳变用此值平滑转回。 */
    private static final float HUD_SMOOTH_SLOW_FLIP_TC = 0.4F;
    /** 同一渲染帧内两次 update() 调用（renderPlayerEntity 与 pollHudDisplayBodyYaw 双路径）
     *  的 dt 上限（s）。两次调用若跨毫秒，第二次会以相同 V/B 重新推进，状态机（低通与
     *  时间戳）会被二次推进。dt < 该值视为同帧双路径，跳过。 */
    private static final float HUD_SMOOTH_DUP_DT = 0.003F;
    /** 上次渲染时使用的跟随模式，用于切换模式时重置平滑状态。 */
    private static int lastFollowMode = -1;
    /** 最近一次 pollHudDisplayBodyYaw 的平滑跟随角（模式 1 为身体 offset、模式 3 为头部偏航；
     *  非平滑模式为 NaN），供 renderPlayerEntity 消费。 */
    private static float lastPolledFollow = Float.NaN;
    private static long lastPolledFollowTime = 0L;

    /**
     * 帧率无关的平滑状态机（原版平滑/ysm 平滑共用）：对「跟随角 = V - B」做单一状态的
     * 偏差自适应低通。目标值 = 连续差值 V - Bs 直接 clamp ±maxOffset（沿用 vanilla/ysm
     * 阈值）。单一状态低通不会出现「两个独立低通信号相减」在反向/阈值附近产生的振荡与截断
     * 锯齿；偏差大时时间常数缩小（高速近直通，保持原版手感），偏差小时强平滑（消除 20Hz 锯齿）。
     * <p>
     * 高速旋转天然免翻转：V 与 B 都是**连续累积角度**（renderYawOffset 与 rotationYaw 一样
     * 不经 lerpYaw 的 wrap180），连续差值 V−B 直接 clamp 是单调的——|V−B| 瞬态越过 ±180°
     * （快速甩动、B 追赶前）只会把目标钳在 ±maxOffset，永不会翻转到另一侧（旧的
     * clamp(wrap180(V−B)) 会在跨 ±180° 时翻转，需要方向锁修复，该机制现已删除）。
     * 因此本状态机不需要任何方向锁定。
     * <p>
     * 不自动归中：站立时 B 不动 → 跟随角停在限位；前进时原版 renderYawOffset 追赶镜头方向
     * （30%/tick）→ V−B 回落 → 跟随角归中。已知极限：站立时单帧甩动 >360° 时 renderYawOffset
     * 按 mod 360 对齐，连续差值 V−B 可能停在 >maxOffset 处（跟随角保持限位），需回头转动
     * 才能归中——属「无人工归中」的刻意取舍。
     */
    private static final class HudFollowState {
        private final float maxOffset;
        /** 低通后的连续身体偏航（去除 20Hz tick 步进，不 wrap180，与 V 同参考系）。 */
        private float bodySmooth;
        /** 低通后的跟随角（屏幕可见值：模式 1 为身体 offset，模式 3 为头部偏航）。 */
        private float offsetSmooth;
        private boolean initialized;
        private long lastTimeMs;

        HudFollowState(float maxOffset) {
            this.maxOffset = maxOffset;
        }

        void reset() {
            initialized = false;
        }

        /**
         * @param rawViewYaw  视角偏航 V 的原始帧间插值（连续累积）
         * @param realBodyYaw 真实身体偏航 B（连续线性插值，逐 tick 更新）
         * @return 低通后的 clamp(V - Bs, ±maxOffset)，其中 Bs 为低通后的连续身体偏航；
         *         连续差值单调 clamp，永不翻转，不自动归中（归中靠原版身体追赶）
         */
        float update(float rawViewYaw, float realBodyYaw) {
            long now = Minecraft.getSystemTime();
            if (!initialized || lastTimeMs == 0L) {
                initialized = true;
                lastTimeMs = now;
                bodySmooth = realBodyYaw;
                offsetSmooth = MathHelper.clamp_float(rawViewYaw - bodySmooth, -maxOffset, maxOffset);
                return offsetSmooth;
            }
            float dt = (now - lastTimeMs) / 1000.0F;
            if (dt < HUD_SMOOTH_DUP_DT) {
                // 同一渲染帧内重复调用（renderPlayerEntity 与 pollHudDisplayBodyYaw 双路径）：
                // 跳过，避免状态机一帧推进两次（低通与时间戳二次推进）。dt < 3ms 视为同帧。
                return offsetSmooth;
            }
            lastTimeMs = now;
            if (dt > HUD_SMOOTH_RESYNC_DT) {
                // 长时间暂停：重同步（用户不在看，瞬移无感）
                bodySmooth = realBodyYaw;
                offsetSmooth = MathHelper.clamp_float(rawViewYaw - bodySmooth, -maxOffset, maxOffset);
                return offsetSmooth;
            }
            if (dt > HUD_SMOOTH_MAX_DT) {
                // 中等帧卡顿：本帧不推进（卡顿期间 B 也未推进），下一帧起正常平滑继续，绝不瞬移
                return offsetSmooth;
            }
            // 先低通身体偏航 B（20Hz tick 步进 → 平滑）：V 与 B 均为连续累积角度，直接差值
            // 无 wrap180 路径歧义（甩动后 B 连续追上 V 时差值自然回落，跟随角归中）。
            // 自适应：B 实际转动（侧移/走位，|dB| 大）时用小时间常数快速跟踪，身体转向不迟滞；
            // 仅 tick 步进抖动（|dB| 小）时用大时间常数强平滑，消除 20Hz 锯齿。
            float dB = realBodyYaw - bodySmooth;
            float bodyTC = Math.abs(dB) > HUD_SMOOTH_BODY_FAST_DEG
                ? HUD_SMOOTH_BODY_FAST_TC
                : HUD_SMOOTH_BODY_TC;
            float alphaB = 1.0F - (float) Math.exp(-dt / bodyTC);
            bodySmooth += dB * alphaB;
            // 连续差值 V − Bs 直接 clamp（单调，不 wrap180 → 永不翻转，无需方向锁）
            float target = MathHelper.clamp_float(rawViewYaw - bodySmooth, -maxOffset, maxOffset);
            // 偏差自适应低通：偏差越大时间常数越小（高速近直通），正常快速转头跟手。
            // 例外：偏差超过 HUD_SMOOTH_SLOW_FLIP_DEG（快速反向甩动使 target 从一侧跳到
            // 另一侧，如 +60 → -60）时改用大时间常数慢追，避免一两帧内猛甩 120°+ 的「闪」。
            float delta = target - offsetSmooth;
            if (Math.abs(delta) < 0.001F) {
                offsetSmooth = target;
                return offsetSmooth;
            }
            float dev = Math.abs(delta);
            float t = dev > HUD_SMOOTH_SLOW_FLIP_DEG
                ? HUD_SMOOTH_SLOW_FLIP_TC
                : HUD_SMOOTH_OFFSET_TC / (1.0F + dev / HUD_FAST_DEVIATION_DEG);
            float alpha = 1.0F - (float) Math.exp(-dt / t);
            offsetSmooth = offsetSmooth + delta * alpha;
            return offsetSmooth;
        }
    }

    private static final HudFollowState hudFollowState = new HudFollowState(HUD_SMOOTH_MAX_OFFSET);
    /** ysm 平滑模式的头部状态机：身体锁屏，头部参照真实身体 B（±60°，无自动归中，天然免翻转）。 */
    private static final HudFollowState ysmHeadState = new HudFollowState(HUD_YSM_HEAD_MAX_OFFSET);

    /** 切换跟随模式时重置平滑状态，避免旧模式的残留值造成跳变。 */
    private static void ensureFollowMode(int mode) {
        if (lastFollowMode != mode) {
            lastFollowMode = mode;
            hudFollowState.reset();
            ysmHeadState.reset();
        }
    }

    /**
     * 每帧轮询（即使 FBO 不重渲染也调用）：推进当前平滑模式的状态机。返回该模式下的
     * 平滑跟随角（原版平滑 = 屏幕可见身体 offset，ysm 平滑 = 头部偏航），供 HudPreviewCache
     * 判断模型是否仍在转动（转动期间逐帧重渲染，静止时继续走 FBO 缓存）；非平滑模式返回 NaN。
     * 结果存入 lastPolledFollow 供 renderPlayerEntity 消费（不覆写任何实体字段）。
     */
    public static float pollHudDisplayBodyYaw(EntityPlayer player, float partialTicks) {
        int mode = Config.HUD_FOLLOW_MODE;
        ensureFollowMode(mode);
        float viewYaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
        float bodyYaw = player.prevRenderYawOffset + (player.renderYawOffset - player.prevRenderYawOffset) * partialTicks;
        float follow;
        if (mode == Config.HUD_FOLLOW_VANILLA_SMOOTH) {
            // 原版平滑：平滑后的屏幕可见身体 offset
            follow = hudFollowState.update(viewYaw, bodyYaw);
        } else if (mode == Config.HUD_FOLLOW_YSM_SMOOTH) {
            // ysm 平滑：平滑后的头部随视角偏航（参照真实身体 B，归中靠原版身体追赶）
            follow = ysmHeadState.update(viewYaw, bodyYaw);
        } else {
            lastPolledFollow = Float.NaN;
            return Float.NaN;
        }
        lastPolledFollow = follow;
        lastPolledFollowTime = Minecraft.getSystemTime();
        return follow;
    }

    private static Quaternion j2l(Quaternionf jomlQuat) {
        return Utils.j2l(jomlQuat);
    }
}
