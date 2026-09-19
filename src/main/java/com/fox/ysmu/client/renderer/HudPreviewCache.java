package com.fox.ysmu.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

import org.lwjgl.opengl.GL11;

import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;
import com.fox.ysmu.util.FboCache;
import com.fox.ysmu.util.RenderUtil;

/**
 * Caches the HUD player preview (selfie model, the Alt+P one) in an off-screen
 * framebuffer so the full GeckoLib animation + render pipeline runs at a bounded
 * rate instead of on every frame; intermediate frames just blit the cached texture.
 *
 * <p>The cache uses a <b>screen-sized</b> FBO and delegates to
 * {@link RenderUtil#renderPlayerEntity} which expects GUI-scaled coordinates.
 * The projection matrix is left as-is (already set up by the HOTBAR overlay
 * event) — we only intercept the framebuffer target.
 *
 * <h3>When it re-renders</h3>
 * Two triggers only:
 * <ul>
 *   <li><b>Discrete changes</b> — held item, armour, configured position/scale,
 *       resolution, or an explicit {@link #invalidate()}. Applied immediately; they
 *       are rare and a delayed one reads as a glitch.</li>
 *   <li><b>The rate policy</b> (see the constants below) — everything else,
 *       including angle tracking. It used to be that any body-yaw change over
 *       0.001 deg forced an immediate re-render, which meant the preview re-baked on
 *       every frame while the player was turning, whatever the budget said.</li>
 * </ul>
 * A covering screen skips the preview entirely (see {@code ClientEventHandler}).
 *
 * <p>In first person this pass is the <b>only</b> one advancing the self player's
 * animation (vanilla does not render the local player in the world there), so the
 * rate gets a second, hard bound — see {@link #HUD_SOLE_DRIVER_TARGET_HZ} and
 * {@link #HUD_SOLE_DRIVER_MAX_SKIP_FRAMES}.
 */
public class HudPreviewCache {

    private final FboCache fboCache = new FboCache();
    private boolean needsUpdate = true;

    // Snapshot for change detection
    private double prevScale;
    private double prevYawOffset;
    private int prevItemHash;
    private int prevArmorHash;
    // Screen dimensions (pixels) when the FBO was last rendered
    private int prevScreenW;
    private int prevScreenH;

    // ── Adaptive refresh rate ───────────────────────────────────────────────
    //
    // The preview is refreshed on a *rate* policy, not a frame-count policy: the
    // rate is expressed in Hz and integrated with a wall-clock accumulator.
    //
    //   budgetMsPerSec : how much of every second the preview may cost  (15 %)
    //   budgetHz       = budgetMsPerSec / renderCostMs   (cost measured below)
    //   frameHz        = 1000 / frameDeltaMs
    //   targetHz       = clamp(min(budgetHz, frameHz, MAX_HZ), MIN_HZ, MAX_HZ)
    //
    // Why not a frame count (what this class used to do): "refresh every N frames"
    // silently couples the rate to the frame rate — the same N was ~60 Hz at 400 fps
    // and 3.75 Hz at 60 fps, so the *low*-fps case (exactly where the preview is
    // already the bottleneck) got the choppiest preview. In Hz the policy means the
    // same thing on every machine, and MIN_HZ keeps the preview readable when the
    // frame rate is low, at the cost of exceeding the budget there. That trade is
    // deliberate: a frozen preview is a visible regression, overspending is not.

    /** Share of wall-clock time the HUD preview may cost (15 % -> 150 ms per second). */
    private static final float HUD_BUDGET_MS_PER_SEC = 150.0F;
    /** Never refresh faster than this, however cheap the preview gets. */
    private static final float HUD_MAX_HZ = 120.0F;
    /** Never refresh slower than this, however expensive the preview gets — below
     *  roughly 10 Hz the "selfie" model reads as frozen rather than as animating. */
    private static final float HUD_MIN_HZ = 12.0F;
    /** Floor for the measured cost, only to keep the division finite. */
    private static final float HUD_MIN_RENDER_COST_MS = 0.1F;
    /** Ignore frame deltas above this (pause menu, chunk load, alt-tab). */
    private static final float HUD_MAX_FRAME_DELTA_MS = 200.0F;

