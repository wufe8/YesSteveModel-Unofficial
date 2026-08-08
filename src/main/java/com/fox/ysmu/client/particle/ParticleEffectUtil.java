package com.fox.ysmu.client.particle;

import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

import com.fox.ysmu.Config;
import com.fox.ysmu.ysmu;
import com.fox.ysmu.util.RenderUtil;

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
            return false;
        }
        if (RenderUtil.RENDERING_IN_INVENTORY || RenderUtil.RENDERING_IN_PAPERDOLL) {
            // GUI 预览（背包/纸娃娃）里不往世界生成粒子
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || !mc.theWorld.isRemote) {
            return false;
        }
        String particleName = normalizeParticleId(id);
        if (count < 0) {
            count = 0;
        }
        if (count > MAX_BATCH_COUNT) {
            count = MAX_BATCH_COUNT;
        }
        if (lifetime < 1) {
            lifetime = 1;
        }
        if (Config.DEBUG_PARTICLE) {
            ysmu.LOG.info("[YSMU-PARTICLE] id='{}' name='{}' abs={} offset=({},{},{}) delta=({},{},{}) speed={} count={} lifetime={}",
                id, particleName, isAbsolute, ox, oy, oz, dx, dy, dz, speed, count, lifetime);
        }
        if (count == 0) {
            double[] spawn = rotateOffset(entity, ox, oy, oz, isAbsolute);
            double x = entity.posX + spawn[0];
            double y = entity.posY + spawn[1];
            double z = entity.posZ + spawn[2];
            final double vx = speed * dx;
            final double vy = speed * dy;
            final double vz = speed * dz;
            mc.func_152344_a(() -> {
                if (mc.theWorld != null) {
                    mc.theWorld.spawnParticle(particleName, x, y, z, vx, vy, vz);
                }
            });
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
            final double y = entity.posY + spawn[1];
            final double z = entity.posZ + spawn[2];
            mc.func_152344_a(() -> {
                if (mc.theWorld != null) {
                    mc.theWorld.spawnParticle(particleName, x, y, z, vx, vy, vz);
                }
            });
        }
        return true;
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
