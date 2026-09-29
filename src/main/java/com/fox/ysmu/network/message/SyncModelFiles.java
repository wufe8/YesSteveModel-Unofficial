package com.fox.ysmu.network.message;

import static com.fox.ysmu.model.ServerModelManager.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import net.minecraft.entity.player.EntityPlayerMP;

import org.apache.commons.io.FileUtils;

import com.fox.ysmu.Config;
import com.fox.ysmu.data.EncryptTools;
import com.fox.ysmu.model.format.ServerModelInfo;
import com.fox.ysmu.network.NetworkHandler;
import com.fox.ysmu.util.ThreadTools;
import com.fox.ysmu.util.UuidUtils;
import com.fox.ysmu.ysmu;
import com.google.common.collect.Lists;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class SyncModelFiles implements IMessage {

    private static final int LEGACY_DIRECT_SEND_LIMIT = 1900 * 1024;
    /**
     * 客户端一次性上报的缓存 MD5 数量上限。恶意客户端可伪造巨大 count
     * 触发 {@code new String[count]} 分配超大数组造成内存 DoS，因此必须在
     * 反序列化入口硬限制（服务端模型数量远小于该值，正常玩家不会触顶）。
     */
    private static final int MAX_MODEL_COUNT = 100000;

    private String[] md5Info;

    public SyncModelFiles() {}

    public SyncModelFiles(String[] md5Info) {
        this.md5Info = md5Info;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int count = buf.readInt();
        if (count < 0 || count > MAX_MODEL_COUNT) {
            throw new IllegalArgumentException("Invalid YSM sync model count: " + count);
        }
        this.md5Info = new String[count];
        for (int i = 0; i < count; i++) {
            this.md5Info[i] = ByteBufUtils.readUTF8String(buf);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.md5Info.length);
        for (String md5 : this.md5Info) {
            ByteBufUtils.writeUTF8String(buf, md5);
        }
    }

    public static class Handler implements IMessageHandler<SyncModelFiles, IMessage> {

        @Override
        public IMessage onMessage(SyncModelFiles message, MessageContext ctx) {
            EntityPlayerMP sender = ctx.getServerHandler().playerEntity;
            if (sender != null) {
                // Model sync starts here after RequestSyncModel asks the client for its cached MD5 names.
                sendPassword(sender);
                sendModelFiles(message.md5Info, sender);
            }
            return null;
        }

        private void sendModelFiles(String[] md5Info, EntityPlayerMP sender) {
            Collection<String> cache = CACHE_NAME_INFO.values()
                .stream()
                .map(ServerModelInfo::getMd5)
                .collect(Collectors.toList());
            List<String> output = Lists.newArrayList(cache);
            for (String md5 : md5Info) {
                if (cache.contains(md5)) {
                    output.remove(md5);
                    NetworkHandler.sendToClientPlayer(new RequestLoadModel(md5), sender);
                }
            }
            for (String md5 : output) {
                File modelFile = CACHE_SERVER.resolve(md5)
                    .toFile();
                ThreadTools.THREAD_POOL.submit(() -> sendModelFile(md5, modelFile, sender));
            }
        }

        private void sendModelFile(String md5, File modelFile, EntityPlayerMP sender) {
            try {
                long fileLength = modelFile.length();
                if (fileLength > Integer.MAX_VALUE) {
                    ysmu.LOG.warn(
                        "Skipping YSM legacy model cache file {} because it is too large: {} bytes",
                        md5,
                        fileLength);
                    return;
                }
                if (fileLength <= LEGACY_DIRECT_SEND_LIMIT) {
                    NetworkHandler.sendToClientPlayer(
                        new SendModelFile(FileUtils.readFileToByteArray(modelFile)),
                        sender);
                    return;
                }

                if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_SYNC) {
                    ysmu.LOG.info(
                        "Sending large YSM legacy model cache {} in chunks: size={} bytes, chunk={} bytes",
                        md5,
                        fileLength,
                        SendModelFileChunk.MAX_CHUNK_BYTES);
                }
                int offset = 0;
                byte[] buffer = new byte[SendModelFileChunk.MAX_CHUNK_BYTES];
                try (InputStream input = FileUtils.openInputStream(modelFile)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (read == 0) {
                            continue;
                        }
                        byte[] chunk = Arrays.copyOf(buffer, read);
                        NetworkHandler.sendToClientPlayer(
                            new SendModelFileChunk(md5, (int) fileLength, offset, chunk),
                            sender);
                        offset += read;
                    }
                }
            } catch (IOException e) {
                ysmu.LOG.warn("Failed to read YSM server model cache file " + md5, e);
            }
        }

        private void sendPassword(EntityPlayerMP sender) {
            try {
                byte[] password = FileUtils.readFileToByteArray(PASSWORD_FILE.toFile());
                UUID playerId = sender.getUniqueID();
                byte[] uuid = UuidUtils.asBytes(playerId);
                byte[] output = EncryptTools.encryptPassword(uuid, password);
                // 客户端必须先收到密码才能解密随后的 RequestLoadModel 缓存文件。
                // 原先这里把它 submit 到 THREAD_POOL（与其他模型文件发送同池），而
                // RequestLoadModel 是在 sendModelFiles 里**同步**发出的：池里排在前面的
                // 大文件先把线程占满时，密码包就会晚于加载请求到达，客户端只能空等。
                // 密码文件很小，直接在本线程（Netty handler）发出的代价可以忽略，
                // 换来的是「密码先于加载请求」这条顺序不再依赖线程池调度。
                NetworkHandler.sendToClientPlayer(new SendModelPassword(playerId, output), sender);
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to send YSM model sync password to " + sender.getCommandSenderName(), e);
            }
        }
    }
}
