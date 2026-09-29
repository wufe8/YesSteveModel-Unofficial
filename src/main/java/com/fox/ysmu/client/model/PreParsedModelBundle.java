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

    /**
     * 解析开始时的应用代际（{@code ClientModelManager.currentApplyGeneration()}）。
     * 主线程应用前会比对当前代际：不等说明这份数据描述的同步索引已被替换
     * （/ysm reload、重连），必须整体丢弃 —— 否则会把已删除的模型复活，
     * 或把「模型 → 缓存文件」映射写到旧缓存目录/密钥上。
     */
    public long applyGeneration;

    /**
     * 该 bundle 是否来自客户端本地注册（{@code /ysmclient load}）。
     * 主线程 apply 时据此执行来源优先级：服务端已提供的同名模型不被本地覆盖。
     */
    public boolean localRegistration;

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
    /** True when geometry is lazy: sync only extracts light metadata (scale/extra/
     *  bone+cube stats); the heavy GeoModel object graph is built on first use via
     *  AssetManager.geo(geoId).get() → GeoModelProvider (same path as idle reload). */
    public boolean lazyGeometry;
    /** Optional raw model (OpenYSM sync path) whose extra-wheel / display / GUI-image
     *  data is registered inside {@code applyPreParsed} so each synced model consumes a
     *  single main-thread task instead of a separate scheduled frame per model. Null for
     *  the eager default-model path and legacy sync. */
    public RawYsmModel extraWheelRaw;
    public final Map<String, byte[]> controllerFiles = new LinkedHashMap<>();
    public final Map<String, String> molangMapping = new LinkedHashMap<>();
    public final Map<String, List<org.apache.commons.lang3.tuple.Pair<String, String>>> molangConditional = new LinkedHashMap<>();
    /** {@code ctrl.set_beginning_transition_length} / {@code ctrl.indicate_reload}
     *  提取出来的按动画名的提示（见 MolangFunctionParser.parseAnimationHints）。 */
    public final com.fox.ysmu.client.animation.molang.MolangFunctionParser.AnimationHints molangHints =
        new com.fox.ysmu.client.animation.molang.MolangFunctionParser.AnimationHints();
    /** {@code functions/<名字>.molang} 的函数体（名字小写），供 {@code fn.*} 调用。 */
    public final Map<String, String> molangFunctions = new LinkedHashMap<>();
    /** 事件订阅：{@code player_init}/{@code player_update} → 按文件顺序要执行的函数名。 */
    public final Map<String, List<String>> molangEventHandlers = new LinkedHashMap<>();
    /** 动画控制脚本：{@code @player_ctrl_<槽位>.molang} 的槽位名（小写）→ 正文。 */
    public final Map<String, String> molangControlScripts = new LinkedHashMap<>();

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
    /** Projectile animation files keyed by projectile animation ID (e.g. ysmu:<model>/projectile_#arrow). */
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
