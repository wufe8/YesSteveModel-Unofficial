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
            if (modelInfo != null) {
                ResourceLocation modelLoc = message.modelId.isEmpty() ? null : new ResourceLocation(message.modelId);
                ResourceLocation textureLoc = message.selectTexture.isEmpty() ? null
                    : new ResourceLocation(message.selectTexture);
                // 服务端白名单校验：客户端只能选择服务器上真实存在的模型，防止伪造
                // 任意 modelId 广播给周围玩家，导致其他客户端尝试加载不存在的模型。
                // 模型 ID 以 ResourceLocation path 段为准（可能带 domain，如
                // "ysmu:model" 或 "model"）；纹理 ID 需以该模型 ID 为前缀
                // （"model/texture" 或 "model/main"）。不合法则整体忽略本次设置。
                if (modelLoc != null && !isAllowedModel(modelLoc)) {
                    return;
                }
                if (textureLoc != null && modelLoc != null
                    && !isTextureOfModel(textureLoc, modelLoc)) {
                    return;
                }
                modelInfo.setModelAndTexture(modelLoc, textureLoc);
            }
        }

        /**
         * 校验模型 ID 是否存在于服务端模型白名单（OpenYSM 同步索引或 legacy 缓存）。
         * 忽略 domain 段：客户端可能发 "ysmu:model" 或 "model"，服务端 key 为
         * 内部 ID（不含 domain）。空 ID（回默认模型）视为合法。
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
