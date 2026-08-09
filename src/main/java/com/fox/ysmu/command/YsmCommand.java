package com.fox.ysmu.command;

import static com.fox.ysmu.compat.Utils.isValidResourceLocation;
import static com.fox.ysmu.model.ServerModelManager.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;

import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.model.ServerModelManager;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.network.message.SyncGamePath;
import com.fox.ysmu.network.sync.OpenYsmModelSyncServer;

public class YsmCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "ysm";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/ysm <reload|play|playsound|setgamepath|buffer|welcome|debug|reset|sync|help> [args] (type /ysm help for details)";
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args,
                "reload", "play", "playsound", "setgamepath", "buffer", "welcome", "debug", "reset", "sync", "help");
        }
        if (args.length == 2) {
            if ("welcome".equalsIgnoreCase(args[0])) {
                return getListOfStringsMatchingLastWord(args, "on", "off");
            }
            if ("debug".equalsIgnoreCase(args[0])) {
                return getListOfStringsMatchingLastWord(args, "query", "overlay", "eval");
            }
            if ("reset".equalsIgnoreCase(args[0])) {
                return getListOfStringsMatchingLastWord(args,
                    net.minecraft.server.MinecraftServer.getServer()
                        .getConfigurationManager().getAllUsernames());
            }
        }
        if (args.length == 2 && "setgamepath".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "<path>");
        }
        if (args.length == 3) {
            if ("setgamepath".equalsIgnoreCase(args[0])) {
                return getListOfStringsMatchingLastWord(args, "[version]");
            }
            if ("debug".equalsIgnoreCase(args[0]) && "overlay".equalsIgnoreCase(args[1])) {
                return getListOfStringsMatchingLastWord(args, "on", "off", "toggle");
            }
        }
        return null;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    /**
     * Allow setgamepath subcommand for non-OP players (client-side config only).
     * Other subcommands still require the default permission level.
     */
    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        // If the sender has the default permission, allow everything
        if (super.canCommandSenderUseCommand(sender)) return true;
        // Otherwise, only allow setgamepath (client-side only, no server impact)
        if (sender instanceof net.minecraft.command.ICommandSender) {
            // We can't check args here easily, but we'll handle permission
            // denial gracefully in processCommand for non-setgamepath subcommands.
            return true; // allow all to reach processCommand; denied there
        }
        return false;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        // help 对所有玩家可用，在权限检查之前处理。
        if ("help".equalsIgnoreCase(args[0])) {
            processHelp(sender, args);
            return;
        }
        // setgamepath, welcome, and buffer are allowed for all players (client-side only).
        // Other subcommands (play, reload, debug) require the default permission level.
        // TODO: add @p/@a/@r target selector support for debug and play subcommands.
        if (!"setgamepath".equalsIgnoreCase(args[0]) && !"welcome".equalsIgnoreCase(args[0])
            && !"buffer".equalsIgnoreCase(args[0]) && !"sync".equalsIgnoreCase(args[0])
            && !super.canCommandSenderUseCommand(sender)) {
            throw new CommandException("commands.generic.permission");
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            processReload(sender);
        } else if ("play".equalsIgnoreCase(args[0])) {
            processPlay(sender, args);
        } else if ("playsound".equalsIgnoreCase(args[0])) {
            processPlaySound(sender, args);
        } else if ("setgamepath".equalsIgnoreCase(args[0])) {
            processSetGamePath(sender, args);
        } else if ("buffer".equalsIgnoreCase(args[0])) {
            processBuffer(sender);
        } else if ("sync".equalsIgnoreCase(args[0])) {
            processSync(sender);
        } else if ("welcome".equalsIgnoreCase(args[0])) {
            processWelcome(sender, args);
        } else if ("debug".equalsIgnoreCase(args[0])) {
            processDebug(sender, args);
        } else if ("reset".equalsIgnoreCase(args[0])) {
            processReset(sender, args);
        } else {
            throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    private void processReload(ICommandSender sender) {
        StopWatch watch = new StopWatch();
        watch.start();
        com.fox.ysmu.ysmu.LOG.info("YSM reload command started by {}", sender.getCommandSenderName());
        int skipped = checkModelFiles(sender, CUSTOM);
        ServerModelManager.reloadPacks();
        ServerModelManager.sendRequestSyncModelMessage(sender.getEntityWorld().playerEntities);
        watch.stop();
        com.fox.ysmu.ysmu.LOG.info("YSM reload command finished in {}ms, players notified: {}",
            watch.getTime(), sender.getEntityWorld().playerEntities.size());
        sender.addChatMessage(new ChatComponentTranslation(
            "message.yes_steve_model.model.reload.info", watch.getTime(), skipped));
    }

    /** 游戏内指令帮助：/ysm help [subcommand]。无参数列出全部子命令，带参数显示详情。
     *  服务端命令只能用翻译键输出（服务端无法判断客户端语言）。 */
    private void processHelp(ICommandSender sender, String[] args) {
        if (args.length >= 2) {
            String sub = args[1].toLowerCase(java.util.Locale.ROOT);
            boolean found = "reload".equals(sub) || "play".equals(sub) || "playsound".equals(sub)
                || "buffer".equals(sub) || "setgamepath".equals(sub) || "welcome".equals(sub)
                || "sync".equals(sub) || "debug".equals(sub) || "reset".equals(sub)
                || "ysmclient".equals(sub);
            if (!found) {
                sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.unknown", sub));
                return;
            }
            sender.addChatMessage(new ChatComponentTranslation(
                "commands.yes_steve_model.help.detail.title", sub));
            sender.addChatMessage(new ChatComponentTranslation(
                "commands.yes_steve_model.help.detail." + sub + ".usage"));
            sender.addChatMessage(new ChatComponentTranslation(
                "commands.yes_steve_model.help.detail." + sub + ".desc"));
            return;
        }
        sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.title"));
        sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.line.reload"));
        sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.line.play"));
        sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.line.playsound"));
        sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.line.buffer"));
        sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.line.setgamepath"));
        sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.line.welcome"));
        sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.line.sync"));
        sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.line.reset"));
        sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.line.debug"));
        sender.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.help.line.ysmclient"));
        // 关闭进服 welcome 引导的提示：整行可点击，执行 /ysm welcome off。
        net.minecraft.util.ChatComponentTranslation off = new net.minecraft.util.ChatComponentTranslation(
            "commands.yes_steve_model.help.welcome_off");
        off.getChatStyle().setChatClickEvent(new net.minecraft.event.ClickEvent(
            net.minecraft.event.ClickEvent.Action.RUN_COMMAND, "/ysm welcome off"));
        sender.addChatMessage(off);
    }

    private void processPlay(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("commands.generic.player.notFound");
        }
        // 合并剩余参数，支持带空格的动画名；剔除可能包含的引号
        String animName = "";
        if (args.length >= 2) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(args[i]);
            }
            animName = sb.toString().replaceAll("[\"']", "");
        }
        if (StringUtils.isBlank(animName)) {
            sender.addChatMessage(new ChatComponentTranslation("message.yes_steve_model.model.animation_roulette.play", "?"));
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        if (eep == null) {
            player.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r Cannot play animation: player data not found."));
            return;
        }
        // Check if the animation exists in the current model's animation file
        net.minecraft.util.ResourceLocation modelId = eep.getModelId();
        if (modelId != null) {
            net.minecraft.util.ResourceLocation mainId = com.fox.ysmu.util.ModelIdUtil.getMainId(modelId);
            software.bernie.geckolib3.file.AnimationFile animFile = software.bernie.geckolib3.resource.GeckoLibCache
                .getInstance().getAnimations().get(mainId);
            if (animFile == null || !animFile.animations.containsKey(animName)) {
                player.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r Animation §e" + animName + "§r not found in current model."));
                return;
            }
        }
        eep.playAnimation(animName);
        player.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r Play: " + animName));
    }

    private void processPlaySound(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("commands.generic.player.notFound");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        // Merge remainder args into a single sound name
        String soundName = "";
        if (args.length >= 2) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(args[i]);
            }
            soundName = sb.toString().replaceAll("[\"']", "");
        }
        if (StringUtils.isBlank(soundName)) {
            // List all cached sounds
            Map<String, byte[]> sounds = com.fox.ysmu.client.audio.YSMSoundManager.getSoundFiles();
            if (sounds.isEmpty()) {
                player.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r No cached sounds."));
            } else {
                player.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r Cached sounds (in-memory):"));
                for (Map.Entry<String, byte[]> e : sounds.entrySet()) {
                    player.addChatMessage(new ChatComponentText("  §e" + e.getKey() + "§r → §7" + e.getValue().length + " bytes"));
                }
                player.addChatMessage(new ChatComponentText("§6Use §e/ysm playsound <name>§6 to play one."));
            }
            return;
        }
        // Try exact match first, then case-insensitive partial match
        com.fox.ysmu.client.audio.YSMSoundManager.playSound(player, soundName, 1.0f, 1.0f);
        player.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r Playing sound: " + soundName));
    }

    private void processSetGamePath(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("commands.generic.player.notFound");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;

        if (args.length < 2) {
            // Show current configuration
            String currentPath = com.fox.ysmu.Config.HIGH_VERSION_GAME_PATH;
            String currentVer = com.fox.ysmu.Config.HIGH_VERSION_ASSET_VERSION;
            if (currentPath.isEmpty()) {
                player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.setgamepath.none"));
                player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.setgamepath.usage"));
                player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.setgamepath.example"));
            } else {
                player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.setgamepath.current", currentPath));
                player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.setgamepath.version", currentVer));
                player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.setgamepath.usage"));
                player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.setgamepath.example"));
            }
            return;
        }

        String newPath = args[1];
        String newVersion = args.length >= 3 ? args[2] : com.fox.ysmu.Config.HIGH_VERSION_ASSET_VERSION;

        // Allow clearing the path with an empty string
        if ("\"\"".equals(newPath) || "''".equals(newPath)) {
            newPath = "";
        }

        // Send the updated config to the client via network packet
        NetworkHandler.CHANNEL.sendTo(new SyncGamePath(newPath, newVersion), player);

        if (newPath.isEmpty()) {
            player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.setgamepath.cleared"));
        } else {
            player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.setgamepath.set", newPath));
            player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.setgamepath.version", newVersion));
            player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.setgamepath.local_notice"));
        }
    }

    private void processBuffer(ICommandSender sender) {
        if (!(sender instanceof net.minecraft.entity.player.EntityPlayerMP)) {
            throw new CommandException("commands.generic.player.notFound");
        }
        net.minecraft.entity.player.EntityPlayerMP player = (net.minecraft.entity.player.EntityPlayerMP) sender;
        // Send a packet to the client to read MBeans and display buffer info
        com.fox.ysmu.network.NetworkHandler.CHANNEL.sendTo(new com.fox.ysmu.network.message.ShowBufferInfo(), player);
        player.addChatMessage(new net.minecraft.util.ChatComponentText(
            "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r Querying buffer info..."));
    }

    /**
     * 玩家主动重新同步（/ysm sync）：请求服务端重新发起 OpenYSM 同步。
     * 非 OP 可用，但受 RESYNC_COOLDOWN_SECONDS 限频（防高频刷上行带宽）。
     */
    private void processSync(ICommandSender sender) {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("commands.generic.player.notFound");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        int result = OpenYsmModelSyncServer.requestResync(player);
        if (result == OpenYsmModelSyncServer.RESYNC_OK) {
            player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.sync.resync.started"));
        } else if (result == OpenYsmModelSyncServer.RESYNC_COOLDOWN) {
            long remaining = OpenYsmModelSyncServer.resyncRemainingSeconds(player.getUniqueID());
            player.addChatMessage(new ChatComponentTranslation(
                "commands.yes_steve_model.sync.resync.cooldown", remaining));
        } else if (result == OpenYsmModelSyncServer.RESYNC_DISABLED) {
            player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.sync.resync.disabled"));
        } else {
            player.addChatMessage(new ChatComponentTranslation("commands.yes_steve_model.sync.resync.failed"));
        }
    }

    private void processWelcome(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("commands.generic.player.notFound");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        boolean newValue = true; // default: on
        if (args.length >= 2) {
            if ("off".equalsIgnoreCase(args[1]) || "0".equalsIgnoreCase(args[1]) || "false".equalsIgnoreCase(args[1])) {
                newValue = false;
            }
        }
        NetworkHandler.CHANNEL.sendTo(new com.fox.ysmu.network.message.SetWelcomeConfig(newValue), player);
        String key = newValue ? "commands.yes_steve_model.welcome.on" : "commands.yes_steve_model.welcome.off";
        player.addChatMessage(new ChatComponentTranslation(key));
    }

    private void processDebug(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("commands.generic.player.notFound");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        // debug 子命令整体要求权限等级 4（单机主机玩家必定可用；服务器上
        // 需要明确授予等级 4 的 OP，默认 op-permission-level=2 的服被挡住）。
        // debug 会查询/求值 Molang（eval 可调用任意 ysm 函数，含副作用函数），
        // 风险面大于普通子命令，故不沿用命令默认的等级 2。
        // 1.7.10 的 EntityPlayerMP.canCommandSenderUseCommand 为双参签名
        // (permissionLevel, commandName)，与 CommandBase 内部调用方式一致。
        if (!player.canCommandSenderUseCommand(4, getCommandName())) {
            throw new CommandException("commands.generic.permission");
        }
        if (args.length < 2) {
            player.addChatMessage(new ChatComponentText(
                "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r Usage: /ysm debug <query|overlay|eval>"));
            return;
        }
        String sub = args[1].toLowerCase(java.util.Locale.ROOT);
        if ("query".equals(sub)) {
            if (args.length < 3) {
                player.addChatMessage(new ChatComponentText(
                    "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r Usage: /ysm debug query <variable> [variable2 ...]"));
                return;
            }
            java.util.ArrayList<String> varNames = new java.util.ArrayList<>();
            for (int i = 2; i < args.length; i++) {
                String name = args[i];
                if (name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                }
                varNames.add(name);
            }
            NetworkHandler.CHANNEL.sendTo(
                new com.fox.ysmu.network.message.PacketQueryMolangVar(varNames), player);
        } else if ("eval".equals(sub)) {
            // 权限等级 4 已在 processDebug 入口统一检查（eval 可调用任意
            // ysm 函数，含 particle/play_sound 等副作用函数，风险面最大）。
            if (args.length < 3) {
                player.addChatMessage(new ChatComponentText(
                    "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r Usage: /ysm debug eval <expression>"
                        + "  (e.g. math.floor(3.7) or math.clamp(5,0,2)+1)"));
                return;
            }
            // 合并剩余参数为完整表达式（支持带空格的参数）
            StringBuilder expr = new StringBuilder();
            for (int i = 2; i < args.length; i++) {
                if (expr.length() > 0) expr.append(" ");
                expr.append(args[i]);
            }
            String expression = expr.toString().trim();
            if (expression.startsWith("\"") && expression.endsWith("\"")) {
                expression = expression.substring(1, expression.length() - 1);
            }
            NetworkHandler.CHANNEL.sendTo(
                new com.fox.ysmu.network.message.PacketEvalMolang(expression), player);
        } else if ("overlay".equals(sub)) {
            boolean wasActive = com.fox.ysmu.client.gui.debug.DebugOverlay.isActive();
            if (args.length >= 3 && "off".equalsIgnoreCase(args[2])) {
                if (wasActive) com.fox.ysmu.client.gui.debug.DebugOverlay.toggle();
            } else if (args.length >= 3 && "on".equalsIgnoreCase(args[2])) {
                if (!wasActive) com.fox.ysmu.client.gui.debug.DebugOverlay.toggle();
            } else {
                com.fox.ysmu.client.gui.debug.DebugOverlay.toggle();
            }
            String status = com.fox.ysmu.client.gui.debug.DebugOverlay.isActive()
                ? "\u00a7aON" : "\u00a77OFF";
            player.addChatMessage(new ChatComponentText(
                "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r Debug overlay: " + status));
            if (com.fox.ysmu.client.gui.debug.DebugOverlay.isActive()) {
                com.fox.ysmu.client.gui.debug.DebugOverlay.tryShowToggleHint();
            }
        } else {
            player.addChatMessage(new ChatComponentText(
                "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r Unknown debug subcommand: " + sub
                + ". Use: query | overlay | eval"));
        }
    }

    /**
     * 重置其他玩家的 Molang 变量状态（/ysm reset <selector>）。
     * 重置自己请用客户端命令 {@code /ysmclient reset}（纯本地，不走服务端）。
     * 支持原版选择器（@p / @a / 玩家名）；清理在目标玩家的客户端执行
     * （漫游变量/帧间状态/v.* 残留），保留模型定义，不需要重载模型。
     */
    private void processReset(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.addChatMessage(new ChatComponentText(
                "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r Usage: /ysm reset <player|@a|@p>"
                    + "  — or use §e/ysmclient reset§r for yourself."));
            return;
        }
        net.minecraft.entity.player.EntityPlayerMP[] targets;
        try {
            targets = net.minecraft.command.PlayerSelector.matchPlayers(sender, args[1]);
        } catch (Exception e) {
            throw new CommandException("commands.generic.player.notFound");
        }
        if (targets == null || targets.length == 0) {
            throw new CommandException("commands.generic.player.notFound");
        }
        for (net.minecraft.entity.player.EntityPlayerMP target : targets) {
            NetworkHandler.CHANNEL.sendTo(new com.fox.ysmu.network.message.PacketResetMolang(), target);
        }
        sender.addChatMessage(new ChatComponentText(
            "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r Reset Molang variables for §e"
                + targets.length + "§r player(s)."));
    }

    /** 检查自定义模型文件夹结构；对每个不完整（缺 main.json/arm.json/材质）的文件夹
     *  报告具体错误，并返回「跳过」的文件夹数量（结构不完整、无法加载的模型数）。
     *  材质名不合法只告警不计数（模型仍可加载）。 */
    private int checkModelFiles(ICommandSender sender, Path rootPath) {
        Collection<File> dirs = FileUtils.listFiles(rootPath.toFile(), DirectoryFileFilter.INSTANCE, null);
        int skipped = 0;
        for (File dir : dirs) {
            String dirName = dir.getName();
            boolean noMainModelFile = true;
            boolean noArmModelFile = true;
            boolean noTextureFile = true;
            Collection<File> files = FileUtils.listFiles(rootPath.resolve(dirName).toFile(), FileFileFilter.FILE, null);
            for (File file : files) {
                String fileName = file.getName();
                if (MAIN_MODEL_FILE_NAME.equals(fileName) && isNotBlankFile(file)) {
                    noMainModelFile = false;
                }
                if (ARM_MODEL_FILE_NAME.equals(fileName) && isNotBlankFile(file)) {
                    noArmModelFile = false;
                }
                if (fileName.endsWith(".png")) {
                    noTextureFile = false;
                    String name = file.getName();
                    name = name.substring(0, name.length() - 4);
                    if (!isValidResourceLocation(name)) {
                        String showName = String.format("%s/%s.png", dirName, name);
                        sender.addChatMessage(new ChatComponentTranslation("message.yes_steve_model.model.reload.error.texture_name", showName));
                    }
                }
            }
            if (noMainModelFile) {
                sender.addChatMessage(new ChatComponentTranslation("message.yes_steve_model.model.reload.error.no_main_file", dirName));
            }
            if (noArmModelFile) {
                sender.addChatMessage(new ChatComponentTranslation("message.yes_steve_model.model.reload.error.no_arm_file", dirName));
            }
            if (noTextureFile) {
                sender.addChatMessage(new ChatComponentTranslation("message.yes_steve_model.model.reload.error.no_texture_file", dirName));
            }
            if (noMainModelFile || noArmModelFile || noTextureFile) {
                skipped++;
            }
        }
        return skipped;
    }

    private static boolean isNotBlankFile(File file) {
        try {
            String fileText = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
            return StringUtils.isNoneBlank(fileText);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }
}
