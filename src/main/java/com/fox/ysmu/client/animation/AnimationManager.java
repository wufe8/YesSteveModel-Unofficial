package com.fox.ysmu.client.animation;

import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import com.fox.ysmu.client.animation.condition.*;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.compat.BackhandCompat;
import com.fox.ysmu.eep.ExtendedModelInfo;
import com.google.common.collect.Lists;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

public final class AnimationManager {

    private static AnimationManager MANAGER;
    /** True when the main controller body animation is handled by the legacy system
        (no player.main OpenYSM controller match). */
    public static volatile boolean legacyBodyActive = false;
    private final Int2ObjectOpenHashMap<LinkedList<AnimationState>> data = new Int2ObjectOpenHashMap<>();
    private final Map<UUID, Integer> swingProgressByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> useDurationByPlayer = new ConcurrentHashMap<>();
    /** Tracks the last held item hash to detect item changes for animation reload. */
    private final Map<UUID, Integer> lastMainhandItemHash = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastOffhandItemHash = new ConcurrentHashMap<>();

    public static AnimationManager getInstance() {
        if (MANAGER == null) {
            MANAGER = new AnimationManager();
        }
        return MANAGER;
    }

    @NotNull
    private static <P extends IAnimatable> PlayState playLoopAnimation(AnimationEvent<P> event, String animationName) {
        return playAnimation(event, animationName, ILoopType.EDefaultLoopTypes.LOOP);
    }

    @NotNull
    private static <P extends IAnimatable> PlayState playAnimation(AnimationEvent<P> event, String animationName,
        ILoopType loopType) {
        event.getController()
            .setAnimation(new AnimationBuilder().addAnimation(animationName, loopType));
        return PlayState.CONTINUE;
    }

    @NotNull
    private static <P extends IAnimatable> PlayState playAnimation(AnimationEvent<P> event, String animationName) {
        event.getController()
            .setAnimation(new AnimationBuilder().addAnimation(animationName));
        return PlayState.CONTINUE;
    }

    /**
     * 只在动画存在时播放。防止 GeckoLib 的 setAnimation() 在动画不存在时静默失败
     * （不设 animationQueue），导致控制器处于 Stopped 状态且模型冻结。
     */
    private static <P extends IAnimatable> PlayState playIfAnimExists(AnimationEvent<P> event, String animationName,
        ILoopType loopType, ResourceLocation animId) {
        if (animationExistsInFile(animId, animationName)) {
            return playAnimation(event, animationName, loopType);
        }
        return PlayState.STOP;
    }

    private static boolean animationExistsInFile(ResourceLocation animId, String animationName) {
        if (animId == null || animationName == null) {
            return false;
        }
        AnimationFile file = GeckoLibCache.getInstance().getAnimations().get(animId);
        return file != null && file.animations.containsKey(animationName);
    }

    private static ResourceLocation getAnimationId(AnimationEvent<CustomPlayerEntity> event) {
        return event.getAnimatable()
            .getAnimation();
    }

    public void register(AnimationState state) {
        if (data.containsKey(state.getPriority())) {
            data.get(state.getPriority())
                .add(state);
        } else {
            LinkedList<AnimationState> states = Lists.newLinkedList();
            states.add(state);
            data.put(state.getPriority(), states);
        }
    }

    public PlayState predicateParallel(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        if (Minecraft.getMinecraft()
            .isGamePaused()) {
            return PlayState.STOP;
        }
        CustomPlayerEntity animatable = event.getAnimatable();
        ResourceLocation animId = animatable != null ? animatable.getAnimation() : null;
        String geckoName = event.getController().getName();
        if (animId != null && OpenYsmPlayerControllerRuntime.hasAnyController(animId)) {
            if (geckoName != null && geckoName.startsWith("pre_parallel_")) {
                return PlayState.STOP;
            }
            PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
            if (controllerState != null) {
                // 梯子上跳过 parallel 控制器结果，防止 climbing_start 等动画的
                // Root rotation [90,0,0] 覆盖主控制器的梯子姿态导致模型平躺过渡
                if (geckoName != null && geckoName.startsWith("parallel_")) {
                    EntityPlayer player = animatable != null ? animatable.getPlayer() : null;
                    if (player != null && player.isOnLadder()) {
                        return PlayState.STOP;
                    }
                }
                return controllerState;
            }
            return PlayState.STOP;
        }
        return playLoopAnimation(event, animationName);
    }

    public PlayState predicateOpenYsmSlot(AnimationEvent<CustomPlayerEntity> event) {
        if (Minecraft.getMinecraft()
            .isGamePaused()) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        return controllerState == null ? PlayState.STOP : controllerState;
    }