    // ── Second bound: "this pass is the only thing driving the animation" ────
    //
    // The rate policy above answers "how much may the preview cost". In first person
    // it is also the *only* pass that advances the self player's animation, so the
    // policy alone answers the wrong question: with no upper bound it happily allows
    // 120 Hz, and before HUD_FRAME_RATIO existed the sole-driver branch baked on *every*
    // frame (at 1381 fps that is 1381 x 0.5 ms = 69 % of wall clock — measured, and the
    // reason standing still got slower). Two things keep it sane:
    //
    //   HUD_FRAME_RATIO        — the target may never exceed 3/4 of the frame rate.
    //   HUD_SOLE_DRIVER_...    — the pass may not be *starved* either: once the interval
    //                            exceeds the controller's own re-entry window it stops
    //                            being a throttle and becomes a bug (see below).

    /** Fraction of the frame rate the target refresh rate may use. 3/4 means "bake at
     *  most 3 frames out of 4", so one frame in four reuses the cached texture:
     *  90 Hz when the frame rate is locked to 120, 120 Hz (the general ceiling) from
     *  160 fps up. Chosen from measurement, not taste — see the class comment on the
     *  rate policy: an unlocked client at 1381 fps wants 120 Hz, a 240 fps lock wants
     *  120 Hz, and a 120 fps lock wants 90 Hz, which is what this gives. */
    private static final float HUD_FRAME_RATIO = 0.75F;
    /** Hardest allowed pass interval, in render frames, while this pass is the only
     *  animation pass — derived from the controller's own window rather than chosen
     *  (see {@link OpenYsmPlayerControllerRuntime#safePassWindowFrames()}).
     *  Package-private so {@code HudPreviewSoleDriverBoundTest} can pin the relation. */
    static final int HUD_SOLE_DRIVER_MAX_SKIP_FRAMES =
        OpenYsmPlayerControllerRuntime.safePassWindowFrames();

    /** Window over which the published {@link Stats} are averaged. */
    private static final long STAT_WINDOW_NANOS = 500_000_000L;

    private long lastFrameNanos;
    private float smoothFrameDeltaMs = 16.0F; // start at ~60fps
    /** Smoothed cost of one full preview re-render, in ms, measured around
     *  {@link RenderUtil#renderPlayerEntity}. First sample seeds it directly. */
    private float smoothRenderCostMs = 0.0F;
    /** Wall-clock credit towards the next periodic refresh. Owned here rather than
     *  handed to {@link FboCache#checkAndResize}: that method resets its own
     *  countdown whenever the interval <b>value</b> differs from the previous frame,
     *  which is fine for a fixed step but fatal for a measured one — a drifting
     *  estimate made the comparison differ every frame, so the countdown reset every
     *  frame and the cache re-rendered on 100 % of frames instead of ~50 %. Measured
     *  from a spark profile: re-renders per frame went 0.48 -> 1.01. */
    private double refreshAccumMs = 0.0D;

    /** Render frames since the preview last actually re-baked. Only the sole-driver
     *  branch reads it (as a hard bound, {@link #HUD_SOLE_DRIVER_MAX_SKIP_FRAMES}). */
    private int framesSinceBake;

    // ── Published stats ─────────────────────────────────────────────────────
    /** Written on the client thread once per {@link #STAT_WINDOW_NANOS}; read by the
     *  F3 debug lines. */
    private final Stats stats = new Stats();

    private long statWindowStartNanos;
    private int statWindowFrames;
    private int statWindowBakes;
    private double statWindowBakeMs;
    private double statWindowBlitMs;
    private int statWindowMaxGap;
    private boolean statWindowSoleDriver;
    private double statWindowTargetHzSum;

    /** Snapshot of the preview's measured refresh behaviour. {@link #bakeHz} is the
     *  FBO refresh rate — the rate the paperdoll animation is actually sampled at,
     *  i.e. its "effective frame rate". Negative fields mean "no sample yet". */
    public static final class Stats {

