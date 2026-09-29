package com.fox.ysmu.network;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

import com.fox.ysmu.Tags;
import com.fox.ysmu.network.message.*;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public final class NetworkHandler {

    public static final String PROTOCOL_VERSION = Tags.VERSION;
    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("ysmu_network");

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

    public static void init() {
        registerServerboundMessages();
        registerClientboundMessages();
        initBukkit();
    }

    private static void registerServerboundMessages() {
        CHANNEL.registerMessage(
            SyncModelFiles.Handler.class,
            SyncModelFiles.class,
            SERVERBOUND_SYNC_MODEL_FILES,
            Side.SERVER);
        CHANNEL.registerMessage(
            SetModelAndTexture.Handler.class,
            SetModelAndTexture.class,
            SERVERBOUND_SET_MODEL_AND_TEXTURE,
            Side.SERVER);
        CHANNEL.registerMessage(
            SetPlayAnimation.Handler.class,
            SetPlayAnimation.class,
            SERVERBOUND_SET_PLAY_ANIMATION,
            Side.SERVER);
        CHANNEL.registerMessage(
            SetStarModel.Handler.class,
            SetStarModel.class,
            SERVERBOUND_SET_STAR_MODEL,
            Side.SERVER);
        CHANNEL.registerMessage(
            C2SModelSyncPayload17.Handler.class,
            C2SModelSyncPayload17.class,
            SERVERBOUND_OPENYSM_MODEL_SYNC_PAYLOAD_17,
            Side.SERVER);
        CHANNEL.registerMessage(
            C2SVersionCheck17.Handler.class,
            C2SVersionCheck17.class,
            SERVERBOUND_OPENYSM_VERSION_CHECK_17,
            Side.SERVER);
        CHANNEL.registerMessage(
            C2SCompleteFeedback17.Handler.class,
            C2SCompleteFeedback17.class,
            SERVERBOUND_OPENYSM_COMPLETE_FEEDBACK_17,
            Side.SERVER);
        // ysm.sync(...)（wiki: molang/script「主动同步」）：客户端发起，服务端广播给所有人。
        CHANNEL.registerMessage(
            C2SMolangSync.Handler.class,
            C2SMolangSync.class,
            SERVERBOUND_MOLANG_SYNC,
            Side.SERVER);
    }

    private static void registerClientboundMessages() {
        CHANNEL.registerMessage(
            SendModelFile.Handler.class,
            SendModelFile.class,
            CLIENTBOUND_SEND_MODEL_FILE,
            Side.CLIENT);
        CHANNEL.registerMessage(
            RequestSyncModel.Handler.class,
            RequestSyncModel.class,
            CLIENTBOUND_REQUEST_SYNC_MODEL,
            Side.CLIENT);
        CHANNEL.registerMessage(
            RequestLoadModel.Handler.class,
            RequestLoadModel.class,
            CLIENTBOUND_REQUEST_LOAD_MODEL,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SyncModelInfo.Handler.class,
            SyncModelInfo.class,
            CLIENTBOUND_SYNC_MODEL_INFO,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SyncStarModels.Handler.class,
            SyncStarModels.class,
            CLIENTBOUND_SYNC_STAR_MODELS,
            Side.CLIENT);
        CHANNEL.registerMessage(
            RequestServerModelInfo.Handler.class,
            RequestServerModelInfo.class,
            CLIENTBOUND_REQUEST_SERVER_MODEL_INFO,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SyncPlayerMotionState.Handler.class,
            SyncPlayerMotionState.class,
            CLIENTBOUND_SYNC_PLAYER_MOTION_STATE,
            Side.CLIENT);
        CHANNEL.registerMessage(
            CompleteFeedback.Handler.class,
            CompleteFeedback.class,
            CLIENTBOUND_COMPLETE_FEEDBACK,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SendModelPassword.Handler.class,
            SendModelPassword.class,
            CLIENTBOUND_SEND_MODEL_PASSWORD,
            Side.CLIENT);
        CHANNEL.registerMessage(
            S2CModelSyncPayload17.Handler.class,
            S2CModelSyncPayload17.class,
            CLIENTBOUND_OPENYSM_MODEL_SYNC_PAYLOAD_17,
            Side.CLIENT);
        CHANNEL.registerMessage(
            S2CSyncIndexChunk17.Handler.class,
            S2CSyncIndexChunk17.class,
            CLIENTBOUND_OPENYSM_SYNC_INDEX_CHUNK_17,
            Side.CLIENT);
        CHANNEL.registerMessage(
            S2CVersionCheck17.Handler.class,
            S2CVersionCheck17.class,
            CLIENTBOUND_OPENYSM_VERSION_CHECK_17,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SendModelFileChunk.Handler.class,
            SendModelFileChunk.class,
            CLIENTBOUND_SEND_MODEL_FILE_CHUNK,
            Side.CLIENT);
        CHANNEL.registerMessage(
            S2CSetDebugOverlay.Handler.class,
            S2CSetDebugOverlay.class,
            CLIENTBOUND_SET_DEBUG_OVERLAY,
            Side.CLIENT);
        CHANNEL.registerMessage(
            S2CPlaySound.Handler.class,
            S2CPlaySound.class,
            CLIENTBOUND_PLAY_SOUND,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SyncGamePath.Handler.class,
            SyncGamePath.class,
            CLIENTBOUND_SYNC_GAME_PATH,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SetWelcomeConfig.Handler.class,
            SetWelcomeConfig.class,
            CLIENTBOUND_SET_WELCOME_CONFIG,
            Side.CLIENT);
        CHANNEL.registerMessage(
            ShowBufferInfo.Handler.class,
            ShowBufferInfo.class,
            CLIENTBOUND_SHOW_BUFFER_INFO,
            Side.CLIENT);
        CHANNEL.registerMessage(
            PacketQueryMolangVar.Handler.class,
            PacketQueryMolangVar.class,
            CLIENTBOUND_QUERY_MOLANG_VAR,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SpawnParticleCommand.Handler.class,
            SpawnParticleCommand.class,
            CLIENTBOUND_SPAWN_PARTICLE_COMMAND,
            Side.CLIENT);
        CHANNEL.registerMessage(
            PacketEvalMolang.Handler.class,
            PacketEvalMolang.class,
            CLIENTBOUND_EVAL_MOLANG,
            Side.CLIENT);
        CHANNEL.registerMessage(
            PacketResetMolang.Handler.class,
            PacketResetMolang.class,
            CLIENTBOUND_RESET_MOLANG,
            Side.CLIENT);
        // ysm.sync(...) 的下行广播：各客户端用同样参数触发发起者模型的 sync 事件脚本。
        CHANNEL.registerMessage(
            S2CMolangSync.Handler.class,
            S2CMolangSync.class,
            CLIENTBOUND_MOLANG_SYNC,
            Side.CLIENT);
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
        CHANNEL.registerMessage(
            OpenModelGuiMessage.Handler.class,
            OpenModelGuiMessage.class,
            OPEN_NPC_MODEL_GUI,
            Side.CLIENT);
        CHANNEL.registerMessage(
            SetNpcModelAndTexture.Handler.class,
            SetNpcModelAndTexture.class,
            SET_NPC_MODEL_ID,
            Side.SERVER);
        CHANNEL.registerMessage(
            SyncNpcDataMessage.Handler.class,
            SyncNpcDataMessage.class,
            SYNC_NPC_DATA,
            Side.CLIENT);
        CHANNEL.registerMessage(
            UpdateNpcDataMessage.Handler.class,
            UpdateNpcDataMessage.class,
            UPDATE_NPC_DATA,
            Side.CLIENT);
    }

    public static void sendToClientPlayer(IMessage message, EntityPlayer player) {
        if (player instanceof EntityPlayerMP) {
            CHANNEL.sendTo(message, (EntityPlayerMP) player);
        }
    }
}
