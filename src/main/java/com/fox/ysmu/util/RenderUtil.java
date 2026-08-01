package com.fox.ysmu.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
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
                            // Render ride/boat vehicle entities on top
                            renderExtraEntity(yaw, player, entity, dispatcher);
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
        } catch (ExecutionException e) {
            e.printStackTrace();
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

    // Placeholder for ride/boat vehicle entity rendering (not yet implemented).
    private static void renderExtraEntity(float yaw, EntityPlayer player, CustomPlayerEntity playerEntity,
        RenderManager dispatcher) {}

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
        } catch (ExecutionException e) {
            e.printStackTrace();
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

    public static void renderEntityInInventory(int pPosX, int pPosY, int pScale, EntityPlayer player,
        ResourceLocation modelId, ResourceLocation textureId, boolean disablePreviewRotation) {
        renderEntityInInventory(pPosX, pPosY, pScale, player, modelId, textureId, entity -> {
            // Keep preview animation if set (don't clear)
        }, disablePreviewRotation);
    }

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
                com.fox.ysmu.client.asset.AssetManager.geo(previewMainId).touch();
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
        } catch (ExecutionException e) {
            e.printStackTrace();
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
        try {
            // 对 rotationYaw 做帧间插值，避免每 tick 刷新的卡顿
            float interpolatedYaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;

            GL11.glTranslatef((float) (posX + scale * 0.5), (float) (posY + scale * 2), (float) z);
            GL11.glScalef(-scale, scale, scale);
            GL11.glRotatef(180.0F, 0.0F, 0.0F, 1.0F);
            GL11.glRotatef(interpolatedYaw + yawOffset, 0.0F, 1.0F, 0.0F);

            GL11.glRotatef(135.0F, 0.0F, 1.0F, 0.0F);
            RenderHelper.enableStandardItemLighting();
            GL11.glRotatef(-135.0F, 0.0F, 1.0F, 0.0F);

            GL11.glTranslatef(0.0F, player.yOffset, 0.0F);
            withGuiEntityLighting(() -> RenderManager.instance.renderEntityWithPosYaw(player, 0.0D, 0.0D, 0.0D, 0.0F, partialTicks));
        } finally {
            GL11.glPopMatrix();
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            RENDERING_IN_PAPERDOLL = false;
        }
    }

    private static Quaternion j2l(Quaternionf jomlQuat) {
        return Utils.j2l(jomlQuat);
    }
}
