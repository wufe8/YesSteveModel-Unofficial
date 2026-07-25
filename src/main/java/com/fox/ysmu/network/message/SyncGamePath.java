package com.fox.ysmu.network.message;

import com.fox.ysmu.Config;
import com.fox.ysmu.compat.LocalAssetProvider;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * 服务端→客户端：同步高版本游戏资产路径和版本。
 * 玩家在聊天栏输入 /ysm setgamepath <path> [version] 时触发，
 * 服务端将此消息转发给客户端，客户端更新 Config 并重新初始化 LocalAssetProvider。
 */
public class SyncGamePath implements IMessage {

    private String gamePath;
    private String assetVersion;

    public SyncGamePath() {}

    public SyncGamePath(String gamePath, String assetVersion) {
        this.gamePath = gamePath;
        this.assetVersion = assetVersion;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int pathLen = buf.readInt();
        byte[] pathBytes = new byte[pathLen];
        buf.readBytes(pathBytes);
        this.gamePath = new String(pathBytes, java.nio.charset.StandardCharsets.UTF_8);

        int verLen = buf.readInt();
        byte[] verBytes = new byte[verLen];
        buf.readBytes(verBytes);
        this.assetVersion = new String(verBytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        byte[] pathBytes = gamePath.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeInt(pathBytes.length);
        buf.writeBytes(pathBytes);

        byte[] verBytes = assetVersion.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeInt(verBytes.length);
        buf.writeBytes(verBytes);
    }

    public static class Handler implements IMessageHandler<SyncGamePath, IMessage> {

        @Override
        public IMessage onMessage(SyncGamePath message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT) {
                // Update config on the client side
                Config.HIGH_VERSION_GAME_PATH = message.gamePath;
                Config.HIGH_VERSION_ASSET_VERSION = message.assetVersion;
                Config.save();
                // Reinitialize the asset provider with the new path
                LocalAssetProvider.reset();
                if (Config.DEBUG_SOUND) {
                    com.fox.ysmu.ysmu.LOG.info("[YSMU-SOUND] Game path updated via command: '{}' v{}",
                        message.gamePath, message.assetVersion);
                }
            }
            return null;
        }
    }

    public String getGamePath() {
        return gamePath;
    }

    public String getAssetVersion() {
        return assetVersion;
    }
}
