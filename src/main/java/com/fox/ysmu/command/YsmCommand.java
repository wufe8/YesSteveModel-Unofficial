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
        return "/ysm <reload|play|playsound|setgamepath|buffer|welcome> [args]";
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args,
                "reload", "play", "playsound", "setgamepath", "buffer", "welcome");
        }
        if (args.length == 2 && "welcome".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "on", "off");
        }
        if (args.length == 2 && "setgamepath".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "<path>");
        }
        if (args.length == 3 && "setgamepath".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "[version]");
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
        // setgamepath, welcome, and buffer are allowed for all players (client-side only).
        // Other subcommands require the default permission level.
        if (!"setgamepath".equalsIgnoreCase(args[0]) && !"welcome".equalsIgnoreCase(args[0])
            && !"buffer".equalsIgnoreCase(args[0])
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
        } else if ("welcome".equalsIgnoreCase(args[0])) {
            processWelcome(sender, args);
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
