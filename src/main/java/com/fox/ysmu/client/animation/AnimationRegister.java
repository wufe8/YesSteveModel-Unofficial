package com.fox.ysmu.client.animation;

import java.util.function.BiPredicate;
import java.util.function.DoubleSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;

import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.compat.BackhandCompat;
import com.fox.ysmu.compat.BlockingCompat;
import com.fox.ysmu.compat.EtFuturumCompat;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;
import com.fox.ysmu.client.animation.molang.BonePivotAbsFunction;
import com.fox.ysmu.client.animation.molang.CtrlArmorFunction;
import com.fox.ysmu.client.animation.molang.CtrlHoldFunction;
import com.fox.ysmu.client.animation.molang.CtrlItemFunction;
import com.fox.ysmu.client.animation.molang.EquippedEnchantmentLevelFunction;
import com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime;
import com.fox.ysmu.client.animation.molang.ParticleFunction;
import com.fox.ysmu.client.animation.molang.PerlinNoiseFunction;
import com.fox.ysmu.client.animation.molang.YsmDebugDumpFunction;
import com.fox.ysmu.client.animation.molang.YsmEffectLevelFunction;
import com.fox.ysmu.client.animation.molang.YsmSoundFunction;
import com.fox.ysmu.client.animation.molang.QueryBlockTagFunction;
import com.fox.ysmu.client.animation.molang.QueryDebugOutputFunction;
import com.fox.ysmu.client.animation.molang.QueryDurabilityFunction;
import com.fox.ysmu.client.animation.molang.QueryItemNameAnyFunction;
import com.fox.ysmu.client.animation.molang.QueryItemTagFunction;
import com.fox.ysmu.client.animation.molang.QueryPositionDeltaFunction;
import com.fox.ysmu.client.animation.molang.QueryPositionFunction;
import com.fox.ysmu.client.animation.molang.RelativeBlockNameFunction;
import com.fox.ysmu.client.particle.ParticleEffectUtil;
import net.minecraft.world.EnumSkyBlock;

import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.MolangStringPool;
import software.bernie.geckolib3.core.molang.ScopedMolangVariable;
import software.bernie.geckolib3.core.molang.functions.MolangPhysicsBridge;
import software.bernie.geckolib3.model.provider.data.EntityModelData;
import software.bernie.geckolib3.resource.GeckoLibCache;
import software.bernie.geckolib3.util.MolangUtils;

public class AnimationRegister {

    private static final double MIN_SPEED = 0.05;

    public static void registerAnimationState() {
        registerHighPriorityStates();
        registerRidingFlyingStates();
        registerDamageJumpSneakStates();
        registerMovementStates();
        registerIdleFallback();
    }

    private static void registerHighPriorityStates() {
        register("death", ILoopType.EDefaultLoopTypes.PLAY_ONCE, Priority.HIGHEST, (player, event) -> player.isDead);
        // 睡觉：床朝向的旋转由 applyRotations 处理（-90°），sleep 状态只负责选动画。
        register("sleep", Priority.HIGHEST, (player, event) -> player.isPlayerSleeping());
        register("swim", Priority.HIGHEST, (player, event) -> player.isInWater() && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED);
        // 注意：climb/climbing 动画定义 Root rotation = [90,0,0]（水平爬行/游泳姿态），
        // 在 Modern YSM 中对应 Pose.SWIMMING，而非 isOnLadder()。
        // 之前错误绑定到 isOnLadder() 导致玩家上梯时模型横躺（#1 楼梯俯仰翻转问题）。
        // 1.7.10 无 Pose.SWIMMING，改为水中+离地触发，仅保留给有 swim 姿态动画的模型使用。
        register("climb", Priority.HIGHEST, (player, event) -> player.isInWater() && !isPlayerOnGround(player) && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED);
        register("climbing", Priority.HIGHEST, (player, event) -> player.isInWater() && !isPlayerOnGround(player));
        register("ladder_up", Priority.HIGHEST, (player, event) -> player.isOnLadder() && motionYState(player, 0.1D) == 1);
        register("ladder_stillness", Priority.HIGHEST, (player, event) -> player.isOnLadder() && motionYState(player, 0.1D) == 0);
        register("ladder_down", Priority.HIGHEST, (player, event) -> player.isOnLadder() && motionYState(player, 0.1D) == -1);
    }

    private static void registerRidingFlyingStates() {
        register("ride_pig", Priority.HIGH, (player, event) -> player.ridingEntity instanceof EntityPig);
        register("ride", Priority.HIGH, (player, event) -> player.isRiding() && !(player.ridingEntity instanceof EntityBoat));
        register("boat", Priority.HIGH, (player, event) -> player.ridingEntity instanceof EntityBoat);
        register("sit", Priority.HIGH, (player, event) -> player.isRiding());
        register("elytra_fly", Priority.HIGH, (player, event) -> EtFuturumCompat.isElytraFlying(player));
        register("fly", Priority.HIGH, (player, event) -> isPlayerFlying(player));
        register("swim_stand", Priority.NORMAL, (player, event) -> player.isInWater());
    }

    private static void registerDamageJumpSneakStates() {
        register("attacked", ILoopType.EDefaultLoopTypes.PLAY_ONCE, Priority.NORMAL, (player, event) -> player.hurtTime > 0);
        register("jump", Priority.NORMAL, (player, event) -> isPlayerJumping(player));
        register("sneak", Priority.NORMAL, (player, event) -> isPlayerOnGround(player) && player.isSneaking() && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED);
        register("sneaking", Priority.NORMAL, (player, event) -> isPlayerOnGround(player) && player.isSneaking());
    }

