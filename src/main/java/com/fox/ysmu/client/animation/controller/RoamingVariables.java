package com.fox.ysmu.client.animation.controller;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime;

/**
 * Roaming values, model declarations and derived-name caches share one lifecycle here.
 * Rendering calls the frame-cache invalidation hook; model reload clears declarations and
 * derived names, while user reset clears values without dropping model defaults.
 * Legacy public maps remain exposed through the controller runtime for compatibility.
 */
public final class RoamingVariables {

    private RoamingVariables() {}

    /** Roaming variables set from outside the render loop (e.g. GUI config panel).
     *  Key is the variable name WITHOUT the "v." prefix (e.g. "roaming.ef").
     *  NOTE: This is a global flat map shared across all models.  To prevent
     *  cross-model contamination, use {@link #getRoamingVarsForModel(ResourceLocation)}
     *  instead of iterating this map directly. */
    public static final Map<String, Double> PENDING_ROAMING = new ConcurrentHashMap<>();
    /** Tracks which PENDING_ROAMING keys were explicitly set by user interaction
     *  (not just default-initialized). Used by the ?? operator to distinguish
     *  "user set to 0" from "never set (defaults to 0)".
     *  NOTE: 全局集合仅用于"无模型上下文"写入的兜底（见 markRoamingExplicit）。
     *  正常路径应使用 {@link #markRoamingExplicit}/{@link #isRoamingExplicit}
     *  按模型维度读写，避免模型 A 的显式设置泄漏到模型 B。 */
    public static final java.util.Set<String> EXPLICIT_ROAMING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 显式设置的 roaming 变量按模型隔离：modelId → set of varName（不含 v. 前缀）。
     * 用户通过轮盘 GUI 或在控制器 onEntry/onExit 表达式中设置 v.roaming.* 时，
     * 只标记该变量属于当前操作的模型，避免同名但含义不同的自定义变量跨模型串值。
     */
    private static final Map<ResourceLocation, java.util.Set<String>> EXPLICIT_ROAMING_BY_MODEL =
        new ConcurrentHashMap<>();

