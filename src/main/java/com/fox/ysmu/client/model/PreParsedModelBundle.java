package com.fox.ysmu.client.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /** Set of animation names whose keyframe Molang expressions reference ctrl.tac_* variables.
     *  Populated during parseAnimationsToBundle() by scanning raw animation JSON bytes,
     *  consumed by applyPreParsed() to expand TacZ dependency detection in controllers. */
    public final java.util.Set<String> tacAnimNames = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PreParsedModelBundle(ResourceLocation modelId) {
        this.modelId = modelId;
    }
}
