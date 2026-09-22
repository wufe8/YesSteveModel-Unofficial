package com.fox.ysmu.network.message;

import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 → 服务端：给某个 NPC / 非本地实体设置模型与贴图。
 *
 * <p><b>本树未实现</b>（{@link Handler} 会明确报一条 {@code [YSMU-COMPAT]} WARN 再丢弃）。
 * 这是上游 Bukkit 侧 NPC 集成的残骸：包与注册都在（见
 * {@code NetworkHandler#initBukkit()}），但没有任何地方发送 {@link OpenModelGuiMessage}
 * —— 那是唯一能打开"非本地玩家"模型 GUI 的入口 —— 所以连发送侧都不可达；1.7.10 端也没有
 * NPC 模型存储与同步。id 保持不动：{@code NetworkHandler} 用显式 discriminator 注册，
 * 删掉不会让其它 id 位移，但外部/旧版对端发过来会变成 unknown discriminator。</p>
 */
public class SetNpcModelAndTexture implements IMessage {

    private String modelId;
    private String selectTexture;
    private int npcId;

    public SetNpcModelAndTexture() {}

    public SetNpcModelAndTexture(net.minecraft.util.ResourceLocation modelId,
        net.minecraft.util.ResourceLocation selectTexture, int npcId) {
        this.modelId = modelId.toString();
        this.selectTexture = selectTexture.toString();
        this.npcId = npcId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.modelId = ByteBufUtils.readUTF8String(buf);
        this.selectTexture = ByteBufUtils.readUTF8String(buf);
        this.npcId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.modelId);
        ByteBufUtils.writeUTF8String(buf, this.selectTexture);
        buf.writeInt(this.npcId);
    }

    public static class Handler implements IMessageHandler<SetNpcModelAndTexture, IMessage> {

        /** 已经报过"未实现"的 npcId（每个实体一条，GUI 反复操作时不刷屏）。 */
        private static final java.util.Set<Integer> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public IMessage onMessage(SetNpcModelAndTexture message, MessageContext ctx) {
            // 未实现 —— 但要**明确**说出来，不能静默成功：调用方（模型 GUI 的"非本地玩家"
            // 分支）发了请求、永远不会生效，沉默的 no-op 只会让下一次排查从这里重新开始。
            // NPC 模型是上游 Bukkit 侧集成的特性，1.7.10 端只搬了包与注册，触发器、NPC 模型
            // 存储与同步都没搬；整条链在本树不可达（见 NetworkHandler.initBukkit 的注释）。
            if (WARNED.add(message.npcId)) {
                ysmu.LOG.warn(
                    "[YSMU-COMPAT] NPC model switching is not implemented in the 1.7.10 port (npcId={}); request dropped",
                    message.npcId);
            }
            return null;
        }
    }
}
