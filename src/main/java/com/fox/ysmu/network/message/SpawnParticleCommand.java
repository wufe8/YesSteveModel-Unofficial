package com.fox.ysmu.network.message;

import java.nio.charset.StandardCharsets;

import net.minecraft.client.Minecraft;

import com.fox.ysmu.client.particle.ParticleEffectUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * 服务端→客户端：执行 /particle 指令，在客户端世界坐标生成粒子。
 *
 * <p>粒子是纯客户端本地系统（CustomParticleManager + 高版本纹理），服务端无法直接
 * 生成，所以服务端解析 /particle 指令后通过本包广播，客户端在收到后回到主线程调用
 * {@link ParticleEffectUtil#spawnAt}（绝对坐标入口，复用行为表/自定义纹理/fallback
 * 全链路）。</p>
 */
public class SpawnParticleCommand implements IMessage {

    private String name;
    private double x;
    private double y;
    private double z;
    private double dx;
    private double dy;
    private double dz;
    private float speed;
    private int count;

    public SpawnParticleCommand() {}

    public SpawnParticleCommand(String name, double x, double y, double z,
            double dx, double dy, double dz, float speed, int count) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.speed = speed;
        this.count = count;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int len = buf.readInt();
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        this.name = new String(bytes, StandardCharsets.UTF_8);
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.dx = buf.readDouble();
        this.dy = buf.readDouble();
        this.dz = buf.readDouble();
        this.speed = buf.readFloat();
        this.count = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeDouble(dx);
        buf.writeDouble(dy);
        buf.writeDouble(dz);
        buf.writeFloat(speed);
        buf.writeInt(count);
    }

    public static class Handler implements IMessageHandler<SpawnParticleCommand, IMessage> {

        @Override
        public IMessage onMessage(SpawnParticleCommand message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT) {
                // 回主线程再生成粒子（ParticleEffectUtil/纹理加载需 GL 上下文）
                Minecraft.getMinecraft().func_152344_a(new Runnable() {
                    @Override
                    public void run() {
                        Minecraft mc = Minecraft.getMinecraft();
                        if (mc.theWorld != null) {
                            ParticleEffectUtil.spawnAt(mc.theWorld, message.name,
                                message.x, message.y, message.z,
                                message.dx, message.dy, message.dz,
                                message.speed, message.count, 20);
                        }
                    }
                });
            }
            return null;
        }
    }
}
