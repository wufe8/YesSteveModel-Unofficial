package com.fox.ysmu.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;

import org.lwjgl.opengl.GL11;

import com.fox.ysmu.util.FboCache;
import com.fox.ysmu.util.RenderUtil;

/**
 * Caches the HUD player preview (selfie model) in an off-screen framebuffer
 * so that the full GeckoLib animation + render pipeline is only executed every
 * N frames instead of every frame.  The cached FBO texture is drawn as a
 * textured quad on intermediate frames, reducing the HOTBAR overlay cost from
 * ~37 % to a lightweight texture blit.
 *
 * <p>The cache uses a <b>screen-sized</b> FBO and delegates to
 * {@link RenderUtil#renderPlayerEntity} which expects GUI-scaled coordinates.
 * The projection matrix is left as-is (already set up by the HOTBAR overlay
 * event) — we only intercept the framebuffer target.
 *
 * <h3>Invalidation</h3>
 * Triggered when player yaw, held item, armour, or configured position/scale
 * changes, plus a periodic forced refresh whose interval is chosen adaptively
 * based on current frame rate (faster refresh at low FPS, slower at high FPS).
 */
public class HudPreviewCache {

    private static final float YAW_THRESHOLD_DEG = 3.0F;
    private final FboCache fboCache = new FboCache();
    private boolean needsUpdate = true;

    // Snapshot for change detection
    private double prevScale;
    private double prevYawOffset;
    private float prevInterpolatedYaw;
    private int prevItemHash;
    private int prevArmorHash;
    // Screen dimensions (pixels) when the FBO was last rendered
    private int prevScreenW;
    private int prevScreenH;

    // ── Adaptive refresh ──
    private long lastFrameTime;
    private float smoothFrameDeltaMs = 16.0F; // start at ~60fps

    /** Choose a refresh interval (frames) based on current frame time.
     *  At high FPS we can afford a longer cache; at low FPS we update more
     *  often because YSMU is not the bottleneck. */
    private int chooseRefreshInterval() {
        long now = Minecraft.getSystemTime();
        if (lastFrameTime != 0) {
            long rawDelta = now - lastFrameTime;
            if (rawDelta > 0 && rawDelta < 200) {
                smoothFrameDeltaMs = smoothFrameDeltaMs * 0.9F + rawDelta * 0.1F;
            }
        }
        lastFrameTime = now;
        // smoothFrameDeltaMs ≈ smoothed frame time in ms
        if (smoothFrameDeltaMs > 16.0F) return 2;   // below ~62.5 fps
        if (smoothFrameDeltaMs > 8.0F) return 4;   // 62.5-125 fps
        return 8;                                   // above 125 fps
    }

    public HudPreviewCache() {
        needsUpdate = true;
    }

    public void invalidate() {
        needsUpdate = true;
    }

    /**
     * Render the HUD player preview — either re-renders the model to the
     * off-screen FBO (cache miss) or draws the cached FBO texture (cache hit).
     * <p>
     * <b>Must be called from the HOTBAR overlay event</b> so that the GUI
     * projection matrix is already active.
     */
    public void render(EntityPlayer player, double posX, double posY,
                       double scale, double yawOffset, float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();

        // ── Cache disabled: render directly every frame (no optimisation) ──
        if (!com.fox.ysmu.Config.GUI_HUD_PREVIEW_CACHE) {
            RenderUtil.renderPlayerEntity(player, posX, posY, (float) scale, (float) yawOffset, -500, partialTicks);
            return;
        }

        // ── Detect state changes ──
        float interpolatedYaw = player.prevRotationYaw
            + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
        int itemHash = heldItemHash(player);
        int armorHash = armorHash(player);
        ScaledResolution res = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int screenW = mc.displayWidth;
        int screenH = mc.displayHeight;

        boolean resized = screenW != prevScreenW || screenH != prevScreenH;
        boolean yawChanged = Math.abs(interpolatedYaw - prevInterpolatedYaw) > YAW_THRESHOLD_DEG;
        boolean itemChanged = itemHash != prevItemHash;
        boolean armorChanged = armorHash != prevArmorHash;
        boolean configChanged = scale != prevScale || yawOffset != prevYawOffset;

        if (resized || yawChanged || itemChanged || armorChanged || configChanged) {
            needsUpdate = true;
        }

        // ── Re-render model to screen-sized FBO if cache is dirty ──
        boolean doRender = fboCache.checkAndResize(screenW, screenH, chooseRefreshInterval())
            || needsUpdate || resized;

        if (doRender) {
            needsUpdate = false;

            fboCache.bind();
            // Set viewport to full FBO size (bindFramebuffer(false) doesn't set it)
            GL11.glViewport(0, 0, screenW, screenH);
            // Clear with transparent black — the model is rendered on top.
            GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            // Delegate to the EXACT same rendering code that was used before —
            // RenderUtil.renderPlayerEntity sets up its own transforms (push/pop),
            // calls withGuiEntityLighting, and uses the existing GUI projection.
            // The only difference is that output goes to our FBO instead of the screen.
            RenderUtil.renderPlayerEntity(player, posX, posY, (float) scale, (float) yawOffset, -500, partialTicks);

            fboCache.unbind(mc);

            // Update snapshot
            prevScale = scale;
            prevYawOffset = yawOffset;
            prevInterpolatedYaw = interpolatedYaw;
            prevItemHash = itemHash;
            prevArmorHash = armorHash;
            prevScreenW = screenW;
            prevScreenH = screenH;
        }

        // ── Draw the cached FBO texture (full-screen blit) ──
        // The model was rendered at its original screen position inside the
        // FBO.  Draw the full FBO as a textured quad covering the entire
        // screen — only the model area is non-transparent (clear colour was
        // (0,0,0,0)).  All subsequent HUD elements render on top.
        //
        // Use GUI-scaled coordinates (the HOTBAR projection is in scaled units).
        // Save/restore GL state around the draw so fboCache.draw()'s state
        // changes (disables depth, lighting, etc.) don't bleed into subsequent
        // rendering (alt-Y preview panel, hotbar items).
        int guiW = res.getScaledWidth();
        int guiH = res.getScaledHeight();

        // FboCache.draw() always disables depth, lighting, colour-material and enables blend.
        // We know the expected post-state — no need to query via expensive glIsEnabled JNI calls.
        GL11.glDepthMask(false);
        fboCache.draw(0, 0, guiW, guiH);

        // Unconditional restore (avoids glIsEnabled which is ~1 % of this profile under LWJGL3)
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glDepthMask(true);
    }

    // ── helpers ──

    private static int heldItemHash(EntityPlayer player) {
        int h = 0;
        if (player.getHeldItem() != null) {
            h = player.getHeldItem().getItem().hashCode();
            h = 31 * h + player.getHeldItem().getItemDamage();
        }
        return h;
    }

    private static int armorHash(EntityPlayer player) {
        int h = 0;
        for (int i = 0; i < 4; i++) {
            if (player.inventory.armorInventory[i] != null) {
                h = 31 * h + player.inventory.armorInventory[i].getItem().hashCode();
                h = 31 * h + player.inventory.armorInventory[i].getItemDamage();
            } else {
                h = 31 * h;
            }
        }
        return h;
    }
}
