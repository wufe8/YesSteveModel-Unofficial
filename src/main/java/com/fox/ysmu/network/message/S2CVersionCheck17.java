package com.fox.ysmu.network.message;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.sync.OpenYsmModelSyncClient;
import com.fox.ysmu.Config;
import com.fox.ysmu.network.NetworkHandler;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

public class S2CVersionCheck17 implements IMessage {

    private String version = "";

    public S2CVersionCheck17() {}

    public S2CVersionCheck17(String version) {
        this.version = version == null ? "" : version;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.version = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.version);
    }

    public String getVersion() {
        return version;
    }

    public static class Handler implements IMessageHandler<S2CVersionCheck17, IMessage> {

        @Override
        public IMessage onMessage(S2CVersionCheck17 message, MessageContext ctx) {
            if (ctx.side == Side.CLIENT && Config.ENABLE_SYNC_PROTOCOL) {
                OpenYsmModelSyncClient.resetConnectionState();
                // 新一轮同步即将开始（进服握手 / /ysm reload）：立即结束旧同步的后台循环。
                // 旧 SyncState 已被整体替换，但其看门狗与完成轮询仍在旧实例上运行，靠
                // SYNC_IN_PROGRESS 判断是否退出；此处置 false 让它们在下一次迭代立即退出，
                // 否则加载期间 reload 时旧任务会继续空转到解析排空/60s 停滞。新同步在
                // packet03 处重新置 true。
                ClientModelManager.SYNC_IN_PROGRESS = false;
                NetworkHandler.CHANNEL.sendToServer(new C2SVersionCheck17(NetworkHandler.PROTOCOL_VERSION));
            }
            return null;
        }
    }
}