    /**
     * 标记某模型的 roaming 变量为"用户显式设置"。
     *
     * <p>{@code modelId == null}（写入方拿不到模型上下文）时**不再**把名字加进全局集合：
     * {@link #isRoamingExplicit} 先查全局集合，所以那样会让一个模型的设置对**所有**模型表现为
     * "用户显式设置"（跨模型串值的经典入口）。只有真正全局的名字
     * （{@link #isGlobalRoamingName}：轮盘锁/轮盘动画）才退化成全局标记。</p>
     */
    public static void markRoamingExplicit(ResourceLocation modelId, String varName) {
        if (varName == null) {
            return;
        }
        if (modelId != null) {
            EXPLICIT_ROAMING_BY_MODEL.computeIfAbsent(modelId,
                k -> java.util.Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(varName);
        } else if (isGlobalRoamingName(varName)) {
            EXPLICIT_ROAMING.add(varName);
        }
    }

    /**
     * 常驻变量（{@code v.roaming.*}）的动画侧写回：把动画 / 时间轴 / 关键帧 Molang 写下的值
     * 记为"该模型下已被设置"，否则下一帧 {@link MolangPhysicsRuntime#begin} 注入 ysm.json 里的
     * 默认值时会把它冲掉。
     *
     * <p>wiki 语义依据（{@code /wiki/molang/var/}）：{@code variable.roaming.} 是"常驻变量"，
     * 与实体变量一样**赋值后一直保持**，只有再次赋值才改变；默认值只用于"从未赋过值"的 null
     * 初值，绝不是每帧复位。实测案例：某模型轮盘"变身"的时间轴写
     * {@code v.roaming.a=1-v.roaming.b; v.roaming.b=v.roaming.a;}，而 {@code v.roaming.b}
     * 同时是模型自定义配置里的复选框变量（默认 0）。默认值每帧回写 → 时间轴写下的 1 在下一帧
     * 被冲成 0 → 第二次"变身"算出的目标状态与第一次相同 → 变身只能生效一次，之后无法切回。</p>
     *
     * <p>写入的仍是 {@link #PENDING_ROAMING}（全局平坦表，与轮盘 GUI 同一个存储），因此轮盘
     * 配置界面读到的就是动画刚写下的状态；跨模型隔离由 {@code EXPLICIT_ROAMING_BY_MODEL}
     * 提供（该标记只对这个模型生效）。该存储没有玩家维度：多人下某玩家动画写下的常驻变量
     * 会对同模型的其他人可见 —— 与轮盘 GUI 写入的现状一致，属既有设计的限制。</p>
     *
     * @param varName 变量名，可带 {@code v.} / {@code variable.} 前缀
     */
    public static void noteRoamingWrite(ResourceLocation modelId, String varName, double value) {
        if (varName == null) {
            return;
        }
        RoamingName info = roamingName(varName);
        if (!info.isRoamingName()) {
            // 只有常驻变量会被默认值每帧回写；其它 v.* 没有这条写回，不必记录。
            return;
        }
        String name = info.container;
        PENDING_ROAMING.put(name, value);
        markRoamingExplicit(modelId, name);
        invalidateFrameRoamingCache();
    }

    /** 全局轮盘变量：不属于任何模型，对所有模型生效（轮盘锁 / 轮盘动画）。 */
    private static boolean isGlobalRoamingName(String varName) {
        return "lock_wheel".equals(varName) || "wheel_anim".equals(varName);
    }

    /**
     * 这个名字是否属于该模型的常驻变量族：模型声明过（{@code ysm.json} 的 config_forms）、
     * 被本模型显式设置过，或者是全局轮盘变量。
     *
     * <p>控制器路径最后一级"全局 {@code PENDING_ROAMING} 回退读"必须先过这一关：那张表是
     * 跨模型共享的扁平表，不加判断时模型 A 在轮盘里设的值会漏给模型 B（B 在控制器条件里读
     * {@code v.x} 就拿到 A 的值）。关键帧路径对同类残留（{@code GLOBAL_VAR_OWNER} 来源不是
     * 当前模型）是返回 0 的，两条路径的口径必须一致。</p>
     *
     * <p>{@code x} 与 {@code roaming.x} 两种写法都认：模型声明时用哪种写就存哪种
     * （`config_forms` 的 {@code value} 通常是 {@code v.roaming.x}，但也有写成 {@code v.x} 的）。</p>
     */
    public static boolean isRoamingNameForModel(ResourceLocation modelId, String varName) {
        if (varName == null) {
            return false;
        }
        if (isGlobalRoamingName(varName)) {
            return true;
        }
        if (modelId == null) {
            return false;
        }
        if (isRoamingExplicit(modelId, varName)) {
            return true;
        }
        java.util.Set<String> declared = MODEL_ROAMING_VARS.get(modelId);
        if (declared == null) {
            return false;
        }
        if (declared.contains(varName)) {
            return true;
        }
        RoamingName info = roamingName(varName);
        return info.plain != null ? declared.contains(info.plain) : declared.contains(info.prefixed);
    }

    /** 判断某变量是否在指定模型上被显式设置。全局标记（无模型上下文写入）对所有模型生效。 */
    public static boolean isRoamingExplicit(ResourceLocation modelId, String varName) {
        if (EXPLICIT_ROAMING.contains(varName)) {
            return true;
        }
        if (modelId != null) {
            java.util.Set<String> set = EXPLICIT_ROAMING_BY_MODEL.get(modelId);
            return set != null && set.contains(varName);
        }
        return false;
    }

    /** The prefix that marks a roaming variable in a model's declared names. */
    private static final String ROAMING_PREFIX = "roaming.";

    /** varName -> (keyPrefix -> the fully-qualified keys to write). See
     *  {@link #injectRoamingVar}. */
    private static final Map<String, Map<String, String[]>> ROAMING_KEYS = new ConcurrentHashMap<>();

    /** 由 varName 派生出来的全部字符串，按 varName 记忆化。
     *
     *  <p>这些派生（剥 {@code roaming.} 前缀、{@code toLowerCase}、拼 {@code keyPrefix}
     *  别名键、拼自校准 tag）原本在**每帧 x 每个常驻变量 x 每个命中控制器**上重算一遍；实机
     *  JFR 里 {@code injectRoamingVar:316/333/337} 与 {@code noteRoamingWrite:159} 合计占掉
     *  约 20% 的分配样本（byte[] + String + substring）。派生只依赖 varName 本身，缓存一次即可。</p>
     */
    static final class RoamingName {

        /** varName 的小写形式。 */
        final String lc;
        /** {@code roaming.} 之后的名字（无该前缀时为 null），即注入用的裸名。 */
        final String plain;
        /** 裸名的小写（无前缀时 null）。 */
        final String plainLc;
        /** {@code ROAMING_PREFIX} + varName，供反查 declared 集合用。 */
        final String prefixed;
        /** 只剥 {@code variable.} / {@code v.}、保留 {@code roaming.} 的名字（noteRoamingWrite 用）。 */
        final String container;

        private final String aliasBare;
        private final String aliasBareLc;
        private final String aliasV;
        private final String aliasVLc;

        RoamingName(String varName) {
            this.lc = varName.toLowerCase(java.util.Locale.ROOT);
            boolean hasPlain = varName.startsWith(ROAMING_PREFIX);
            this.plain = hasPlain ? varName.substring(ROAMING_PREFIX.length()) : null;
            this.plainLc = plain == null ? null : plain.toLowerCase(java.util.Locale.ROOT);
            this.prefixed = ROAMING_PREFIX + varName;
            this.aliasBare = plain;
            this.aliasBareLc = plainLc == null || plainLc.equals(plain) ? plain : plainLc;
            this.aliasV = plain == null ? null : "v." + plain;
            this.aliasVLc = plain == null ? null : (plain.equals(aliasBareLc) ? aliasV : "v." + plainLc);
            String name = varName;
            if (name.startsWith("variable.")) {
                name = name.substring("variable.".length());
            }
            if (name.startsWith("v.")) {
                name = name.substring(2);
            }
            this.container = name;
        }

        /** keyPrefix 对应的裸名别名键（无裸名时为 null）。 */
        String alias(String keyPrefix) {
            return keyPrefix.isEmpty() ? aliasBare : aliasV;
        }

        String aliasLc(String keyPrefix) {
            return keyPrefix.isEmpty() ? aliasBareLc : aliasVLc;
        }

        boolean isRoamingName() {
            return container.startsWith(ROAMING_PREFIX);
        }
    }

    private static final Map<String, RoamingName> ROAMING_NAMES = new ConcurrentHashMap<>();

    /** 取（并按需计算）varName 的派生字符串；调用点在每帧热路径上。 */
    static RoamingName roamingName(String varName) {
        RoamingName cached = ROAMING_NAMES.get(varName);
        if (cached != null) {
            return cached;
        }
        RoamingName created = new RoamingName(varName);
        ROAMING_NAMES.put(varName, created);
        return created;
    }

    /** (modelId -> 裸名 -> 自校准 tag)：tag 也只拼一次，热路径不再产生临时 String。 */
    private static final Map<ResourceLocation, Map<String, String>> ROAMING_TAGS = new ConcurrentHashMap<>();

    private static String roamingTag(ResourceLocation modelId, String plain) {
        return ROAMING_TAGS.computeIfAbsent(modelId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(plain, p -> modelId + "|" + p);
    }

    /** Derive (once per varName+keyPrefix) every key {@link #injectRoamingVar} writes:
     *  the original name, its lowercase form, and — for a {@code roaming.}-prefixed
     *  name — the prefix-stripped bare name and its lowercase form. */
    private static String[] deriveRoamingKeys(String keyPrefix, String varName) {
        RoamingName info = roamingName(varName);
        String lc = info.lc;
        String plain = info.plain;
        String lcPlain = info.plainLc;
        String[] keys = new String[4];
        int n = 0;
        keys[n++] = keyPrefix + varName;
        if (!lc.equals(varName)) {
            keys[n++] = keyPrefix + lc;
        }
        if (plain != null) {
            keys[n++] = keyPrefix + plain;
            if (!lcPlain.equals(plain)) {
                keys[n++] = keyPrefix + lcPlain;
            }
        }
        return n == 4 ? keys : java.util.Arrays.copyOf(keys, n);
    }

    /** 模型**自己**在关键帧/控制器里赋值过的变量裸名（不含 {@code v.} / {@code roaming.} 前缀）。
     *
     *  <p>注入 roaming 变量的"裸名别名"时，这些名字必须跳过：模型把它们当自己的变量用，
     *  注入会在同一帧里和模型自己的赋值互相覆盖，表现成值在两个来源之间交替 —— 用户实测
     *  `v.bq_qx2` 在注入值(2)与模型计算值(1)之间震荡、表情/部件闪烁（见
     *  {@link #LAST_INJECTED_ALIAS} 的自校准）。</p>
     *
     *  <p>为什么不能无脑删掉别名：`v.roaming.x` 与 `v.x` 在 YSM 里是**两个不同的变量**
     *  （wiki: molang/var，`variable.roaming.` 是常驻变量、`variable.` 是实体变量），
     *  但确实有模型在关键帧里只写裸名 —— 第一方 wine_fox 的 `14_momo` 就是
     *  `"scale": "1-v.smallfox_size"` 而它的滑块是 `v.roaming.smallfox_size`。所以别名
     *  要保，只是不能覆盖模型自己的变量。</p> */
    private static final Map<ResourceLocation, java.util.Set<String>> MODEL_OWNED_VARS =
        new ConcurrentHashMap<>();

    /** (模型 + "|" + 裸名) → 上一次注入的值。下一次注入前如果发现目标 map 里这个键**不是**
     *  我们注入的值，就说明模型自己改写过它 ⇒ 记进 {@link #MODEL_OWNED_VARS}，从此不再注入别名。
     *  自校准，不需要在模型加载时扫一遍脚本。 */
    private static final Map<String, Double> LAST_INJECTED_ALIAS = new ConcurrentHashMap<>();

    static boolean isModelOwnedVar(ResourceLocation modelId, String bareName) {
        if (modelId == null) {
            return false;
        }
        java.util.Set<String> owned = MODEL_OWNED_VARS.get(modelId);
        return owned != null && owned.contains(bareName);
    }

    private static void markModelOwnedVar(ResourceLocation modelId, String bareName) {
        MODEL_OWNED_VARS
            .computeIfAbsent(modelId, k -> java.util.Collections.newSetFromMap(new ConcurrentHashMap<>()))
            .add(bareName);
    }

    private static String[] roamingKeys(String keyPrefix, String varName) {
        Map<String, String[]> byPrefix = ROAMING_KEYS.get(varName);
        if (byPrefix == null) {
            byPrefix = new java.util.HashMap<>(2);
            ROAMING_KEYS.put(varName, byPrefix);
        }
        String[] keys = byPrefix.get(keyPrefix);
        if (keys == null) {
            keys = deriveRoamingKeys(keyPrefix, varName);
            byPrefix.put(keyPrefix, keys);
        }
        return keys;
    }

    /** 把 roaming 变量注入目标 map：原 case + 小写 +（roaming. 前缀剥离后的）裸名 + 裸名小写，
     *  使控制器条件（RuntimeState，keyPrefix=""）与关键帧 Molang（ScopeState，keyPrefix="v."）
     *  都能按多种写法命中同一变量。两处注入逻辑唯一实现，避免重复漂移。
     *
     *  <p>Keys are derived once and cached ({@link #ROAMING_KEYS}) and a value is
     *  only written when it actually changes. Both maps this feeds are plain
     *  {@code HashMap}s that live across frames, and this method is called once per
     *  roaming variable per frame per matching controller, so the unconditional
     *  version rebuilt up to four Strings (plus two {@code toLowerCase}) and re-put
     *  identical values every time: a client profile attributed 1.26 s of a 24.8 s
     *  client thread to the {@code HashMap.put}/{@code String} work in here. */
    public static void injectRoamingVar(java.util.Map<String, Double> target, String keyPrefix,
        String varName, double value, ResourceLocation modelId) {
        injectRoamingVar(target, null, keyPrefix, varName, value, modelId);
    }

    /**
     * {@link #injectRoamingVar(java.util.Map, String, String, double, ResourceLocation)} 的重载：
     * 额外把**真正写进去**的键收进 {@code dirtySink}。
     *
     * <p>作用域变量同步（{@code MolangPhysicsRuntime.syncToRuntimeState}）改成增量后，
     * 所有绕过 {@code setVariable()} 直接写 map 的路径都必须自己标脏，否则那个变量对控制器
     * 条件就是"永不更新"。本方法就是其中之一（{@link MolangPhysicsRuntime#begin} 每帧注入
     * 常驻变量）。没写（值没变 / 主动跳过裸名别名）的键不入脏集 —— 值没变就不需要同步。
     *
     * @param dirtySink 可为 null（调用方不需要脏集时）
     */
    public static void injectRoamingVar(java.util.Map<String, Double> target,
        java.util.Set<String> dirtySink, String keyPrefix, String varName, double value,
        ResourceLocation modelId) {
        RoamingName info = roamingName(varName);
        String plain = info.plain;
        boolean ownsBare = plain != null && isModelOwnedVar(modelId, plain);
        String alias = info.alias(keyPrefix);
        String aliasLc = info.aliasLc(keyPrefix);
        for (String key : roamingKeys(keyPrefix, varName)) {
            if (alias != null && (key.equals(alias) || key.equals(aliasLc))) {
                // 裸名别名：模型自己也会写这个名字时不能注入（否则就是覆盖模型的变量）。
                if (ownsBare) {
                    continue;
                }
                if ("v.".equals(keyPrefix) && modelId != null) {
                    // 自校准：上一次注入后，这个键是否被模型自己改写成了别的值？
                    String tag = roamingTag(modelId, plain);
                    Double injected = LAST_INJECTED_ALIAS.get(tag);
                    Double current = target.get(key);
                    if (injected != null && current != null && current.doubleValue() != injected.doubleValue()) {
                        markModelOwnedVar(modelId, plain);
                        ownsBare = true;
                        continue;
                    }
                    LAST_INJECTED_ALIAS.put(tag, value);
                }
            }
            Double previous = target.get(key);
            if (previous == null || previous.doubleValue() != value) {
                target.put(key, value);
                if (dirtySink != null) {
                    dirtySink.add(key);
                }
            }
        }
    }

    /**
     * Tracks which roaming variable names each model has registered via its
     * extraAnimationButtons config forms.  Used to filter PENDING_ROAMING
     * entries so that model A's roaming variables don't leak into model B's
     * ScopeState or controller RuntimeState.
     * Key = model ResourceLocation (mainId), value = set of variable names
     * WITHOUT the "v." prefix (e.g. "qh", "roaming.ef").
     */
    private static final Map<ResourceLocation, java.util.Set<String>> MODEL_ROAMING_VARS = new ConcurrentHashMap<>();

    /**
     * Per-model initial default values for roaming variables, set during
     * {@code registerExtraWheel()} from each model's ysm.json config forms.
     * These are used as the base in {@link #computeRoamingVarsForModel},
     * overlaid with any explicitly user-set values from {@link #PENDING_ROAMING}.
     * Key = model ResourceLocation (mainId), value = varName → default double.
     */
    private static final Map<ResourceLocation, Map<String, Double>> MODEL_ROAMING_DEFAULTS = new ConcurrentHashMap<>();

    /**
     * Frame-scoped cache for getRoamingVarsForModel — valid only within begin()/end().
     * Keyed by modelId to prevent cross-model contamination when multiple models
     * render in the same frame (e.g. GUI preview + player entity have different
     * MODEL_ROAMING_VARS sizes and the cache would return the wrong model's data).
     */
    private static final java.util.Map<ResourceLocation, Map<String, Double>> frameRoamingCache =
        new java.util.HashMap<>();

    /**
     * Registers a roaming variable name as belonging to the given model.
     * Called during model registration (registerExtraWheel).
     */
    public static void registerModelRoamingVar(ResourceLocation modelId, String varName) {
        MODEL_ROAMING_VARS.computeIfAbsent(modelId, k -> java.util.Collections.newSetFromMap(new ConcurrentHashMap<>()))
            .add(varName);
    }

    /**
     * Stores a per-model default value for a roaming variable, set from the
     * model's ysm.json config forms.  Used as the base value in
     * {@link #computeRoamingVarsForModel} when the variable has not been
     * explicitly set by the user via the wheel GUI.
     */
    public static void setModelRoamingDefault(ResourceLocation modelId, String varName, double value) {
        MODEL_ROAMING_DEFAULTS.computeIfAbsent(modelId, k -> new ConcurrentHashMap<>())
            .put(varName, value);
    }

    /**
     * Returns the subset of PENDING_ROAMING entries that belong to the given model.
     * Also includes global entries (lock_wheel, wheel_anim) that are not model-specific.
     * Results are cached for the duration of the current render frame (begin()/end()).
     */
    public static Map<String, Double> getRoamingVarsForModel(ResourceLocation modelId) {
        // Frame-scoped cache: all ~33 callers per frame share the same result.
        Map<String, Double> cached = frameRoamingCache.get(modelId);
        if (cached != null) {
            return cached;
        }
        Map<String, Double> result = computeRoamingVarsForModel(modelId);
        frameRoamingCache.put(modelId, result);
        return result;
    }

    /** Invalidates the frame-scoped roaming variable cache (called at frame end). */
    public static void invalidateFrameRoamingCache() {
        frameRoamingCache.clear();
    }

    /** Computes the roaming variable map for the given model — no caching. */
    private static Map<String, Double> computeRoamingVarsForModel(ResourceLocation modelId) {
        if (modelId == null) {
            return java.util.Collections.emptyMap();
        }
        java.util.Set<String> knownVars = MODEL_ROAMING_VARS.get(modelId);
        Map<String, Double> result = new java.util.HashMap<>();

        // 1. Start with per-model defaults from ysm.json config forms.
        //    This ensures each model gets its own initial values regardless
        //    of model load order (fixes cross-model default contamination).
        Map<String, Double> defaults = MODEL_ROAMING_DEFAULTS.getOrDefault(modelId,
            java.util.Collections.emptyMap());
        result.putAll(defaults);

        // 2. Overlay with explicitly user-set values from PENDING_ROAMING.
        //    Only vars explicitly marked for THIS model are overlaid — initial
        //    defaults set by registerExtraWheel() are NOT in EXPLICIT_ROAMING,
        //    so they stay per-model.  Using isRoamingExplicit(modelId, ...)
        //    keeps model A's user-set values from leaking into model B even
        //    when both models happen to use the same variable name.
        for (Map.Entry<String, Double> entry : PENDING_ROAMING.entrySet()) {
            String key = entry.getKey();
            boolean inExplicit = isRoamingExplicit(modelId, key);
            // Global vars that are not model-specific
            boolean isGlobal = "lock_wheel".equals(key) || "wheel_anim".equals(key);
            if (inExplicit || isGlobal) {
                result.put(key, entry.getValue());
            }
        }

        // 3. For models with no registered vars, also include any PENDING_ROAMING
        //    entries that are explicitly set but not known to any registered model
        //    (e.g. runtime-registered vars from radio button expressions).
        //    isKnownToAnyModel() guard: only truly unregistered vars leak through
        //    here — a var that belongs to model A (registered via its forms) must
        //    NOT be injected into a model B that has no registered vars.
        if (knownVars == null || knownVars.isEmpty()) {
            for (Map.Entry<String, Double> entry : PENDING_ROAMING.entrySet()) {
                String key = entry.getKey();
                if (isRoamingExplicit(modelId, key) && !isKnownToAnyModel(key)) {
                    result.put(key, entry.getValue());
                }
            }
        }

        return result;
    }

    /** 判断变量名是否已注册到任何模型（MODEL_ROAMING_VARS）。 */
    private static boolean isKnownToAnyModel(String varName) {
        for (java.util.Set<String> vars : MODEL_ROAMING_VARS.values()) {
            if (vars.contains(varName)) {
                return true;
            }
        }
        return false;
    }

    /** Clears the per-model roaming variable tracking (called during cache reset). */
    public static void clearModelRoamingVars() {
        MODEL_ROAMING_VARS.clear();
        MODEL_ROAMING_DEFAULTS.clear();
        EXPLICIT_ROAMING_BY_MODEL.clear();
        MODEL_OWNED_VARS.clear();
        LAST_INJECTED_ALIAS.clear();
        ROAMING_KEYS.clear();
        ROAMING_NAMES.clear();
        ROAMING_TAGS.clear();
        invalidateFrameRoamingCache();
    }

    /**
     * 重置指定模型的用户漫游变量（/ysm reset 用）。
     * 只清除"用户通过 GUI/轮盘显式设置"的值：EXPLICIT_ROAMING_BY_MODEL 中属于
     * 该模型的标记，以及 PENDING_ROAMING 中被这些标记覆盖的条目。
     * 保留模型定义（MODEL_ROAMING_VARS / MODEL_ROAMING_DEFAULTS），因此 reset 后
     * 变量回落到模型 ysm.json 定义的默认值，而不是消失。
     */
    public static void resetUserRoamingVars(ResourceLocation modelId) {
        if (modelId == null) {
            return;
        }
        java.util.Set<String> explicit = EXPLICIT_ROAMING_BY_MODEL.remove(modelId);
        if (explicit != null) {
            for (String varName : explicit) {
                PENDING_ROAMING.remove(varName);
            }
            // 全局标记也可能含该模型的变量（无模型上下文写入的兜底路径）
            EXPLICIT_ROAMING.removeAll(explicit);
        }
        // 全局标记里非模型专属的条目（如 lock_wheel / wheel_anim）也一并重置，
        // 这些是用户设置的全局状态，reset 时应回默认。
        EXPLICIT_ROAMING.remove("lock_wheel");
        EXPLICIT_ROAMING.remove("wheel_anim");
        PENDING_ROAMING.remove("lock_wheel");
        PENDING_ROAMING.remove("wheel_anim");
        invalidateFrameRoamingCache();
    }

    /**
     * 重置所有用户的漫游变量（/ysm reset @a 用）。清空全部用户显式设置，
     * 保留模型定义（MODEL_ROAMING_VARS / MODEL_ROAMING_DEFAULTS）。
     */
    public static void resetAllUserRoamingVars() {
        PENDING_ROAMING.clear();
        EXPLICIT_ROAMING.clear();
        EXPLICIT_ROAMING_BY_MODEL.clear();
        invalidateFrameRoamingCache();
    }

}
