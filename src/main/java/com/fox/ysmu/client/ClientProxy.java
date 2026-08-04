package com.fox.ysmu.client;

import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.input.*;
import cpw.mods.fml.client.registry.ClientRegistry;
import net.minecraft.client.Minecraft;

import com.fox.ysmu.CommonProxy;
import com.fox.ysmu.client.animation.AnimationRegister;
import com.fox.ysmu.client.renderer.CustomPlayerRenderer;
import com.fox.ysmu.eep.ExtendedStarModels;
import com.fox.ysmu.network.message.SyncPlayerMotionState;
import com.fox.ysmu.network.message.SyncStarModels;
import com.fox.ysmu.client.animation.RemotePlayerMotionStates;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import software.bernie.geckolib3.geo.GeoReplacedEntityRenderer;

public class ClientProxy extends CommonProxy {

    private static CustomPlayerRenderer CUSTOM_PLAYER_RENDERER;

    // Override CommonProxy methods here, if you want a different behaviour on the client (e.g. registering renders).
    // Don't forget to call the super methods as well.
    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        AnimationRegister.registerAnimationState();
        AnimationRegister.registerVariables();
        // 注册内置 empty 动画的兜底/过滤钩子（vendored AnimationFile 无反向依赖）。
        com.fox.ysmu.client.animation.YsmBuiltinAnimations.registerHooks();

        // 注册可选模组依赖检测。当控制器使用某模组的变量（如 ctrl.tac_*），
        // 但该模组未加载时，运行时自动跳过对应控制器。
        com.fox.ysmu.client.animation.controller.ModDependencyRegistry.register(
            new com.fox.ysmu.client.animation.controller.ModDependency("tacz",
                "ctrl.tac_"));
        // 以下模组在 1.7.10 上通常不存在，注册后其控制器的变量条件
        // 会被自动检测并跳过。如需在 1.7.10 上使用这些模组，只需确保
        // Loader.isModLoaded() 返回 true 即可。
        com.fox.ysmu.client.animation.controller.ModDependencyRegistry.register(
            new com.fox.ysmu.client.animation.controller.ModDependency("parcool",
                "ctrl.parcool_"));
        com.fox.ysmu.client.animation.controller.ModDependencyRegistry.register(
            new com.fox.ysmu.client.animation.controller.ModDependency("slashblade",
                "ctrl.slashblade_"));
        com.fox.ysmu.client.animation.controller.ModDependencyRegistry.register(
            new com.fox.ysmu.client.animation.controller.ModDependency("swem",
                "ctrl.swem_"));
        CUSTOM_PLAYER_RENDERER = new CustomPlayerRenderer();
        GeoReplacedEntityRenderer.registerReplacedEntity(CustomPlayerEntity.class, CUSTOM_PLAYER_RENDERER);
        ClientRegistry.registerKeyBinding(AnimationRouletteKey.ANIMATION_ROULETTE_KEY);
        ExtraAnimationKey.registerKeyBindings();
        ClientRegistry.registerKeyBinding(ExtraPlayerConfigKey.EXTRA_PLAYER_RENDER_KEY);
        ClientRegistry.registerKeyBinding(PlayerModelScreenKey.PLAYER_MODEL_KEY);

        // 客户端本地模型加载命令 /ysmlocal（纯客户端，纯净服/YSMU 服都可用）。
        // 用独立命令名而非 /ysm 子命令：若在客户端注册同名 "ysm"，ClientCommandHandler
        // 会优先拦截所有 /ysm 调用，导致 reload/play 等服务端子命令无法到达服务器。
        net.minecraftforge.client.ClientCommandHandler.instance
            .registerCommand(new com.fox.ysmu.command.CommandLoadLocal());
    }

    public static CustomPlayerRenderer getInstance() {
        return CUSTOM_PLAYER_RENDERER;
    }

    @Override
    public void handleStarModels(SyncStarModels message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            ExtendedStarModels eep = ExtendedStarModels.get(mc.thePlayer);
            if (eep != null) {
                eep.setStarModels(message.getStarModels());
            }
        }
    }

    @Override
    public void handlePlayerMotionState(SyncPlayerMotionState message) {
        RemotePlayerMotionStates.update(message.getPlayerId(), message.getFlags());
    }
}
