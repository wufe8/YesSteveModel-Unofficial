package com.fox.ysmu.client.animation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

import com.fox.ysmu.Config;
import com.fox.ysmu.ysmu;

import org.apache.commons.lang3.StringUtils;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.expressions.MolangExpression;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;

/**
 * 移动动画防滑步（stride matching）。
 *
 * 问题：YSM 的 walk/run/sneak 等移动动画是固定周期循环（动画长度固定），
 * 步态周期覆盖的"步幅"固定；当玩家实际速度与动画设计速度不一致时，
 * 脚就会在冰面上滑（滑步）。
 *
 * 解法：按真实水平速度缩放移动类动画的播放倍速（GeckoLib
 * {@code AnimationController.animationSpeed}）：
 *
 *     设计速度 = 该步态规范步幅 S / 动画周期 T
 *     倍速     = 基础倍率 × 平滑水平速度 / 设计速度
 *              = 基础倍率 × 平滑水平速度 × T / S
 *
 * 设计速度直接由模型动画的实际周期计算，对需要"腿与地面变化接触"的
 * 四种步态生效：walk/run/sneak/swim。每种步态用各自的规范步幅归一化：
 *
 *     walk/run：共享同一双足步态步幅 4.317（= 4.317×默认 walk 1.00s）
 *     sneak：   自己的步幅 1.295（= 1.295×默认 sneak 1.00s）
 *     swim：    自己的步幅 1.727（= 1.727×默认 swim 1.00s）
 *
 * 这样 walk(1.0s)/run(0.6667s) 归一化到同一步幅；sneak/swim 各自归一到自己的
 * 步幅。同一步态下，同一实际速度脚速一致；再套用实际速度随动。
 * 玩家水平速度每帧实时读取（可受药水 buff/debuff 等影响），倍速随之自适应。
 * 外部配置只暴露一个基础倍率（{@code Config.ANIMATION_SPEED_MATCH_BASE}，
 * 默认 1.0），步幅是内部常量。fly 等没有"腿与地面变化接触"的动画不参与（不缩放）。
 *
 * 名称表（方案 A）仍用于：1) 判断该动画是否移动类（分类）；
 * 2) 周期未知时的回退设计速度。通过 {@link SpeedProvider} 接口解耦，
 * 后续可替换为模型自带字段（方案 B）或可视化校准页（方案 C）的实现。
 * 播放倍速的平滑状态目前按玩家 UUID 保存在静态 Map 中（有状态版本）；
 * 后续可考虑改为无状态实现。
 */
public final class MovementSpeedMatcher {

    /** 低于此水平速度（blocks/s）视为静止，不缩放（交回状态机切换 idle）。 */
    private static final double MIN_GROUND_SPEED = 0.1;
    /** 倍速下限：防止极端钳制导致动画几乎定格。 */
    private static final double MIN_MULTIPLIER = 0.2;
    /** 倍速上限。 */
    private static final double MAX_MULTIPLIER = 3.0;

    // ---- 方案 A：动画名 → 设计速度 (blocks/s) 名称表（仅作回退） ----
    // 参考 vanilla 1.7.10 玩家速度：行走 4.317、冲刺 5.612（×1.3）、
    // 潜行 1.295（×0.3）、游泳 1.727（×0.4）。fly 无"腿地接触"不参与。
    // 现代 OpenYSM 模型（如乐魂）的动画名是模型自定义的（如"行走"/"疾跑"），
    // 不在表中 —— 由 SpeedProvider 返回 -1 表示不缩放，等待方案 B/C 覆盖。
    private static final String[] LOCOMOTION_NAMES = {
        "walk", "walking", "walk_loop",
        "run", "running", "run_loop",
        "sprint", "sprinting",
        "sneak", "sneaking", "crouch", "crouching",
        "swim", "swimming",
    };
    private static final double[] LOCOMOTION_SPEEDS = {
        4.317, 4.317, 4.317,
        5.612, 5.612, 5.612,
        5.612, 5.612,
        1.295, 1.295, 1.295, 1.295,
        1.727, 1.727,
    };

