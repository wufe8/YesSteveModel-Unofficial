package com.fox.ysmu.network.message;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.eep.ExtendedModelInfo;
import com.fox.ysmu.model.ServerModelManager;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class SetModelAndTexture implements IMessage {

    private String modelId;
    private String selectTexture;

    public SetModelAndTexture() {}

    public SetModelAndTexture(ResourceLocation modelId, ResourceLocation selectTexture) {
        this.modelId = modelId.toString();
        this.selectTexture = selectTexture.toString();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.modelId = ByteBufUtils.readUTF8String(buf);
        this.selectTexture = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.modelId);
        ByteBufUtils.writeUTF8String(buf, this.selectTexture);
    }

    public static class Handler implements IMessageHandler<SetModelAndTexture, IMessage> {

        @Override
        public IMessage onMessage(SetModelAndTexture message, MessageContext ctx) {
            EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
            if (sender != null) {
                handleEEP(message, sender);
            }
            return null;
        }

        private void handleEEP(SetModelAndTexture message, EntityPlayerMP sender) {
            ExtendedModelInfo modelInfo = ExtendedModelInfo.get(sender);
            if (modelInfo == null) {
                return;
            }
            // 空 id：早先的注释把它当"回默认模型"，但没有任何调用方发空 id，也没有任何地方
            // 把它解析成默认模型；按原样透传只会把 EEP 的 modelId / selectTexture 置成 null，
            // 之后 saveNBTData()（每次 dirty 广播都调）与渲染路径就 NPE。整条消息忽略。
            if (message.modelId.isEmpty() || message.selectTexture.isEmpty()) {
                return;
            }
            ResourceLocation modelLoc;
            ResourceLocation textureLoc;
            try {
                modelLoc = new ResourceLocation(message.modelId);
                textureLoc = new ResourceLocation(message.selectTexture);
            } catch (RuntimeException e) {
                // 1.7.10 的 ResourceLocation 对非法字符直接抛异常，而白名单校验在解析之后，
                // 所以伪造的 id 会在校验之前就把整条栈打进日志（每个包一条，可刷屏）。丢弃。
                return;
            }
            // 服务端白名单校验：客户端只能选择服务器上真实存在的模型，防止伪造
            // 任意 modelId 广播给周围玩家，导致其他客户端尝试加载不存在的模型。
            // 模型 ID 以 ResourceLocation path 段为准（可能带 domain，如
            // "ysmu:model" 或 "model"）；纹理 ID 需以该模型 ID 为前缀
            // （"model/texture" 或 "model/main"）。不合法则整体忽略本次设置。
            if (!isAllowedModel(modelLoc)) {
                return;
            }
            if (!isTextureOfModel(textureLoc, modelLoc)) {
                return;
            }
            modelInfo.setModelAndTexture(modelLoc, textureLoc);
        }

        /**
         * 校验模型 ID 是否存在于服务端模型白名单（OpenYSM 同步索引或 legacy 缓存）。
         * 忽略 domain 段：客户端可能发 "ysmu:model" 或 "model"，服务端 key 为
         * 内部 ID（不含 domain）。空 path（例如 {@code "ysmu:"}）按"默认模型"放行 ——
         * 真正会造成 null 字段的空字符串 id 已经在 {@link #handleEEP} 里挡掉了。
         */
        private static boolean isAllowedModel(ResourceLocation modelLoc) {
            String path = modelLoc.getResourcePath();
            if (path == null || path.isEmpty()) {
                return true; // 空路径 → 默认模型
            }
            // 可能带子段：模型基 ID 是 "/main"、"/arm" 之前的部分
            String basePath = path;
            int slash = path.indexOf('/');
            if (slash > 0) {
                basePath = path.substring(0, slash);
            }
            return ServerModelManager.OPEN_YSM_SYNC_INFO.containsKey(basePath)
                || ServerModelManager.CACHE_NAME_INFO.containsKey(basePath)
                || ServerModelManager.RAW_MODEL_INFO.containsKey(basePath);
        }

        /** 校验纹理 ID 是否为该模型 ID 的子 ID（"base/xxx"）。 */
        private static boolean isTextureOfModel(ResourceLocation textureLoc, ResourceLocation modelLoc) {
            String texturePath = textureLoc.getResourcePath();
            String modelPath = modelLoc.getResourcePath();
            if (texturePath == null || modelPath == null) {
                return false;
            }
            if (texturePath.equals(modelPath)) {
                return true;
            }
            return texturePath.startsWith(modelPath + "/");
        }
    }
}
