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

public class YsmCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "ysm";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/ysm <reload|play|playsound|setgamepath> [args]";
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
        // setgamepath is allowed for all players (client-side config only).
        // Other subcommands require the default permission level.
        if (!"setgamepath".equalsIgnoreCase(args[0]) && !super.canCommandSenderUseCommand(sender)) {
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
        } else {
            throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    private void processReload(ICommandSender sender) {
        StopWatch watch = new StopWatch();
        watch.start();
        com.fox.ysmu.ysmu.LOG.info("YSM reload command started by {}", sender.getCommandSenderName());
        checkModelFiles(sender, CUSTOM);
        ServerModelManager.reloadPacks();
        ServerModelManager.sendRequestSyncModelMessage(sender.getEntityWorld().playerEntities);
        watch.stop();
        com.fox.ysmu.ysmu.LOG.info("YSM reload command finished in {}ms, players notified: {}",
            watch.getTime(), sender.getEntityWorld().playerEntities.size());
        sender.addChatMessage(new ChatComponentTranslation("message.yes_steve_model.model.reload.info", watch.getTime()));
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
        if (eep != null) {
            eep.playAnimation(animName);
            player.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r Play: " + animName));
        }
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
            Map<String, java.nio.file.Path> sounds = com.fox.ysmu.client.audio.YSMSoundManager.getSoundFiles();
            if (sounds.isEmpty()) {
                player.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r No cached sounds."));
            } else {
                player.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r Cached sounds:"));
                for (Map.Entry<String, java.nio.file.Path> e : sounds.entrySet()) {
                    String fn = e.getValue().getFileName().toString();
                    player.addChatMessage(new ChatComponentText("  §e" + e.getKey() + "§r → §7" + fn));
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
                player.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r No game path configured."));
                player.addChatMessage(new ChatComponentText("§6Usage: §e/ysm setgamepath <path> [version]"));
                player.addChatMessage(new ChatComponentText("§7Example: §e/ysm setgamepath C:/Users/x/.minecraft 1.21"));
            } else {
                player.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r Current game path: §e" + currentPath));
                player.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r Asset version: §e" + currentVer));
                player.addChatMessage(new ChatComponentText("§7Use §e/ysm setgamepath \"\"§7 to clear."));
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
            player.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r Game path cleared."));
        } else {
            player.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r Game path set to: §e" + newPath));
            player.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r Asset version: §e" + newVersion));
            player.addChatMessage(new ChatComponentText("§7Changes are local only. Reconnect or reload models if needed."));
        }
    }

    private void checkModelFiles(ICommandSender sender, Path rootPath) {
        Collection<File> dirs = FileUtils.listFiles(rootPath.toFile(), DirectoryFileFilter.INSTANCE, null);
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
        }
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