    // ---- 各步态规范步幅（blocks/完整步幅周期，内部常量，不暴露给配置）----
    // 源自 vanilla 速度 × 默认模型对应动画周期（walk/sneak/swim 均 1.00s）：
    //   walk/run = 4.317 × 1.0 = 4.317
    //   sneak    = 1.295 × 1.0 = 1.295
    //   swim     = 1.727 × 1.0 = 1.727
    private static final double WALK_RUN_STRIDE = 4.317;
    private static final double SNEAK_STRIDE = 1.295;
    private static final double SWIM_STRIDE = 1.727;

    private static final String[] WALK_RUN_NAMES = {
        "walk", "walking", "walk_loop",
        "run", "running", "run_loop",
        "sprint", "sprinting",
    };
    private static final String[] SNEAK_NAMES = {
        "sneak", "sneaking", "crouch", "crouching",
    };
    private static final String[] SWIM_NAMES = {
        "swim", "swimming",
    };

    /**
     * 动画设计速度提供者。
     * 返回该动画名对应的"设计速度"（blocks/s）；返回 <= 0 表示该动画
     * 不是移动类动画（如 idle/jump/swing），不应做倍速缩放。
     */
    public interface SpeedProvider {
        double designSpeedFor(String animationName);
    }

    /** 默认名称表实现（方案 A）。 */
    public static final SpeedProvider DEFAULT_PROVIDER = name -> {
        if (name != null) {
            for (int i = 0; i < LOCOMOTION_NAMES.length; i++) {
                if (LOCOMOTION_NAMES[i].equalsIgnoreCase(name)) {
                    return LOCOMOTION_SPEEDS[i];
                }
            }
        }
        return -1.0d;
    };

    /** 每玩家平滑倍速状态（有状态版本；后续无状态版本可移除）。 */
    private static final Map<UUID, Double> SMOOTHED_MULTIPLIER = new ConcurrentHashMap<>();
    /** anim_speed 表达式解析缓存（按表达式文本，全局共享）。 */
    private static final Map<String, MolangExpression> ANIM_SPEED_CACHE = new ConcurrentHashMap<>();

    private MovementSpeedMatcher() {}

    /**
     * 计算当前应施加的动画播放倍速（1.0 = 不缩放）。
     *
     * @param player             玩家（非 null）
     * @param primaryAnimationName 正在播放的主动画名
     * @param provider           设计速度提供者（null 时用默认名称表）
     * @param cycleSeconds       该动画的周期时长（秒）；<=0 表示未知，回退名称表
     * @return 播放倍速
     */
    public static double computeMultiplier(EntityPlayer player, String primaryAnimationName,
        SpeedProvider provider, double cycleSeconds) {
        SpeedProvider prov = provider != null ? provider : DEFAULT_PROVIDER;
        double design = designSpeed(primaryAnimationName, prov, cycleSeconds);
        if (design <= 0.0d) {
            // 非移动类动画：立即恢复 1.0，不留平滑残留
            clearSmoothing(player);
            return 1.0d;
        }
        double groundSpeed = groundSpeed(player);
        double target;
        if (groundSpeed < MIN_GROUND_SPEED) {
            // 静止：缓慢回到 1.0（状态机即将切到 idle，避免动画突然定格）
            target = 1.0d;
        } else {
            // 基础倍率 × 实际速度 / 设计速度。玩家速度每帧实时读取
            //（药水 buff/debuff 等会改变），倍速随之自适应。
            target = Config.ANIMATION_SPEED_MATCH_BASE * (groundSpeed / design);
        }
        target = MathHelper.clamp_double(target, MIN_MULTIPLIER, MAX_MULTIPLIER);

        // 客观验证：DEBUG_CONTROLLER 开启时每秒打印一次实际数值
        if (Config.DEBUG_CONTROLLER && allowDebugLog("SPEED-MATCH")) {
            ysmu.LOG.info("[YSMU-SPEED] anim='{}' cycle={}s design={} groundSpeed={} target={}x (BASE={})",
                primaryAnimationName, cycleSeconds, design, groundSpeed, target, Config.ANIMATION_SPEED_MATCH_BASE);
        }

        double response = Config.ANIMATION_SPEED_MATCH_RESPONSE;
        UUID id = player.getUniqueID();
        Double prev = SMOOTHED_MULTIPLIER.get(id);
        double smoothed = prev == null ? target : prev + (target - prev) * response;
        SMOOTHED_MULTIPLIER.put(id, smoothed);
        return smoothed;
    }

