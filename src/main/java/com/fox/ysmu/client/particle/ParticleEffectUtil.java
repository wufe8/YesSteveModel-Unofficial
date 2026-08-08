package com.fox.ysmu.client.particle;

import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

import com.fox.ysmu.Config;
import com.fox.ysmu.ysmu;

/**
 * OpenYSM 风格粒子生成工具（1.7.10 落地层）。
 *
 * 供 {@code particle()}/{@code abs_particle()} Molang 函数使用，参数语义对齐
 * OpenYSM 的 {@code ParticleEffectUtil}：
 * <pre>
 *   particle(id, ox, oy, oz, dx, dy, dz, speed, count, lifetime)
 * </pre>
 * <ul>
 *   <li>{@code count == 0}：单粒子。位置 = 实体 + offset（相对模式按实体朝向旋转）；
 *       速度 = {@code speed × delta}。</li>
 *   <li>{@code count > 0}：{@code count} 个粒子。位置 = 实体 + offset + 高斯散布
 *       （{@code delta} 为 σ）；速度 = 高斯分布 × {@code speed}。</li>
 *   <li>{@code isAbsolute == true}（abs_particle）：offset 直接作为世界坐标偏移，
 *       不按实体朝向旋转。</li>
 * </ul>
 *
 * 1.7.10 没有"按任意注册粒子 id 生成"的通用 API，这里用
 * {@link net.minecraft.world.World#spawnParticle(String, double, double, double, double, double, double)}
 * 按 vanilla 粒子名生成；未知 id 静默跳过（不抛异常）。{@code lifetime} 参数
 * 1.7.10 无法直接设置，仅记录（内置粒子自带生命周期）。
 *
 * 所有生成都通过 {@link Minecraft#func_152344_a(Runnable)} 提交到客户端主线程，
 * 避免在渲染线程直接操作粒子系统。
 */
public final class ParticleEffectUtil {

    /** 批量粒子上限，防止模型包误用刷屏（1.7.10 粒子系统自身有数量上限）。 */
    private static final int MAX_BATCH_COUNT = 64;

    /**
     * 高版本粒子名 → 1.7.10 内置近似粒子名（降级）。
     * 当高版本游戏资产缺失（asset index 无 textures/particle）时，用 1.7.10 内置粒子
     * 近似显示，保证粒子链路可见；配置了完整高版本资产后自动改用真实纹理。
     */
    private static final java.util.Map<String, String> VANILLA_FALLBACK = new java.util.HashMap<>();

    static {
        VANILLA_FALLBACK.put("falling_dripstone_water", "dripWater");
        VANILLA_FALLBACK.put("dripping_dripstone_water", "dripWater");
        VANILLA_FALLBACK.put("falling_dripstone_lava", "dripLava");
        VANILLA_FALLBACK.put("dripping_dripstone_lava", "dripLava");
        VANILLA_FALLBACK.put("rain", "droplet");
        VANILLA_FALLBACK.put("snowflake", "snowshovel");
        VANILLA_FALLBACK.put("snow", "snowshovel");
        VANILLA_FALLBACK.put("bubble_column_up", "bubble");
        VANILLA_FALLBACK.put("bubble_pop", "bubble");
        VANILLA_FALLBACK.put("poof", "smoke");
        VANILLA_FALLBACK.put("small_flame", "flame");
        VANILLA_FALLBACK.put("soul_fire_flame", "flame");
        VANILLA_FALLBACK.put("campfire_cosy_smoke", "smoke");
        VANILLA_FALLBACK.put("campfire_signal_smoke", "largesmoke");
        VANILLA_FALLBACK.put("large_smoke", "largesmoke");
        VANILLA_FALLBACK.put("explosion", "largeexplode");
        VANILLA_FALLBACK.put("explosion_emitter", "hugeexplosion");
        VANILLA_FALLBACK.put("dragon_breath", "witchMagic");
        VANILLA_FALLBACK.put("end_rod", "townaura");
        VANILLA_FALLBACK.put("totem_of_undying", "happyVillager");
    }

    /** 当前渲染帧的实体上下文（由 {@code AnimationRegister.setParserValue} 每帧写入），
     *  供无实体上下文的 mclib Function 在 get() 时刻读取。 */
    private static volatile Entity currentEntity;

