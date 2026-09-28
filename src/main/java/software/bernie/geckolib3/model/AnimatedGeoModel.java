package software.bernie.geckolib3.model;

import java.util.Collections;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import cpw.mods.fml.common.FMLCommonHandler;
import software.bernie.geckolib3.animation.AnimationTicker;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.processor.AnimationProcessor;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.geo.exception.GeoModelException;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.model.provider.GeoModelProvider;
import software.bernie.geckolib3.model.provider.IAnimatableModelProvider;
import software.bernie.geckolib3.resource.GeckoLibCache;

@SuppressWarnings({ "rawtypes", "unchecked" })
public abstract class AnimatedGeoModel<T extends IAnimatable> extends GeoModelProvider<T>
    implements IAnimatableModel<T>, IAnimatableModelProvider<T> {

    private final AnimationProcessor animationProcessor;
    private GeoModel currentModel;

    protected AnimatedGeoModel() {
        this.animationProcessor = new AnimationProcessor(this);
    }

    public void registerBone(GeoBone bone) {
        registerModelRenderer(bone);

        for (GeoBone childBone : bone.childBones) {
            registerBone(childBone);
        }
    }

    @Override
    public void setLivingAnimations(T entity, Integer uniqueID, @Nullable AnimationEvent customPredicate) {
        // Each animation has it's own collection of animations (called the
        // EntityAnimationManager), which allows for multiple independent animations
        AnimationData manager = entity.getFactory()
            .getOrCreateAnimationData(uniqueID);
        manager.bindAnimationFile(getAnimationFileLocation(entity));
        if (manager.ticker == null) {
            AnimationTicker ticker = new AnimationTicker(manager);
            manager.ticker = ticker;
            FMLCommonHandler.instance()
                .bus()
                .register(ticker);
        }
        if (!Minecraft.getMinecraft()
            .isGamePaused() || manager.shouldPlayWhilePaused) {
            seekTime = manager.tick + Minecraft.getMinecraft().timer.renderPartialTicks;
        } else {
            seekTime = manager.tick;
        }

        AnimationEvent<T> predicate;
        if (customPredicate == null) {
            predicate = new AnimationEvent<T>(
                entity,
                0,
                0,
                (float) (manager.tick - lastGameTickTime),
                false,
                Collections.emptyList());
        } else {
            predicate = customPredicate;
        }

        predicate.animationTick = seekTime;
        animationProcessor.preAnimationSetup(predicate.getAnimatable(), seekTime);
        if (!this.animationProcessor.getModelRendererList()
            .isEmpty()) {
            animationProcessor.tickAnimation(
                entity,
                uniqueID,
                seekTime,
                predicate,
                GeckoLibCache.getInstance().parser,
                shouldCrashOnMissing);
        }
    }

    @Override
    public AnimationProcessor getAnimationProcessor() {
        return this.animationProcessor;
    }

    public void registerModelRenderer(IBone modelRenderer) {
        animationProcessor.registerModelRenderer(modelRenderer);
    }

    @Override
    public Animation getAnimation(String name, IAnimatable animatable) {
        AnimationFile file = GeckoLibCache.getInstance()
            .getAnimations()
            .get(this.getAnimationFileLocation((T) animatable));
        if (file != null) {
            return file.getAnimation(name);
        }
        return null;
    }

    @Override
    public GeoModel getModel(ResourceLocation location) {
        GeoModel model = super.getModel(location);
        if (model == null) {
            throw new GeoModelException(location, "Could not find model.");
        }
        if (model != currentModel) {
            // 命中骨骼登记缓存时只换引用：预览页十几个模型轮流用同一个模型实例，
            // 旧实现每次切换都要清空 + 递归重走整棵骨骼树 + 重存初始快照。
            if (!this.animationProcessor.selectModel(model)) {
                for (GeoBone bone : model.topLevelBones) {
                    registerBone(bone);
                }
            }
            this.currentModel = model;
        }
        return model;
    }

    public GeoModel getCurrentModel() {
        return currentModel;
    }

    /**
     * 资源框架释放这份几何（{@code ReleaseMode.DROP_HEAP}，见 {@code GeoModelProvider}）时调用。
     *
     * <p>两件事：让处理器丢掉骨骼登记；清掉 {@link #currentModel} 对它的强引用 ——
     * 后者只被赋新值、从不置空，于是"最后渲染过的那份几何"即使被淘汰也永远释放不掉
     * （骨骼表引用的 cube 几何跟着一起留）。释放后若再次渲染到该模型，取到的是重新解析出的
     * 新对象，会正常重建。
     */
    public void onModelReleased(GeoModel model) {
        if (model == null) {
            return;
        }
        this.animationProcessor.forgetModel(model);
        if (this.currentModel == model) {
            this.currentModel = null;
        }
    }

    @Override
    public double getCurrentTick() {
        return (Minecraft.getSystemTime() / 50d);
    }
}