        /** False when {@code Config.GUI_HUD_PREVIEW_CACHE} is off (no FBO at all). */
        public boolean cacheEnabled = true;
        /** Bakes per second, averaged over the last window. */
        public float bakeHz = -1.0F;
        /** What the rate policy asked for over the same window. */
        public float targetHz = -1.0F;
        /** The frame rate the policy was working against. */
        public float frameHz = -1.0F;
        /** Bakes per rendered frame (1.0 = the cache never hits). */
        public float bakesPerFrame = -1.0F;
        /** Average cost of one bake (the full model pipeline). */
        public float bakeCostMs = -1.0F;
        /** Average cost of the cached-texture blit that runs every frame. */
        public float blitCostMs = -1.0F;
        /** Largest gap, in render frames, between two bakes inside the window. This is
         *  the number to compare against the controller's re-entry window. */
        public int maxGapFrames = -1;
        /** True while first person, where this pass is the only animation pass. */
        public boolean soleDriver;
    }

    /** Fed once per frame, whether or not the model is re-rendered this frame.
     *  Returns this frame's delta in ms (clamped), and updates the frame-rate EMA.
     *
     *  <p>Uses {@link System#nanoTime()} rather than {@code Minecraft.getSystemTime()}:
     *  the latter is integer milliseconds, and at 300-500 fps a frame is 2-3 ms and a
     *  preview re-render is under 1 ms, so millisecond quantisation would both bias
     *  the cost estimate low (many samples round to 0) and make the frame delta
     *  uselessly coarse. */
    private float trackFrameTime() {
        long now = System.nanoTime();
        float dtMs = lastFrameNanos == 0 ? 0.0F : (now - lastFrameNanos) / 1.0e6F;
        lastFrameNanos = now;
        if (dtMs > 0.0F && dtMs < HUD_MAX_FRAME_DELTA_MS) {
            smoothFrameDeltaMs = smoothFrameDeltaMs * 0.9F + dtMs * 0.1F;
        }
        return Math.min(dtMs, HUD_MAX_FRAME_DELTA_MS);
    }

    /** The frame rate the rate policy sizes itself against, in Hz. */
    private float currentFrameHz() {
        float frameMs = (smoothFrameDeltaMs > 0.0F && !Float.isInfinite(smoothFrameDeltaMs))
            ? smoothFrameDeltaMs
            : 16.0F;
        return 1000.0F / Math.max(frameMs, 0.5F);
    }

    /** The refresh rate this preview is allowed to run at, in Hz.
     *
     *  @param soleDriver true in first person, where this pass is the only one
     *                    advancing the self player's animation: the rate then also gets
     *                    a *lower* bound (the re-entry window). That bound is expressed
     *                    in frames and re-checked every frame in {@link #render}; the
     *                    term here only keeps the displayed target honest. */
    private float targetRefreshHz(boolean soleDriver) {
        if (!(smoothRenderCostMs > 0.0F) || Float.isInfinite(smoothRenderCostMs)) {
            // Never measured (or a degenerate sample): assume the preview is
            // expensive rather than free, so a bad estimate cannot pin the rate high.
            return HUD_MIN_HZ;
        }
        float costMs = Math.max(smoothRenderCostMs, HUD_MIN_RENDER_COST_MS);
        float budgetHz = HUD_BUDGET_MS_PER_SEC / costMs;
        float frameHz = currentFrameHz();
        float hz = Math.min(budgetHz, Math.min(frameHz * HUD_FRAME_RATIO, HUD_MAX_HZ));
        if (soleDriver) {
            // Never target an interval wider than the controller's re-entry window: past
            // it the controller reads the throttle as "model switched away and back" and
            // restarts every animation from tick 0.
            hz = Math.max(hz, frameHz / HUD_SOLE_DRIVER_MAX_SKIP_FRAMES);
        }
        if (!(hz > 0.0F)) {
            return HUD_MIN_HZ;
        }
        return Math.max(hz, HUD_MIN_HZ);
    }

    /** Advance the refresh clock by one frame; true when the periodic refresh is due.
     *
     *  <p>A time accumulator rather than a frame countdown, so the *average* rate is
     *  exactly {@code targetHz} even when frame times jitter. Credit is dropped when a
     *  single frame covers several intervals (pause menu, chunk load, the model screen
     *  closing) instead of being paid back as a catch-up burst. */
    private boolean refreshDue(float dtMs, float targetHz) {
        refreshAccumMs += dtMs;
        double intervalMs = 1000.0D / targetHz;
        if (refreshAccumMs < intervalMs) {
            return false;
        }
        refreshAccumMs -= intervalMs;
        if (refreshAccumMs >= intervalMs) {
            refreshAccumMs %= intervalMs;
        }
        return true;
    }

