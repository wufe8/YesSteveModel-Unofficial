package com.fox.ysmu.client.renderer;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.client.renderer.layer.CustomPlayerItemInHandLayer;
import com.fox.ysmu.data.NPCData;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.event.api.SpecialPlayerRenderEvent;
import com.fox.ysmu.util.ModelIdUtil;

import it.unimi.dsi.fastutil.Pair;
import net.geckominecraft.client.renderer.GlStateManager;
import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;
import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

public class CustomPlayerRenderer extends GeoReplacedEntityRenderer<CustomPlayerEntity> {

    private GeoModel geoModel;
    /** Tracks which model locations have already been logged as missing (throttle). */
    private final Set<String> missingGeoModelLogged = Collections.synchronizedSet(new HashSet<>());
    /** Tracks the last main model per player to detect model switches. */
    private final Map<UUID, ResourceLocation> lastPlayerModel = new ConcurrentHashMap<>();
    @SuppressWarnings("all")
    public CustomPlayerRenderer() {
        super(new CustomPlayerModel(), new CustomPlayerEntity());
        addLayer(new CustomPlayerItemInHandLayer<>(this));
    }

    @Override
    public void doRender(EntityLivingBase entityObj, double x, double y, double z, float entityYaw,
        float partialTicks) {
        if (this.animatable != null && entityObj instanceof EntityPlayer player) {
            UUID pid = player.getUniqueID();
            ResourceLocation oldModel = lastPlayerModel.get(pid);
            ResourceLocation newModel;
            ExtendedModelInfo eep = ExtendedModelInfo.get(player);
            if (eep != null) {
                this.animatable.setPlayer(player);
                if (NPCData.contains(pid)) {
                    Pair<ResourceLocation, ResourceLocation> data = NPCData.getData(pid);
                    newModel = ModelIdUtil.getMainId(data.left());
                    this.animatable.setMainModel(newModel);
                    this.animatable.setTexture(data.right());
                } else {
                    newModel = ModelIdUtil.getMainId(eep.getModelId());
                    this.animatable.setMainModel(newModel);
                    this.animatable.setTexture(eep.getSelectTexture());
                }
                // Detect model switch and reset stale per-player animation state
                if (oldModel != null && !oldModel.equals(newModel)) {
                    com.fox.ysmu.client.animation.AnimationManager.getInstance().resetPlayerState(pid);
                    com.fox.ysmu.client.audio.YSMSoundManager.stopAll();
                }
                lastPlayerModel.put(pid, newModel);
            }
            if (MinecraftForge.EVENT_BUS.post(
                new SpecialPlayerRenderEvent(
                    player,
                    this.animatable,
                    ModelIdUtil.getModelIdFromMainId(this.animatable.getMainModel())))) {
                return;
            }
        }
        ResourceLocation location = this.modelProvider.getModelLocation(animatable);
        GeoModel geoModel = GeckoLibCache.getInstance()
            .getGeoModels()
            .get(location);
        if (geoModel != null) {
            this.geoModel = geoModel;
            super.doRender(entityObj, x, y, z, entityYaw, partialTicks);
            // Render projectile overlay when holding a bow (nocked arrow + bow mechanism)
            renderProjectileOverlay(entityObj, x, y, z, partialTicks);
        } else {
            // Throttled logging: only log once per missing model per game session
            if (missingGeoModelLogged.add(location.toString())) {
                com.fox.ysmu.ysmu.LOG.info(
                    "YSM renderer cannot find geo model: location={}, mainModel={}, texture={}, geoModelsInCache={}",
                    location,
                    this.animatable != null ? this.animatable.getMainModel() : "null",
                    this.animatable != null ? this.animatable.getTexture() : "null",
                    GeckoLibCache.getInstance().getGeoModels().keySet().stream()
                        .map(ResourceLocation::toString)
                        .filter(s -> s.contains(location.getResourceDomain() + ":" + location.getResourcePath().replace("/main", "")))
                        .collect(java.util.stream.Collectors.joining(", ")));
            }
        }
    }

    // @Override
    // public RenderType getRenderType(Object animatable, float partialTick, PoseStack poseStack, @Nullable
    // MultiBufferSource bufferSource, @Nullable VertexConsumer buffer, int packedLight, ResourceLocation texture) {
    // return RenderType.entityTranslucent(texture);
    // }

    // @Override
    // public boolean shouldShowName(Entity entity) {
    // double distance = this.entityRenderDispatcher.distanceToSqr(entity);
    // float renderDistance = entity.isDiscrete() ? 32.0F : 64.0F;
    // if (distance >= (double) (renderDistance * renderDistance)) {
    // return false;
    // } else {
    // Minecraft minecraft = Minecraft.getInstance();
    // LocalPlayer player = minecraft.player;
    // if (player == null) {
    // return false;
    // }
    // boolean invisible = !entity.isInvisibleTo(player);
    // if (entity != player) {
    // Team team1 = entity.getTeam();
    // Team team2 = player.getTeam();
    // if (team1 != null) {
    // Team.Visibility team$visibility = team1.getNameTagVisibility();
    // return switch (team$visibility) {
    // case ALWAYS -> invisible;
    // case NEVER -> false;
    // case HIDE_FOR_OTHER_TEAMS ->
    // team2 == null ? invisible : team1.isAlliedTo(team2) && (team1.canSeeFriendlyInvisibles() || invisible);
    // case HIDE_FOR_OWN_TEAM -> team2 == null ? invisible : !team1.isAlliedTo(team2) && invisible;
    // };
    // }
    // }
    // return Minecraft.renderNames() && entity != minecraft.getCameraEntity() && invisible && !entity.isVehicle();
    // }
    // }

