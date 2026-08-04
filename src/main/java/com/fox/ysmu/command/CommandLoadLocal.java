package com.fox.ysmu.command;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentTranslation;

import com.fox.ysmu.client.sync.LocalModelLoader;
import com.fox.ysmu.util.ThreadTools;

/**
 * 客户端命令 /ysmlocal：把本地扫描到的模型（config/ysmu/custom + builtin）注册为
 * 仅自己可见的模型。
 *
 * 通过 Forge {@code ClientCommandHandler} 注册为纯客户端命令（GuiChat 先尝试客户端
 * 命令处理器，命中则不再发送到服务器）：
 * - 服务端未装 YSMU（纯净服）时也可用；
 * - 服务端装有 YSMU 时，本地独有的模型会追加为仅自己可见，与服务器同名的模型
 *   保持服务端版本（不覆盖，避免混淆）。
 *
 * 不使用 /ysm 子命令：若在客户端注册同名 "ysm" 命令，ClientCommandHandler 会优先
 * 拦截所有 /ysm 调用，导致 reload/play 等服务端子命令无法到达服务器。独立命令名
 * 保证与现有命令完全隔离。
 */
@SideOnly(Side.CLIENT)
public class CommandLoadLocal extends CommandBase {

    @Override
    public String getCommandName() {
        return "ysmlocal";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/ysmlocal";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true; // 纯客户端命令，所有玩家可用
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.loadlocal.started"));
        // 解析/注册较重，放到后台线程；完成后回主线程报告。
        ThreadTools.THREAD_POOL.submit(() -> {
            int[] result = LocalModelLoader.registerLocalModels();
            int registered = result[0], skipped = result[1], failed = result[2];
            Minecraft mc = Minecraft.getMinecraft();
            mc.func_152344_a(() -> {
                if (mc.thePlayer == null) {
                    return;
                }
                if (registered == 0 && skipped == 0 && failed == 0) {
                    mc.thePlayer.addChatMessage(
                        new ChatComponentTranslation("commands.yes_steve_model.loadlocal.none"));
                } else {
                    mc.thePlayer.addChatMessage(new ChatComponentTranslation(
                        "commands.yes_steve_model.loadlocal.complete", registered, skipped, failed));
                }
            });
        });
    }
}