    /** Fold one frame into the published statistics, and republish once per window. */
    private void publishStats(boolean baked, float bakeMs, double blitMs, int gapFrames,
                              boolean soleDriver, float targetHz) {
        long now = System.nanoTime();
        if (statWindowStartNanos == 0L) {
            statWindowStartNanos = now;
        }
        statWindowFrames++;
        if (baked) {
            statWindowBakes++;
        }
        statWindowBakeMs += bakeMs;
        statWindowBlitMs += blitMs;
        statWindowMaxGap = Math.max(statWindowMaxGap, gapFrames);
        statWindowSoleDriver = soleDriver;
        statWindowTargetHzSum += targetHz;

        long elapsed = now - statWindowStartNanos;
        if (elapsed < STAT_WINDOW_NANOS) {
            return;
        }
        float seconds = elapsed / 1.0e9F;
        stats.cacheEnabled = true;
        stats.bakeHz = statWindowBakes / seconds;
        stats.targetHz = statWindowFrames > 0
            ? (float) (statWindowTargetHzSum / statWindowFrames)
            : -1.0F;
        stats.frameHz = currentFrameHz();
        stats.bakesPerFrame = statWindowBakes / (float) statWindowFrames;
        // Per-bake cost, not per-frame: baking happens on a minority of frames, so
        // dividing by the frame count would report the bake cost scaled by the hit rate.
        stats.bakeCostMs = statWindowBakes > 0
            ? (float) (statWindowBakeMs / statWindowBakes)
            : 0.0F;
        stats.blitCostMs = statWindowFrames > 0
            ? (float) (statWindowBlitMs / statWindowFrames)
            : 0.0F;
        stats.maxGapFrames = statWindowMaxGap;
        stats.soleDriver = statWindowSoleDriver;

        statWindowStartNanos = now;
        statWindowFrames = 0;
        statWindowBakes = 0;
        statWindowBakeMs = 0.0D;
        statWindowBlitMs = 0.0D;
        statWindowMaxGap = 0;
        statWindowTargetHzSum = 0.0D;
    }

    /** Latest measured refresh behaviour — for the F3 debug lines. */
    public Stats stats() {
        return stats;
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
            stats.cacheEnabled = false;
            RenderUtil.renderPlayerEntity(player, posX, posY, (float) scale, (float) yawOffset, -500, partialTicks);
            return;
        }
        stats.cacheEnabled = true;

        // ── Poll the follow-mode smoother ──
        // This MUST run once per frame whatever we decide below: it advances the
        // time-based low-pass that renderPlayerEntity consumes via lastPolledFollow
        // (see RenderUtil.pollHudDisplayBodyYaw). Its *value* is no longer used for
        // invalidation — angle tracking goes through the rate policy like everything
        // else, so that turning cannot pin the preview at the full frame rate.
        RenderUtil.pollHudDisplayBodyYaw(player, partialTicks);

        int itemHash = heldItemHash(player);
        int armorHash = armorHash(player);
        ScaledResolution res = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int screenW = mc.displayWidth;
        int screenH = mc.displayHeight;

        // Discrete changes: applied immediately. They happen rarely and a delayed
        // held-item / armour / scale update reads as a glitch rather than as lag.
        boolean discreteDirty = screenW != prevScreenW || screenH != prevScreenH
            || itemHash != prevItemHash || armorHash != prevArmorHash
            || scale != prevScale || yawOffset != prevYawOffset;