    private static void registerMovementStates() {
        register("run", Priority.LOW, (player, event) -> isPlayerOnGround(player) && player.isSprinting());
        register("walk", Priority.LOW, (player, event) -> isPlayerOnGround(player) && event.getLimbSwingAmount() > MIN_SPEED);
    }

    private static void registerIdleFallback() {
        register("idle", Priority.LOWEST, (player, event) -> true);
    }

    @SuppressWarnings("deprecation")
    public static void registerVariables() {
        MolangParser parser = GeckoLibCache.getInstance().parser;
        registerQueryVariables(parser);
        registerYsmVariables(parser);
    }

    /**
     * 注册 vendored GeckoLib/Molang 的反向控制钩子（MolangParser / ScopedMolangVariable），
     * 让 vendored 代码零引用 mod 类（与 YsmBuiltinAnimations.registerHooks 同模式）。
     * 仅需在 ClientProxy.init 调用一次（幂等），运行时只读；必须在任何模型/动画加载前执行
     * （ClientProxy.init 在 FML init 阶段，早于模型加载）。
     */
    public static void registerMolangHooks() {
        // 1) YSMU 特有 Molang 函数注册（每构造一个新 MolangParser 都会执行一次）。
        // ctrl.* / query.* / ysm.* 说明见原 vendored doCoreRemaps()（已迁移至此）。
        MolangParser.ysmFunctionRegistrar = functions -> {
            // ctrl.hold/use/swing/armor：keyframe/时间轴/.molang 脚本里的真实现。
            // 以前这三个注册的是恒 0 桩（真实现只在控制器条件路径），于是
            // ctrl.hold('mainhand', '$minecraft:apple') 在脚本里永远不成立；
            // 现在两条路径共用 CtrlItemMatcher 的规则。
            functions.put("ctrl.hold", CtrlItemFunction.class);
            functions.put("ctrl.use", CtrlItemFunction.class);
            functions.put("ctrl.swing", CtrlItemFunction.class);
            functions.put("ctrl.armor", CtrlArmorFunction.class);
            // ctrl.ride：keyframe 路径的桩（恒 0）。控制器条件路径有真实现，见
            // OpenYsmControllerExpressionEvaluator.functionValue。
            functions.put("ctrl.ride", CtrlHoldFunction.class);
            // query.position_delta(axis)：函数版按轴返回位移分量；变量版由 setEntityQueryValues 提供。
            functions.put("query.position_delta", QueryPositionDeltaFunction.class);
            // query.position(axis)：按轴返回当前渲染实体绝对位置（BE wiki 语义，Y=脚底）。
            functions.put("query.position", QueryPositionFunction.class);
            // query.relative_block_has_any_tag：只支持能原生回答的标签（minecraft:replaceable），
            // 其余返回 false —— 1.7.10 没有数据驱动的方块标签。
            functions.put("query.relative_block_has_any_tag", QueryBlockTagFunction.class);
            // query.is_item_name_any：按槽位取物品注册名匹配（物品标签类查询仍不支持）。
            functions.put("query.is_item_name_any", QueryItemNameAnyFunction.class);
            // query.max_durability / query.remaining_durability(slotType)：YSM-wiki molang/ref 2.2.1。
            functions.put("query.max_durability", QueryDurabilityFunction.class);
            functions.put("query.remaining_durability", QueryDurabilityFunction.class);
            // query.equipped_item_{any_tag,all_tags}(slotType, tag...)：1.7.10 没有数据驱动的物品
            // 标签，只回答能原生回答的（物品类型 + 矿物词典），其余 false。
            // 必须注册：未注册函数会让整条关键帧表达式解析失败、整个动画被丢弃。
            functions.put("query.equipped_item_any_tag", QueryItemTagFunction.class);
            functions.put("query.equipped_item_all_tags", QueryItemTagFunction.class);
            // ysm.play_sound / stop_sound / stop_all_sounds：模型 Molang 音效播放（走 YSMSoundManager）。
            functions.put("ysm.play_sound", YsmSoundFunction.class);
            functions.put("ysm.stop_sound", YsmSoundFunction.class);
            functions.put("ysm.stop_all_sounds", YsmSoundFunction.class);
            // ysm.mod_version：1.7.10 模组版本是字符串、无统一数字语义，注册桩函数防报错刷屏。
            functions.put("ysm.mod_version", CtrlHoldFunction.class);
            // ysm.perlin_noise：3D 柏林噪声（返回 [0,1]），自实现。
            functions.put("ysm.perlin_noise", PerlinNoiseFunction.class);
            // ysm.relative_block_name：返回玩家相对偏移处方块注册名（OpenYSM 语义，±5 格）。
            functions.put("ysm.relative_block_name", RelativeBlockNameFunction.class);
            // ysm.equipped_enchantment_level：返回指定槽位物品上给定附魔的等级之和。
            functions.put("ysm.equipped_enchantment_level", EquippedEnchantmentLevelFunction.class);
            // ysm.effect_level：返回渲染实体身上给定药水效果的等级之和（1 级 = 1）。
            functions.put("ysm.effect_level", YsmEffectLevelFunction.class);
            // query.debug_output(...)：调试输出到聊天框（wiki: 仅动画调试模式有效）。
            functions.put("query.debug_output", QueryDebugOutputFunction.class);
            // ysm.dump_*：调试用的物品/方块/模组/药水/群系信息转储。
            functions.put("ysm.dump_equipped_item", YsmDebugDumpFunction.class);
            functions.put("ysm.dump_relative_block", YsmDebugDumpFunction.class);
            functions.put("ysm.dump_mods", YsmDebugDumpFunction.class);
            functions.put("ysm.dump_effects", YsmDebugDumpFunction.class);
            functions.put("ysm.dump_biome", YsmDebugDumpFunction.class);
            // ysm.particle / particle / abs_particle：OpenYSM 粒子 Molang 函数。
            // ParticleFunction 通过 MolangStringPool 还原字符串参数（粒子 id），
            // 实体上下文由 ParticleEffectUtil.setCurrentEntity 每帧写入。
            functions.put("ysm.particle", ParticleFunction.class);
            // ysm.abs_particle：与 ysm.particle 成对。漏注册时整条关键帧表达式解析失败、
            // 整个 animation 被丢弃（与 query.equipped_item_*_tag 同一条约定），
            // 所以四个名字必须都在。ParticleFunction 按注册名里的 "abs_" 判定绝对模式。
            functions.put("ysm.abs_particle", ParticleFunction.class);
            functions.put("particle", ParticleFunction.class);
            functions.put("abs_particle", ParticleFunction.class);
            // ysm.bone_pivot_abs：骨骼绝对枢轴（模型单位），沿父链应用完整变换。
            // .x/.y/.z 后缀由 MolangParser.rewriteVectorFunction 重写为 _x/_y/_z 注册名。
            functions.put("ysm.bone_pivot_abs_x", BonePivotAbsFunction.class);
            functions.put("ysm.bone_pivot_abs_y", BonePivotAbsFunction.class);
            functions.put("ysm.bone_pivot_abs_z", BonePivotAbsFunction.class);
            // ysm.keyboard(键码...)：关键帧/时间轴 Molang 里的按键查询。
            // 以前和 ctrl.* 共用恒返回 0 的 CtrlHoldFunction，导致按键驱动的模型收不到输入。
            functions.put("ysm.keyboard", com.fox.ysmu.client.animation.molang.YsmKeyboardFunction.class);
            // ysm.mouse(按钮...)：鼠标按钮查询（wiki 2.5.0），此前完全没有实现。
            functions.put("ysm.mouse", com.fox.ysmu.client.animation.molang.YsmMouseFunction.class);
            // ysm.sync(...)：把参数同步给服务器上的所有玩家，触发他们那边该模型的 sync 事件脚本。
            functions.put("ysm.sync", com.fox.ysmu.client.animation.molang.YsmSyncFunction.class);
            // ysm.has_any_curios(槽位, id...)：Curios 在 1.7.10 上用前身 Baubles（GTNH 是
            // Baubles-Expanded）实现；未安装时恒 false，槽位没有对应物时也会提示一次。
            functions.put("ysm.has_any_curios",
                com.fox.ysmu.client.animation.molang.QueryHasAnyCuriosFunction.class);
        };

        // 2) `??` 运算符的"显式设置"判定（按当前渲染模型，防跨模型污染）。
        MolangParser.explicitVariableLookup = fullName -> {
            if (!MolangPhysicsRuntime.containsKey(fullName)) {
                return false;
            }
            String lookupName = fullName.startsWith("v.") ? fullName.substring(2) : fullName;
            return OpenYsmPlayerControllerRuntime.isRoamingExplicit(
                MolangPhysicsRuntime.getCurrentModelId(), lookupName);
        };

        // 3) v.* 变量的 (player, model) 作用域存储。无帧上下文时各方法优雅降级
        //    （contains=false / get 返回 fallback / set 返回 false → 回退全局 VARIABLES）。
        ScopedMolangVariable.store = new ScopedMolangVariable.ScopedVariableStore() {
            @Override
            public boolean contains(String name) {
                return MolangPhysicsRuntime.containsKey(name);
            }

            @Override
            public double get(String name, double fallback) {
                return MolangPhysicsRuntime.getVariable(name, fallback);
            }

            @Override
            public boolean set(String name, double value) {
                return MolangPhysicsRuntime.setVariable(name, value);
            }
        };

        // 3.5) v.* 全局 fallback 的模型隔离：当前模型 scope 无该变量时，
        // 只读回当前模型自己写入的全局值；其他模型写入的残留（如某模型
        // timeline 写的 v.roaming.a 被 14_momo 读到）返回 0。无来源记录
        // 的系统注册变量不受影响。
        ScopedMolangVariable.globalFallback = (name, fallback) ->
            MolangPhysicsRuntime.getGlobalScopedValue(name, fallback);

        // 4) vendored 物理函数桥（ysm.first_order / second_order / bone_rot / bone_pos / bone_scale）。
        // 无帧上下文时 MolangPhysicsRuntime 各方法优雅降级（first/secondOrder 返回 input，bone* 返回 0）。
        MolangPhysicsBridge.physics = new MolangPhysicsBridge.Physics() {
            @Override
            public double bonePosition(int nameId, char axis) {
                return MolangPhysicsRuntime.bonePosition(nameId, axis);
            }

            @Override
            public double boneRotation(int nameId, char axis) {
                return MolangPhysicsRuntime.boneRotation(nameId, axis);
            }

            @Override
            public double boneScale(int nameId, char axis) {
                return MolangPhysicsRuntime.boneScale(nameId, axis);
            }

            @Override
            public double firstOrder(int nameId, double input, double response) {
                return MolangPhysicsRuntime.firstOrder(nameId, input, response);
            }

            @Override
            public double secondOrder(int nameId, double input, double frequency, double coefficient, double response) {
                return MolangPhysicsRuntime.secondOrder(nameId, input, frequency, coefficient, response);
            }
        };
    }

