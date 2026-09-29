package com.fox.ysmu.client.entity;

import static com.fox.ysmu.util.ControllerUtils.*;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.AnimationManager;
import com.fox.ysmu.client.animation.condition.ConditionArmor;
import com.fox.ysmu.client.animation.molang.MolangInstructionExecutor;
import com.fox.ysmu.client.model.CustomPlayerModel;

import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.resource.GeckoLibCache;
import software.bernie.geckolib3.util.GeckoLibUtil;

public class CustomPlayerEntity implements IAnimatable {

    private final AnimationFactory factory = GeckoLibUtil.createFactory(this, true);
    private ResourceLocation mainModel = CustomPlayerModel.DEFAULT_MAIN_MODEL;
    private ResourceLocation texture = CustomPlayerModel.DEFAULT_TEXTURE;
    private String previewAnimation = "";
    /** Base animation name for GUI preview (set by ModelButton, consumed by predicateMain). */
    private String guiBaseAnimation = "";
    /** When false, GUI animation predicates return STOP immediately (set by ModelButton when GUI_ENHANCEMENTS disabled). */
    private boolean guiAnimationsEnabled = true;
    private EntityPlayer player = null;

    @NotNull
    private static <P extends IAnimatable> PlayState playLoopAnimation(AnimationEvent<P> event, String animationName) {
        event.getController()
            .setAnimation(new AnimationBuilder().addAnimation(animationName, ILoopType.EDefaultLoopTypes.LOOP));
        return PlayState.CONTINUE;
    }

    /**
     * 越往后优先级越高
     */
    @Override

