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
import com.fox.ysmu.ysmu;

import it.unimi.dsi.fastutil.Pair;
import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

public class CustomPlayerRenderer extends GeoReplacedEntityRenderer<CustomPlayerEntity> {

    private GeoModel geoModel;
    /** Tracks which model locations have already been logged as missing (throttle). */
    private final Set<String> missingGeoModelLogged = Collections.synchronizedSet(new HashSet<>());
    /** 诊断（DEBUG_MODEL_LOAD 门控）：本地玩家每个模型「首次实际渲染」已打印过（防刷屏）。 */
    private static final Set<String> FIRST_LOCAL_RENDER_LOGGED = ConcurrentHashMap.newKeySet();
    /** Tracks the last main model per player to detect model switches. */
    private final Map<UUID, ResourceLocation> lastPlayerModel = new ConcurrentHashMap<>();

    /**
     * Suppressed in-world render errors, keyed by tag|exceptionClass|message.
     * A model that fails to render (molang error, bone NPE, GeoModelException from
     * a geo evicted mid-frame) must skip the frame, not throw into Minecraft's
     * crash handler — that rebuilt the crash report every tick, spamming "Negative
     * index in crash report handler" and stuttering. Each unique error is logged
     * once (with stack) for diagnosis.
     */
    private static final Set<String> SUPPRESSED_RENDER_ERRORS = ConcurrentHashMap.newKeySet();

    private static void suppressRenderError(String tag, Exception e) {
        String key = tag + '|' + e.getClass().getName() + '|' + e.getMessage();
        if (SUPPRESSED_RENDER_ERRORS.add(key)) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-RENDER] {} suppressed ({}): {}",
                tag, e.getClass().getSimpleName(), String.valueOf(e.getMessage()), e);
        }
    }

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
                    ResourceLocation selTex = eep.getSelectTexture();
                    if (selTex == null || !com.fox.ysmu.client.ClientModelManager.isTextureRegistered(selTex)) {
                        // Persisted selection may reference a texture that was filtered
                        // out (e.g. author avatar) or never registered — fall back to
                        // the model's default texture (or first valid) and repair it.
                        java.util.List<ResourceLocation> validTex =
                            com.fox.ysmu.client.ClientModelManager.MODELS.get(eep.getModelId());
                        selTex = com.fox.ysmu.client.ClientModelManager.resolveDefaultTexture(newModel, validTex);
                        if (selTex != null) {
                            eep.setSelectTexture(selTex);
                        }
                    }
                    this.animatable.setTexture(selTex);
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
        ResourceLocation mainModelId = this.animatable != null
            ? this.animatable.getMainModel() : null;
        if (mainModelId != null) {
            // 记录「正在使用」（本地玩家/其他玩家/NPC 每帧渲染即使用）：刷新时间戳并
            // 首次主动预暖，驱动快速卸载扫描（切换模型后旧模型 ~5s 内释放）。
            com.fox.ysmu.client.ClientModelManager.markModelInUse(mainModelId);
        }
        GeoModel geoModel = GeckoLibCache.getInstance()
            .getGeoModels()
            .get(location);
        if (geoModel == null && mainModelId != null) {
            // Trigger a background reload through the asset lifecycle framework if the
            // geo was idle-unloaded; the result is applied on the main thread a few
            // frames later — no more render-thread stutter on reload.
            com.fox.ysmu.client.asset.AssetManager.geo(mainModelId).get();
            geoModel = GeckoLibCache.getInstance()
                .getGeoModels()
                .get(location);
        }
        if (geoModel != null) {
            this.geoModel = geoModel;
            if (mainModelId != null) {
                // 诊断（DEBUG_MODEL_LOAD 门控）：本地玩家模型「真正以自定义模型渲染」的
                // 首次时间（geo 可用 = apply 已完成），applyDoneMsAgo 即 apply → 真正显示
                // 的延迟；-1 表示尚未 apply（防御性记录，正常不应出现）。
                if (entityObj instanceof EntityPlayer p
                    && p.equals(net.minecraft.client.Minecraft.getMinecraft().thePlayer)
                    && com.fox.ysmu.Config.DEBUG_MODEL_LOAD) {
                    if (FIRST_LOCAL_RENDER_LOGGED.add(p.getUniqueID() + "|" + mainModelId)) {
                        Long appliedAt = com.fox.ysmu.client.ClientModelManager.MODEL_APPLY_TIME.get(mainModelId);
                        ysmu.LOG.info("[YSMU-RENDER] first custom render local player model: id={}, applyDoneMsAgo={}",
                            mainModelId, appliedAt != null ? (System.currentTimeMillis() - appliedAt) : -1L);
                    }
                }
                // Keep geo/anim warm via the framework and ensure textures are uploaded
                // (raw bytes stay in RAM, so upload is cheap and synchronous).
                com.fox.ysmu.client.asset.AssetManager.geo(mainModelId).touch();
                com.fox.ysmu.client.asset.AssetManager.anim(mainModelId).get();
                com.fox.ysmu.client.ClientModelManager.ensureTexturesLoaded(mainModelId);
            }
            com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime.setBoneTracking(true,
                getWidthScale(animatable), getHeightScale(animatable), getWidthScale(animatable),
                mainModelId);
            try {
                super.doRender(entityObj, x, y, z, entityYaw, partialTicks);
                // 渲染 AdventureBackpack2 背部可穿戴物品（直升机背包等）
                if (entityObj instanceof EntityPlayer player
                    && com.fox.ysmu.Config.RENDER_WEARABLE) {
                    com.fox.ysmu.compat.AdventureBackpackCompat.renderWearable(
                        player, x, y, z, partialTicks);
                }
            } catch (Exception e) {
                suppressRenderError("doRender[" + location + "]", e);
            } finally {
                com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime.setBoneTracking(false,
                    1.0F, 1.0F, 1.0F, null);
            }
        } else if (mainModelId == null
            || !com.fox.ysmu.client.asset.AssetManager.geo(mainModelId).isPending()) {
            // Only log as truly missing when the model isn't being (re)loaded in the
            // background — avoids false "missing" noise during async reload.
            // Throttled: only log once per missing model per game session.
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
