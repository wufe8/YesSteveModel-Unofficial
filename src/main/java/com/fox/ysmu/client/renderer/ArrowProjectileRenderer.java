package com.fox.ysmu.client.renderer;

import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;
import com.fox.ysmu.client.animation.controller.ProjectileTimelineRuntime;
import com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime;
import com.fox.ysmu.client.particle.ParticleEffectUtil;
import com.fox.ysmu.util.ModelIdUtil;

import software.bernie.geckolib3.core.easing.EasingManager;
import software.bernie.geckolib3.core.easing.EasingType;
import software.bernie.geckolib3.core.keyframe.BoneAnimation;
import software.bernie.geckolib3.core.keyframe.KeyFrame;
import software.bernie.geckolib3.core.keyframe.VectorKeyFrameList;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangStringPool;
import software.bernie.geckolib3.core.util.MathUtil;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.geo.render.built.GeoQuad;
import software.bernie.geckolib3.resource.GeckoLibCache;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;

import com.eliotlash.mclib.math.IValue;

/**
 * Renders a projectile sub-entity GeoModel (e.g. #arrow) in place of a vanilla entity.
 * Called from the RenderArrow mixin when the arrow has a custom model ID.
 */
public class ArrowProjectileRenderer {

    /** {@code ysm.on_ground_time} 的逐箭计时（EntityArrow 的 ticksInGround 是私有的，读不到）。 */
    private static final com.fox.ysmu.util.ProjectileGroundTracker GROUND_TRACKER =
        new com.fox.ysmu.util.ProjectileGroundTracker();

    /** DEBUG_ANIMATION 下逐箭 dump 的限流：每条箭每 20 tick 最多一行，避免刷屏。 */
    private static final java.util.Map<Integer, Integer> LAST_DEBUG_DUMP_TICK = new java.util.HashMap<>();

    // Track which projectile GeoModels have had their bone tree dumped to
    // avoid re-printing the massive tree every frame when DEBUG_MODEL_LOAD is on.
    private static final java.util.Set<ResourceLocation> DUMPED_TREES =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** 每帧渲染路径的告警去重：同一内容只打一次，避免模型缺 geo/贴图时每帧刷屏
     *  （与 RenderUtil 的 suppress 模式一致）。reload 后可通过 clearDumpedTrees 复位。 */
    private static final java.util.Set<String> LOGGED_RENDER_WARNS =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** Reset the dedup tracker so bone trees dump again on next render (after reload). */
    public static void clearDumpedTrees() {
        DUMPED_TREES.clear();
        LOGGED_RENDER_WARNS.clear();
    }