    /**
     * 设计速度（blocks/s）。
     * walk/run/sneak/swim：周期已知时按 该步态基准步幅 / 周期 计算（归一化）；
     * 周期未知时回退名称表 vanilla 速度。非移动类（含 fly）返回 <= 0（不缩放）。
     */
    private static double designSpeed(String animationName, SpeedProvider provider, double cycleSeconds) {
        double stride = gaitStride(animationName);
        if (stride <= 0.0d) {
            // 非移动类（或待方案 B/C 覆盖），不缩放
            return -1.0d;
        }
        if (cycleSeconds > 0.0d) {
            // 各步态归一到自己的基准步幅：设计速度 = 步幅 / T。
            // 短周期动画设计速度更高，同实际速度下倍速更低 →
            // 同一步态下脚速一致。
            return stride / cycleSeconds;
        }
        return provider.designSpeedFor(animationName);
    }

    /** 返回该动画所属步态的基准步幅（blocks/完整步幅周期）；非移动类返回 <= 0。 */
    private static double gaitStride(String animationName) {
        if (animationName == null) {
            return -1.0d;
        }
        for (String n : WALK_RUN_NAMES) {
            if (n.equalsIgnoreCase(animationName)) {
                return WALK_RUN_STRIDE;
            }
        }
        for (String n : SNEAK_NAMES) {
            if (n.equalsIgnoreCase(animationName)) {
                return SNEAK_STRIDE;
            }
        }
        for (String n : SWIM_NAMES) {
            if (n.equalsIgnoreCase(animationName)) {
                return SWIM_STRIDE;
            }
        }
        return -1.0d;
    }

    /**
     * 从模型动画文件读取指定动画的周期时长（秒）。
     * 返回 <= 0 表示未知（动画缺失或没有长度）。
     *
     * @param file          模型动画文件（可为 null）
     * @param animationName 动画名
     * @return 周期秒数（GeckoLib animationLength 是 tick，÷20 转秒）
     */
    public static double cycleSeconds(AnimationFile file, String animationName) {
        if (file != null && animationName != null) {
            Animation anim = file.getAnimation(animationName);
            if (anim != null && anim.animationLength != null && anim.animationLength > 0.0d) {
                return anim.animationLength / 20.0d;
            }
        }
        return -1.0d;
    }