    private ParticleEffectUtil() {}

    /** 设置当前渲染帧的实体上下文。 */
    public static void setCurrentEntity(Entity entity) {
        currentEntity = entity;
    }

    /** 读取当前渲染帧的实体上下文（mclib 粒子函数使用）。 */
    public static Entity getCurrentEntity() {
        return currentEntity;
    }

    /**
     * 生成粒子。参数语义与 OpenYSM {@code ParticleEffectUtil.handleParticle} 对齐。
     *
     * @return 是否成功（id 为空 / GUI 预览 / 世界无效 → false）
     */
    public static boolean handleParticle(Entity entity, String id,
        double ox, double oy, double oz,
        double dx, double dy, double dz,
        double speed, int count, int lifetime, boolean isAbsolute) {
        if (entity == null || id == null || id.trim().isEmpty()) {
            if (Config.DEBUG_PARTICLE) {
                ysmu.LOG.info("[YSMU-PARTICLE] EARLY RETURN: entity={} id='{}'", entity, id);
            }
            return false;
        }
        // 不再按渲染上下文拦截（RENDERING_IN_INVENTORY/PAPERDOLL）：模型预览页要么无动画、
        // 要么只播放 preview 动画，不会触发粒子 timeline；而第一人称时玩家主模型动画由
        // HUD 纸娃娃驱动，粒子必须正常生成到世界坐标（第一人称屏幕可见）。
        // 只要实体与客户端世界有效就生成粒子。
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || !mc.theWorld.isRemote) {
            if (Config.DEBUG_PARTICLE) {
                ysmu.LOG.info("[YSMU-PARTICLE] EARLY RETURN: mc={} world={} remote={}",
                    mc, mc == null ? null : mc.theWorld, mc == null ? null : mc.theWorld.isRemote);
            }
            return false;
        }
        String particleName = normalizeParticleId(id);
        // 自定义粒子：若高版本游戏资产里有该粒子的纹理（如 falling_dripstone_water），
        // 则生成 CustomParticleFX（独立渲染）；否则回退 vanilla spawnParticle。
        int customTexId = ParticleTextureManager.getTextureId(particleName);
        // 高版本资产缺失时，把常见高版本粒子名映射到 1.7.10 内置近似粒子，保证效果可见。
        String emitName = particleName;
        if (customTexId < 0) {
            String fallback = VANILLA_FALLBACK.get(particleName);
            if (fallback != null) {
                emitName = fallback;
                if (Config.DEBUG_PARTICLE) {
                    ysmu.LOG.info("[YSMU-PARTICLE] '{}' no high-version texture -> vanilla fallback '{}'",
                        particleName, fallback);
                }
            }
        }
        if (count < 0) {
            count = 0;
        }
        if (count > MAX_BATCH_COUNT) {
            count = MAX_BATCH_COUNT;
        }
        if (lifetime < 1) {
            lifetime = 1;
        }
        // 调试/测试用全局 Y 偏移校正（Config.PARTICLE_Y_ADJUST，默认 0）
        oy -= Config.PARTICLE_Y_ADJUST;
        // 调试/测试用：xyz 偏移全部置 0（粒子直接生成在实体位置）
        if (Config.PARTICLE_ZERO_OFFSET) {
            ox = 0.0;
            oy = 0.0;
            oz = 0.0;
        }
        if (Config.DEBUG_PARTICLE) {
            ysmu.LOG.info("[YSMU-PARTICLE] id='{}' name='{}' abs={} offset=({},{},{}) delta=({},{},{}) speed={} count={} lifetime={}",
                id, particleName, isAbsolute, ox, oy, oz, dx, dy, dz, speed, count, lifetime);
        }
        if (count == 0) {
            double[] spawn = rotateOffset(entity, ox, oy, oz, isAbsolute);
            double x = entity.posX + spawn[0];
            // 1.7.10 玩家 posY = 脚底 + yOffset(1.62)（Entity.posY = boundingBox.minY + yOffset），
            // 而 OpenYSM(1.20.1) 的 entity.getY() = 脚底。直接用 posY 会让粒子系统性偏高约一个
            // 眼睛高度（rossi 火焰剑 / mingf 火把都偏高 ~1.6）。用 boundingBox.minY（脚底）与
            // OpenYSM 语义对齐。
            double y = entity.boundingBox.minY + spawn[1];
            double z = entity.posZ + spawn[2];
            final double vx = speed * dx;
            final double vy = speed * dy;
            final double vz = speed * dz;
            if (Config.DEBUG_PARTICLE) {
                float dbgYaw = entity instanceof EntityLivingBase
                    ? ((EntityLivingBase) entity).renderYawOffset : entity.rotationYaw;
                ysmu.LOG.info("[YSMU-PARTICLE] spawn {} -> world=({},{},{}) yaw={} entityPos=({},{},{})",
                    isAbsolute ? "abs" : "rel", x, y, z, dbgYaw, entity.posX, entity.posY, entity.posZ);
            }
            emit(mc, emitName, customTexId, x, y, z, vx, vy, vz, lifetime);
            return true;
        }
        Random random = entity.worldObj.rand;
        for (int i = 0; i < count; i++) {
            double spreadX = random.nextGaussian() * dx;
            double spreadY = random.nextGaussian() * dy;
            double spreadZ = random.nextGaussian() * dz;
            final double vx = random.nextGaussian() * speed;
            final double vy = random.nextGaussian() * speed;
            final double vz = random.nextGaussian() * speed;
            double[] spawn = rotateOffset(entity, ox + spreadX, oy + spreadY, oz + spreadZ, isAbsolute);
            final double x = entity.posX + spawn[0];
            // 同 count==0：1.7.10 玩家 posY 含 yOffset(1.62)，用 boundingBox.minY（脚底）对齐 OpenYSM。
            final double y = entity.boundingBox.minY + spawn[1];
            final double z = entity.posZ + spawn[2];
            emit(mc, emitName, customTexId, x, y, z, vx, vy, vz, lifetime);
        }
        return true;
    }

    /**
     * 生成单个粒子：命中自定义纹理则 {@code addEffect(CustomParticleFX)}（独立渲染层 3），
     * 否则走 1.7.10 vanilla {@code spawnParticle}（未知名静默跳过）。
     * 全部经 {@code func_152344_a} 提交到客户端主线程。
     */
    private static void emit(Minecraft mc, String particleName, int customTexId,
        double x, double y, double z, double vx, double vy, double vz, int lifetime) {
        if (customTexId >= 0) {
            mc.func_152344_a(() -> {
                if (mc.theWorld != null) {
                    // 尺寸/重力先用合理默认（后续可按高版本 particle JSON 的 behavior 解析细化）
                    CustomParticleManager.add(new CustomParticleFX(
                        mc.theWorld, x, y, z, vx, vy, vz, customTexId, 2.0F, lifetime, 0.6F));
                }
            });
        } else {
            mc.func_152344_a(() -> {
                if (mc.theWorld != null) {
                    mc.theWorld.spawnParticle(particleName, x, y, z, vx, vy, vz);
                }
            });
        }
    }

    /**
     * 相对模式：offset 按实体朝向绕 Y 轴旋转，与 OpenYSM 的 yBodyRot/yRot 语义一致。
     * 玩家用 {@code renderYawOffset}（身体偏航，与模型渲染一致），其他实体用
     * {@code rotationYaw}。绝对模式原样返回。
     */
    private static double[] rotateOffset(Entity entity, double ox, double oy, double oz, boolean isAbsolute) {
        if (isAbsolute) {
            return new double[] { ox, oy, oz };
        }
        float yaw = entity instanceof EntityLivingBase
            ? ((EntityLivingBase) entity).renderYawOffset
            : entity.rotationYaw;
        double a = Math.toRadians(-yaw);
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        double rx = ox * cos + oz * sin;
        double rz = -ox * sin + oz * cos;
        return new double[] { rx, oy, rz };
    }

    /** 1.7.10 的 {@code spawnParticle} 用裸粒子名（如 "flame"），去掉 "minecraft:" 前缀。 */
    private static String normalizeParticleId(String id) {
        String trimmed = id.trim();
        if (trimmed.startsWith("minecraft:")) {
            return trimmed.substring("minecraft:".length());
        }
        return trimmed;
    }
}