    /** 每帧渲染路径的 warn（去重：同一条消息只打一次）。 */
    private static void warnOnce(String tag, String format, Object... args) {
        String msg = String.format(java.util.Locale.ROOT, format, args);
        if (LOGGED_RENDER_WARNS.add(tag + "|" + msg)) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-ARROW] {}", msg);
        }
    }

    /**
     * Try to render a custom projectile model for the given entity and model ID.
     * @return true if a custom model was rendered (vanilla rendering should be skipped),
     *         false if no custom model is available (vanilla should proceed).
     */
    @SuppressWarnings("unchecked")
    public static boolean render(Entity entity, double x, double y, double z, float yaw, float partialTicks,
        ResourceLocation modelId, IGeoRenderer<?> geoRenderer) {

        if (modelId == null || entity == null) return false;

        if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD && com.fox.ysmu.Config.DEBUG_MODEL_RENDER) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] render start: modelId={}, entity={}", modelId, entity.getEntityId());
        }

        // Find projectile entity type "minecraft:arrow" for this model
        List<String> projTypes = ClientModelManager.PROJECTILE_MODEL_IDS.get(modelId);
        if (projTypes == null) {
            warnOnce("no-projectile-models", "no PROJECTILE_MODEL_IDS for {}", modelId);
            return false;
        }
        if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD && com.fox.ysmu.Config.DEBUG_MODEL_RENDER) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] projTypes={}", projTypes);
        }

        String arrowType = null;
        for (String t : projTypes) {
            if (t.contains("arrow")) {
                arrowType = t;
                break;
            }
        }
        if (arrowType == null) {
            warnOnce("no-arrow-type", "no arrowType found in {}", projTypes);
            return false;
        }

        // Get the projectile GeoModel
        ResourceLocation projGeoId = ModelIdUtil.getSubModelId(modelId, "projectile_" + arrowType);
        if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD && com.fox.ysmu.Config.DEBUG_MODEL_RENDER) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] looking for geo: {}", projGeoId);
        }
        GeoModel projModel = GeckoLibCache.getInstance().getGeoModels().get(projGeoId);
        if (projModel == null) {
            warnOnce("geo-not-found", "GeoModel not found: {}", projGeoId);
            return false;
        }
        if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD && com.fox.ysmu.Config.DEBUG_MODEL_RENDER) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] GeoModel found: topLevelBones={}",
                projModel.topLevelBones != null ? projModel.topLevelBones.size() : 0);
            if (projModel.topLevelBones != null) {
                for (GeoBone b : projModel.topLevelBones) {
                    com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW]   bone '{}': cubes={}, childBones={}, hidden={}",
                        b.name,
                        b.childCubes != null ? b.childCubes.size() : 0,
                        b.childBones != null ? b.childBones.size() : 0,
                        b.isHidden());
                }
            }
        }

        // Find projectile texture
        List<ResourceLocation> projTexList = ClientModelManager.PROJECTILE_TEXTURE_IDS.get(modelId);
        ResourceLocation projTexId = null;
        if (projTexList != null) {
            String prefix = "projectile_" + arrowType + "_";
            for (ResourceLocation tid : projTexList) {
                if (tid.getResourcePath().contains(prefix)) {
                    projTexId = tid;
                    break;
                }
            }
        }
        if (projTexId == null) {
            warnOnce("no-texture", "no texture found for {}", modelId);
            return false;
        }
        if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD && com.fox.ysmu.Config.DEBUG_MODEL_RENDER) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] texture: {}", projTexId);
        }

        // === Do a quick sanity check: dump bone tree once per model in debug mode ===
        if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD && com.fox.ysmu.Config.DEBUG_MODEL_RENDER && DUMPED_TREES.add(projGeoId)) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] === Full bone tree dump for {} ===", projGeoId);
            dumpBoneTree(projModel.topLevelBones, 0);
        }

        // === Render the actual projectile GeoModel ===
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(x, y, z);

            // Rotate to match arrow flight direction (vanilla convention)
            float interpYaw = entity.prevRotationYaw
                + (entity.rotationYaw - entity.prevRotationYaw) * partialTicks;
            float interpPitch = entity.prevRotationPitch
                + (entity.rotationPitch - entity.prevRotationPitch) * partialTicks;
            GL11.glRotatef(interpYaw - 90.0F, 0.0F, 1.0F, 0.0F);
            GL11.glRotatef(interpPitch, 0.0F, 0.0F, 1.0F);
            GL11.glScalef(0.7f, 0.7f, 0.7f);

            net.geckominecraft.client.renderer.GlStateManager.disableCull();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            Minecraft.getMinecraft().renderEngine.bindTexture(projTexId);

            // Apply projectile animations (parallel0-7, post_main, etc.)
            // before rendering so bone transforms are correct.
            if (entity instanceof EntityArrow arrow) {
                applyProjectileAnimations(projModel, arrow, partialTicks, projGeoId, modelId, arrowType,
                    x, y, z, interpYaw, interpPitch);
            }

            // Render all bone cubes through GeckoLib's renderRecursively
            // which properly applies MATRIX_STACK bone transforms (pivot/rotation).
            Tessellator tess = Tessellator.instance;
            tess.startDrawing(GL11.GL_QUADS);
            for (GeoBone bone : projModel.topLevelBones) {
                ((IGeoRenderer<Entity>) geoRenderer)
                    .renderRecursively(tess, entity, bone, 1.0F, 1.0F, 1.0F, 1.0F);
            }
            tess.draw();
        } catch (Throwable e) {
            // 每帧路径：Error 也要接住（否则 vanilla 会每帧构造一份 CrashReport），并按内容去重。
            String key = "render|" + e.getClass().getName() + '|' + e.getMessage();
            if (LOGGED_RENDER_WARNS.add(key)) {
                com.fox.ysmu.ysmu.LOG.warn("[YSMU-ARROW] Model render failed", e);
            }
        } finally {
            GL11.glPopMatrix();
        }
        return true;
    }

    /**
     * Apply projectile animation keyframes to the GeoModel bones before rendering.
     * Evaluates Molang expressions in keyframes using the arrow entity context.
     *
     * @param renderX/renderY/renderZ 实体渲染插值坐标（= 模型原点），供时间轴里的
     *        {@code ysm.particle(...)} 把模型空间偏移换算到世界坐标；与 {@code glTranslated} 同源。
     * @param interpYaw/interpPitch   与 {@code glRotatef} 相同的模型旋转，粒子偏移沿用同一套。
     */
    @SuppressWarnings("rawtypes")
    private static void applyProjectileAnimations(GeoModel model, EntityArrow arrow, float partialTicks,
        ResourceLocation projGeoId, ResourceLocation modelId, String arrowType,
        double renderX, double renderY, double renderZ, float interpYaw, float interpPitch) {
        // Get the animation file for this projectile
        AnimationFile animFile = GeckoLibCache.getInstance().getAnimations().get(projGeoId);
        if (animFile == null || animFile.animations == null) {
            // No animations defined for this projectile
            return;
        }

        // Calculate current animation time from arrow age
        double ageInTicks = arrow.ticksExisted + partialTicks;

        // Set projectile-specific Molang variables into the shared parser context.
        // MolangParser.VARIABLES is a static map shared across all evaluation.
        // Detect in-ground state: check position delta instead of motion.
        // In 1.7.10 EntityArrow, motionX/Y/Z may retain non-zero values even
        // when the arrow is stuck (onCollide doesn't always clear them).
        // Position delta (prevPos vs pos) is more reliable.
        double dx = arrow.posX - arrow.prevPosX;
        double dy = arrow.posY - arrow.prevPosY;
        double dz = arrow.posZ - arrow.prevPosZ;
        double deltaLength = Math.sqrt(dx * dx + dy * dy + dz * dz);
        boolean isInGround = !arrow.isDead
            && (deltaLength < 0.0001 || arrow.onGround);
        // 本方法里所有 DEBUG_ANIMATION 日志都是逐帧的，先统一过一遍限流
        // （每支箭每 20 tick 至多一行），否则开调试时一个屏幕的箭就能刷爆日志。
        boolean debugThisTick = com.fox.ysmu.Config.DEBUG_ANIMATION
            && allowArrowDebug(arrow.getEntityId(), arrow.ticksExisted);
        if (debugThisTick) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] inGround detection: isDead={}, deltaLen={}, onGround={}, isInGround={}",
                arrow.isDead, deltaLength, arrow.onGround, isInGround);
        }
        setMolangVar("ysm.in_ground", isInGround ? 1.0 : 0.0);
        setMolangVar("ysm.delta_movement_length", deltaLength);
        // ysm.shoot_item_id：wiki 语义是"射出此箭的物品 id"（用来区分普通弓和弩）。
        // 1.7.10 的 EntityArrow 不记录发射武器，而且 1.7.10 没有弩，所以返回**空串**的
        // 池化 id（MolangStringPool.EMPTY_ID）。模型的写法是
        //   "bow":      scale = ysm.shoot_item_id != 'minecraft:crossbow'
        //   "crossbow": scale = ysm.shoot_item_id == 'minecraft:crossbow'
        // 与空串比较都会落到"弓"那一支。绝不能填 1 这种数字：那会撞上池化字符串 id。
        setMolangVar("ysm.shoot_item_id", MolangStringPool.EMPTY_ID);
        // ysm.on_ground_time：wiki 里单位是**刻**，落地后累计、被移动则归零。
        // 原实现写的是 ageInTicks * 0.05（= 从发射到现在的秒数），量纲和起点都不对，
        // 模型里 ysm.on_ground_time <= 2 这种"刚落地"判断永远不会在刚落地时成立。
        if (arrow.isDead) {
            GROUND_TRACKER.forget(arrow.getEntityId());
            com.fox.ysmu.client.animation.controller.ProjectileTimelineRuntime
                .forget(arrow.getEntityId(), projGeoId);
        }
        setMolangVar("ysm.on_ground_time",
            GROUND_TRACKER.update(arrow.getEntityId(), isInGround, arrow.ticksExisted));

        // Inject roaming variables from PENDING_ROAMING (client-side GUI-set values).
        for (Map.Entry<String, Double> entry : OpenYsmPlayerControllerRuntime.PENDING_ROAMING.entrySet()) {
            setMolangVar("v.roaming." + entry.getKey(), entry.getValue());
        }

        // Save initial snapshots if not already done
        saveInitialSnapshots(model.topLevelBones);

        // Reset all bones to their initial snapshot before applying animations
        resetBonesToSnapshot(model.topLevelBones);

        // Determine which animations should play.
        // The state animations (air/ground/fire/water) are selected by entity state and the
        // parallel0..7 slots always play; controller state machines (when the model declares
        // one) contribute their own entries on top. This path is used whether or not the
        // model has controllers — the old "no controllers → play every animation" fallback
        // played all four state animations at once, which stacked every sub-model variant
        // (bow/crossbow/impact effects) onto the same projectile entity.
        boolean hasControllers = com.fox.ysmu.client.animation.controller.OpenYsmAnimationControllerRegistry
            .get(projGeoId) != null;
        if (debugThisTick) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] applyProjectileAnimations: entityId={}, animFile={}, animCount={}, hasControllers={}",
                arrow.getEntityId(), projGeoId,
                animFile != null && animFile.animations != null ? animFile.animations.size() : 0,
                hasControllers);
        }
        List<com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime.ActiveAnimation> activeEntries =
            com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime
                .getActiveAnimationEntries(
                    arrow.getEntityId(),
                    projGeoId,
                    ageInTicks,
                    com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime.ProjectileState
                        .of(isInGround, arrow.isInWater(), arrow.isBurning()));
        List<String> activeAnims = new java.util.ArrayList<>(activeEntries.size());
        Map<String, Double> tickOffsets = new java.util.HashMap<>();
        for (com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime.ActiveAnimation entry : activeEntries) {
            activeAnims.add(entry.name);
            if (entry.tickOffset != 0.0d) {
                tickOffsets.put(entry.name, entry.tickOffset);
            }
        }
        if (debugThisTick) {
            com.fox.ysmu.ysmu.LOG.info(
                "[YSMU-ARROW] selected {} active anims (inGround={}, inWater={}, burning={}): {}",
                activeAnims.size(), isInGround, arrow.isInWater(), arrow.isBurning(), activeAnims);
        }

        if (activeAnims.isEmpty()) {
            if (debugThisTick) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] activeAnims empty, rendering bind pose");
            }
            return; // No active animations — render in bind pose
        }

        applyActiveAnimations(model, animFile, activeAnims, tickOffsets, ageInTicks, debugThisTick);

        // 时间轴（飞行拖尾 / 命中水花）：必须在骨骼写完之后派发——模型里的
        // ysm.bone_pivot_abs(...) 要读这一帧的骨骼姿态。粒子函数还要"当前实体"上下文，
        // 以及把模型空间偏移按箭矢的渲染变换换算成世界坐标。
        dispatchProjectileTimelines(arrow, projGeoId, animFile, activeAnims, ageInTicks, model,
            renderX, renderY, renderZ, interpYaw, interpPitch);

        // Debug: dump bone scales after all animations applied
        if (debugThisTick) {
            for (GeoBone bone : model.topLevelBones) {
                if (bone == null) continue;
                com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] bone '{}' final: scale=({},{},{}) pos=({},{},{}) rot=({},{},{})",
                    bone.name,
                    bone.getScaleX(), bone.getScaleY(), bone.getScaleZ(),
                    bone.getPositionX(), bone.getPositionY(), bone.getPositionZ(),
                    bone.getRotationX(), bone.getRotationY(), bone.getRotationZ());
                dumpBoneScales(bone.childBones, 1);
            }
            // Also dump Molang variable values that drive the animations
            dumpMolangVars();
        }
    }

    /**
     * 派发弹射物动画的时间轴指令（飞行拖尾、命中水花等），并为它们准备三样上下文：
     *
     * <ol>
     *   <li>{@code ysm.particle(...)} 读的"当前实体" = 这支箭（
     *       {@link ParticleEffectUtil#setCurrentEntity}）；</li>
     *   <li>粒子偏移的基准点/旋转 = 箭矢的渲染变换（模型空间偏移由模型自己用
     *       {@code bone_pivot_abs(...)/16*0.7} 算出，需要按 {@code yaw-90}/{@code pitch} 转过去）；</li>
     *   <li>{@code ysm.bone_pivot_abs(...)} 读的骨骼表 = 这份几何（弹射物不注册进玩家 processor，
     *       没有这份表时该函数恒返回 0，粒子只能落在实体原点）。</li>
     * </ol>
     *
     * <p>三个上下文都在 finally 里还原，玩家渲染路径不受影响。</p>
     */
    private static void dispatchProjectileTimelines(EntityArrow arrow, ResourceLocation projGeoId,
        AnimationFile animFile, List<String> activeAnims, double ageInTicks, GeoModel model,
        double renderX, double renderY, double renderZ, float interpYaw, float interpPitch) {
        Entity previousEntity = ParticleEffectUtil.getCurrentEntity();
        ParticleEffectUtil.setCurrentEntity(arrow);
        ParticleEffectUtil.beginProjectileTransform(renderX, renderY, renderZ, interpYaw, interpPitch);
        java.util.Map<String, software.bernie.geckolib3.core.processor.IBone> previousBones =
            MolangPhysicsRuntime.beginProjectileBones(model.topLevelBones);
        try {
            ProjectileTimelineRuntime.dispatch(arrow.getEntityId(), projGeoId, animFile, activeAnims, ageInTicks);
        } catch (Throwable e) {
            // 每帧路径，按内容去重（模型的时间轴表达式可能引用本环境没有的函数）。
            String key = "timeline|" + e.getClass().getName() + '|' + e.getMessage();
            if (LOGGED_RENDER_WARNS.add(key)) {
                com.fox.ysmu.ysmu.LOG.warn("[YSMU-ARROW] Projectile timeline dispatch failed", e);
            }
        } finally {
            MolangPhysicsRuntime.endProjectileBones(previousBones);
            ParticleEffectUtil.endProjectileTransform();
            ParticleEffectUtil.setCurrentEntity(previousEntity);
        }
    }

    /**
     * Writes the keyframes of {@code activeAnims} onto {@code model}'s bones, in list order:
     * later animations overwrite earlier ones bone-by-bone, which is how the animation priority
     * of {@link com.fox.ysmu.client.animation.controller.ProjectileControllerRuntime} is realised.
     *
     * <p>Package-private and free of entity/GL state on purpose: it lets a plain unit test replay
     * a real model's projectile animations and inspect the resulting bone transforms.</p>
     *
     * <p>这条重载把所有动画都按实体年龄采样（偏移 0），供只有动画名的调用方和单测使用；
     * 正式渲染路径用下面带 {@code tickOffsets} 的重载，让控制器状态动画从进入状态那刻计时。</p>
     */
    static void applyActiveAnimations(GeoModel model, AnimationFile animFile, List<String> activeAnims,
        double ageInTicks, boolean debugThisTick) {
        applyActiveAnimations(model, animFile, activeAnims, java.util.Collections.emptyMap(), ageInTicks,
            debugThisTick);
    }

    /**
     * 同上，但每条动画可以带一个"相对所属控制器状态进入时刻"的 tick 偏移
     * （{@code ProjectileControllerRuntime.ActiveAnimation#tickOffset}）：采样时刻 =
     * {@code ageInTicks - tickOffset}。缺失的动画按偏移 0 处理。
     */
    static void applyActiveAnimations(GeoModel model, AnimationFile animFile, List<String> activeAnims,
        Map<String, Double> tickOffsets, double ageInTicks, boolean debugThisTick) {
        // Only apply keyframes from the active animations
        for (String animName : activeAnims) {
            Animation anim = animFile.animations.get(animName);
            if (anim == null || anim.boneAnimations == null) {
                if (debugThisTick && ("parallel0".equals(animName) || "post_main".equals(animName))) {
                    com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] SKIP anim='{}': anim={} boneAnims={}",
                        animName, anim != null ? "OK" : "NULL",
                        anim != null ? (anim.boneAnimations != null ? anim.boneAnimations.size() : "NULL") : "N/A");
                }
                continue;
            }

            if (debugThisTick && ("parallel0".equals(animName) || "post_main".equals(animName))) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] ENTER anim='{}': boneAnimations={}",
                    animName, anim.boneAnimations.size());
                for (BoneAnimation ba : anim.boneAnimations) {
                    boolean hasScale = ba.scaleKeyFrames != null
                        && ba.scaleKeyFrames.xKeyFrames != null
                        && !ba.scaleKeyFrames.xKeyFrames.isEmpty();
                    com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW]   bone='{}' scaleKF={} rotKF={} posKF={}",
                        ba.boneName, hasScale,
                        ba.rotationKeyFrames != null && ba.rotationKeyFrames.xKeyFrames != null
                            ? ba.rotationKeyFrames.xKeyFrames.size() : 0,
                        ba.positionKeyFrames != null && ba.positionKeyFrames.xKeyFrames != null
                            ? ba.positionKeyFrames.xKeyFrames.size() : 0);
                }
            }

            // 控制器状态动画从进入状态那刻计时（偏移 > 0）；其余动画偏移 0，等于实体年龄。
            Double offset = tickOffsets.get(animName);
            double animAge = ageInTicks - (offset != null ? offset : 0.0d);

            double animLength = anim.animationLength != null ? anim.animationLength : 0;
            double animTick;
            if (animLength > 0) {
                // For non-looping animations (PLAY_ONCE, HOLD_ON_LAST_FRAME),
                // clamp the time to animation length — do NOT wrap with %.
                // Wrapping causes the animation to restart repeatedly, which
                // makes bone scales like Arrow_E oscillate between 0 and 1.
                boolean isLooping = anim.loop != null && anim.loop.isRepeatingAfterEnd()
                    && anim.loop != ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME;
                if (isLooping) {
                    animTick = animAge % animLength;
                } else {
                    animTick = Math.min(animAge, animLength);
                }
            } else {
                animTick = animAge;
            }

            // For each bone animated by this animation
            for (BoneAnimation boneAnim : anim.boneAnimations) {
                GeoBone bone = findBone(model.topLevelBones, boneAnim.boneName);
                if (bone == null) continue;

                BoneSnapshot snap = bone.getInitialSnapshot();

                // Debug: log keyframe info for parallel0's critical bones
                if (debugThisTick && "parallel0".equals(animName)
                    && ("bow".equals(boneAnim.boneName) || "crossbow".equals(boneAnim.boneName))) {
                    String skf = (boneAnim.scaleKeyFrames != null
                        && boneAnim.scaleKeyFrames.xKeyFrames != null
                        && !boneAnim.scaleKeyFrames.xKeyFrames.isEmpty())
                        ? "hasScaleKF" : "NO_SCALE_KF";
                    com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW]   anim='{}' bone='{}': {}",
                        animName, boneAnim.boneName, skf);
                }

                // Apply rotation keyframes
                applyKeyFrameList(bone, boneAnim.rotationKeyFrames, animTick,
                    snap.rotationValueX, snap.rotationValueY, snap.rotationValueZ,
                    true);

                // Apply position keyframes
                applyKeyFrameListPosition(bone, boneAnim.positionKeyFrames, animTick,
                    snap.positionOffsetX, snap.positionOffsetY, snap.positionOffsetZ);

                // Apply scale keyframes
                applyKeyFrameListScale(bone, boneAnim.scaleKeyFrames, animTick,
                    snap.scaleValueX, snap.scaleValueY, snap.scaleValueZ, debugThisTick);
            }
        }
    }

    private static void dumpBoneScales(java.util.List<GeoBone> bones, int depth) {
        if (bones == null) return;
        for (GeoBone bone : bones) {
            if (bone == null) continue;
            StringBuilder indent = new StringBuilder();
            for (int i = 0; i < depth; i++) indent.append("  ");
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] {}bone '{}' final: scale=({},{},{})",
                indent.toString(), bone.name,
                bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());
            dumpBoneScales(bone.childBones, depth + 1);
        }
    }

    private static void dumpMolangVars() {
        double shootId = 0.0, inGround = 0.0, deltaLength = 0.0, onGroundTime = 0.0;
        software.bernie.geckolib3.core.molang.LazyVariable v;
        v = MolangParser.VARIABLES.get("ysm.shoot_item_id");
        if (v != null) shootId = v.get();
        v = MolangParser.VARIABLES.get("ysm.in_ground");
        if (v != null) inGround = v.get();
        v = MolangParser.VARIABLES.get("ysm.delta_movement_length");
        if (v != null) deltaLength = v.get();
        v = MolangParser.VARIABLES.get("ysm.on_ground_time");
        if (v != null) onGroundTime = v.get();
        com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] Molang vars: ysm.shoot_item_id={}, ysm.in_ground={}, ysm.delta_movement_length={}, ysm.on_ground_time={}",
            shootId, inGround, deltaLength, onGroundTime);
    }

    private static void saveInitialSnapshots(List<GeoBone> bones) {
        if (bones == null) return;
        for (GeoBone bone : bones) {
            bone.saveInitialSnapshot();
            saveInitialSnapshots(bone.childBones);
        }
    }

    private static void resetBonesToSnapshot(List<GeoBone> bones) {
        if (bones == null) return;
        for (GeoBone bone : bones) {
            // Clear any persistent hidden state so renderRecursively's
            // scale=(0,0,0) check is the sole visibility gatekeeper.
            if (bone.isHidden()) {
                bone.setHidden(false);
            }
            BoneSnapshot snap = bone.getInitialSnapshot();
            if (snap != null) {
                bone.setRotationX(snap.rotationValueX);
                bone.setRotationY(snap.rotationValueY);
                bone.setRotationZ(snap.rotationValueZ);
                bone.setPositionX((float) snap.positionOffsetX);
                bone.setPositionY((float) snap.positionOffsetY);
                bone.setPositionZ((float) snap.positionOffsetZ);
                bone.setScaleX((float) snap.scaleValueX);
                bone.setScaleY((float) snap.scaleValueY);
                bone.setScaleZ((float) snap.scaleValueZ);
            }
            resetBonesToSnapshot(bone.childBones);
        }
    }

    private static GeoBone findBone(List<GeoBone> bones, String name) {
        if (bones == null) return null;
        for (GeoBone bone : bones) {
            if (bone.name.equals(name)) return bone;
            GeoBone found = findBone(bone.childBones, name);
            if (found != null) return found;
        }
        return null;
    }

    private static void applyKeyFrameList(GeoBone bone, VectorKeyFrameList<KeyFrame<IValue>> frames,
        double tick, double snapX, double snapY, double snapZ, boolean isRotation) {
        if (!hasAllAxes(frames)) return;
        float[] result = evaluateKeyFrameList(frames, tick);
        if (result != null) {
            // Animation values are ADDED to the initial snapshot (GeckoLib convention)
            bone.setRotationX((float) (result[0] + snapX));
            bone.setRotationY((float) (result[1] + snapY));
            bone.setRotationZ((float) (result[2] + snapZ));
        }
    }

    /**
     * 一条骨骼通道是否真的有数据：Bedrock 的标量关键帧（{@code "scale": 0.9}）会被展开成三轴
     * 关键帧，所以正常写法三轴都有数据；三轴全空说明模型只写了**别的通道**（例如只写 rotation）。
     *
     * <p>必须按"三轴齐全"判断，不能只看 {@code frames != null}：解析器会给没写的通道留下一个
     * 存在但轴列表为空的 {@code VectorKeyFrameList}，此时 {@link #evaluateAxis} 对每个空轴返回 0，
     * 于是"只旋转"的动画会把前一条动画写好的缩放**清零**。真实案例：某弹射物的外圈骨骼由
     * {@code parallel2} 写 0.9 缩放、由 {@code parallel3} 只写旋转，结果缩放松在 0 上，落地后
     * 旋转的六边形完全不可见。GeckoLib 自己的玩家路径（{@code AnimationProcessor.tickAnimation}）
     * 也是在 {@code rX/rY/rZ}（或 {@code sX/sY/sZ}）三个点都非空时才写这条通道。</p>
     */
    private static boolean hasAllAxes(VectorKeyFrameList<KeyFrame<IValue>> frames) {
        return frames != null
            && frames.xKeyFrames != null && !frames.xKeyFrames.isEmpty()
            && frames.yKeyFrames != null && !frames.yKeyFrames.isEmpty()
            && frames.zKeyFrames != null && !frames.zKeyFrames.isEmpty();
    }

    private static void applyKeyFrameListPosition(GeoBone bone, VectorKeyFrameList<KeyFrame<IValue>> frames,
        double tick, double snapX, double snapY, double snapZ) {
        if (!hasAllAxes(frames)) return;
        float[] result = evaluateKeyFrameList(frames, tick);
        if (result != null) {
            bone.setPositionX((float) (result[0] + snapX));
            bone.setPositionY((float) (result[1] + snapY));
            bone.setPositionZ((float) (result[2] + snapZ));
        }
    }

    private static void applyKeyFrameListScale(GeoBone bone, VectorKeyFrameList<KeyFrame<IValue>> frames,
        double tick, double snapX, double snapY, double snapZ, boolean debug) {
        if (!hasAllAxes(frames)) {
            if (debug && ("bow".equals(bone.name) || "crossbow".equals(bone.name))) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] applyKeyFrameListScale('{}'): no scale axes on this channel, snap=({},{},{})",
                    bone.name, snapX, snapY, snapZ);
            }
            return;
        }
        float[] result = evaluateKeyFrameList(frames, tick);
        if (result != null) {
            if (debug && ("bow".equals(bone.name) || "crossbow".equals(bone.name))) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] applyKeyFrameListScale('{}'): kfResult=({},{},{}), snap=({},{},{}), xLen={}, yLen={}, zLen={}",
                    bone.name,
                    result[0], result[1], result[2],
                    snapX, snapY, snapZ,
                    frames.xKeyFrames != null ? frames.xKeyFrames.size() : 0,
                    frames.yKeyFrames != null ? frames.yKeyFrames.size() : 0,
                    frames.zKeyFrames != null ? frames.zKeyFrames.size() : 0);
            }
            bone.setScaleX((float) (result[0] * snapX));  // Scale is multiplicative, not additive
            bone.setScaleY((float) (result[1] * snapY));
            bone.setScaleZ((float) (result[2] * snapZ));
        }
    }

    /**
     * Evaluate a VectorKeyFrameList at the given tick.
     * Returns [x, y, z] values interpolated from the current keyframe.
     */
    private static float[] evaluateKeyFrameList(VectorKeyFrameList<KeyFrame<IValue>> frames, double tick) {
        if (frames == null) return null;
        float[] result = new float[3];
        result[0] = evaluateAxis(frames.xKeyFrames, tick);
        result[1] = evaluateAxis(frames.yKeyFrames, tick);
        result[2] = evaluateAxis(frames.zKeyFrames, tick);
        return result;
    }

    /**
     * Evaluate a single axis keyframe list at the given tick.
     * Finds the current keyframe, evaluates start/end values (Molang), and interpolates.
     */
    private static float evaluateAxis(List<KeyFrame<IValue>> keyFrames, double tick) {
        if (keyFrames == null || keyFrames.isEmpty()) return 0;

        // If there's only one keyframe, return its end value
        if (keyFrames.size() == 1) {
            return (float) keyFrames.get(0).getEndValueDouble();
        }

        // Find the current keyframe
        double totalTime = 0;
        for (int i = 0; i < keyFrames.size(); i++) {
            KeyFrame<IValue> frame = keyFrames.get(i);
            double frameLength = frame.getLength() != null ? frame.getLength() : 0;
            double nextTotal = totalTime + frameLength;

            if (nextTotal > tick || i == keyFrames.size() - 1) {
                // This is the current keyframe
                double localTick = tick - totalTime;
                double progress = frameLength > 0 ? Math.min(localTick / frameLength, 1.0) : 1.0;

                double startVal = frame.getStartValueDouble();
                double endVal = frame.getEndValueDouble();

                // Apply easing
                EasingType easing = frame.easingType != null ? frame.easingType : EasingType.Linear;
                double easedProgress = EasingManager.ease(progress, easing, frame.easingArgs);

                return (float) MathUtil.lerp(easedProgress, startVal, endVal);
            }
            totalTime = nextTotal;
        }

        // Past all keyframes - return last keyframe's end value
        KeyFrame<IValue> lastFrame = keyFrames.get(keyFrames.size() - 1);
        return (float) lastFrame.getEndValueDouble();
    }

    /**
     * Set a Molang variable in the shared parser context.
     */
    private static void setMolangVar(String name, double value) {
        MolangParser.VARIABLES.computeIfAbsent(name, k -> new LazyVariable(k, () -> 0.0))
            .set(value);
    }

    /**
     * {@code DEBUG_ANIMATION} 下逐箭日志的限流：同一支箭每 20 tick 至多放行一次。
     *
     * <p>约定：常驻诊断必须同时"有 DEBUG 开关"和"限流"，否则一支箭停在屏幕上就会每帧刷一行。
     * 返回 {@code true} 表示这一帧可以打印。</p>
     */
    private static boolean allowArrowDebug(int entityId, int entityTicks) {
        Integer last = LAST_DEBUG_DUMP_TICK.get(entityId);
        if (last != null && entityTicks - last < 20) {
            return false;
        }
        if (LAST_DEBUG_DUMP_TICK.size() > 512) {
            LAST_DEBUG_DUMP_TICK.clear();
        }
        LAST_DEBUG_DUMP_TICK.put(entityId, entityTicks);
        return true;
    }

    /**
     * Dump the full bone tree structure to the log for debugging.
     */
    private static void dumpBoneTree(List<GeoBone> bones, int indent) {
        if (bones == null) return;
        for (GeoBone bone : bones) {
            if (bone == null) continue;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < indent; i++) sb.append("  ");
            sb.append("bone '").append(bone.name).append("'");
            sb.append(" cubes=").append(bone.childCubes != null ? bone.childCubes.size() : 0);
            sb.append(" childBones=").append(bone.childBones != null ? bone.childBones.size() : 0);
            sb.append(" hidden=").append(bone.isHidden());
            if (bone.childCubes != null) {
                for (int ci = 0; ci < bone.childCubes.size(); ci++) {
                    GeoCube c = bone.childCubes.get(ci);
                    sb.append("\n");
                    for (int j = 0; j < indent + 1; j++) sb.append("  ");
                    sb.append("cube[").append(ci).append("]: mesh=").append(c != null ? c.mesh : "null");
                    sb.append(" quads=").append((c != null && c.quads != null) ? c.quads.length : 0);
                    sb.append(" pivot=").append(c != null && c.pivot != null ? 
                        String.format("(%.3f,%.3f,%.3f)", c.pivot.x, c.pivot.y, c.pivot.z) : "null");
                    sb.append(" size=").append(c != null && c.size != null ? 
                        String.format("(%.3f,%.3f,%.3f)", c.size.x, c.size.y, c.size.z) : "null");
                    if (c != null && c.quads != null) {
                        for (int qi = 0; qi < c.quads.length; qi++) {
                            GeoQuad q = c.quads[qi];
                            if (q != null && q.vertices != null) {
                                sb.append("\n");
                                for (int j = 0; j < indent + 2; j++) sb.append("  ");
                                sb.append("quad[").append(qi).append("]: verts=").append(q.vertices.length);
                                if (q.vertices.length > 0 && q.vertices[0] != null) {
                                    sb.append(" pos0=(")
                                        .append(String.format("%.4f", q.vertices[0].position.x)).append(",")
                                        .append(String.format("%.4f", q.vertices[0].position.y)).append(",")
                                        .append(String.format("%.4f", q.vertices[0].position.z)).append(")");
                                }
                            } else {
                                sb.append(" [null quad]");
                            }
                        }
                    }
                }
            }
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] {}", sb.toString());
            dumpBoneTree(bone.childBones, indent + 1);
        }
    }

}
