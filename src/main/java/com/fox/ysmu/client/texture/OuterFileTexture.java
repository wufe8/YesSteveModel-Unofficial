package com.fox.ysmu.client.texture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.ClientModelManager;
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

    /**
     * VRAM footprint (bytes) of the currently uploaded GPU texture (post-downscale
     * dimensions x 4). 0 when not uploaded. Kept in sync with
     * {@link ClientModelManager}'s running budget counter.
     */
    private volatile long gpuBytes;

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
        // Touch LRU on every access (bind or ensure) so actively-rendered textures
        // stay fresh: the VRAM-budget eviction skips anything used within the last
        // TEXTURE_PROTECT_MS, which prevents freeing a texture that will be bound
        // again next frame (upload -> evict -> re-upload thrash).
        ClientModelManager.touchTextureUsed(this.id);
        if (this.uploaded) {
            return;
        }
        if (this.failed && System.currentTimeMillis() - this.failedAt < FAILED_RETRY_MS) {
            return;
        }
        this.failed = false; // cooldown elapsed — allow a retry
        if (this.data == null) {
            // 字节已被懒卸载释放（Config.TEXTURE_RELEASE_BYTES_ON_IDLE）：从加密客户端
            // 缓存重解密恢复后再上传。让「不白模」成为纹理对象自身的硬约束——任何绑定路径
            // （含第一人称手这类不经过 ensureTexturesLoaded 的）都不会白模；失败时与解码
            // 失败同样节流重试。
            com.fox.ysmu.client.ClientModelManager.restoreTextureData(
                this,
                com.fox.ysmu.util.ModelIdUtil.getMainId(
                    com.fox.ysmu.util.ModelIdUtil.getModelIdFromSubId(this.id)),
                this.id);
            if (this.data == null) {
                // 恢复失败（缓存缺失/损坏）：节流，避免每帧重复解密整个模型文件。
                this.failed = true;
                this.failedAt = System.currentTimeMillis();
                return;
            }
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
            // Track the actual VRAM footprint (post-downscale dims) and enforce the
            // budget right after a real upload. Bytes stay in RAM, so eviction just
            // frees the GPU copy — re-upload is cheap and never white.
            long bytes = (long) toUpload.getWidth() * toUpload.getHeight() * 4L;
            ClientModelManager.onTextureGpuBytesChanged(bytes - this.gpuBytes);
            this.gpuBytes = bytes;
            ClientModelManager.enforceVramBudget();
        } catch (Exception e) {
            // Covers IOException (decode) and any GL/RuntimeException (upload).
            // Marked failed to avoid retrying a broken texture on every bind.
            ysmu.LOG.warn("[YSMU-TEX] upload({}): failed to upload: {}", this.id, e.getMessage());
            this.failed = true;
            this.failedAt = System.currentTimeMillis();
        }
    }

    /**
     * Optionally downscales a decoded texture to Config.TEXTURE_TARGET_SIZE
     * (0 = off) to bound VRAM on large model libraries.
     *
     * <p>The downscale factor is always a power of two (1/2, 1/4, ...), so the
     * result lands in [target, 2*target) — e.g. target 512: 1024x1024 ->
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
     *
     * <p>Downscale uses an exact box average (each output texel is the mean of
     * its divisor×divisor source block) instead of Graphics2D bilinear resizing.
     * Graphics2D.drawImage with BILINEAR applies pixel-center sampling, which
     * introduces a half-texel offset: the downscaled texel grid is no longer
     * aligned with the source, so GL_NEAREST sampling of the reduced texture
     * lands on neighbor pixels. That shows up as random "white/off-color"
     * patches on models with many small UV faces (e.g. GUMI2.6.2's 3×2-px faces)
     * while other models look fine. The box average keeps every output texel
     * exactly aligned to its source block, so nearest sampling is 1:1 correct.
     */
    private BufferedImage downscale(BufferedImage src) {
        int target = Config.TEXTURE_TARGET_SIZE;
        if (target <= 0) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int maxDim = Math.max(w, h);
        if (maxDim <= target) {
            return src;
        }
        int divisor = 1;
        while (maxDim / divisor >= 2 * target) {
            divisor <<= 1;
        }
        int newW = Math.max(1, w / divisor);
        int newH = Math.max(1, h / divisor);
        if (newW == w && newH == h) {
            return src;
        }
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        // Exact box average: output[ox,oy] = mean of src[ox*div..(ox+1)*div-1, oy*div..(oy+1)*div-1].
        // Accumulates in long/int to avoid precision drift, handles the partial
        // source block at the right/bottom edge (w or h not a multiple of divisor).
        //
        // Transparent pixels carry garbage RGB (blockbench textures typically use
        // (0,0,0,0)); naively averaging them into a block makes edge texels both
        // semi-transparent AND dark/muddy, which shows up as white/off-color
        // splotches on models with many small UV faces under GL_NEAREST. So RGB
        // is averaged only over OPAQUE source pixels (their real color), while
        // alpha is the opaque coverage ratio — edge texels stay the right color
        // and merely fade by coverage.
        int[] srcPixels = src.getRGB(0, 0, w, h, null, 0, w);
        int[] outPixels = new int[newW * newH];
        for (int oy = 0; oy < newH; oy++) {
            int y0 = oy * divisor;
            int y1 = Math.min(y0 + divisor, h);
            for (int ox = 0; ox < newW; ox++) {
                int x0 = ox * divisor;
                int x1 = Math.min(x0 + divisor, w);
                long r = 0, g = 0, b = 0, a = 0;
                int opaqueCount = 0;
                int totalCount = 0;
                for (int y = y0; y < y1; y++) {
                    int rowBase = y * w;
                    for (int x = x0; x < x1; x++) {
                        int argb = srcPixels[rowBase + x];
                        int alpha = (argb >>> 24) & 0xFF;
                        a += alpha;
                        if (alpha > 0) {
                            r += (argb >>> 16) & 0xFF;
                            g += (argb >>> 8) & 0xFF;
                            b += argb & 0xFF;
                            opaqueCount++;
                        }
                        totalCount++;
                    }
                }
                int outA = (int) (a / totalCount);
                int outR, outG, outB;
                if (opaqueCount > 0) {
                    outR = (int) (r / opaqueCount);
                    outG = (int) (g / opaqueCount);
                    outB = (int) (b / opaqueCount);
                } else {
                    outR = outG = outB = 0;
                }
                outPixels[oy * newW + ox] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
            }
        }
        scaled.setRGB(0, 0, newW, newH, outPixels, 0, newW);
        if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE) {
            ysmu.LOG.info("[YSMU-TEX] upload({}): downscaled {}x{} -> {}x{} (target {}, divisor {})",
                this.id, w, h, newW, newH, target, divisor);
        }
        return scaled;
    }

    /** Frees the GPU texture and marks this object as needing a re-upload. */
    public void freeGlTexture() {
        this.deleteGlTexture();
        this.uploaded = false;
        if (this.gpuBytes > 0) {
            ClientModelManager.onTextureGpuBytesChanged(-this.gpuBytes);
            this.gpuBytes = 0;
        }
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

    /** VRAM footprint (bytes) of the currently uploaded GPU texture; 0 if not uploaded. */
    public long getGpuBytes() {
        return this.gpuBytes;
    }

    /** Whether raw bytes are currently held in the heap. */
    public boolean hasData() {
        return this.data != null;
    }
}
