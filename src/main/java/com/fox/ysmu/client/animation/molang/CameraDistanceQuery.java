package com.fox.ysmu.client.animation.molang;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

/**
 * {@code query.distance_from_camera} —— **相机**到实体的距离（格）。
 *
 * <p>语义按参考实现（{@code OpenYSM/.../molang/builtin/QueryBinding.java}）：
 * {@code gameRenderer.getMainCamera().getPosition().distanceTo(entity.position())}，
 * 也就是相机**真实位置**到实体原点的距离。1.20.1 的 {@code Camera.getPosition()} 在第三人称下
 * 位于玩家身后（还要再经方块裁剪），所以默认视距 4 格时这个值约 4.3，只有把镜头挤到贴脸才会掉到
 * 1.x —— 模型正是用这个量做"镜头凑近才显示某某部件"的效果。</p>
 *
 * <p><b>原来的实现是 {@code mc.renderViewEntity.getDistanceToEntity(player)}，对本机玩家恒等于 0</b>：
 * {@code renderViewEntity} 就是玩家自己，第三人称的镜头偏移只存在于
 * {@code EntityRenderer.orientCamera()} 的 GL 变换里，从不写回任何实体。于是所有"按相机距离做条件"
 * 的模型在第三人称下都会走"镜头贴在脸上"的分支。实测症状：一个用
 * {@code !q.is_first_person && q.distance_from_camera < 1.75} 实现"镜头贴近才脸红"的模型，
 * 第三人称下永远显示害羞的脸红部件（用户报的遗留 bug），而第一人称因为
 * {@code !q.is_first_person} 为假所以看着正常。</p>
 *
 * <p>远程玩家／其它实体不受这条修复影响：它们的相机实体不是自己，
 * {@code getDistanceToEntity} 本来就是正确的相机到实体距离，保持原样。</p>
 *
 * <p><b>已知差距</b>：原版 {@code orientCamera()} 会用 8 条射线按"身后有没有方块"把第三人称距离
 * 缩短，而缩短后的值在 1.7.10 里只是局部变量、不写回字段。这里用的是字段
 * {@code thirdPersonDistance}（未缩短值），所以"背贴墙把镜头挤进来"那种情形会报得偏大 ——
 * 模型看不到"镜头极近"分支。要补齐需要在 {@code orientCamera} 里 Mixin 捕获 d7。</p>
 */
public final class CameraDistanceQuery {

    /** 眼睛相对实体原点的高度（原版 {@code orientCamera} 里的 1.62）。 */
    private static final double EYE_HEIGHT = 1.62D;

    private CameraDistanceQuery() {}

    /** 相机到该玩家／实体的距离（格）。 */
    public static double forPlayer(EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc.renderViewEntity;
        if (view == null) {
            return 0.0D;
        }
        if (player != mc.thePlayer) {
            // 远程玩家：相机实体不是它自己，这个距离本来就是对的。
            return view.getDistanceToEntity(player);
        }
        float partialTicks = mc.timer == null ? 1.0F : mc.timer.renderPartialTicks;
        int thirdPersonView = mc.gameSettings.thirdPersonView;
        double back = 0.0D;
        EntityRenderer renderer = mc.entityRenderer;
        if (thirdPersonView > 0 && renderer != null) {
            back = renderer.thirdPersonDistanceTemp
                + (renderer.thirdPersonDistance - renderer.thirdPersonDistanceTemp) * partialTicks;
        }
        double eyeX = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
        double eyeY = player.prevPosY + (player.posY - player.prevPosY) * partialTicks
            - (player.yOffset - EYE_HEIGHT);
        double eyeZ = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;
        return distance(eyeX, eyeY, eyeZ,
            player.posX, player.posY, player.posZ,
            back, player.rotationYaw, player.rotationPitch, thirdPersonView);
    }

    /** 纯计算部分（拆出来便于单测）：先按原版 {@code orientCamera} 求相机位置，再取到实体原点的距离。
     *
     *  @param eyeX/eyeY/eyeZ 插值后的眼睛位置
     *  @param entityX/Y/Z    实体原点（玩家在脚下，与 {@code getDistanceToEntity} 同一口径）
     *  @param back           第三人称镜头后退距离（第一人称传 0）
     *  @param thirdPersonView 0=第一人称，1=背后，2=面前 */
    static double distance(double eyeX, double eyeY, double eyeZ,
                           double entityX, double entityY, double entityZ,
                           double back, float yaw, float pitch, int thirdPersonView) {
        if (thirdPersonView > 0 && back > 0.0D) {
            float f2 = pitch;
            if (thirdPersonView == 2) {
                // 原版：前视（F5 两次）把 pitch 翻 180°，镜头落到身前。
                f2 += 180.0F;
            }
            double d3 = -MathHelper.sin(yaw / 180.0F * (float) Math.PI)
                * MathHelper.cos(f2 / 180.0F * (float) Math.PI) * back;
            double d4 = MathHelper.cos(yaw / 180.0F * (float) Math.PI)
                * MathHelper.cos(f2 / 180.0F * (float) Math.PI) * back;
            double d5 = -MathHelper.sin(f2 / 180.0F * (float) Math.PI) * back;
            eyeX -= d3;
            eyeY -= d5;
            eyeZ -= d4;
        }
        double dx = eyeX - entityX;
        double dy = eyeY - entityY;
        double dz = eyeZ - entityZ;
        return MathHelper.sqrt_double(dx * dx + dy * dy + dz * dz);
    }
}
