package com.fox.ysmu.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SpawnParticleCommand;

/**
 * /particle 指令（1.7.10 原版没有，本模组补充，对齐官方语义）。
 *
 * <pre>
 *   /particle &lt;name&gt;                    （在自身位置生成单个粒子）
 *   /particle &lt;name&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;       （在指定位置生成单个粒子）
 *   /particle &lt;name&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;dx&gt; &lt;dy&gt; &lt;dz&gt; &lt;speed&gt; &lt;count&gt; [force|normal] [&lt;viewers&gt;]
 * </pre>
 *
 * <p>语义与官方 wiki 一致：count==0 时单粒子、速度 = delta×speed（方向向量）；
 * count&gt;0 时位置 = pos + 高斯×delta（σ）、速度 = 高斯×speed。坐标解析复用官方通用
 * {@code CommandBase.func_110666_a}（/tp、/summon、/playsound 同款，支持 {@code ~}
 * 相对坐标，纯整数绝对坐标自动 +0.5 居中到方块中心）；delta/speed 为纯数值。
 * [force|normal] 与 [&lt;viewers&gt;] 已接受但**暂静默忽略**（当前统一广播给所有在线
 * 玩家）。因粒子系统在客户端，服务端解析后广播 {@link SpawnParticleCommand} 包给
 * 所有在线玩家，客户端本地生成（含自定义高版本纹理粒子）。</p>
 */
public class ParticleCommand extends CommandBase {

    /** count 上限：防止刷屏（官方无上限，但 1.7.10 粒子系统/本模组有数量保护）。 */
    private static final int MAX_COUNT = 1000;

    @Override
    public String getCommandName() {
        return "particle";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/particle <name> [<x> <y> <z>] [<dx> <dy> <dz>] <speed> <count> [force|normal] [<viewers>]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        String name = args[0];
        // ~ 相对坐标基准：执行者位置（玩家）；非玩家且未显式给绝对坐标时按 0。
        double ox = 0.0D;
        double oy = 0.0D;
        double oz = 0.0D;
        if (sender instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) sender;
            ox = player.posX;
            oy = player.posY;
            oz = player.posZ;
        }
        double x = ox;
        double y = oy;
        double z = oz;
        double dx = 0.0D;
        double dy = 0.0D;
        double dz = 0.0D;
        double speed = 0.0D;
        int count = 0;
        if (args.length == 1) {
            // 未指定位置：以执行者为基准（必须为玩家）
            getCommandSenderAsPlayer(sender);
        } else if (args.length == 4) {
            x = func_110666_a(sender, ox, args[1]);
            y = func_110666_a(sender, oy, args[2]);
            z = func_110666_a(sender, oz, args[3]);
        } else if (args.length >= 9 && args.length <= 11) {
            // 前 9 个参数为 name/pos(3)/delta(3)/speed/count；末尾可选的
            // [force|normal] 与 [<viewers>]（第 10、11 个）已接受但暂静默忽略
            // （当前统一广播给所有在线玩家）。
            x = func_110666_a(sender, ox, args[1]);
            y = func_110666_a(sender, oy, args[2]);
            z = func_110666_a(sender, oz, args[3]);
            // delta/speed 是纯数值（不能用 func_110666_a：它会为纯整数自动 +0.5 居中）
            dx = parseDouble(sender, args[4]);
            dy = parseDouble(sender, args[5]);
            dz = parseDouble(sender, args[6]);
            speed = parseDouble(sender, args[7]);
            count = parseIntBounded(sender, args[8], 0, MAX_COUNT);
        } else {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        NetworkHandler.CHANNEL.sendToAll(new SpawnParticleCommand(
            name, x, y, z, dx, dy, dz, (float) speed, count));
    }
}