    public PlayState predicateCap(AnimationEvent<CustomPlayerEntity> event) {
        CustomPlayerEntity animatable = event.getAnimatable();
        EntityPlayer player = animatable.getPlayer();
        if (player == null) {
            if (animatable.hasPreviewAnimation()) {
                return playLoopAnimation(event, animatable.getPreviewAnimation());
            }
            return PlayState.STOP;
        }

        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep != null && eep.isPlayAnimation()) {
            return playAnimation(event, eep.getAnimation());
        }
        return PlayState.STOP;
    }

    @NotNull
    public PlayState predicateMain(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            legacyBodyActive = false;
            return controllerState;
        }
        legacyBodyActive = true;
        ResourceLocation animId = getAnimationId(event);
        AnimationFile animFile = animId == null ? null
            : GeckoLibCache.getInstance().getAnimations().get(animId);
        for (int i = Priority.HIGHEST; i <= Priority.LOWEST; i++) {
            if (!data.containsKey(i)) {
                continue;
            }
            LinkedList<AnimationState> states = data.get(i);
            for (AnimationState state : states) {
                if (state.getPredicate().test(player, event)) {
                    String animationName = state.getAnimationName();
                    if (animFile != null && animFile.animations.containsKey(animationName)) {
                        ILoopType loopType = state.getLoopType();

                        return playAnimation(event, animationName, loopType);
                    }
                }
            }
        }
        if (animFile != null && !animFile.animations.isEmpty()) {
            if (animFile.animations.containsKey("idle")) {
                return playLoopAnimation(event, "idle");
            }
            String firstAnim = animFile.animations.keySet().iterator().next();
            return playLoopAnimation(event, firstAnim);
        }
        return PlayState.STOP;
    }

    public PlayState predicateOffhandHold(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }

        // 修改为使用BackhandCompat兼容层
        ItemStack offhandItem = BackhandCompat.getOffhandItem(player);
        if (offhandItem != null) {
            // 攻击/使用期间暂停持握动画，让挥砍/使用控制器接管；但不标记为空(-1)
            // 这样攻击结束后持握控制器恢复时，last == hash 不会触发 markNeedsReload()，
            // 避免了过渡动画重复播放
            if (!checkSwingAndUse(player, false)) {
                return PlayState.STOP;
            }
            int hash = itemHash(offhandItem);
            Integer last = lastOffhandItemHash.put(player.getUniqueID(), hash);
            if (last == null || last != hash || last == -1) {
                event.getController().markNeedsReload();
            }
            return playIfPresent(event, findHoldAnimation(event, player, false));
        } else {
            lastOffhandItemHash.put(player.getUniqueID(), -1);
        }
        return PlayState.STOP;
    }

    public PlayState predicateMainhandHold(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        if (player.fishEntity != null) {
            return playAnimation(event, "hold_mainhand:fishing", ILoopType.EDefaultLoopTypes.LOOP);
        }

        if (player.getHeldItem() != null) {
            // 攻击/使用期间暂停持握动画，让挥砍/使用控制器接管；但不标记为空(-1)
            if (!checkSwingAndUse(player, true)) {
                return PlayState.STOP;
            }
            int hash = itemHash(player.getHeldItem());
            Integer last = lastMainhandItemHash.put(player.getUniqueID(), hash);
            // Reload when item changes OR when coming back from empty hand
            if (last == null || last != hash || last == -1) {
                event.getController().markNeedsReload();
            }
            return playIfPresent(event, findHoldAnimation(event, player, true));
        } else {
            // Mark as empty
            lastMainhandItemHash.put(player.getUniqueID(), -1);
        }
        return PlayState.STOP;
    }

    public PlayState predicateSwing(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        if (!player.isSwingInProgress) {
            swingProgressByPlayer.remove(player.getUniqueID());
            return PlayState.STOP;
        }
        if (!player.isPlayerSleeping()) {
            if (markSwingStart(player)) {
                event.getController().shouldResetTick = true;
                event.getController().markNeedsReload();
                event.getController()
                    .adjustTick(0);
            }
            String conditionalAnimation = findSwingAnimation(event, player);
            if (StringUtils.isNoneBlank(conditionalAnimation)) {
                ResourceLocation animId = getAnimationId(event);
                if (animationExistsInFile(animId, conditionalAnimation)) {
                    return playAnimation(event, conditionalAnimation, ILoopType.EDefaultLoopTypes.LOOP);
                }
            }
            return playAnimation(event, "swing_hand", ILoopType.EDefaultLoopTypes.LOOP);
        }
        return PlayState.STOP;
    }

    private boolean markSwingStart(EntityPlayer player) {
        UUID playerId = player.getUniqueID();
        if (!player.isSwingInProgress) {
            swingProgressByPlayer.remove(playerId);
            return false;
        }
        // swingProgressInt 在 1.7.10 中是递减的（从最大值→0）。
        // 旧逻辑 currentProgress < previousProgress 在递减时每帧都 true，
        // 导致 markNeedsReload() 每帧重置动画，swing_hand 永远播不出来。
        // 改用 boolean 跟踪：只在新攻击的第一帧返回 true。
        boolean wasAlreadySwinging = swingProgressByPlayer.containsKey(playerId);
        swingProgressByPlayer.put(playerId, 0); // 仅用作标记
        return !wasAlreadySwinging;
    }

    public PlayState predicateUse(AnimationEvent<CustomPlayerEntity> event) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        if (player.isUsingItem() && !player.isPlayerSleeping()) {
            if (markUseStart(player)) {
                event.getController().shouldResetTick = true;
                event.getController().markNeedsReload();
                event.getController()
                    .adjustTick(0);
            }
            boolean isMainHand = BackhandCompat.getUsedItemHand(player);
            String conditionalAnimation = findUseAnimation(event, player, isMainHand);
            if (StringUtils.isNoneBlank(conditionalAnimation)) {
                return playAnimation(event, conditionalAnimation);
            }
            return playAnimation(event, isMainHand ? "use_mainhand" : "use_offhand", ILoopType.EDefaultLoopTypes.LOOP);
        }
        useDurationByPlayer.remove(player.getUniqueID());
        return PlayState.STOP;
    }

    private boolean markUseStart(EntityPlayer player) {
        UUID playerId = player.getUniqueID();
        if (!player.isUsingItem()) {
            useDurationByPlayer.remove(playerId);
            return false;
        }
        int currentDuration = player.getItemInUseDuration();
        Integer previousDuration = useDurationByPlayer.put(playerId, currentDuration);
        return previousDuration == null || currentDuration < previousDuration;
    }

    public PlayState predicateArmor(AnimationEvent<CustomPlayerEntity> event, int slotIndex) {
        EntityPlayer player = event.getAnimatable()
            .getPlayer();
        if (player == null) {
            return PlayState.STOP;
        }
        PlayState controllerState = OpenYsmPlayerControllerRuntime.tryApply(event);
        if (controllerState != null) {
            return controllerState;
        }
        ItemStack itemBySlot = player.getEquipmentInSlot(slotIndex);
        if (itemBySlot == null) {
            return PlayState.STOP;
        }

        String conditionalAnimation = findArmorAnimation(event, player, slotIndex);
        if (StringUtils.isNoneBlank(conditionalAnimation)) {
            return playLoopAnimation(event, conditionalAnimation);
        }

        ResourceLocation animation = getAnimationId(event);
        String slotName = ConditionArmor.getSlotNameFromIndex(slotIndex);
        String defaultName = slotName + ":default";
        if (GeckoLibCache.getInstance()
            .getAnimations()
            .get(animation).animations.containsKey(defaultName)) {
            return playAnimation(event, defaultName, ILoopType.EDefaultLoopTypes.LOOP);
        }
        return PlayState.STOP;
    }

    private static PlayState playIfPresent(AnimationEvent<CustomPlayerEntity> event, String animationName) {
        if (StringUtils.isNoneBlank(animationName)) {
            return playAnimation(event, animationName);
        }
        return PlayState.STOP;
    }

    private static String findHoldAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        boolean isMainHand) {
        ResourceLocation id = getAnimationId(event);
        ConditionalHold conditionalHold = isMainHand ? ConditionManager.getHoldMainhand(id)
            : ConditionManager.getHoldOffhand(id);
        return conditionalHold == null ? null : conditionalHold.doTest(player, isMainHand);
    }

    private static String findSwingAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player) {
        ConditionalSwing conditionalSwing = ConditionManager.getSwing(getAnimationId(event));
        return conditionalSwing == null ? null : conditionalSwing.doTest(player, BackhandCompat.swingingArm(player));
    }

    private static String findUseAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        boolean isMainHand) {
        ResourceLocation id = getAnimationId(event);
        ConditionalUse conditionalUse = isMainHand ? ConditionManager.getUseMainhand(id)
            : ConditionManager.getUseOffhand(id);
        return conditionalUse == null ? null : conditionalUse.doTest(player, isMainHand);
    }

    private static String findArmorAnimation(AnimationEvent<CustomPlayerEntity> event, EntityPlayer player,
        int slotIndex) {
        ConditionArmor conditionArmor = ConditionManager.getArmor(getAnimationId(event));
        return conditionArmor == null ? null : conditionArmor.doTest(player, slotIndex);
    }

    /**
     * 判断持握动画是否应暂停。
     * 攻击/使用期间返回 false，让 swing/use 控制器接管；但不标记物品为空，
     * 这样攻击结束后持握控制器恢复时不会触发 markNeedsReload()。
     */
    private boolean checkSwingAndUse(EntityPlayer player, boolean isMainHand) {
        if (player.isSwingInProgress && BackhandCompat.swingingArm(player) == isMainHand) {
            return false;
        }
        return !player.isUsingItem() || BackhandCompat.getUsedItemHand(player) != isMainHand;
    }

    /** Returns a hash that changes when the held item type changes. */
    private static int itemHash(net.minecraft.item.ItemStack stack) {
        return stack == null || stack.getItem() == null ? 0 : stack.getItem().hashCode();
    }
}
