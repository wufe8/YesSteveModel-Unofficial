package com.fox.ysmu.client.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fox.ysmu.model.resource.pojo.RawYsmModel;

import net.minecraft.util.ResourceLocation;

import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.geo.raw.pojo.ExtraInfo;
import software.bernie.geckolib3.geo.render.built.GeoModel;

/**
 * Holds the results of model parsing that was done on a background thread.
 * The main thread only needs to apply these to GeckoLib caches and load textures (OpenGL).
 */
public class PreParsedModelBundle {
    public final ResourceLocation modelId;

    // Geometry data (parsed from JSON on background thread)
    public final Map<ResourceLocation, GeoModel> geoModels = new LinkedHashMap<>();
    public final Map<ResourceLocation, it.unimi.dsi.fastutil.Pair<Double, Double>> scaleInfo = new LinkedHashMap<>();
    public final Map<ResourceLocation, ExtraInfo> extraInfo = new LinkedHashMap<>();
    public final Map<ResourceLocation, String[]> extraAnimationNames = new LinkedHashMap<>();

    // Animation data (parsed on background thread)
    public AnimationFile animationFile = new AnimationFile();
    /** Animation names (light, always extracted eagerly). Needed for condition
     *  classification, model stats, and the GUI animation list even when the heavy
     *  AnimationFile (KeyFrame graph) is deferred to first use (lazy animation). */
    public final java.util.Set<String> animationNames = new java.util.LinkedHashSet<>();
    /** True when this bundle is in lazy-animation mode: the AnimationFile is NOT
     *  registered at sync; the first AssetManager.anim(mainId).get() triggers a
     *  background decrypt+parse that restores it (same path as idle reload). */
    public boolean lazyAnimation;
    /** Optional raw model (OpenYSM sync path) whose extra-wheel / display / GUI-image
     *  data is registered inside {@code applyPreParsed} so each synced model consumes a
     *  single main-thread task instead of a separate scheduled frame per model. Null for
     *  the eager default-model path and legacy sync. */
    public RawYsmModel extraWheelRaw;
    public final Map<String, byte[]> controllerFiles = new LinkedHashMap<>();
    public final Map<String, String> molangMapping = new LinkedHashMap<>();
    public final Map<String, List<org.apache.commons.lang3.tuple.Pair<String, String>>> molangConditional = new LinkedHashMap<>();

    // Texture data (for main-thread OpenGL upload)
    public final Map<ResourceLocation, byte[]> texturesToRegister = new LinkedHashMap<>();
    public final Map<ResourceLocation, byte[]> projTexturesToRegister = new LinkedHashMap<>();
    public final List<ResourceLocation> textureIdList = new ArrayList<>();

    // Model stats
    public int totalBones;
    public int totalCubes;
    public int totalAnims;

    /** Preview animation name from ysm.json (e.g. "gui"). Set by
     *  parseAndRegisterModel() before scheduleApply(), consumed by
     *  applyPreParsed() to populate PREVIEW_ANIMATION synchronously,
     *  eliminating the race between async registerExtraWheel() and
     *  the first ModelButton FBO render. */
    public String previewAnimation = "";

    // Projectile registration tracking
    public final Map<ResourceLocation, List<String>> projectileModelIds = new LinkedHashMap<>();
    public final Map<ResourceLocation, List<ResourceLocation>> projectileTextureIds = new LinkedHashMap<>();
    /** Projectile animation files keyed by projectile animation ID (e.g. ysmu:mingf/projectile_#arrow). */
    public final Map<ResourceLocation, AnimationFile> projAnimationFiles = new LinkedHashMap<>();
    /** Projectile controller file bytes keyed by projectile animation ID. */
    public final Map<ResourceLocation, byte[]> projControllerFiles = new LinkedHashMap<>();
    /** Map of animation name → set of modIds whose detection patterns match the
     *  animation's keyframe Molang expressions. Populated during
     *  parseAnimationsToBundle() by scanning raw animation JSON bytes against
     *  all registered {@link com.fox.ysmu.client.animation.controller.ModDependency}s,
     *  consumed by applyPreParsed() to expand mod dependency detection in controllers. */
    public final java.util.Map<String, java.util.Set<String>> animToModIds = new java.util.LinkedHashMap<>();

    public PreParsedModelBundle(ResourceLocation modelId) {
        this.modelId = modelId;
    }
}
