package com.fox.ysmu.client.texture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.ysmu;

public class OuterFileTexture extends AbstractTexture {

    /** Texture ResourceLocation, used for log messages. */
    private final ResourceLocation id;
    /**
     * Raw PNG bytes. Nulled by {@link #freeData()} once the GPU copy has been
     * uploaded, so that heap usage stays bounded on large model libraries.
     * Restored on demand by re-decrypting the encrypted client cache file
     * (see ClientModelManager#restoreTextureData).
     */
    private byte[] data;

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

    /** True after a decode/upload failed; suppresses repeated retries. */
    private boolean failed;

    public OuterFileTexture(ResourceLocation id, byte[] data) {
        this.id = id;
        this.data = data;
    }

    @Override
    public void loadTexture(IResourceManager resourceManager) throws IOException {
        // Upload is deferred to upload() (invoked from getGlTextureId() and
        // ClientModelManager.ensureTexturesLoaded). TextureManager.loadTexture
        // calls this at registration time; uploading here would push EVERY model's
        // textures into VRAM at once during sync — a huge spike for large model
        // libraries. Deferring means VRAM only grows for models actually rendered.
    }

    @Override
    public int getGlTextureId() {
        this.upload();
        return super.getGlTextureId();
    }

    /**
     * Decodes + uploads the pixel data to a valid GPU texture. No-op if already
     * uploaded, or if the in-memory bytes were freed (they must first be restored
     * via {@link #setData}, e.g. ClientModelManager#restoreTextureData).
     */
    public void upload() {
        if (this.uploaded || this.failed) {
            return;
        }
        if (this.data == null) {
            return; // bytes freed — restore via setData before re-upload
        }
        ByteArrayInputStream inputStream = new ByteArrayInputStream(this.data);
        try {
            BufferedImage bufferedImage;
            try {
                bufferedImage = ImageIO.read(inputStream);
            } finally {
                inputStream.close();
            }
            if (bufferedImage == null) {
                ysmu.LOG.warn("[YSMU-TEX] upload({}): ImageIO returned null for {} bytes", this.id, this.data.length);
                // Suppress repeated decode attempts until data is restored (setData/freeData).
                this.failed = true;
                return;
            }
            TextureUtil.uploadTextureImageAllocate(super.getGlTextureId(), bufferedImage, false, false);
            this.uploaded = true;
            this.failed = false;
        } catch (Exception e) {
            // Covers IOException (decode) and any GL/RuntimeException (upload).
            // Marked failed to avoid retrying a broken texture on every bind.
            ysmu.LOG.warn("[YSMU-TEX] upload({}): failed to upload: {}", this.id, e.getMessage());
            this.failed = true;
        }
    }

    /** Frees the GPU texture and marks this object as needing a re-upload. */
    public void freeGlTexture() {
        this.deleteGlTexture();
        this.uploaded = false;
    }

    /** Drops the in-memory byte[] copy; the encrypted client cache allows re-decrypt restore. */
    public void freeData() {
        this.data = null;
        this.failed = false;
    }

    /** Restores raw bytes (e.g. re-decrypted from the client cache) for a later upload. */
    public void setData(byte[] data) {
        this.data = data;
        this.failed = false;
    }

    /** Whether pixel data is currently uploaded to a valid GPU texture. */
    public boolean isUploaded() {
        return this.uploaded;
    }

    /** Whether raw bytes are currently held in the heap. */
    public boolean hasData() {
        return this.data != null;
    }
}
