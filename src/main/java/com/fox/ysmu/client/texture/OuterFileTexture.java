package com.fox.ysmu.client.texture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;

public class OuterFileTexture extends AbstractTexture {

    private final byte[] data;

    /**
     * Whether the pixel data has been uploaded to a valid GPU texture.
     * <p>
     * We track this explicitly instead of relying on {@code glTextureId != -1}:
     * {@link AbstractTexture#getGlTextureId()} lazily allocates a new GL texture
     * ID on first call, so merely checking {@code getGlTextureId() == -1} after
     * {@code deleteGlTexture()} would allocate a fresh (empty) ID and make the
     * texture look "valid" — skipping the re-upload and rendering white.
     */
    private volatile boolean uploaded;

    public OuterFileTexture(byte[] data) {
        this.data = data;
    }

    @Override
    public void loadTexture(IResourceManager resourceManager) throws IOException {
        // 1. 在上传新纹理前，先删除旧的OpenGL纹理ID（如果存在）
        this.deleteGlTexture();

        // 2. 将字节数组转换为输入流
        ByteArrayInputStream inputStream = new ByteArrayInputStream(this.data);
        BufferedImage bufferedImage;
        try {
            // 3. 使用 ImageIO 读取图像数据到 BufferedImage
            // 这是 1.7.10 中处理图像的标准方式，替代了 NativeImage
            bufferedImage = ImageIO.read(inputStream);            if (bufferedImage == null) {
                throw new IOException("ImageIO.read returned null for " + data.length + " bytes");
            }        } finally {
            // 确保输入流被关闭
            inputStream.close();
        }

        // 4. 使用 1.7.10 的 TextureUtil 将 BufferedImage 上传到GPU
        // a. getGlTextureId() 会在第一次调用时返回-1，TextureUtil会为其分配一个新的纹理ID
        // b. 这个方法处理了创建纹理、绑定、设置参数（如过滤）和上传像素数据的所有步骤
        boolean blur = false; // 是否使用模糊/线性过滤
        boolean clamp = false; // 是否使用边缘拉伸
        TextureUtil.uploadTextureImageAllocate(this.getGlTextureId(), bufferedImage, blur, clamp);
        this.uploaded = true;
    }

    /** Frees the GPU texture and marks this object as needing a re-upload. */
    public void freeGlTexture() {
        this.deleteGlTexture();
        this.uploaded = false;
    }

    /** Whether pixel data is currently uploaded to a valid GPU texture. */
    public boolean isUploaded() {
        return this.uploaded;
    }
}