    @Override
    protected void func_96449_a(EntityLivingBase entity, double x, double y, double z, String displayName,
        float scale, double distanceSq) {
        if (entity instanceof EntityPlayer player && distanceSq < 100.0D) {
            Scoreboard scoreboard = player.getWorldScoreboard();
            ScoreObjective objective = scoreboard.func_96539_a(2);
            if (objective != null) {
                Score score = scoreboard.func_96529_a(player.getCommandSenderName(), objective);
                String scoreText = score.getScorePoints() + " " + objective.getDisplayName();
                double scoreY = player.isPlayerSleeping() ? y - 1.5D : y;
                this.func_147906_a(player, scoreText, x, scoreY, z, 64);
                y += this.getFontRendererFromRenderManager().FONT_HEIGHT * 1.15F * scale;
            }
        }
        super.func_96449_a(entity, x, y, z, displayName, scale, distanceSq);
    }

    @Override
    public float getWidthScale(Object animatable) {
        if (this.animatable != null) {
            return this.animatable.getWidthScale();
        }
        return super.getWidthScale(animatable);
    }

    @Override
    public float getHeightScale(Object animatable) {
        if (this.animatable != null) {
            return this.animatable.getHeightScale();
        }
        return super.getHeightScale(animatable);
    }

    public CustomPlayerEntity getCustomPlayerEntity() {
        return this.animatable;
    }

    @Nullable
    public GeoModel getGeoModel() {
        return geoModel;
    }

    /**
     * Render a projectile sub-entity overlay (e.g. nocked arrow on bow)
     * when the player is holding a bow. This makes the bow's shared parts
     * (crossbow mechanism, arrow on string) visible even without an arrow entity.
     */
    @SuppressWarnings("unchecked")
    private void renderProjectileOverlay(EntityLivingBase entity, double x, double y, double z,
        float partialTicks) {
        if (!(entity instanceof EntityPlayer player)) return;

        // Check if holding a bow in main hand
        ItemStack mainHand = player.getHeldItem();
        boolean hasBow = (mainHand != null && mainHand.getItem() instanceof ItemBow);
        if (!hasBow && mainHand != null) {
            String itemId = mainHand.getItem().getClass().getName().toLowerCase();
            hasBow = itemId.contains("bow");
        }
        com.fox.ysmu.ysmu.LOG.info("[YSMU-PROJ] check: hasBow={}, heldItem={}",
            hasBow, mainHand != null ? mainHand.getItem().getClass().getSimpleName() : "null");
        if (!hasBow) return;

        // Get the player's model ID
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep == null || eep.getModelId() == null) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-PROJ] no eep or modelId for player");
            return;
        }
        ResourceLocation baseModelId = ModelIdUtil.getModelIdFromMainId(
            ModelIdUtil.getMainId(eep.getModelId()));
        com.fox.ysmu.ysmu.LOG.info("[YSMU-PROJ] baseModelId={}", baseModelId);

        // Find projectile types for this model
        List<String> projTypes = ClientModelManager.PROJECTILE_MODEL_IDS.get(baseModelId);
        if (projTypes == null || projTypes.isEmpty()) return;

        // Pick the first projectile type (usually #arrow)
        String projType = projTypes.get(0);
        ResourceLocation projGeoId = ModelIdUtil.getSubModelId(baseModelId, "projectile_" + projType);
        GeoModel projModel = GeckoLibCache.getInstance().getGeoModels().get(projGeoId);
        if (projModel == null) return;

        // Find the projectile texture
        List<ResourceLocation> projTexList = ClientModelManager.PROJECTILE_TEXTURE_IDS.get(baseModelId);
        ResourceLocation projTexId = null;
        if (projTexList != null) {
            String prefix = "projectile_" + projType + "_";
            for (ResourceLocation tid : projTexList) {
                if (tid.getResourcePath().contains(prefix)) {
                    projTexId = tid;
                    break;
                }
            }
        }
        if (projTexId == null) return;

        // Render the projectile model at the player's position.
        // The projectile model's bone hierarchy (crossbow, bow→Arrow→ysmGlowArrow)
        // positions itself relative to the entity origin, matching the bow position.
        GlStateManager.pushMatrix();
        try {
            // Position at entity (super.doRender already popped its matrix)
            GlStateManager.translate(x, y, z);

            // Scale to match model scale
            float scale = 0.7f;
            GlStateManager.scale(scale, scale, scale);

            // Bind projectile texture
            Minecraft.getMinecraft().renderEngine.bindTexture(projTexId);

            // Render only the crossbow bone (bow mechanism) to avoid double-rendering
            // the arrow shaft/fletching when an arrow entity is also present.
            Tessellator tess = Tessellator.instance;
            tess.startDrawing(GL11.GL_QUADS);
            for (GeoBone bone : projModel.topLevelBones) {
                ((IGeoRenderer<CustomPlayerEntity>) this)
                    .renderRecursively(tess, this.animatable, bone, 1.0F, 1.0F, 1.0F, 1.0F);
            }
            tess.draw();
        } catch (Exception e) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-PROJ] Projectile overlay render failed for {}",
                baseModelId, e);
        } finally {
            GlStateManager.popMatrix();
        }
    }
}
