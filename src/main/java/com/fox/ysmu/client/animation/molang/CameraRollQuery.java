package com.fox.ysmu.client.animation.molang;

import net.minecraft.client.Minecraft;

/**
 * {@code query.head_z_rotation} 的数据源：**相机 roll**。
 *
 * <p><b>为什么是相机：</b>1.7.10 完全没有实体 roll —— {@code Entity} 只有 {@code rotationYaw}（Y）与
 * {@code rotationPitch}（X），{@code EntityLivingBase} 再加 {@code renderYawOffset}/{@code rotationYawHead}
 * 两个偏航量；渲染模型虽然留了 {@code bipedHead.rotateAngleZ} 槽位，但整个 {@code net/minecraft/client}
 * 里没有任何地方给它赋值。唯一的 Z 旋转是相机的 {@link net.minecraft.client.renderer.EntityRenderer#camRoll}
 * （public 字段，渲染时作为 Z 轴旋转应用）。对 Minecraft 而言玩家头部朝向与镜头朝向一致（yaw/pitch
 * 就是同一份数据），因此滚转方向也取镜头 —— 这正是"自由视角 / 更好的相机"这类会 roll 镜头的 mod
 * 需要的量，作者用 {@code -query.head_z_rotation} 抵消镜头倾斜时它才有效。</p>
 *
 * <p><b>为什么默认安全：</b>原版**从不写** {@code camRoll}（反编译源码里只有「声明 / 存 prev / 应用」
 * 三处），所以无相机 mod 时这里恒为 0 —— 与官方 YSM（没有这个查询、恒 0）行为完全一致；只有真的
 * 有 mod 去 roll 镜头时它才非 0。</p>
 *
 * <p><b>已知差异</b>：这次只用本机玩家的镜头值（远程玩家的头部 roll 没有任何同步字段，
 * 用本地镜头会算错），所以远程玩家模型上该查询恒 0。</p>
 */
public final class CameraRollQuery {

    private CameraRollQuery() {}

    /**
     * 本帧相机 roll（角度），与 {@code EntityRenderer} 渲染时用的插值完全一致：
     * {@code prevCamRoll + (camRoll - prevCamRoll) * renderPartialTicks}。
     */
    public static float interpolatedRoll() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.entityRenderer == null) {
            return 0.0F;
        }
        float partialTicks = mc.timer == null ? 1.0F : mc.timer.renderPartialTicks;
        return interpolate(mc.entityRenderer.prevCamRoll, mc.entityRenderer.camRoll, partialTicks);
    }

    /** 纯插值（便于单测）：{@code partialTicks} 会被夹到 [0,1]。 */
    static float interpolate(float previous, float current, float partialTicks) {
        float t = partialTicks < 0.0F ? 0.0F : (partialTicks > 1.0F ? 1.0F : partialTicks);
        return previous + (current - previous) * t;
    }

    /** 只有本机玩家能用镜头 roll（远程玩家的头部 roll 无法同步）。 */
    public static boolean isLocalPlayer(net.minecraft.entity.player.EntityPlayer player) {
        return player != null && player == Minecraft.getMinecraft().thePlayer;
    }
}
