package com.fox.ysmu.client.renderer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;

import org.jetbrains.annotations.Nullable;

import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.model.CustomPlayerModel;
import com.fox.ysmu.client.renderer.layer.CustomPlayerItemInHandLayer;
import com.fox.ysmu.data.NPCData;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.event.api.SpecialPlayerRenderEvent;
import com.fox.ysmu.util.ModelIdUtil;

import it.unimi.dsi.fastutil.Pair;
import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;
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
}
