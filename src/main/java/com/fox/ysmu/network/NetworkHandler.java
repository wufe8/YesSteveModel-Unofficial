package com.fox.ysmu.network;

import io.netty.channel.ChannelHandler;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.Tags;
import com.fox.ysmu.network.message.*;
import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.network.FMLEmbeddedChannel;
import cpw.mods.fml.common.network.FMLIndexedMessageToMessageCodec;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleIndexedCodec;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public final class NetworkHandler {

    public static final String PROTOCOL_VERSION = Tags.VERSION;
    private static final String CHANNEL_NAME = "ysmu_network";
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL_NAME);

    /**
     * 物理端。**专用服务器上不能实例化客户端的包处理器**：{@code registerMessage} 会对处理器类
     * 调 {@code Class.newInstance()}，而加载/校验那个类会连带解析它引用到的客户端代码
     * （例如 {@code S2CPlaySound.Handler} 的 lambda 调用 {@code YSMSoundManager}），专用服上
     * {@code net.minecraft.client.*} 被 LaunchClassLoader 拒载，于是 {@code ysmu.init} 直接以
     * {@code NoClassDefFoundError} 崩掉 —— 1.9a1-08 服务端起不来就是这个原因。
     */
    private static final boolean PHYSICAL_CLIENT = FMLCommonHandler.instance()
        .getSide() == Side.CLIENT;
    private static final java.util.Set<Integer> SERVERBOUND_IDS = new java.util.HashSet<>();
    private static final java.util.Set<Integer> CLIENTBOUND_IDS = new java.util.HashSet<>();

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void registerServerbound(Class handlerClass, Class messageClass, int discriminator) {
        SERVERBOUND_IDS.add(discriminator);
        CHANNEL.registerMessage(handlerClass, messageClass, discriminator, Side.SERVER);
    }

    private static void installIncomingGuards() {
        for (Side side : Side.values()) {
            FMLEmbeddedChannel channel = NetworkRegistry.INSTANCE.getChannel(CHANNEL_NAME, side);
            String codecName = channel.pipeline().context(SimpleIndexedCodec.class).name();
            channel.pipeline().addBefore(codecName, "ysmu_incoming_guard",
                new IncomingMessageGuard(side, side == Side.SERVER ? SERVERBOUND_IDS : CLIENTBOUND_IDS));
        }
    }

    /**
     * 注册 clientbound 包。
     *
     * <p>判别号两侧都必须注册：{@code FMLIndexedMessageToMessageCodec.encode} 用 class→id 表
     * 编码，服务端发送下行包时要用它（少了它报文根本发不出去）。但**处理器类**只有客户端需要：
     * {@code registerMessage} 会实例化处理器类，而加载/校验那个类会连带解析它引用到的客户端代码
     * （例如 {@code S2CPlaySound.Handler} 的 lambda 调用 {@code YSMSoundManager}），专用服上
     * {@code net.minecraft.client.*} 被 LaunchClassLoader 拒载，于是 {@code ysmu.init} 以
     * {@code NoClassDefFoundError} 崩掉 —— 1.9a1-08 服务端起不来就是这个原因。</p>
     *
     * <p>所以专用服上**完全不装处理器**，只往共用的 codec 里登记判别号。早先试过"换成空壳处理器
     * 再摘掉"，在 Java 8 上可行、在 Java 17+ 上不行：FML 用处理器实例的类名当 netty pipeline 名
     * （{@code addAfter(type, handler.getClass().getName(), …)}），而 Java 17+ 的非捕获 lambda 是
     * 隐藏类，名字形如 {@code …$$Lambda/0x…}，注册与摘除两次拿到的名字对不上 →
     * {@code NoSuchElementException}。绕开 pipeline 就没有这类名字问题。</p>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void registerClientbound(Class handlerClass, Class messageClass, int discriminator) {
        clientboundRegistered++;
        CLIENTBOUND_IDS.add(discriminator);
        if (PHYSICAL_CLIENT) {
            CHANNEL.registerMessage(handlerClass, messageClass, discriminator, Side.CLIENT);
            return;
        }
        discriminantCodec().addDiscriminator(discriminator, messageClass);
    }

    /**
     * 本频道的 {@code SimpleIndexedCodec}：{@code NetworkRegistry.newChannel(name, handlers)} 把
     * **同一组 handlers** 装进两个方向的 channel（见其源码：循环 Side.values() 时传的是同一个
     * {@code handlers} 数组），所以服务端发送（{@code channels.get(Side.SERVER)}）与客户端接收用的
     * 是同一个 codec 实例，往它上面登记判别号就够两侧用了。
     */
    private static FMLIndexedMessageToMessageCodec<IMessage> discriminantCodec() {
        for (Side side : Side.values()) {
            FMLEmbeddedChannel channel = NetworkRegistry.INSTANCE.getChannel(CHANNEL_NAME, side);
            if (channel == null) {
                continue;
            }
            ChannelHandler codec = channel.pipeline()
                .get(SimpleIndexedCodec.class);
            if (codec instanceof FMLIndexedMessageToMessageCodec) {
                return (FMLIndexedMessageToMessageCodec<IMessage>) codec;
            }
        }
        throw new IllegalStateException("ysmu: no SimpleIndexedCodec on channel " + CHANNEL_NAME);
    }

    // Packet ids are part of the wire protocol. Add new ids; do not renumber existing ones.
    private static final int SERVERBOUND_SYNC_MODEL_FILES = 0;
    private static final int SERVERBOUND_SET_MODEL_AND_TEXTURE = 5;
    private static final int SERVERBOUND_SET_PLAY_ANIMATION = 7;
    private static final int SERVERBOUND_SET_STAR_MODEL = 9;
    private static final int SERVERBOUND_OPENYSM_MODEL_SYNC_PAYLOAD_17 = 14;
    private static final int SERVERBOUND_OPENYSM_VERSION_CHECK_17 = 15;
    private static final int SERVERBOUND_OPENYSM_COMPLETE_FEEDBACK_17 = 16;
    private static final int SERVERBOUND_MOLANG_SYNC = 28;

    private static final int CLIENTBOUND_SEND_MODEL_FILE = 1;
    private static final int CLIENTBOUND_REQUEST_SYNC_MODEL = 2;
    private static final int CLIENTBOUND_REQUEST_LOAD_MODEL = 3;
    private static final int CLIENTBOUND_SYNC_MODEL_INFO = 4;
    private static final int CLIENTBOUND_SYNC_STAR_MODELS = 8;
    private static final int CLIENTBOUND_REQUEST_SERVER_MODEL_INFO = 10;
    private static final int CLIENTBOUND_SYNC_PLAYER_MOTION_STATE = 11;
    private static final int CLIENTBOUND_COMPLETE_FEEDBACK = 12;
    private static final int CLIENTBOUND_SEND_MODEL_PASSWORD = 13;
    private static final int CLIENTBOUND_OPENYSM_MODEL_SYNC_PAYLOAD_17 = 17;
    private static final int CLIENTBOUND_OPENYSM_VERSION_CHECK_17 = 18;
    private static final int CLIENTBOUND_SEND_MODEL_FILE_CHUNK = 19;
    private static final int CLIENTBOUND_SYNC_GAME_PATH = 20;
    private static final int CLIENTBOUND_SET_WELCOME_CONFIG = 21;
    private static final int CLIENTBOUND_SHOW_BUFFER_INFO = 22;
    private static final int CLIENTBOUND_QUERY_MOLANG_VAR = 23;
    private static final int CLIENTBOUND_OPENYSM_SYNC_INDEX_CHUNK_17 = 24;
    private static final int CLIENTBOUND_SPAWN_PARTICLE_COMMAND = 25;
    private static final int CLIENTBOUND_EVAL_MOLANG = 26;
    private static final int CLIENTBOUND_RESET_MOLANG = 27;
    private static final int CLIENTBOUND_MOLANG_SYNC = 29;
    /** /ysm playsound 的客户端执行体（音效库只在客户端，见 S2CPlaySound）。新号，不改已有 id。 */
    private static final int CLIENTBOUND_PLAY_SOUND = 30;
    /** /ysm debug overlay 的客户端执行体（Overlay 是纯客户端状态，见 S2CSetDebugOverlay）。 */
    private static final int CLIENTBOUND_SET_DEBUG_OVERLAY = 31;

    public static final int OPEN_NPC_MODEL_GUI = 93;
    public static final int SET_NPC_MODEL_ID = 94;
    public static final int SYNC_NPC_DATA = 95;
    public static final int UPDATE_NPC_DATA = 96;

    /** 已登记的 clientbound 包数，用于启动日志（专用服上靠它确认走的是传输层登记）。 */
    private static int clientboundRegistered;

    public static void init() {
        registerServerboundMessages();
        registerClientboundMessages();
        initBukkit();
        installIncomingGuards();
        // 一行 INFO：专用服上确认下行包只登记了判别号、没有实例化任何客户端处理器。
        ysmu.LOG.info(
            "YSM network: {} clientbound packets registered {}",
            clientboundRegistered,
            PHYSICAL_CLIENT ? "(client handlers)" : "(discriminators only - dedicated server)");
    }

    private static void registerServerboundMessages() {
        registerServerbound(
            SyncModelFiles.Handler.class,
            SyncModelFiles.class,
            SERVERBOUND_SYNC_MODEL_FILES);
        registerServerbound(SetModelAndTexture.Handler.class, SetModelAndTexture.class, SERVERBOUND_SET_MODEL_AND_TEXTURE);
        registerServerbound(SetPlayAnimation.Handler.class, SetPlayAnimation.class, SERVERBOUND_SET_PLAY_ANIMATION);
        registerServerbound(SetStarModel.Handler.class, SetStarModel.class, SERVERBOUND_SET_STAR_MODEL);
        registerServerbound(C2SModelSyncPayload17.Handler.class, C2SModelSyncPayload17.class, SERVERBOUND_OPENYSM_MODEL_SYNC_PAYLOAD_17);
        registerServerbound(C2SVersionCheck17.Handler.class, C2SVersionCheck17.class, SERVERBOUND_OPENYSM_VERSION_CHECK_17);
        registerServerbound(C2SCompleteFeedback17.Handler.class, C2SCompleteFeedback17.class, SERVERBOUND_OPENYSM_COMPLETE_FEEDBACK_17);
        // ysm.sync(...)（wiki: molang/script「主动同步」）：客户端发起，服务端广播给所有人。
        registerServerbound(C2SMolangSync.Handler.class, C2SMolangSync.class, SERVERBOUND_MOLANG_SYNC);
    }

    private static void registerClientboundMessages() {
        registerClientbound(SendModelFile.Handler.class, SendModelFile.class, CLIENTBOUND_SEND_MODEL_FILE);
        registerClientbound(RequestSyncModel.Handler.class, RequestSyncModel.class, CLIENTBOUND_REQUEST_SYNC_MODEL);
        registerClientbound(RequestLoadModel.Handler.class, RequestLoadModel.class, CLIENTBOUND_REQUEST_LOAD_MODEL);
        registerClientbound(SyncModelInfo.Handler.class, SyncModelInfo.class, CLIENTBOUND_SYNC_MODEL_INFO);
        registerClientbound(SyncStarModels.Handler.class, SyncStarModels.class, CLIENTBOUND_SYNC_STAR_MODELS);
        registerClientbound(RequestServerModelInfo.Handler.class, RequestServerModelInfo.class, CLIENTBOUND_REQUEST_SERVER_MODEL_INFO);
        registerClientbound(SyncPlayerMotionState.Handler.class, SyncPlayerMotionState.class, CLIENTBOUND_SYNC_PLAYER_MOTION_STATE);
        registerClientbound(CompleteFeedback.Handler.class, CompleteFeedback.class, CLIENTBOUND_COMPLETE_FEEDBACK);
        registerClientbound(SendModelPassword.Handler.class, SendModelPassword.class, CLIENTBOUND_SEND_MODEL_PASSWORD);
        registerClientbound(S2CModelSyncPayload17.Handler.class, S2CModelSyncPayload17.class, CLIENTBOUND_OPENYSM_MODEL_SYNC_PAYLOAD_17);
        registerClientbound(S2CSyncIndexChunk17.Handler.class, S2CSyncIndexChunk17.class, CLIENTBOUND_OPENYSM_SYNC_INDEX_CHUNK_17);
        registerClientbound(S2CVersionCheck17.Handler.class, S2CVersionCheck17.class, CLIENTBOUND_OPENYSM_VERSION_CHECK_17);
        registerClientbound(SendModelFileChunk.Handler.class, SendModelFileChunk.class, CLIENTBOUND_SEND_MODEL_FILE_CHUNK);
        registerClientbound(S2CSetDebugOverlay.Handler.class, S2CSetDebugOverlay.class, CLIENTBOUND_SET_DEBUG_OVERLAY);
        registerClientbound(S2CPlaySound.Handler.class, S2CPlaySound.class, CLIENTBOUND_PLAY_SOUND);
        registerClientbound(SyncGamePath.Handler.class, SyncGamePath.class, CLIENTBOUND_SYNC_GAME_PATH);
        registerClientbound(SetWelcomeConfig.Handler.class, SetWelcomeConfig.class, CLIENTBOUND_SET_WELCOME_CONFIG);
        registerClientbound(ShowBufferInfo.Handler.class, ShowBufferInfo.class, CLIENTBOUND_SHOW_BUFFER_INFO);
        registerClientbound(PacketQueryMolangVar.Handler.class, PacketQueryMolangVar.class, CLIENTBOUND_QUERY_MOLANG_VAR);
        registerClientbound(SpawnParticleCommand.Handler.class, SpawnParticleCommand.class, CLIENTBOUND_SPAWN_PARTICLE_COMMAND);
        registerClientbound(PacketEvalMolang.Handler.class, PacketEvalMolang.class, CLIENTBOUND_EVAL_MOLANG);
        registerClientbound(PacketResetMolang.Handler.class, PacketResetMolang.class, CLIENTBOUND_RESET_MOLANG);
        // ysm.sync(...) 的下行广播：各客户端用同样参数触发发起者模型的 sync 事件脚本。
        registerClientbound(S2CMolangSync.Handler.class, S2CMolangSync.class, CLIENTBOUND_MOLANG_SYNC);
    }

    /**
     * 上游 Bukkit 侧 NPC 集成的包组（给 NPC / 非本地实体指定模型）。**本树没有接上**：
     * 这里注册的三个 clientbound 包（{@code OpenModelGuiMessage} / {@code SyncNpcDataMessage} /
     * {@code UpdateNpcDataMessage}）在整个仓库里都没有发送方，于是
     * {@code OpenModelGuiMessage.CURRENT_NPC_ID} 恒为 -1、{@code NPCData} 恒为空、
     * {@code CustomPlayerRenderer} 里的 {@code NPCData.contains(pid)} 分支不可达，
     * {@code SetNpcModelAndTexture} 也因此收不到请求（真收到了会明确报一条 WARN）。
     *
     * <p>保留 id 与类是有意的：这里用显式 discriminator 注册，删掉一个不会让其它 id 位移，
     * 但外部/旧版对端发过来会变成 unknown discriminator；将来真要做 NPC 支持时这也是一套
     * 现成接口。1.7.10 端目前只支持玩家模型。</p>
     */
    private static void initBukkit() {
        registerClientbound(OpenModelGuiMessage.Handler.class, OpenModelGuiMessage.class, OPEN_NPC_MODEL_GUI);
        registerServerbound(SetNpcModelAndTexture.Handler.class, SetNpcModelAndTexture.class, SET_NPC_MODEL_ID);
        registerClientbound(SyncNpcDataMessage.Handler.class, SyncNpcDataMessage.class, SYNC_NPC_DATA);
        registerClientbound(UpdateNpcDataMessage.Handler.class, UpdateNpcDataMessage.class, UPDATE_NPC_DATA);
    }

    public static void sendToClientPlayer(IMessage message, EntityPlayer player) {
        if (player instanceof EntityPlayerMP) {
            CHANNEL.sendTo(message, (EntityPlayerMP) player);
        }
    }
}
