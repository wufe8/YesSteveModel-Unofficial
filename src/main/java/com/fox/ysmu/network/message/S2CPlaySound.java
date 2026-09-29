package com.fox.ysmu.network.message;

import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import com.fox.ysmu.client.audio.YSMSoundManager;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: {@code /ysm playsound [name]} 的客户端执行体。
 *
 * <p>音效库与播放能力都只在客户端（{@code YSMSoundManager} 走 {@code Minecraft.getMinecraft()}
 * 的 SoundHandler / paulscode SoundSystem，模型音效字节也是客户端按需解密）。原先服务端命令
 * 直接调用它：连专用服务器时命令是在**服务端**执行的，{@code Minecraft.getMinecraft()} 在那边
 * 没有实例，于是「列出缓存音效」永远为空、"播放"会在取 SoundHandler 时失败。</p>
 *
 * <p>所以服务端只负责把这个请求转给发起命令的玩家自己的客户端（与
 * {@code ShowBufferInfo}/{@code PacketQueryMolangVar}/{@code SetWelcomePath} 等子命令同一模式：
 * 服务端校验权限，真正需要客户端状态的动作在客户端做）。</p>
 *
 * <p>{@code soundName} 为空表示"列出本客户端已知的音效"。</p>
 */
public class S2CPlaySound implements IMessage {

    private String soundName = "";

    public S2CPlaySound() {}

    public S2CPlaySound(String soundName) {
        this.soundName = soundName == null ? "" : soundName;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.soundName = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, soundName == null ? "" : soundName);
    }

    public static class Handler implements IMessageHandler<S2CPlaySound, IMessage> {

        @Override
        public IMessage onMessage(S2CPlaySound message, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) return null;
            final String soundName = message.soundName;
            // 回客户端主线程执行：音效播放要碰 SoundSystem，列表要读客户端缓存，
            // 都与渲染线程共享状态。
            Minecraft.getMinecraft()
                .func_152344_a(() -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.thePlayer == null) {
                        return;
                    }
                    if (soundName == null || soundName.isEmpty()) {
                        listSounds(mc);
                        return;
                    }
                    // modelId 传 null：`playSound` 的无模型上下文分支会按名字在全部已注册模型音效里
                    // 找，找不到再走命名空间音效（SoundHandler / 本地高版本资产）——
                    // 这正是命令语义（"播放这个名字的音效"），不需要知道当前模型。
                    YSMSoundManager.playSound(mc.thePlayer, soundName, null, 1.0f, 1.0f);
                    mc.thePlayer.addChatMessage(
                        new ChatComponentText("§6§l[§aYSM§6§l]§r Playing sound: " + soundName));
                });
            return null;
        }

        /** 列出本客户端内存里已解密的模型音效（键形如 {@code model::name}）。 */
        private static void listSounds(Minecraft mc) {
            Map<String, byte[]> sounds = YSMSoundManager.getSoundFiles();
            if (sounds.isEmpty()) {
                mc.thePlayer.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r No cached sounds."));
                return;
            }
            mc.thePlayer.addChatMessage(new ChatComponentText("§6§l[§aYSM§6§l]§r Cached sounds (in-memory):"));
            for (Map.Entry<String, byte[]> e : sounds.entrySet()) {
                mc.thePlayer.addChatMessage(
                    new ChatComponentText("  §e" + e.getKey() + "§r → §7" + e.getValue().length + " bytes"));
            }
            mc.thePlayer.addChatMessage(new ChatComponentText("§6Use §e/ysm playsound <name>§6 to play one."));
        }
    }
}
