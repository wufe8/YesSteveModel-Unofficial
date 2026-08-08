package com.fox.ysmu.client.particle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;

import org.lwjgl.opengl.GL11;

/**
 * 自定义粒子管理器（独立于 vanilla {@code EffectRenderer.fxLayers}）。
 *
 * <p>1.7.10 的 {@code EffectRenderer} 只渲染 layer 0/1/2，layer 3 不渲染；
 * 且其内部 {@code fxLayers} 字段在 SRG 环境下 mixin {@code @Shadow} 无法可靠
 * 映射（refmap 只生成方法映射）。因此自定义粒子不进 {@code EffectRenderer}，
 * 而是由本类维护独立列表：</p>
 * <ul>
 *   <li>{@link #add}：生成时加入（ParticleEffectUtil 调用，客户端主线程）</li>
 *   <li>{@link #tick}：每客户端 tick 更新（CommonEventHandler 的 ClientTickEvent）</li>
 *   <li>{@link #render}：由 {@code MixinEffectRenderer} 在 {@code renderParticles}
 *       尾部调用，按纹理分组批量渲染（vanilla 同款混合/亮度设置）</li>
 * </ul>
 */
public final class CustomParticleManager {

    /** 上限：防止模型包误用刷屏（vanilla 单层也是 4000）。 */
    private static final int MAX_PARTICLES = 1024;

    private static final List<CustomParticleFX> PARTICLES = new ArrayList<>();

    private CustomParticleManager() {}

    public static void add(CustomParticleFX fx) {
        if (fx == null) {
            return;
        }
        if (PARTICLES.size() >= MAX_PARTICLES) {
            PARTICLES.remove(0);
        }
        PARTICLES.add(fx);
    }

    /** 每客户端 tick 更新粒子（先于渲染）。 */
    public static void tick() {
        Iterator<CustomParticleFX> it = PARTICLES.iterator();
        while (it.hasNext()) {
            CustomParticleFX fx = it.next();
            if (fx == null || fx.isDead) {
                it.remove();
                continue;
            }
            fx.onUpdate();
            if (fx.isDead) {
                it.remove();
            }
        }
    }

    /** 世界卸载时清空（避免跨世界残留）。 */
    public static void clear() {
        PARTICLES.clear();
    }

    /**
     * 渲染所有自定义粒子：按纹理分组，每组绑定纹理后批量画 billboard。
     * 由 MixinEffectRenderer 在 vanilla renderParticles 尾部调用（此时 GL 状态可用）。
     */
    public static void render(Entity viewer, float partialTicks) {
        if (PARTICLES.isEmpty()) {
            return;
        }
        // 与 vanilla renderParticles 相同的插值基准（粒子 renderParticle 用静态 interpPos）
        EntityFX.interpPosX = viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * (double) partialTicks;
        EntityFX.interpPosY = viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * (double) partialTicks;
        EntityFX.interpPosZ = viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * (double) partialTicks;

        // 按纹理分组：同一纹理一个 batch，避免 batch 内混绑纹理。
        Map<Integer, List<CustomParticleFX>> groups = new HashMap<>();
        for (CustomParticleFX fx : PARTICLES) {
            if (fx == null || fx.isDead) {
                continue;
            }
            int tid = fx.getCustomTextureId();
            if (tid < 0) {
                continue;
            }
            List<CustomParticleFX> list = groups.get(tid);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(tid, list);
            }
            list.add(fx);
        }
        if (groups.isEmpty()) {
            return;
        }

        float rotationX = ActiveRenderInfo.rotationX;
        float rotationZ = ActiveRenderInfo.rotationZ;
        float rotationYZ = ActiveRenderInfo.rotationYZ;
        float rotationXY = ActiveRenderInfo.rotationXY;
        float rotationXZ = ActiveRenderInfo.rotationXZ;
        Tessellator tessellator = Tessellator.instance;
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.003921569F);
        for (Map.Entry<Integer, List<CustomParticleFX>> entry : groups.entrySet()) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, entry.getKey().intValue());
            tessellator.startDrawingQuads();
            for (CustomParticleFX fx : entry.getValue()) {
                tessellator.setBrightness(fx.getBrightnessForRender(partialTicks));
                // 与 vanilla EffectRenderer 相同的传参顺序：rotX, rotXZ, rotZ, rotYZ, rotXY
                fx.renderParticle(tessellator, partialTicks,
                    rotationX, rotationXZ, rotationZ, rotationYZ, rotationXY);
            }
            tessellator.draw();
        }
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDepthMask(true);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
    }
}
