package com.fox.ysmu.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.shader.Framebuffer;

import org.lwjgl.opengl.GL11;

/**
 * Reusable off-screen framebuffer cache for model previews.
 * <p>
 * Manages a {@link Framebuffer} with automatic resizing, dirty-flag
 * invalidation, and periodic refresh.  Handles the boilerplate of binding,
 * clearing, restoring the main framebuffer, and drawing the cached texture
 * as a GUI quad.
 * <p>
 * Used by {@code HudPreviewCache}, {@code ModelButton}, and
 * {@code TextureButton} — all three follow the same render-to-FBO-then-cache
 * pattern.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * FboCache cache = new FboCache();
 *
 * // Each frame:
 * int fbW = ..., fbH = ...;
 * if (cache.checkAndResize(fbW, fbH, refreshInterval)) {
 *     cache.bind();
 *     GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
 *     // ... set up projection matrix for the FBO ...
 *     // ... render model ...
 *     cache.unbind(Minecraft.getMinecraft());
 * }
 * cache.draw(x, y, w, h); // draw cached texture
 * }</pre>
 */
public class FboCache {

    private Framebuffer fbo;
    private boolean dirty = true;
    private int framesUntilRefresh = 0;
    private int lastRefreshInterval = -1;

    /** Check whether the cache needs to be re-rendered.
     *  Also handles FBO creation / resizing.  Call once per frame.
     *
     * @param fbWidth         desired FBO width in physical pixels
     * @param fbHeight        desired FBO height in physical pixels
     * @param refreshInterval frames between forced refreshes (0 = never, -1 = every frame)
     * @return {@code true} if the render should proceed (cache is dirty or FBO was resized)
     */
    public boolean checkAndResize(int fbWidth, int fbHeight, int refreshInterval) {
        if (fbWidth < 1) fbWidth = 1;
        if (fbHeight < 1) fbHeight = 1;

        if (refreshInterval != lastRefreshInterval) {
            framesUntilRefresh = 0;
            lastRefreshInterval = refreshInterval;
        }

        boolean timeToRefresh = refreshInterval > 0 && --framesUntilRefresh <= 0;
        boolean needRender = dirty || timeToRefresh;

        // Resize FBO if dimensions changed
        if (fbo == null || fbo.framebufferTextureWidth != fbWidth
            || fbo.framebufferTextureHeight != fbHeight) {
            if (fbo != null) fbo.deleteFramebuffer();
            fbo = new Framebuffer(fbWidth, fbHeight, true);
            fbo.setFramebufferColor(0.0F, 0.0F, 0.0F, 0.0F);
            needRender = true;
        }

        if (needRender) {
            dirty = false;
            framesUntilRefresh = refreshInterval;
        }
        return needRender;
    }

    /** Bind the off-screen FBO (viewports NOT set — caller must call
     *  {@code GL11.glViewport} explicitly if needed). */
    public void bind() {
        fbo.bindFramebuffer(false);
    }

    /** Restore the main (screen) framebuffer.  Call in {@code finally} block. */
    public void unbind(Minecraft mc) {
        mc.getFramebuffer().bindFramebuffer(true);
    }

    /** Draw the cached FBO texture as a GUI quad covering the given area.
     *  Resets depth test, lighting, colour-material, enables blending, and
     *  resets the texture matrix (GeckoLib may leave a transform). */
    public void draw(int x, int y, int w, int h) {
        if (fbo == null) return;
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_COLOR_MATERIAL);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        // Reset texture matrix — GeckoLib may leave a transform that would
        // distort the UV mapping and cause flickering/invisible textures.
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fbo.framebufferTexture);
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        tess.addVertexWithUV(x, y + h, 0.0, 0.0, 0.0);
        tess.addVertexWithUV(x + w, y + h, 0.0, 1.0, 0.0);
        tess.addVertexWithUV(x + w, y, 0.0, 1.0, 1.0);
        tess.addVertexWithUV(x, y, 0.0, 0.0, 1.0);
        tess.draw();
    }

    /** Force a re-render on the next frame. */
    public void invalidate() {
        dirty = true;
    }

    /** Release the FBO.  Call when the cache is no longer needed. */
    public void delete() {
        if (fbo != null) {
            fbo.deleteFramebuffer();
            fbo = null;
        }
    }

    /** The raw FBO texture ID, or 0 if no FBO has been created yet. */
    public int getTextureId() {
        return fbo != null ? fbo.framebufferTexture : 0;
    }
}
