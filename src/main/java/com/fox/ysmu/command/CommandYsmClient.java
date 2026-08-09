package com.fox.ysmu.command;

import java.util.List;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;

import com.fox.ysmu.client.animation.molang.MolangReset;
import com.fox.ysmu.client.sync.LocalModelLoader;
import com.fox.ysmu.util.ThreadTools;

/**
 * 客户端命令 /ysmclient：纯客户端本地操作，不走服务端。
 *
 * 子命令：
 * - {@code /ysmclient load}：把本地扫描到的模型（config/ysmu/custom + builtin）
 *   注册为仅自己可见的模型（原 /ysmlocal）。
 * - {@code /ysmclient reset}：重置自己的 Molang 变量状态（漫游变量/帧间物理/
 *   v.* 残留）回模型默认值，无需重载模型。
 *
 * 通过 Forge {@code ClientCommandHandler} 注册为纯客户端命令（GuiChat 先尝试
 * 客户端命令处理器，命中则不再发送到服务器）：
 * - 服务端未装 YSMU（纯净服）时也可用；
 * - 服务端装有 YSMU 时，本地独有的模型会追加为仅自己可见，与服务器同名的模型
 *   保持服务端版本（不覆盖，避免混淆）。
 *
 * 不使用 /ysm 子命令：若在客户端注册同名 "ysm" 命令，ClientCommandHandler 会优先
 * 拦截所有 /ysm 调用，导致 reload/play 等服务端子命令无法到达服务器。独立命令名
 * 保证与现有命令完全隔离。
 */
@SideOnly(Side.CLIENT)
public class CommandYsmClient extends CommandBase {

    @Override
    public String getCommandName() {
        return "ysmclient";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/ysmclient <load|reset>";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true; // 纯客户端命令，所有玩家可用
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "load", "reset");
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.addChatMessage(new ChatComponentText(
                "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r Usage: /ysmclient <load|reset>"));
            return;
        }
        if ("load".equalsIgnoreCase(args[0])) {
            processLoad(sender);
        } else if ("reset".equalsIgnoreCase(args[0])) {
            processReset(sender);
        } else {
            sender.addChatMessage(new ChatComponentText(
                "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r Unknown subcommand: " + args[0]
                    + ". Use: load | reset"));
        }
    }

    /** /ysmclient load — 注册本地模型（原 /ysmlocal）。 */
    private void processLoad(ICommandSender sender) {
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

    /** /ysmclient reset — 重置自己的 Molang 变量状态。 */
    private void processReset(ICommandSender sender) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            return;
        }
        MolangReset.resetSelf();
        mc.thePlayer.addChatMessage(new ChatComponentText(
            "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r Molang variables reset."));
    }
}