    @SuppressWarnings("all")
    public void registerControllers(AnimationData data) {
        AnimationManager manager = AnimationManager.getInstance();
        for (int i = 0; i < 8; i++) {
            String controllerName = String.format("pre_parallel_%d_controller", i);
            String animationName = String.format("pre_parallel%d", i);
            data.addAnimationController(
                new AnimationController<>(this, controllerName, 0, e -> manager.predicateParallel(e, animationName)));
        }
        // 具名并行槽位（player.pre_parallel_<非数字后缀>）的备用池：wiki 只定义数字槽位，
        // 但官方对非数字后缀也发控制器，模型会把整块状态机挂在上面。池子固定 + 运行时路由
        // （见 OpenYsmPlayerControllerRuntime.resolveControllers），因为 registerControllers
        // 对每个 animatable 只跑一次，而模型可以随时切换，动态注册会在换模型后失效。
        // 谓词用 predicateOpenYsmSlot（走状态机），不是 predicateParallel（直接播同名动画）。
        // 数量由配置 NamedParallelExtraSlots 决定（默认 8，上限见 ControllerUtils）。
        for (int i = 0; i < Config.NAMED_PARALLEL_EXTRA_SLOTS; i++) {
            data.addAnimationController(new AnimationController(this,
                String.format("pre_parallel_extra_%d_controller", i), 0, manager::predicateOpenYsmSlot));
        }
        data.addAnimationController(
            new AnimationController(this, OPENYSM_PRE_MAIN_CONTROLLER, 0, manager::predicateOpenYsmSlot));
        data.addAnimationController(
            new AnimationController(this, MAIN_CONTROLLER, Config.ANIMATION_TRANSITION_TICKS, manager::predicateMain));
        data.addAnimationController(
            new AnimationController(this, OPENYSM_POST_MAIN_CONTROLLER, 0, manager::predicateOpenYsmSlot));
        data.addAnimationController(
            new AnimationController(this, OPENYSM_PRE_HOLD_CONTROLLER, 0, manager::predicateOpenYsmSlot));
        data.addAnimationController(
            new AnimationController(this, HOLD_OFFHAND_CONTROLLER, Config.ANIMATION_TRANSITION_TICKS, manager::predicateOffhandHold));
        data.addAnimationController(
            new AnimationController(this, HOLD_MAINHAND_CONTROLLER, Config.ANIMATION_TRANSITION_TICKS, manager::predicateMainhandHold));
        data.addAnimationController(
            new AnimationController(this, OPENYSM_POST_HOLD_CONTROLLER, 0, manager::predicateOpenYsmSlot));
        data.addAnimationController(
            new AnimationController(this, OPENYSM_PRE_SWING_CONTROLLER, 0, manager::predicateOpenYsmSlot));
        data.addAnimationController(
            new AnimationController(this, SWING_CONTROLLER, Config.ANIMATION_TRANSITION_TICKS, manager::predicateSwing));
        data.addAnimationController(
            new AnimationController(this, OPENYSM_POST_SWING_CONTROLLER, 0, manager::predicateOpenYsmSlot));
        data.addAnimationController(
            new AnimationController(this, OPENYSM_PRE_USE_CONTROLLER, 0, manager::predicateOpenYsmSlot));
        data.addAnimationController(
            new AnimationController(this, USE_CONTROLLER, Config.ANIMATION_TRANSITION_TICKS, manager::predicateUse));
        data.addAnimationController(
            new AnimationController(this, OPENYSM_POST_USE_CONTROLLER, 0, manager::predicateOpenYsmSlot));
        // 槽位后缀控制器（player.<slot>_<后缀>）的共享备用池。
        // 官方把同一槽位下**所有**匹配 ^player\.<slot>(_.+)?$ 的名字都注册成独立控制器
        // （OpenYSM ControllerSlotBinder），一个槽位可以同时挂 @player_ctrl_<slot>.molang
        // 控制脚本和若干 JSON 状态机；YSMU 的 GeckoLib 控制器是按名字固定的，一个槽位只有一个，
        // 所以这些后缀控制器只能靠固定池 + 运行时路由承载（同具名并行槽位的做法）。
        // 位置放在所有基槽位之后、cap_controller 之前：wiki 的组顺序把 player.post_main_*
        // 排在 player.main 之后（本库里出现最多的就是这一族），而池只有一个位置；
        // 代价是 player.pre_main 后缀（形态切换那类全身动画）也会覆盖身体动画的重叠骨骼，
        // 而不是反过来被身体动画覆盖。
        for (int i = 0; i < Config.SLOT_EXTRA_CONTROLLERS; i++) {
            data.addAnimationController(new AnimationController(this,
                slotExtraControllerName(i), 0, manager::predicateOpenYsmSlot));
        }
        // 轮盘动画（cap 控制器：extra0..7 / gui 预览的 hover/focus）在并行族**之前**注册。
        // wiki「并行动画」：pre_parallel 优先级最低（会被主动画覆盖），parallel **优先级最高**
        // （不同 parallel 之间数字越大越高）。GeckoLib 按控制器注册顺序逐骨覆盖，所以
        // parallel* 必须排在 main/hold/swing/use/**cap** 之后，否则轮盘动画里写的
        // scale/position（例如某模型"打招呼/鼓掌"把 AllBody 缩放写回 1）会盖掉
        // parallel1 的形态缩放，人形与狐形就同时显示。
        data.addAnimationController(
            new AnimationController(this, CAP_CONTROLLER, Config.ANIMATION_TRANSITION_TICKS, manager::predicateCap));
        for (int i = 0; i < 8; i++) {
            String controllerName = String.format("parallel_%d_controller", i);
            String animationName = String.format("parallel%d", i);
            data.addAnimationController(
                new AnimationController<>(this, controllerName, 0, e -> manager.predicateParallel(e, animationName))
                    // wiki「并行动画」：parallel 族是特殊混合动画，**旋转相加**而不是覆盖
                    // （仅旋转，不含位移/缩放）。见 AnimationController#additiveRotation。
                    .setAdditiveRotation(true));
        }
        // 高优先级并行族的具名槽位，同样的固定池（parallel_* 有旋转叠加语义，
        // 池控制器走同一条 predicateOpenYsmSlot → tryApplyController 混合路径）。
        for (int i = 0; i < Config.NAMED_PARALLEL_EXTRA_SLOTS; i++) {
            data.addAnimationController(new AnimationController(this,
                String.format("parallel_extra_%d_controller", i), 0, manager::predicateOpenYsmSlot)
                    .setAdditiveRotation(true));
        }
        // 为每个盔甲槽位注册控制器，使用1-4的索引值。
        // 必须排在并行族**之后**：wiki「护甲动画」要求并行动画把护甲组缩放设成 0、
        // 由护甲动画把对应组缩放改回 1——顺序反了就永远露不出护甲。
        for (int slotIndex = 1; slotIndex <= 4; slotIndex++) {
            String controllerName = String.format("%s_controller", ConditionArmor.getSlotNameFromIndex(slotIndex));
            int finalSlotIndex = slotIndex;
            data.addAnimationController(
                new AnimationController(this, controllerName, 0, e -> manager.predicateArmor(e, finalSlotIndex)));
        }
        data.getAnimationControllers()
            .values()
            .forEach(controller -> {
                controller.registerCustomInstructionListener(event -> {
                    // 诊断：先记一条"这个控制器的时间轴被触发了"（每个控制器一条），
                    // 再执行；两者分开才能区分"动画没播"和"时间轴没执行"。
                    MolangInstructionExecutor.noteTimelineExecution(controller.getName(), event.instructions);
                    MolangInstructionExecutor.execute(event.instructions);
                });
                controller.registerSoundListener(
                    event -> {
                        String ctrlName = event.getController().getName();
                        // 归属 = 当前被渲染模型的持有者。多个玩家各自持有同名控制器（cap_controller
                        // 等），不带归属时 A 的关键帧音效会被 B 的同名播放/停止操作消掉。
                        // 预览实体没有 player，走本地槽位（既有行为）。
                        com.fox.ysmu.client.audio.YSMSoundManager.onSoundKeyframe(
                            this.player, ctrlName, event.sound, getMainModel());
                    });
            });
    }

