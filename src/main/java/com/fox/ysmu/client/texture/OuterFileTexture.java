package com.fox.ysmu.client.texture;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
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
    /** Timestamp of the last failure; retries are allowed after {@link #FAILED_RETRY_MS}. */
    private long failedAt;
    /** Cooldown before a failed upload is retried (avoids permanent white from transient errors). */
    private static final long FAILED_RETRY_MS = 2000L;

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
        if (this.uploaded) {
            return;
        }
        if (this.failed && System.currentTimeMillis() - this.failedAt < FAILED_RETRY_MS) {
            return;
        }
        this.failed = false; // cooldown elapsed — allow a retry
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
                this.failedAt = System.currentTimeMillis();
                return;
            }
            BufferedImage toUpload = downscale(bufferedImage);
            TextureUtil.uploadTextureImageAllocate(super.getGlTextureId(), toUpload, false, false);
            this.uploaded = true;
            this.failed = false;
        } catch (Exception e) {
            // Covers IOException (decode) and any GL/RuntimeException (upload).
            // Marked failed to avoid retrying a broken texture on every bind.
            ysmu.LOG.warn("[YSMU-TEX] upload({}): failed to upload: {}", this.id, e.getMessage());
            this.failed = true;
            this.failedAt = System.currentTimeMillis();
        }
    }

    /**
     * Optionally downscales a decoded texture to Config.TEXTURE_MAX_SIZE (0 = off)
     * to bound VRAM on large model libraries.
     *
     * <p>The downscale factor is always a power of two (1/2, 1/4, ...), so the
     * result lands in [maxSize, 2*maxSize) — e.g. target 512: 1024x1024 ->
     * 512x512, 768x768 stays, 1280x1280 -> 640x640. YSM textures are uploaded
     * with GL_NEAREST filtering, so a non-integer ratio would let bilinear
     * resampling bleed neighbor faces' pixels across face edges, which is very
     * visible in a pixel-art game. A power-of-two ratio maps each output pixel
     * 1:1 to a 2x2/4x4 source block, so edges stay clean under nearest sampling.
     * Never upscales small textures.
     *
     * <p>Why this is safe for UV mapping: GeckoLib normalizes UVs by the model's
     * declared texture_width/height, so every quad samples the image with [0,1]
     * relative coordinates. A uniform (aspect-preserving) resize keeps those
     * [0,1] coordinates pointing at the same content — only the resolution drops.
     * This holds even for non-standard models whose declared size differs from
     * the actual PNG dimensions; we only change resolution, never the mapping.
     */
    private BufferedImage downscale(BufferedImage src) {
        int maxSize = Config.TEXTURE_MAX_SIZE;
        if (maxSize <= 0) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int maxDim = Math.max(w, h);
        if (maxDim <= maxSize) {
            return src;
        }
        int divisor = 1;
        while (maxDim / divisor >= 2 * maxSize) {
            divisor <<= 1;
        }
        int newW = Math.max(1, w / divisor);
        int newH = Math.max(1, h / divisor);
        if (newW == w && newH == h) {
            return src;
        }
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, newW, newH, null);
        } finally {
            g.dispose();
        }
        if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE) {
            ysmu.LOG.info("[YSMU-TEX] upload({}): downscaled {}x{} -> {}x{} (max {}, divisor {})",
                this.id, w, h, newW, newH, maxSize, divisor);
        }
        return scaled;
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