    private static void registerQueryVariables(MolangParser parser) {
        parser.register(new LazyVariable("query.actor_count", 0));
        parser.register(new LazyVariable("query.anim_time", 0));
        parser.register(new LazyVariable("query.delta_time", 0.05));

        parser.register(new LazyVariable("query.body_x_rotation", 0));
        parser.register(new LazyVariable("query.body_y_rotation", 0));
        parser.register(new LazyVariable("query.cardinal_facing_2d", 0));
        parser.register(new LazyVariable("query.distance_from_camera", 0));
        parser.register(new LazyVariable("query.equipment_count", 0));
        parser.register(new LazyVariable("query.eye_target_x_rotation", 0));
        parser.register(new LazyVariable("query.eye_target_y_rotation", 0));
        parser.register(new LazyVariable("query.ground_speed", 0));

        parser.register(new LazyVariable("query.has_cape", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.has_rider", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.head_x_rotation", 0));
        parser.register(new LazyVariable("query.head_y_rotation", 0));
        // YSM-wiki: molang/ref 只有 head_x/head_y，没有 head_z（1.7.10 也没有实体 roll）。
        // YSMU 扩展：用**相机 roll** 当这个值 —— 本机玩家的头部朝向与镜头一致，而原版从不写
        // EntityRenderer.camRoll（恒 0，仅相机类 mod 会写），所以无相机 mod 时与官方行为一致。
        parser.register(new LazyVariable("query.head_z_rotation", 0));
        parser.register(new LazyVariable("query.health", 0));
        parser.register(new LazyVariable("query.hurt_time", 0));

        parser.register(new LazyVariable("query.is_eating", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_first_person", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_in_water", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_in_water_or_rain", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_jumping", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_on_fire", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_on_ground", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_playing_dead", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_riding", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_sleeping", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_sneaking", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_spectator", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_sprinting", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_swimming", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_using_item", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.is_blocking", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.item_in_use_duration", 0));
        parser.register(new LazyVariable("query.item_max_use_duration", 0));
        parser.register(new LazyVariable("query.item_remaining_use_duration", 0));

        parser.register(new LazyVariable("query.life_time", 0));
        parser.register(new LazyVariable("query.max_health", 0));
        parser.register(new LazyVariable("query.modified_distance_moved", 0));
        parser.register(new LazyVariable("query.moon_phase", 0));

        parser.register(new LazyVariable("query.player_level", 0));
        parser.register(new LazyVariable("query.time_of_day", 0));
        parser.register(new LazyVariable("query.time_stamp", 0));
        parser.register(new LazyVariable("query.vertical_speed", 0));
        parser.register(new LazyVariable("query.all_animations_finished", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.any_animation_finished", MolangUtils.FALSE));
        parser.register(new LazyVariable("query.walk_distance", 0));
        parser.register(new LazyVariable("query.yaw_speed", 0));

        parser.register(new LazyVariable("query.position_delta", 0));
    }

    private static void registerYsmVariables(MolangParser parser) {
        parser.register(new LazyVariable("ysm.head_yaw", 0));
        parser.register(new LazyVariable("ysm.head_pitch", 0));
        parser.register(new LazyVariable("ysm.has_helmet", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.has_chest_plate", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.has_leggings", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.has_boots", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.has_mainhand", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.has_offhand", MolangUtils.FALSE));

        parser.register(new LazyVariable("ysm.has_elytra", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.elytra_rot_x", 0));
        parser.register(new LazyVariable("ysm.elytra_rot_y", 0));
        parser.register(new LazyVariable("ysm.elytra_rot_z", 0));

        parser.register(new LazyVariable("ysm.is_close_eyes", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.is_passenger", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.is_sleep", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.is_sneak", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.is_riptide", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.eye_in_water", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.on_ladder", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.is_fishing", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.swinging", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.swing_time", 0));
        parser.register(new LazyVariable("ysm.swinging_arm", 0));
        parser.register(new LazyVariable("ysm.mainhand_charged_crossbow", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.offhand_charged_crossbow", MolangUtils.FALSE));

        // 这六个原先只靠 setYsmValues 里的 setValue 隐式创建（getVariable 会 computeIfAbsent），
        // 显式注册后它们从第 0 帧起就有确定值，setPreviewParserValues 的"归零"也不再依赖创建时机。
        parser.register(new LazyVariable("ysm.ground_speed2", 0));
        parser.register(new LazyVariable("ysm.input_vertical", 0));
        parser.register(new LazyVariable("ysm.input_horizontal", 0));
        parser.register(new LazyVariable("ysm.xxa", 0));
        parser.register(new LazyVariable("ysm.yya", 0));
        parser.register(new LazyVariable("ysm.zza", 0));

        parser.register(new LazyVariable("ysm.armor_value", 0));
        parser.register(new LazyVariable("ysm.hurt_time", 0));
        parser.register(new LazyVariable("ysm.food_level", 20));
        parser.register(new LazyVariable("ysm.time_delta", 0));
        parser.register(new LazyVariable("ysm.person_view", 0));
        parser.register(new LazyVariable("ysm.rendering_in_inventory", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.rendering_in_paperdoll", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.is_open_air", MolangUtils.FALSE));
        parser.register(new LazyVariable("ysm.weather", 0));
        parser.register(new LazyVariable("ysm.dimension_name", 0));
        parser.register(new LazyVariable("ysm.block_light", 0));
        parser.register(new LazyVariable("ysm.sky_light", 0));
        parser.register(new LazyVariable("ysm.texture_name", 0));
        // 渲染实体类型：字符串池 id（0=空串，非玩家渲染）。
        parser.register(new LazyVariable("ysm.entity_type", 0));
        parser.register(new LazyVariable("ysm.is_player", MolangUtils.FALSE));
        // 1.7.10 无车万女仆（TLM），恒 false。
        parser.register(new LazyVariable("ysm.is_maid", MolangUtils.FALSE));

        // parser.register(new LazyVariable("ysm.first_person_mod_hide", MolangUtils.FALSE));
    }

    public static void setParserValue(AnimationEvent<CustomPlayerEntity> animationEvent, MolangParser parser,
        EntityModelData data, EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return;
        }
        // 粒子 Molang 函数（particle/abs_particle）的实体上下文：mclib Function
        // 无状态，粒子函数在 get() 时刻从这里读取当前渲染帧的玩家。
        ParticleEffectUtil.setCurrentEntity(player);
        RemotePlayerAnimationQueries.QueryValues queryValues = RemotePlayerAnimationQueries
            .get(animationEvent, player, data.netHeadYaw);
        setEntityQueryValues(parser, data, player, mc, queryValues);
        setStateQueryValues(parser, player, mc);
        setItemUseQueryValues(parser, player);
        setWorldQueryValues(parser, player, mc);
        setYsmValues(animationEvent, parser, data, player, mc, queryValues);
    }

    private static void setEntityQueryValues(MolangParser parser, EntityModelData data, EntityPlayer player,
        Minecraft mc, RemotePlayerAnimationQueries.QueryValues queryValues) {
        parser.setValue("query.actor_count", () -> mc.theWorld.loadedEntityList.size());
        // rotationPitch 是玩家的垂直视角，而 body_x_rotation 应代表身体俯仰
        // OpenYSM 中对 body_x_rotation 做了帧间插值：lerp(xRotO, xRot)
        parser.setValue("query.body_x_rotation", player.rotationPitch);
        // renderYawOffset 对应身体的偏航角（转动头部时身体不会立即跟随），
        // 等效于 OpenYSM 中的 yBodyRot。切勿使用 rotationYaw（头部偏航）。
        parser.setValue("query.body_y_rotation", () -> MathHelper.wrapAngleTo180_float(player.renderYawOffset));
        parser.setValue("query.cardinal_facing_2d", () -> {
            int facing = MathHelper.floor_double((double) (player.rotationYaw * 4.0F / 360.0F) + 0.5D) & 3;
            // YSM mapping: North=2, South=3, West=4, East=5
            double[] YSM_CARDINAL = {3.0, 4.0, 2.0, 5.0};
            return YSM_CARDINAL[facing];
        });
        parser.setValue("query.distance_from_camera", () -> mc.renderViewEntity.getDistanceToEntity(player));
        parser.setValue("query.equipment_count", () -> getEquipmentCount(player));
        parser.setValue("query.eye_target_x_rotation", () -> player.rotationPitch);
        parser.setValue("query.eye_target_y_rotation", () -> player.rotationYaw);
        parser.setValue("query.ground_speed", queryValues.groundSpeed());
        parser.setValue("query.has_cape", () -> MolangUtils.booleanToFloat(hasCape(player)));
        parser.setValue("query.has_rider", () -> MolangUtils.booleanToFloat(player.riddenByEntity != null));
        // 同上：相机 roll，且只对本机玩家（远程玩家的 roll 无同步字段）。详见 CameraRollQuery。
        parser.setValue("query.head_z_rotation", () -> com.fox.ysmu.client.animation.molang.CameraRollQuery
            .isLocalPlayer(player) ? com.fox.ysmu.client.animation.molang.CameraRollQuery.interpolatedRoll() : 0.0d);
        parser.setValue("query.health", player::getHealth);
        parser.setValue("query.hurt_time", () -> player.hurtTime);
        parser.setValue("query.modified_distance_moved", () -> player.distanceWalkedModified);
        parser.setValue("query.vertical_speed", () -> getVerticalSpeed(player));
        parser.setValue("query.walk_distance", () -> player.distanceWalkedOnStepModified);
        parser.setValue("query.yaw_speed", queryValues.yawSpeed());

        parser.setValue("query.position_delta", () -> {
            double dx = player.posX - player.prevPosX;
            double dy = player.posY - player.prevPosY;
            double dz = player.posZ - player.prevPosZ;
            QueryPositionDeltaFunction.dx = dx;
            QueryPositionDeltaFunction.dy = dy;
            QueryPositionDeltaFunction.dz = dz;
            return Math.sqrt(dx*dx + dy*dy + dz*dz);
        });
    }

    private static void setStateQueryValues(MolangParser parser, EntityPlayer player, Minecraft mc) {
        parser.setValue("query.is_eating", () -> MolangUtils.booleanToFloat(player.getItemInUse() != null && player.getItemInUse().getItemUseAction() == EnumAction.eat));
        parser.setValue("query.is_first_person", () -> MolangUtils.booleanToFloat(mc.gameSettings.thirdPersonView == 0));
        parser.setValue("query.is_in_water", () -> MolangUtils.booleanToFloat(player.isInWater()));
        parser.setValue("query.is_in_water_or_rain", () -> MolangUtils.booleanToFloat(player.isWet()));
        parser.setValue("query.is_jumping", () -> MolangUtils.booleanToFloat(isPlayerJumping(player)));
        parser.setValue("query.is_on_fire", () -> MolangUtils.booleanToFloat(player.isBurning()));
        parser.setValue("query.is_on_ground", () -> MolangUtils.booleanToFloat(isPlayerOnGround(player)));
        parser.setValue("query.is_playing_dead", () -> MolangUtils.booleanToFloat(player.isDead));
        parser.setValue("query.is_riding", () -> MolangUtils.booleanToFloat(player.isRiding()));
        parser.setValue("query.is_sleeping", () -> MolangUtils.booleanToFloat(player.isPlayerSleeping()));
        parser.setValue("query.is_sneaking", () -> MolangUtils.booleanToFloat(player.isSneaking()));
        parser.setValue("query.is_sprinting", () -> MolangUtils.booleanToFloat(player.isSprinting()));
        parser.setValue("query.is_swimming", () -> MolangUtils.booleanToFloat(player.isInWater()));
        parser.setValue("query.is_using_item", () -> MolangUtils.booleanToFloat(player.isUsingItem()));
        parser.setValue("query.is_blocking", () -> MolangUtils.booleanToFloat(BlockingCompat.isBlocking(player)));
    }

    private static void setItemUseQueryValues(MolangParser parser, EntityPlayer player) {
        // In 1.7.10, item use ticks count down. Modern versions count up. The logic is inverted.
        parser.setValue("query.item_in_use_duration", () -> (getMaxUseDuration(player) - player.getItemInUseCount()) / 20.0);
        parser.setValue("query.item_max_use_duration", () -> getMaxUseDuration(player) / 20.0);
        parser.setValue("query.item_remaining_use_duration", () -> player.getItemInUseCount() / 20.0);
    }

    private static void setWorldQueryValues(MolangParser parser, EntityPlayer player, Minecraft mc) {
        parser.setValue("query.max_health", player::getMaxHealth);
        parser.setValue("query.moon_phase", () -> mc.theWorld.getMoonPhase());
        parser.setValue("query.player_level", () -> player.experienceLevel);
        parser.setValue("query.time_of_day", () -> MolangUtils.normalizeTime(mc.theWorld.getWorldTime()));
        parser.setValue("query.time_stamp", () -> mc.theWorld.getWorldTime());
    }

    /**
     * GUI 预览渲染前先调用的"玩家运动/视角"查询兜底。
     *
     * <p>预览实体是 {@code setPlayer(null)} 的假实体，{@link #setParserValue} 整段都不会执行
     * （它要求 {@code getPlayer() != null}），而 parser 是全局共享的 —— 于是预览里的动画求值读到的是
     * **世界渲染上一次留下的实时值**。实测（临时探针）预览里
     * {@code query.yaw_speed≈130}、{@code body_y_rotation=-180}、{@code head_yaw=22} 全是世界玩家的状态。</p>
     *
     * <p>后果对"累加型"模型很致命：模型把这类速率累加进 {@code v.} 变量（头发/披风滞后）时，预览里
     * 输入长期非 0 且不衰减 → 头发在模型选择界面里**无休止地 360 度旋转**（看起来像"鼠标没锁在屏幕中心"
     * 那种快速转角度）。预览里模型应当保持中性姿态，所以这里把"玩家在动/在看"的信号全部归零；
     * 会随时间自然推进的量（{@code query.anim_time}/{@code life_time}、{@code ysm.fps}、
     * {@code ysm.rendering_in_*}）刻意不动，预览动画本身仍要播放。</p>
     */
    public static void setPreviewParserValues(MolangParser parser) {
        if (parser == null) {
            return;
        }
        for (String name : PREVIEW_NEUTRAL_QUERIES) {
            parser.setValue(name, 0.0d);
        }
    }

    /** 预览里必须归零的"玩家运动/视角"查询：只放会驱动位移、形变、累加的开量。 */
    static final String[] PREVIEW_NEUTRAL_QUERIES = { "query.head_x_rotation",
        "query.head_y_rotation", "query.head_z_rotation", "query.eye_target_x_rotation",
        "query.eye_target_y_rotation", "query.body_x_rotation", "query.body_y_rotation", "query.yaw_speed",
        "query.ground_speed", "query.vertical_speed", "query.modified_distance_moved", "query.walk_distance",
        "ysm.head_yaw", "ysm.head_pitch", "ysm.ground_speed2", "ysm.input_vertical", "ysm.input_horizontal",
        "ysm.xxa", "ysm.yya", "ysm.zza", "ysm.elytra_rot_x", "ysm.elytra_rot_y", "ysm.elytra_rot_z" };

    /**
     * 头部旋转查询的唯一绑定点。
     *
     * <p>YSM-wiki: molang/ref 明写「{@code ysm.head_yaw} **与 query.head_x_rotation 相同**、
     * {@code ysm.head_pitch} **与 query.head_y_rotation 相同**」，参考实现的 QueryBinding 也是这么
     * 绑的（{@code head_x_rotation -> netHeadYaw}、{@code head_y_rotation -> headPitch}）。
     * 也就是**头部**这一对查询是 x = 左右视角(yaw)、y = 上下视角(pitch)，与
     * {@code query.eye_target_*}/{@code body_*} 那两对（x = pitch、y = yaw，同样是参考实现的绑法）
     * 刻意不同。</p>
     *
     * <p>原先这里 x 绑的是 pitch、y 绑的是 yaw（与 eye_target 同理但头部不是），于是模型用
     * {@code query.head_x_rotation} 做的**左右**摆动（头发/披风侧向甩动，或与 {@code ysm.head_yaw}
     * 混用算 yaw）拿到的是俯仰角：平视时 pitch≈0 看不出问题，头抬到顶/低到底时被塞进 ±90 的
     * 左右旋转 —— 头发过度翻转、卷曲。</p>
     *
     * <p>把两个名字接到同一对 supplier 上，从结构上保证 wiki 的等价关系不会被再次写反。</p>
     */
    static void bindHeadRotationQueries(MolangParser parser, DoubleSupplier headYaw, DoubleSupplier headPitch) {
        parser.setValue("ysm.head_yaw", headYaw);
        parser.setValue("ysm.head_pitch", headPitch);
        parser.setValue("query.head_x_rotation", headYaw);
        parser.setValue("query.head_y_rotation", headPitch);
    }

    private static void setYsmValues(AnimationEvent<CustomPlayerEntity> animationEvent, MolangParser parser,
        EntityModelData data, EntityPlayer player, Minecraft mc, RemotePlayerAnimationQueries.QueryValues queryValues) {
        bindHeadRotationQueries(parser, queryValues::headYaw, () -> data.headPitch);
        parser.setValue("ysm.input_vertical", () -> player.moveForward);
        parser.setValue("ysm.input_horizontal", () -> player.moveStrafing);
        parser.setValue("ysm.xxa", () -> player.moveStrafing);
        parser.setValue("ysm.zza", () -> player.moveForward);
        parser.setValue("ysm.has_helmet", () -> getSlotValue(player, 4));
        parser.setValue("ysm.has_chest_plate", () -> getSlotValue(player, 3));
        parser.setValue("ysm.has_leggings", () -> getSlotValue(player, 2));
        parser.setValue("ysm.has_boots", () -> getSlotValue(player, 1));
        parser.setValue("ysm.has_mainhand", () -> getSlotValue(player, 0));
        parser.setValue("ysm.has_offhand", () -> getSlotValue(player, 5));
        parser.setValue("ysm.is_close_eyes", () -> getEyeCloseState(animationEvent, player));
        parser.setValue("ysm.is_passenger", () -> MolangUtils.booleanToFloat(player.isRiding()));
        parser.setValue("ysm.is_sleep", () -> MolangUtils.booleanToFloat(player.isPlayerSleeping()));
        parser.setValue("ysm.is_sneak", () -> MolangUtils.booleanToFloat(isPlayerOnGround(player) && player.isSneaking()));
        // OpenYSM 语义：eye_in_water = 眼睛是否在水下（isUnderWater）。
        // 1.7.10 无 isUnderWater，用眼睛高度所在方块是否为水近似。
        parser.setValue("ysm.eye_in_water", () -> MolangUtils.booleanToFloat(isEyeInWater(player)));
        parser.setValue("ysm.on_ladder", () -> MolangUtils.booleanToFloat(player.isOnLadder()));
        parser.setValue("ysm.is_fishing", () -> MolangUtils.booleanToFloat(player.fishEntity != null));
        parser.setValue("ysm.swinging", () -> MolangUtils.booleanToFloat(player.isSwingInProgress));
        parser.setValue("ysm.swing_time", () -> player.swingProgressInt);
        parser.setValue("ysm.swinging_arm", () -> BackhandCompat.swingingArm(player) ? 0.0d : 1.0d);
        parser.setValue("ysm.mainhand_charged_crossbow", () -> MolangUtils.booleanToFloat(
            com.fox.ysmu.compat.TinkersCrossbowCompat.isCrossbowLoaded(player.getHeldItem())));
        parser.setValue("ysm.offhand_charged_crossbow", () -> MolangUtils.booleanToFloat(
            com.fox.ysmu.compat.TinkersCrossbowCompat.isCrossbowLoaded(BackhandCompat.getOffhandItem(player))));
        parser.setValue("ysm.armor_value", player::getTotalArmorValue);
        parser.setValue("ysm.hurt_time", () -> player.hurtTime);
        parser.setValue("ysm.food_level", () -> player.getFoodStats().getFoodLevel());
        parser.setValue("ysm.time_delta", com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime::getTimeDelta);
        // wiki: molang/ref 2.5.1 —— ysm.ground_speed2：真实水平速度（每 tick 位置差值 ×20）。
        // 与 query.ground_speed 的"对齐 wiki 基准"近似值不同：不裁切、不平滑，本地/远程同一口径。
        parser.setValue("ysm.ground_speed2", () -> RemotePlayerAnimationQueries
            .realGroundSpeed(player.posX - player.prevPosX, player.posZ - player.prevPosZ));
        parser.setValue("ysm.has_elytra", () -> MolangUtils.booleanToFloat(
            com.fox.ysmu.compat.EtFuturumCompat.hasElytraEquipped(player)));
        parser.setValue("ysm.elytra_rot_x", () -> player.rotationPitch);
        parser.setValue("ysm.elytra_rot_y", () -> player.rotationYaw);
        parser.setValue("ysm.elytra_rot_z", 0);
        parser.setValue("ysm.person_view", () -> mc.gameSettings.thirdPersonView);
        parser.setValue("ysm.rendering_in_inventory", () -> MolangUtils.booleanToFloat(
            com.fox.ysmu.util.RenderUtil.RENDERING_IN_INVENTORY));
        parser.setValue("ysm.rendering_in_paperdoll", () -> MolangUtils.booleanToFloat(
            com.fox.ysmu.util.RenderUtil.RENDERING_IN_PAPERDOLL));
        parser.setValue("ysm.is_open_air", () -> MolangUtils.booleanToFloat(
            player.worldObj.canBlockSeeTheSky(
                MathHelper.floor_double(player.posX),
                MathHelper.floor_double(player.posY + 1.0D),
                MathHelper.floor_double(player.posZ))));
        parser.setValue("ysm.weather", () -> {
            if (mc.theWorld.isThundering()) return 2.0;
            if (mc.theWorld.isRaining()) return 1.0;
            return 0.0;
        });
        parser.setValue("ysm.dimension_name", () -> (double) player.dimension);
        parser.setValue("ysm.block_light", () -> player.worldObj.getBlockLightValue(
            MathHelper.floor_double(player.posX),
            MathHelper.floor_double(player.posY),
            MathHelper.floor_double(player.posZ)));
        parser.setValue("ysm.sky_light", () -> player.worldObj.getSavedLightValue(
            EnumSkyBlock.Sky,
            MathHelper.floor_double(player.posX),
            MathHelper.floor_double(player.posY),
            MathHelper.floor_double(player.posZ)));
        parser.setValue("ysm.texture_name", 0);
        // 玩家渲染路径：渲染对象必为玩家模型（CustomPlayerRenderer）。
        // entity_type 用字符串池 id，使 keyframe（mclib）里 ysm.entity_type=='player'
        // 经 MolangParser.replaceStringLiterals 池化后可以数字比较命中。
        parser.setValue("ysm.entity_type", () -> MolangStringPool.intern("player"));
        parser.setValue("ysm.is_player", () -> MolangUtils.booleanToFloat(true));
        parser.setValue("ysm.is_maid", () -> MolangUtils.booleanToFloat(false));
    }

    private static boolean hasCape(EntityPlayer player) {
        if (player instanceof AbstractClientPlayer) {
            AbstractClientPlayer clientPlayer = (AbstractClientPlayer) player;
            // 'isCapeLoaded' & 'isModelPartShown' are modern. 1.7.10 has simpler checks.
            // func_152122_n() checks if the cape texture is available and should be rendered.
            return !player.isInvisible() && clientPlayer.func_152122_n() && clientPlayer.getLocationCape() != null;
        }
        return false;
    }

    private static int getEquipmentCount(EntityPlayer player) {
        int count = 0;
        for (ItemStack s : player.inventory.armorInventory) {
            if (s != null) {
                count += 1;
            }
        }
        return count;
    }

    private static float getMaxUseDuration(EntityPlayer player) {
        ItemStack useItem = player.getItemInUse();
        if (useItem == null) {
            return 0.0f;
        } else {
            return useItem.getMaxItemUseDuration();
        }
    }

    private static float getVerticalSpeed(EntityPlayer player) {
        return (float) ((player.posY - player.prevPosY) * 20.0);
    }

    private static void register(String animationName, ILoopType loopType, int priority,
        BiPredicate<EntityPlayer, AnimationEvent<CustomPlayerEntity>> predicate) {
        AnimationManager manager = AnimationManager.getInstance();
        manager.register(new AnimationState(animationName, loopType, priority, predicate));
    }

    private static void register(String animationName, int priority,
        BiPredicate<EntityPlayer, AnimationEvent<CustomPlayerEntity>> predicate) {
        register(animationName, ILoopType.EDefaultLoopTypes.LOOP, priority, predicate);
    }

    private static double getEyeCloseState(AnimationEvent<CustomPlayerEntity> animationEvent, EntityPlayer player) {
        double remainder = (animationEvent.getAnimationTick() + Math.abs(
            player.getUniqueID()
                .getLeastSignificantBits())
            % 10) % 90;
        boolean isBlinkTime = 85 < remainder && remainder < 90;
        return MolangUtils.booleanToFloat(player.isPlayerSleeping() || isBlinkTime);
    }

    private static double getSlotValue(EntityPlayer player, int slotIndex) {
        if (slotIndex == 5) {
            return MolangUtils.booleanToFloat(BackhandCompat.getOffhandItem(player) != null);
        } else {
            return MolangUtils.booleanToFloat(player.getEquipmentInSlot(slotIndex) != null);
        }
    }

    /** OpenYSM isUnderWater 的 1.7.10 近似：眼睛高度所在方块为水材质。 */
    private static boolean isEyeInWater(EntityPlayer player) {
        if (player.worldObj == null) {
            return false;
        }
        // 1.7.10 玩家 posY 已含 yOffset(1.62) = 眼睛高度（Entity.posY = boundingBox.minY + yOffset），
        // 相机直接用 posY。OpenYSM 的 isUnderWater 检查眼睛（getY() + eyeHeight）所在流体，
        // 这里等价检查 floor(posY) 所在方块即可——不能再 + getEyeHeight()（会高出约 1.62）。
        int eyeY = MathHelper.floor_double(player.posY);
        int eyeX = MathHelper.floor_double(player.posX);
        int eyeZ = MathHelper.floor_double(player.posZ);
        return player.worldObj.getBlock(eyeX, eyeY, eyeZ).getMaterial() == net.minecraft.block.material.Material.water;
    }

    private static boolean isPlayerOnGround(EntityPlayer player) {
        // 本地玩家
        if (player == Minecraft.getMinecraft().thePlayer) {
            return player.onGround;
        } else {
            return RemotePlayerMotionStates.isOnGround(player);
        }
    }

    private static boolean isPlayerFlying(EntityPlayer player) {
        // 检查鞘翅飞行
        if (EtFuturumCompat.isElytraFlying(player)) {
            return true;
        }
        // 本地玩家
        if (player == Minecraft.getMinecraft().thePlayer) {
            return player.capabilities.isFlying;
        } else {
            return RemotePlayerMotionStates.isFlying(player);
        }
    }

    private static boolean isPlayerJumping(EntityPlayer player) {
        if (isPlayerFlying(player) || player.isRiding() || isPlayerOnGround(player) || player.isInWater()) {
            return false;
        }
        if (player == Minecraft.getMinecraft().thePlayer) {
            return motionYState(player, 0.0D) != 0;
        }
        return true;
    }

    /**
     * 获取玩家的垂直移动状态
     * 返回值: 0=静止/未知, 1=向上, -1=向下
     */
    private static int motionYState(EntityPlayer player, double threshold) {
        double motionY;
        if (player == Minecraft.getMinecraft().thePlayer) {
            motionY = player.motionY;
        } else {
            motionY = (player.posY - player.prevPosY) * 2.0D;
        }
        if (motionY > threshold) {
            return 1;
        } else if (motionY < -threshold) {
            return -1;
        } else {
            return 0;
        }
    }
}