        // ── Where the rate policy gets a second bound ──
        // In first person vanilla does not render the local player in the world at all
        // (`RenderGlobal.renderEntities` skips `entity == renderViewEntity` unless
        // `thirdPersonView != 0` and the player is not sleeping). So this render is the
        // ONLY pass that advances the self player's animation and controller state
        // machine, which cuts both ways:
        //
        //  * Throttling it saves nothing on a *visible* model — the model is drawn here
        //    and nowhere else, so a lower rate is a lower animation sampling rate, not
        //    a removed duplicate. Hence HUD_SOLE_DRIVER_TARGET_HZ.
        //  * Worse, the controller runtime counts passes
        //    (`OpenYsmPlayerControllerRuntime.isReEntry`: parked for more than 10 render
        //    frames means the model was switched away and back) — so a rate-limited pass
        //    is read as a re-entry, `setAnimation` re-arms, and every animation restarts
        //    from tick 0: walk and swing look frozen on their first frame, and every
        //    animation state change twitches. Measured in game, and the exact symptom
        //    ControllerReEntryFrameTest describes from the opposite direction.
        //
        // A pass that is NOT the sole driver (third person: the world render; the HUD
        // config screen: its own preview) refreshes `lastActiveFrame` for us, so there
        // the plain rate policy applies. Note this is tested on thirdPersonView alone,
        // not on `currentScreen == null`: an inventory or the roulette screen does not
        // render the self model either, and that is precisely when the paperdoll is the
        // one thing still animating.
        boolean soleAnimationDriver = mc.gameSettings.thirdPersonView == 0;

        float dtMs = trackFrameTime();
        float targetHz = targetRefreshHz(soleAnimationDriver);
        boolean rateDue = refreshDue(dtMs, targetHz);

        // Hard bound: never let the pass interval reach the controller's re-entry window.
        // A frame count, not a rate — the window is counted in render frames, so this
        // holds regardless of what the frame time does inside the interval.
        framesSinceBake++;
        boolean reEntryDeadline = soleAnimationDriver
            && framesSinceBake >= HUD_SOLE_DRIVER_MAX_SKIP_FRAMES;
        boolean periodicDue = rateDue || reEntryDeadline;

        // Interval 0 => FboCache never forces a refresh on its own; the periodic
        // refresh is driven by refreshDue() above.
        boolean doRender = fboCache.checkAndResize(screenW, screenH, 0)
            || needsUpdate || discreteDirty || periodicDue;

        int gapFrames = framesSinceBake;
        float bakeCostMs = 0.0F;
        if (doRender) {
            framesSinceBake = 0;
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
            long renderStart = System.nanoTime();
            RenderUtil.renderPlayerEntity(player, posX, posY, (float) scale, (float) yawOffset, -500, partialTicks);
            bakeCostMs = (System.nanoTime() - renderStart) / 1.0e6F;
            // First sample seeds the estimate; afterwards a 0.7/0.3 EMA.
            smoothRenderCostMs = smoothRenderCostMs <= 0.0F
                ? bakeCostMs
                : smoothRenderCostMs * 0.7F + bakeCostMs * 0.3F;

            fboCache.unbind(mc);

            // Update snapshot
            prevScale = scale;
            prevYawOffset = yawOffset;
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

        // FboCache.draw() disables depth/lighting/colour-material, enables blend,
        // resets the texture matrix and binds the FBO texture. Save the pre-draw
        // state and restore it EXACTLY afterwards: an unconditional "enable" left
        // GL_LIGHTING on, which darkens the vanilla hotbar background (GUI quads
        // get lighting-modulated by the fixed pipeline), and GL_BLEND on for the
        // rest of the HUD frame (regression since 1.9a1-05 — "hotbar stays darker
        // whenever YSMU is loaded").
        boolean depthWasOn = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean lightWasOn = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean colorMatWasOn = GL11.glIsEnabled(GL11.GL_COLOR_MATERIAL);
        boolean blendWasOn = GL11.glIsEnabled(GL11.GL_BLEND);

        GL11.glDepthMask(false);
        long blitStart = System.nanoTime();
        fboCache.draw(0, 0, guiW, guiH);
        double blitCostMs = (System.nanoTime() - blitStart) / 1.0e6D;

        // Restore GL state exactly as it was before the draw
        if (depthWasOn) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
        if (lightWasOn) GL11.glEnable(GL11.GL_LIGHTING); else GL11.glDisable(GL11.GL_LIGHTING);
        if (colorMatWasOn) GL11.glEnable(GL11.GL_COLOR_MATERIAL); else GL11.glDisable(GL11.GL_COLOR_MATERIAL);
        if (!blendWasOn) GL11.glDisable(GL11.GL_BLEND);
        GL11.glDepthMask(true);

        publishStats(doRender, bakeCostMs, blitCostMs, gapFrames, soleAnimationDriver, targetHz);
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