    /**
     * 求值当前动画的 anim_speed（逐动画播放倍率，数字或 Molang 表达式）。
     * 无该字段/求值失败返回 1.0（不缩放）。负值钳制为 0（冻结）。
     */
    public static double animSpeedFor(AnimationFile file, String animationName, MolangParser parser) {
        if (file == null || animationName == null) {
            return 1.0d;
        }
        Animation anim = file.getAnimation(animationName);
        if (anim == null || anim.animSpeed == null || anim.animSpeed.isEmpty()) {
            return 1.0d;
        }
        String raw = anim.animSpeed.trim();
        try {
            return Math.max(0.0d, Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            // Molang 表达式：每帧求值（依赖 query 变量，如 query.ground_speed）
            try {
                // parseExpression 内部会 lowercase（字符串字面量除外），缓存 key 统一小写以避免重复条目
                String key = raw.toLowerCase(java.util.Locale.ROOT);
                MolangExpression expr = ANIM_SPEED_CACHE.computeIfAbsent(key, s -> {
                    try {
                        return parser.parseExpression(s);
                    } catch (Exception ex) {
                        return null;
                    }
                });
                if (expr != null) {
                    return Math.max(0.0d, expr.get());
                }
            } catch (Exception ignored) {
                // 求值失败回退 1.0
            }
            return 1.0d;
        }
    }

    /**
     * 计算并写入控制器播放倍速 = anim_speed × 防滑步倍率。
     * legacy（AnimationManager）与 OpenYSM（OpenYsmPlayerControllerRuntime）
     * 两条播放路径共用此实现，避免重复。
     *
     * @param ctrl             目标控制器（非 null）
     * @param player           玩家；null（GUI 预览实体）时不干预，避免覆盖预览冻结
     * @param animationName    正在播放的动画名；空白视为不缩放
     * @param isBodyController 是否主身体控制器——防滑步仅对主身体控制器生效，
     *                         anim_speed 对所有控制器生效
     * @param file             模型动画文件（可 null）
     */
    public static void applyPlaybackSpeed(AnimationController<?> ctrl, EntityPlayer player,
        String animationName, boolean isBodyController, AnimationFile file) {
        if (ctrl == null || player == null || StringUtils.isBlank(animationName)) {
            return;
        }
        double strideMultiplier = 1.0d;
        // 防滑步：仅主身体控制器按真实速度缩放（设计速度 = 步幅 / 周期）
        if (Config.ANIMATION_SPEED_MATCH && isBodyController) {
            double cycleSeconds = cycleSeconds(file, animationName);
            strideMultiplier = computeMultiplier(player, animationName, DEFAULT_PROVIDER, cycleSeconds);
        }
        // anim_speed：模型作者逐动画播放倍率（所有控制器都生效）
        double animSpeed = animSpeedFor(file, animationName, GeckoLibCache.getInstance().parser);
        // 最终倍率 = anim_speed × 防滑步倍率
        ctrl.animationSpeed = strideMultiplier * animSpeed;
    }

    /** 清除指定玩家的平滑状态（例如切换模型/离开世界时）。 */
    public static void clearSmoothing(EntityPlayer player) {
        if (player != null) {
            SMOOTHED_MULTIPLIER.remove(player.getUniqueID());
        }
    }

    /** 清除所有平滑状态。 */
    public static void clearAll() {
        SMOOTHED_MULTIPLIER.clear();
    }

    /**
     * 水平速度（blocks/s）。
     * 用位置差值（每 tick 真实位移）× 20 计算，本地/远程一致。
     *
     * 不能对本地玩家用 motionX：1.7.10 客户端本地玩家的 motion 在渲染期
     * 数值约为 pos 差值的 1/4.317（实测 ground_speed 只有 ~1.0 而非 4.317），
     * 会导致 BASE 需要设到 ~4.317 才等效于 1.0（用户观察到 4.31 ≈ 4.317）。
     * 与 OpenYsmControllerExpressionEvaluator.horizontalSpeed()（控制器
     * ground_speed 查询）保持一致。
     */
    private static double groundSpeed(EntityPlayer player) {
        double dx = player.posX - player.prevPosX;
        double dz = player.posZ - player.prevPosZ;
        return MathHelper.sqrt_double(dx * dx + dz * dz) * 20.0d;
    }

    /** 调试日志限流：同一 tag 每秒最多一次。 */
    private static final Map<String, Long> DEBUG_LOG_LAST_TIME = new ConcurrentHashMap<>();

    private static boolean allowDebugLog(String tag) {
        long now = System.currentTimeMillis();
        Long last = DEBUG_LOG_LAST_TIME.get(tag);
        if (last != null && now - last < 1000) {
            return false;
        }
        DEBUG_LOG_LAST_TIME.put(tag, now);
        return true;
    }
}