    public ResourceLocation getMainModel() {
        if (GeckoLibCache.getInstance()
            .getGeoModels()
            .containsKey(this.mainModel)) {
            return mainModel;
        }
        // Lazy-load geo model from encrypted cache before falling back to default
        com.fox.ysmu.client.ClientModelManager.ensureGeoModelLoaded(this.mainModel);
        if (GeckoLibCache.getInstance()
            .getGeoModels()
            .containsKey(this.mainModel)) {
            return mainModel;
        }
        return CustomPlayerModel.DEFAULT_MAIN_MODEL;
    }

    public void setMainModel(ResourceLocation mainModel) {
        this.mainModel = mainModel;
    }

    public ResourceLocation getAnimation() {
        if (GeckoLibCache.getInstance()
            .getAnimations()
            .containsKey(this.mainModel)) {
            return mainModel;
        }
        // 主模型动画缺失（已闲置卸载 / 后台加载中 / 该模型确实无动画）。
        // 绝不能 fallback 到内置默认模型动画：默认动画的骨架与当前模型不同，
        // controller 播放它时会在当前骨架的 boneSnapshot 里查不到对应骨 → NPE
        // （AnimationController.process 读 boneSnapshot.rotationValueX）。
        // 改为返回 mainModel：缺失动画会被 GeckoLib 安全跳过（仅 warn），并触发
        // 后台重载（ensureAnimationsLoaded → AssetManager.anim().get()），
        // 就绪后下一帧恢复正常播放。
        com.fox.ysmu.client.ClientModelManager.ensureAnimationsLoaded(this.mainModel);
        return this.mainModel;
    }

    public float getHeightScale() {
        if (ClientModelManager.SCALE_INFO.containsKey(this.mainModel)) {
            return ClientModelManager.SCALE_INFO.get(this.mainModel)
                .left()
                .floatValue();
        }
        return 0.7f;
    }

    public float getWidthScale() {
        if (ClientModelManager.SCALE_INFO.containsKey(this.mainModel)) {
            return ClientModelManager.SCALE_INFO.get(this.mainModel)
                .right()
                .floatValue();
        }
        return 0.7f;
    }

    public EntityPlayer getPlayer() {
        return player;
    }

    public void setPlayer(EntityPlayer player) {
        this.player = player;
    }

    @Override

    public AnimationFactory getFactory() {
        return this.factory;
    }

    public ResourceLocation getTexture() {
        return texture;
    }

    public void setTexture(ResourceLocation texture) {
        this.texture = texture;
    }

    public String getPreviewAnimation() {
        return previewAnimation;
    }

    public void setPreviewAnimation(String previewAnimation) {
        this.previewAnimation = previewAnimation;
    }

    public void clearPreviewAnimation() {
        this.previewAnimation = "";
    }

    public boolean hasPreviewAnimation() {
        return StringUtils.isNoneBlank(this.previewAnimation);
    }

    public boolean hasPreviewAnimation(String previewAnimation) {
        return hasPreviewAnimation() && previewAnimation.equals(this.previewAnimation);
    }

    public String getGuiBaseAnimation() {
        return guiBaseAnimation;
    }

    public void setGuiBaseAnimation(String guiBaseAnimation) {
        this.guiBaseAnimation = guiBaseAnimation;
    }

    public boolean hasGuiBaseAnimation() {
        return StringUtils.isNoneBlank(this.guiBaseAnimation);
    }

    public boolean areGuiAnimationsEnabled() {
        return guiAnimationsEnabled;
    }

    public void setGuiAnimationsEnabled(boolean enabled) {
        this.guiAnimationsEnabled = enabled;
    }
}
