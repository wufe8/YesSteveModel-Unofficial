package com.fox.ysmu.client.model;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.animation.AnimationRegister;
import com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime;
import com.fox.ysmu.client.animation.RemotePlayerAnimationQueries;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.model.AnimatedGeoModel;
import software.bernie.geckolib3.model.provider.data.EntityModelData;
import software.bernie.geckolib3.resource.GeckoLibCache;

@SuppressWarnings("all")
public class CustomPlayerModel extends AnimatedGeoModel {

    public static final ResourceLocation DEFAULT_MAIN_MODEL = ModelIdUtil
        .getMainId(new ResourceLocation(ysmu.MODID, "default"));
    public static final ResourceLocation DEFAULT_MAIN_ANIMATION = ModelIdUtil
        .getMainId(new ResourceLocation(ysmu.MODID, "default"));
    public static final ResourceLocation DEFAULT_TEXTURE = new ResourceLocation(ysmu.MODID, "default/default.png");
    public static float FIRST_PERSON_HEAD_POS;
    private final Map<IBone, HeadPoseOffset> headPoseOffsets = new IdentityHashMap<>();
    /** Cached bone names belonging to each model's preview animation. */
    private static final Map<ResourceLocation, java.util.Set<String>> PREVIEW_BONE_CACHE = new java.util.HashMap<>();
    /** Tracks which model+player combos have been logged for visibility diagnostics (throttle). */
    private static final java.util.Set<String> VISIBILITY_LOG_THROTTLE = java.util.Collections.newSetFromMap(
        new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    @Override

    public ResourceLocation getModelLocation(Object object) {
        if (object instanceof CustomPlayerEntity customPlayer) {
            return customPlayer.getMainModel();
        }
        return DEFAULT_MAIN_MODEL;
    }

    @Override

    public ResourceLocation getTextureLocation(Object object) {
        if (object instanceof CustomPlayerEntity customPlayer) {
            return customPlayer.getTexture();
        }
        return DEFAULT_TEXTURE;
    }

    @Override

    public ResourceLocation getAnimationFileLocation(Object object) {
        if (object instanceof CustomPlayerEntity customPlayer) {
            return customPlayer.getAnimation();
        }
        return DEFAULT_MAIN_ANIMATION;
    }

    @Override
    public void setLivingAnimations(IAnimatable animatable, Integer instanceId, AnimationEvent animationEvent) {
        clearHeadPoseOffsets();
        List extraData = animationEvent.getExtraData();
        MolangParser parser = GeckoLibCache.getInstance().parser;
        if (!Minecraft.getMinecraft()
            .isGamePaused() && extraData.size() == 1
            && extraData.get(0) instanceof EntityModelData data
            && animatable instanceof CustomPlayerEntity customPlayer
            && customPlayer.getPlayer() != null) {
            EntityPlayer player = customPlayer.getPlayer();
            AnimationRegister.setParserValue(animationEvent, parser, data, player);
            try {
                super.setLivingAnimations(animatable, instanceId, animationEvent);
                this.codeAnimation(animationEvent, data, player);
                applyWeaponBoneVisibility(customPlayer, player);
            } finally {
                MolangPhysicsRuntime.end();
            }
        } else {
            try {
                super.setLivingAnimations(animatable, instanceId, animationEvent);
            } finally {
                MolangPhysicsRuntime.end();
            }
        }
    }

    /**
     * Hides weapon display bones when the player isn't holding a matching item.
     * Uses the same detection chain (ConditionManager → ConditionalHold) that
     * drives weapon animations, so bone visibility stays in sync.
     */
    private void applyWeaponBoneVisibility(CustomPlayerEntity customPlayer, EntityPlayer player) {
        ResourceLocation animId = customPlayer.getAnimation();
        // Use the same hold detection as predicateMainhandHold/predicateOffhandHold
        boolean hasMainhandItem = false;
        boolean hasOffhandItem = false;
        try {
            com.fox.ysmu.client.animation.condition.ConditionalHold mainHand =
                com.fox.ysmu.client.animation.condition.ConditionManager.getHoldMainhand(animId);
            String mainResult = mainHand != null ? mainHand.doTest(player, true) : null;
            hasMainhandItem = mainResult != null && mainResult.contains(":sword");

            com.fox.ysmu.client.animation.condition.ConditionalHold offHand =
                com.fox.ysmu.client.animation.condition.ConditionManager.getHoldOffhand(animId);
            String offResult = offHand != null ? offHand.doTest(player, false) : null;
            hasOffhandItem = offResult != null && offResult.contains(":sword");
        } catch (Exception e) {
            // Fallback: use InnerClassify for type detection
            net.minecraft.item.ItemStack mainStack = player.getHeldItem();
            hasMainhandItem = mainStack != null && "sword".equals(
                com.fox.ysmu.client.animation.condition.InnerClassify.getItemType(mainStack));
            net.minecraft.item.ItemStack offStack = com.fox.ysmu.compat.BackhandCompat.getOffhandItem(player);
            hasOffhandItem = offStack != null && "sword".equals(
                com.fox.ysmu.client.animation.condition.InnerClassify.getItemType(offStack));
        }
        // 攻击组合技 (hasActiveCombo) 已注释掉 (2025-06-26)
        // if (com.fox.ysmu.client.animation.AnimationManager.hasActiveCombo(player)) {
        //     hasMainhandItem = true;
        // }
        List<IBone> bones = getAnimationProcessor().getModelRendererList();
        // Check if an extra animation (wheel animation) is currently playing.
        com.fox.ysmu.eep.ExtendedModelInfo eep = null;
        boolean extraAnimActive = false;
        String extraAnimName = null;
        try {
            eep = com.fox.ysmu.eep.ExtendedModelInfo.get(player);
            extraAnimActive = eep != null && eep.isPlayAnimation();
            if (extraAnimActive) {
                extraAnimName = eep.getAnimation();
            }
        } catch (Exception ignored) {}
        int totalBones = 0;
        int hiddenCount = 0;
        int expressionCount = 0;
        int previewCount = 0;
        java.util.List<String> hiddenBoneNames = new java.util.ArrayList<>();
        for (IBone bone : bones) {
            if (bone instanceof GeoBone) {
                String name = ((GeoBone) bone).getName();
                if (name == null) continue;
                boolean isWeaponBone = name.contains("_Sword") || name.contains("_Blade")
                    || name.contains("_Handle") || name.contains("_Thruster")
                    || name.contains("_WolfHead") || name.contains("Knife");
                boolean isRightHand = name.startsWith("Right_") || name.startsWith("MRight_");
                boolean isLeftHand = name.startsWith("Left_") || name.startsWith("MLeft_");
                if (isWeaponBone) {
                    boolean visible = isRightHand ? hasMainhandItem
                        : isLeftHand ? hasOffhandItem
                        : hasMainhandItem || hasOffhandItem;
                    bone.setHidden(!visible);
                }
                // Hide expression/effect overlay bones by default.
                // Extra (wheel) animations explicitly set these bones' visibility:
                // when extraAnimActive we setHidden(false) so the animation takes control.
                boolean isExpression = name.equals("Effects") || name.equals("Sweat")
                    || name.contains("Effect") || name.contains("LaughEyes")
                    || name.contains("CryEyes") || name.contains("DizzinessEyes")
                    || name.contains("SpeechlessBrow") || name.contains("EyeBrow2")
                    || name.equals("AngerFace1") || name.equals("BlackFace1") || name.equals("BlackFace2")
                    || name.equals("AngryMouth1") || name.equals("AngryEffects1")
                    || name.equals("SurprisedEffects1") || name.equals("SurprisedEffects2")
                    || name.equals("SurprisedMouth1") || name.equals("SurprisedMouth2")
                    || name.equals("SpeechlessEffects1") || name.equals("SpeechlessEffects2")
                    || name.equals("SpeechlessEffects3") || name.equals("SpeechlessEffects4")
                    || name.equals("SpeechlessEffects5") || name.equals("SpeechlessEffects6")
                    || name.equals("SpeechlessMouth1") || name.equals("CryMouth1") || name.equals("CryMouth2")
                    || name.equals("LaughMouth1") || name.equals("LaughMouth2") || name.equals("LaughMouth3")
                    || name.equals("LaughMouth4") || name.equals("IdiotMouth1") || name.equals("ZheMeQiang")
                    || name.equals("ConfusionEffects1") || name.equals("SoundEffects1")
                    || name.contains("RightSpeechless") || name.contains("LeftSpeechless");
                // Hide bones belonging to the model's preview animation (e.g. gui decoration).
                // Skip in GUI screens (model selection/roulette preview) so decorative
                // background objects from the preview animation remain visible.
                boolean isPreviewBone = Minecraft.getMinecraft().currentScreen == null
                    && isPreviewAnimationBone(name, animId);
                // Hide reference/guide bones that are only used during modeling
                // (e.g. zero-thickness positioning grids at Y=0 like "dingwei").
                boolean isReferenceBone = name.equals("dingwei");
                totalBones++;
                if (isExpression) expressionCount++;
                if (isPreviewBone && !isExpression) previewCount++;
                if (isExpression || isPreviewBone || isReferenceBone) {
                    boolean show = false;
                    if (extraAnimActive && extraAnimName != null && animId != null) {
                        try {
                            software.bernie.geckolib3.file.AnimationFile animFile =
                                GeckoLibCache.getInstance().getAnimations().get(animId);
                            if (animFile != null) {
                                software.bernie.geckolib3.core.builder.Animation anim =
                                    animFile.animations.get(extraAnimName);
                                if (anim != null && anim.boneAnimations != null) {
                                    for (software.bernie.geckolib3.core.keyframe.BoneAnimation ba : anim.boneAnimations) {
                                        if (name.equals(ba.boneName)) {
                                            show = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    // Never hide structural bones (those with children in the bone hierarchy)
                    // — doing so would cascade to all descendants and make the model invisible.
                    // Only leaf bones (GUI decorations like curtain, backgrounds) may be hidden.
                    if (!((GeoBone) bone).childBones.isEmpty()) {
                        show = true;
                    }
                    bone.setHidden(!show);
                    if (!show) {
                        hiddenCount++;
                        hiddenBoneNames.add(name);
                    }
                }
            }
        }
        // Diagnostic: log visibility stats once per model switch (throttled per animId)
        if (hiddenCount > 0 || totalBones > 0) {
            String throttleKey = animId.toString();
            if (VISIBILITY_LOG_THROTTLE.add(throttleKey)) {
                com.fox.ysmu.ysmu.LOG.info(
                    "YSM visibility: {}/{} bones hidden for {} (extraAnimActive={}, extraAnimName={}, expressionBones={}, previewBones={}, hiddenBones={})",
                    hiddenCount, totalBones, animId, extraAnimActive, extraAnimName, expressionCount, previewCount, hiddenBoneNames);
            }
        }
        // Diagnostic: log root bone scale/pos once per model switch (throttled per animId)
        if (!bones.isEmpty()) {
            IBone firstBone = bones.get(0);
            if (firstBone instanceof GeoBone) {
                String name = ((GeoBone) firstBone).getName();
                if (name != null) {
                    String rbKey = "rb:" + animId;
                    if (VISIBILITY_LOG_THROTTLE.add(rbKey)) {
                        com.fox.ysmu.ysmu.LOG.info("YSM root bone '{}' scale=({},{},{}) pos=({},{},{}) hidden={}",
                            name,
                            firstBone.getScaleX(), firstBone.getScaleY(), firstBone.getScaleZ(),
                            firstBone.getPositionX(), firstBone.getPositionY(), firstBone.getPositionZ(),
                            firstBone.isHidden());
                    }
                }
            }
        }
        // Diagnostic: find any bone with scale=(0,0,0) which would make the entire branch invisible
        java.util.List<String> zeroScaleBones = new java.util.ArrayList<>();
        for (IBone bone : bones) {
            if (bone instanceof GeoBone && ((GeoBone) bone).getName() != null) {
                String bName = ((GeoBone) bone).getName();
                float sx = bone.getScaleX();
                float sy = bone.getScaleY();
                float sz = bone.getScaleZ();
                if (sx == 0f && sy == 0f && sz == 0f) {
                    GeoBone gb = (GeoBone) bone;
                    zeroScaleBones.add(bName + "(parent=" + (gb.parent != null ? gb.parent.getName() : "null") + ")");
                }
            }
        }
        if (!zeroScaleBones.isEmpty()) {
            String zsKey = "zs:" + animId;
            if (VISIBILITY_LOG_THROTTLE.add(zsKey)) {
                com.fox.ysmu.ysmu.LOG.info("YSM zero-scale bones (branch invisible!): {}", zeroScaleBones);
            }
        }
    }

    /**
     * Returns true if the named bone belongs exclusively to the model's preview
     * animation, i.e. it is animated in {@code preview_animation} but NOT in any
     * other game animation. This prevents normal player bones (MRoot, Head, etc.)
     * from being hidden just because the preview animation also references them.
     */
    private static boolean isPreviewAnimationBone(String boneName, ResourceLocation animId) {
        if (animId == null) return false;
        java.util.Set<String> bones = PREVIEW_BONE_CACHE.get(animId);
        if (bones == null) {
            bones = java.util.Collections.emptySet();
            String previewAnim = ClientModelManager.PREVIEW_ANIMATION.get(animId);
            if (previewAnim != null) {
                software.bernie.geckolib3.file.AnimationFile file =
                    GeckoLibCache.getInstance().getAnimations().get(animId);
                if (file != null) {
                    // Collect all bone names from the preview animation
                    software.bernie.geckolib3.core.builder.Animation panim = file.getAnimation(previewAnim);
                    if (panim != null && panim.boneAnimations != null && !panim.boneAnimations.isEmpty()) {
                        java.util.Set<String> previewBones = new java.util.HashSet<>();
                        for (software.bernie.geckolib3.core.keyframe.BoneAnimation ba : panim.boneAnimations) {
                            previewBones.add(ba.boneName);
                        }
                        // Collect bone names from ALL other (non-preview) animations.
                        // Bones that appear only in the preview animation are decoration.
                        java.util.Set<String> gameBones = new java.util.HashSet<>();
                        for (java.util.Map.Entry<String, software.bernie.geckolib3.core.builder.Animation> entry
                            : file.animations.entrySet()) {
                            if (entry.getKey().equals(previewAnim)) continue;
                            software.bernie.geckolib3.core.builder.Animation anim = entry.getValue();
                            if (anim != null && anim.boneAnimations != null) {
                                for (software.bernie.geckolib3.core.keyframe.BoneAnimation ba : anim.boneAnimations) {
                                    gameBones.add(ba.boneName);
                                }
                            }
                        }
                        // Only hide bones that are unique to the preview animation
                        previewBones.removeAll(gameBones);
                        if (!previewBones.isEmpty()) {
                            bones = previewBones;
                        }
                    }
                }
            }
            PREVIEW_BONE_CACHE.put(animId, bones);
        }
        return bones.contains(boneName);
    }

    /** Clears the static preview bone cache (e.g. during /ysm reload). */
    public static void clearPreviewBoneCache() {
        PREVIEW_BONE_CACHE.clear();
        VISIBILITY_LOG_THROTTLE.clear();
    }

    /** Returns the number of cached preview bone entries (for diagnostic logging). */
    public static int getPreviewBoneCacheSize() {
        return PREVIEW_BONE_CACHE.size();
    }

    private void codeAnimation(AnimationEvent animationEvent, EntityModelData data, EntityPlayer player) {
        // FIXME: 2023/6/21 这一块设计应该改成 molang 的，而且这个寻找效率低下
        IBone head = getBone("Head");
        FIRST_PERSON_HEAD_POS = 24;
        if (head != null) {
            float headPitch = (float) Math.toRadians(data.headPitch);
            float headYaw = (float) Math.toRadians(
                RemotePlayerAnimationQueries.get(animationEvent, player, data.netHeadYaw)
                    .headYaw());
            head.setRotationX(head.getRotationX() + headPitch);
            head.setRotationY(head.getRotationY() + headYaw);
            headPoseOffsets.put(head, new HeadPoseOffset(headPitch, headYaw));
            FIRST_PERSON_HEAD_POS = head.getPivotY()
                * ((CustomPlayerEntity) animationEvent.getAnimatable()).getHeightScale();
        }
        if (getCurrentModel().firstPersonViewLocator != null) {
            float heightScale = ((CustomPlayerEntity) animationEvent.getAnimatable()).getHeightScale();
            GeoBone locator = getCurrentModel().firstPersonViewLocator;
            FIRST_PERSON_HEAD_POS = locator.getPivotY() * heightScale;
        }
    }

    private void clearHeadPoseOffsets() {
        if (headPoseOffsets.isEmpty()) {
            return;
        }
        for (Map.Entry<IBone, HeadPoseOffset> entry : headPoseOffsets.entrySet()) {
            IBone bone = entry.getKey();
            HeadPoseOffset offset = entry.getValue();
            bone.setRotationX(bone.getRotationX() - offset.rotationX);
            bone.setRotationY(bone.getRotationY() - offset.rotationY);
        }
        headPoseOffsets.clear();
    }

    private static final class HeadPoseOffset {
        private final float rotationX;
        private final float rotationY;

        private HeadPoseOffset(float rotationX, float rotationY) {
            this.rotationX = rotationX;
            this.rotationY = rotationY;
        }
    }

    @Override

    @Nullable
    public IBone getBone(String boneName) {
        return getAnimationProcessor().getBone(boneName);
    }

    @Override

    public void setMolangQueries(IAnimatable animatable, double seekTime) {
        if (animatable instanceof CustomPlayerEntity customPlayer) {
            MolangPhysicsRuntime.begin(customPlayer, seekTime, getAnimationProcessor());
        }
    }
}
