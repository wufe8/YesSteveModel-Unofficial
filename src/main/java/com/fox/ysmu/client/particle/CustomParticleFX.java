package com.fox.ysmu.client.particle;

import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.world.World;

/**
 * 自定义纹理粒子（独立渲染层 3）。
 *
 * <p>1.7.10 的 {@link EntityFX} 默认从内置 {@code /particles/particles.png} atlas
 * 取纹理（layer 0），无法直接绑定任意外部 PNG。本类把自定义纹理的 GL texture id
 * 直接交给渲染层（见 {@code MixinEffectRenderer}），并在
 * {@link #renderParticle} 里用自定义 UV（默认整张图 0..1）画 billboard。</p>
 *
 * <p>行为默认沿用 {@link EntityFX} 的物理（重力 {@code particleGravity}、速度衰减、
 * 寿命 {@code particleMaxAge}）；后续可按高版本 particle JSON 的 behavior 细化。</p>
 */
public class CustomParticleFX extends EntityFX {

    /** 外部加载的自定义纹理 GL id（渲染层绑定）。 */
    private final int glTextureId;
    private final float uMin;
    private final float vMin;
    private final float uMax;
    private final float vMax;

    public CustomParticleFX(World world, double x, double y, double z,
            double vx, double vy, double vz, int glTextureId,
            float scale, int maxAge, float gravity) {
        super(world, x, y, z);
        this.glTextureId = glTextureId;
        this.motionX = vx;
        this.motionY = vy;
        this.motionZ = vz;
        this.particleScale = scale;
        this.particleMaxAge = Math.max(maxAge, 1);
        this.particleGravity = gravity;
        this.uMin = 0.0F;
        this.vMin = 0.0F;
        this.uMax = 1.0F;
        this.vMax = 1.0F;
    }

    /** 渲染层 3：独立于 vanilla 的 layer 0/1/2，由 MixinEffectRenderer 额外渲染。 */
    @Override
    public int getFXLayer() {
        return 3;
    }

    /** 渲染层绑定该纹理。 */
    public int getCustomTextureId() {
        return glTextureId;
    }

    @Override
    public void renderParticle(Tessellator tess, float partialTicks,
            float rotationX, float rotationZ, float rotationYZ,
            float rotationXY, float rotationXZ) {
        float size = 0.1F * this.particleScale;
        float posX = (float) (this.prevPosX + (this.posX - this.prevPosX) * (double) partialTicks - interpPosX);
        float posY = (float) (this.prevPosY + (this.posY - this.prevPosY) * (double) partialTicks - interpPosY);
        float posZ = (float) (this.prevPosZ + (this.posZ - this.prevPosZ) * (double) partialTicks - interpPosZ);
        tess.setColorRGBA_F(this.particleRed, this.particleGreen, this.particleBlue, this.particleAlpha);
        tess.addVertexWithUV(
            (double) (posX - rotationX * size - rotationXY * size),
            (double) (posY - rotationZ * size),
            (double) (posZ - rotationYZ * size - rotationXZ * size),
            (double) this.uMax, (double) this.vMax);
        tess.addVertexWithUV(
            (double) (posX - rotationX * size + rotationXY * size),
            (double) (posY + rotationZ * size),
            (double) (posZ - rotationYZ * size + rotationXZ * size),
            (double) this.uMax, (double) this.vMin);
        tess.addVertexWithUV(
            (double) (posX + rotationX * size + rotationXY * size),
            (double) (posY + rotationZ * size),
            (double) (posZ + rotationYZ * size + rotationXZ * size),
            (double) this.uMin, (double) this.vMin);
        tess.addVertexWithUV(
            (double) (posX + rotationX * size - rotationXY * size),
            (double) (posY - rotationZ * size),
            (double) (posZ + rotationYZ * size - rotationXZ * size),
            (double) this.uMin, (double) this.vMax);
    }
}
