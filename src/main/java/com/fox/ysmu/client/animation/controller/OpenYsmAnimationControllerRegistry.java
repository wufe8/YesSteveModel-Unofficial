package com.fox.ysmu.client.animation.controller;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.AnimationEntry;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Controller;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.ControllerSet;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.NamedParallelSlots;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.State;
import com.fox.ysmu.client.animation.controller.OpenYsmControllerDefinitions.Transition;
import com.fox.ysmu.client.animation.molang.MolangScriptRegistry;
import com.fox.ysmu.Config;
import com.fox.ysmu.ysmu;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class OpenYsmAnimationControllerRegistry {

    private static final Map<ResourceLocation, ControllerSet> CONTROLLERS = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private OpenYsmAnimationControllerRegistry() {}

    public static void register(ResourceLocation animationId, Iterable<byte[]> controllerFiles) {
        register(animationId, controllerFiles, null);
    }

    /**
     * @param animationNames every animation defined by the model (light name scan,
     *                       available in both eager and lazy animation mode). Used to
     *                       synthesise the implicit parallel controllers that OpenYSM
     *                       builds from the animation list rather than from
     *                       {@code controller/*.json}. Pass {@code null} for
     *                       non-player/sub-model registrations (e.g. projectiles).
     */
    public static void register(ResourceLocation animationId, Iterable<byte[]> controllerFiles,
        Iterable<String> animationNames) {
        if (animationId == null || controllerFiles == null) {
            return;
        }
        ControllerSet set = new ControllerSet();
        int fileCount = 0;
        for (byte[] bytes : controllerFiles) {
            if (bytes == null || bytes.length == 0) {
                continue;
            }
            fileCount++;
            try {
                parseControllerFile(set, bytes);
            } catch (Exception e) {
                ysmu.LOG.warn("Failed to parse OpenYSM animation controller for {}", animationId, e);
            }
        }
        // OpenYSM's ParallelProcessor also emits a controller for every animation
        // named pre_parallel0..7 / parallel0..7 that the model does not declare an
        // entry for: CompositeAnimationController then falls back to a
        // NamedAnimationPredicate that plays that raw animation. Without this,
        // a model that only declares player.parallel_0..7 never plays
        // pre_parallel1..7 — the animations that scale MHat/MCape/LeftShoes/
        // Left_Sword/face effects from v.roaming.* — so every 轮盘 checkbox and
        // radio silently did nothing and the parts stayed visible.
        synthesizeImplicitParallelControllers(set, animationNames);
        if (set.controllers.isEmpty()) {
            CONTROLLERS.remove(animationId);
            return;
        }
        CONTROLLERS.put(animationId, set);
        if (Config.DEBUG_MODEL_LOAD && Config.DEBUG_MODEL_PARSE) {
            ysmu.LOG.info(
                "YSM client registered OpenYSM controllers for {}: files={}, controllers={}, names={}",
                animationId,
                fileCount,
                set.controllers.size(),
                set.controllers.keySet());
        }
    }

    public static ControllerSet get(ResourceLocation animationId) {
        return CONTROLLERS.get(animationId);
    }

    /** {@code pre_parallelN} / {@code parallelN} — OpenYSM's ParallelProcessor
     *  animation-name matcher ({@code ^(pre_)?parallel[0-7]$}). */
    private static final java.util.regex.Pattern IMPLICIT_PARALLEL_ANIMATION =
        java.util.regex.Pattern.compile("^(pre_parallel|parallel)([0-7])$");

    /**
     * Creates {@code player.pre_parallel_N} / {@code player.parallel_N} controllers
     * for parallel animations that have no declared controller entry, mirroring
     * OpenYSM's {@code ParallelProcessor}. A declared entry always wins
     * ({@code CompositeAnimationController.init} prefers the animation entry), so
     * an existing controller with the same name is left untouched.
     */
    private static void synthesizeImplicitParallelControllers(ControllerSet set, Iterable<String> animationNames) {
        if (animationNames == null) {
            return;
        }
        for (String animationName : animationNames) {
            if (animationName == null) {
                continue;
            }
            java.util.regex.Matcher matcher = IMPLICIT_PARALLEL_ANIMATION.matcher(animationName);
            if (!matcher.matches()) {
                continue;
            }
            String controllerName = "player." + matcher.group(1) + "_" + matcher.group(2);
            String shortName = matcher.group(1) + "_" + matcher.group(2);
            if (set.controllers.containsKey(controllerName) || set.controllers.containsKey(shortName)
                || set.declaredNames.contains(controllerName) || set.declaredNames.contains(shortName)) {
                continue;
            }
            State state = new State();
            state.name = "default";
            state.animations.add(new AnimationEntry(animationName, ""));
            Controller controller = new Controller();
            controller.name = controllerName;
            controller.initialState = state.name;
            controller.states.put(state.name, state);
            set.controllers.put(controllerName, controller);
            if (Config.DEBUG_CONTROLLER) {
                ysmu.LOG.info("[YSMU-CTRL] implicit parallel controller {} -> '{}'",
                    controllerName, animationName);
            }
        }
    }

    /** Returns true if the model has an OpenYSM controller with the given name. */
    public static boolean hasController(ResourceLocation animationId, String controllerName) {
        ControllerSet set = CONTROLLERS.get(animationId);
        return set != null && set.controllers.containsKey(controllerName);
    }

    /**
     * {@code player.<slot>_<后缀>} 的槽位族名（{@code slot}，如 {@code pre_main}）；不是
     * 槽位后缀控制器返回 {@code null}（基槽位 {@code player.pre_main} 本身也算"不是"）。
     * <p>
     * 官方对同一槽位下带后缀的名字也发独立控制器（OpenYSM {@code ControllerSlotBinder} 的
     * {@code controllerNameMatcher = ^player\.<slot>(_.+){0,1}$}），族名就是匹配到的那一段。
     */
    public static String slotFamilyOf(String controllerName) {
        if (controllerName == null) {
            return null;
        }
        String name = controllerName.startsWith("player.")
            ? controllerName.substring("player.".length())
            : controllerName;
        for (String slot : com.fox.ysmu.util.ControllerUtils.OPENYSM_SLOTS) {
            if (name.length() > slot.length() + 1
                && name.startsWith(slot)
                && name.charAt(slot.length()) == '_') {
                return slot;
            }
        }
        return null;
    }

    /** 槽位后缀控制器的一项：承载它的池控制器需要知道"属于哪个槽位族"以及要跑哪个 JSON 控制器。 */
    public static final class SlotExtra {

        /** 槽位族名（{@code pre_main} / {@code post_main} / …），决定 Root 骨骼与组顺序的处理。 */
        public final String family;
        /**
         * 控制脚本槽位名（{@code <slot>_<后缀>}）。
         * 官方对 {@code @player_ctrl_<slot>_<后缀>.molang} 也发控制器，所以池控制器同样按这个名字
         * 去查控制脚本；模型没写这个脚本时查不到，回落到 JSON 控制器。
         */
        public final String controlSlot;
        /** 当前模型里这个后缀对应的 JSON 控制器键（{@code ControllerSet#controllers} 里的原拼写）。 */
        public final String controllerKey;

        SlotExtra(String family, String controlSlot, String controllerKey) {
            this.family = family;
            this.controlSlot = controlSlot;
            this.controllerKey = controllerKey;
        }

        @Override
        public String toString() {
            return controllerKey;
        }
    }

    /**
     * 当前模型的槽位后缀控制器路由表：第 i 项由第 i 个池控制器
     * （{@code openysm_slot_extra_<i>_controller}）承载。
     * <p>
     * 顺序按名字**小写形式**排序（{@code TreeMap}），理由与
     * wiki「动画控制器」2.6.3 的"同一组内的控制器按名称字母序排序后依次加载"一致，
     * 也让路由表在同一份模型内稳定可复现。只收 {@code set.controllers} 里真实存在的条目
     * （带 states 的）：只声明空 states 的占位名没有状态机可跑，占了池位就是浪费。
     * <p>
     * 列表按模型缓存在 {@link ControllerSet} 上，池控制器的谓词每帧都会问一次。
     */
    public static List<SlotExtra> slotExtraControllers(ResourceLocation animationId) {
        ControllerSet set = animationId == null ? null : CONTROLLERS.get(animationId);
        if (set == null) {
            return Collections.emptyList();
        }
        List<SlotExtra> cached = set.slotExtraCache;
        if (cached != null) {
            return cached;
        }
        // key 用小写形式去重（同一个槽位可能同时写了 player. 前缀与短名），value 保留首次见到的拼写。
        java.util.TreeMap<String, SlotExtra> ordered = new java.util.TreeMap<>();
        for (String name : set.controllers.keySet()) {
            if (name == null) {
                continue;
            }
            String family = slotFamilyOf(name);
            if (family == null) {
                continue;
            }
            int suffixStart = (name.startsWith("player.") ? "player.".length() : 0) + family.length() + 1;
            String suffix = name.substring(suffixStart);
            String controlSlot = family + "_" + suffix;
            ordered.putIfAbsent(controlSlot.toLowerCase(java.util.Locale.ROOT), new SlotExtra(family, controlSlot, name));
        }
        List<SlotExtra> list = ordered.isEmpty()
            ? Collections.emptyList()
            : new ArrayList<>(ordered.values());
        set.slotExtraCache = list;
        return list;
    }

    /** True when the model declares any parallel controller
     *  ({@code player.pre_parallel_*} / {@code player.parallel_*}).
     *  <p>A model that ships parallel controllers owns those animations: it
     *  commonly merges several raw {@code pre_parallelN} animations into one
     *  state (one {@code player.pre_parallel_0} plays {@code pre_parallel1..7}),
     *  so the legacy per-slot controllers must not replay them as well. Every
     *  {@code pre_parallelN}/{@code parallelN} animation the model actually defines
     *  gets its own controller from {@link #synthesizeImplicitParallelControllers},
     *  so nothing is lost by suppressing the raw per-slot fallback. */
    public static boolean hasParallelController(ResourceLocation animationId) {
        ControllerSet set = CONTROLLERS.get(animationId);
        if (set == null) {
            return false;
        }
        for (String name : set.controllers.keySet()) {
            if (name != null && name.contains("parallel")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 具名（非数字后缀）并行槽位的**有序槽位名**列表，例如 {@code ["表情"]}。
     * <p>
     * wiki 只定义数字槽位 {@code pre_parallel0..7} / {@code parallel0..7}，但官方对非数字后缀
     * 也发控制器，于是模型会把整块状态机挂在一个具名槽位上。YSMU 用固定的备用池
     * （{@code *_extra_<i>_controller}）承载它们：第 i 个池控制器 = 这份列表的第 i 项。
     * <p>
     * 列表取自 {@code controllers ∪ declaredNames ∪ 控制脚本槽位}，按槽位名**小写形式**排序：
     * <ul>
     *   <li>用 declaredNames 是为了**索引稳定** —— 只声明了空 states 的占位槽位也必须占一个
     *       位置，否则它后面的槽位会整体前移、错播别人的动画；</li>
     *   <li>把 {@code @player_ctrl_<槽位>.molang} 里声明的具名并行槽位也算进来，是为了支持
     *       **只有脚本、没有 JSON 控制器**的槽位（它同样需要一个池控制器承载）；</li>
     *   <li>{@code player.} 前缀与短名视为同一个槽位（按槽位名去重），前缀写法优先；</li>
     *   <li>JSON 声明与控制脚本共用同一次排序，所以一个槽位无论来自哪边都落在同一个下标。</li>
     * </ul>
     *
     * @param animationId 模型的动画 id
     * @param family      族名，{@code pre_parallel} 或 {@code parallel}
     */
    public static List<String> namedParallelSlots(ResourceLocation animationId, String family) {
        if (animationId == null || family == null) {
            return Collections.emptyList();
        }
        ControllerSet set = CONTROLLERS.get(animationId);
        long generation = MolangScriptRegistry.generation();
        if (set == null) {
            // 模型只有 functions/ 而没有 controller/*.json：没有 ControllerSet 可挂缓存，
            // 用一张按 (模型, 族) 的静态表兜底；脚本表一变就重算。
            String cacheKey = animationId + "\u0000" + family;
            NamedParallelSlots cached = SCRIPT_ONLY_SLOT_CACHE.get(cacheKey);
            if (cached != null && cached.scriptGeneration == generation) {
                return cached.slots;
            }
            List<String> slots = computeNamedParallelSlots(null, animationId, family);
            SCRIPT_ONLY_SLOT_CACHE.put(cacheKey, new NamedParallelSlots(generation, slots));
            return slots;
        }
        // 池控制器的谓词每帧都会问一次，所以结果缓存在 ControllerSet 上；
        // 列表内容依赖控制脚本表，因此缓存项带上算它时的脚本版本。
        NamedParallelSlots cached = set.namedParallelSlotCache.get(family);
        if (cached != null && cached.scriptGeneration == generation) {
            return cached.slots;
        }
        List<String> slots = computeNamedParallelSlots(set, animationId, family);
        set.namedParallelSlotCache.put(family, new NamedParallelSlots(generation, slots));
        return slots;
    }

    /** 模型完全没有 controller/*.json 时，只有控制脚本的具名并行槽位表的兜底缓存。 */
    private static final Map<String, NamedParallelSlots> SCRIPT_ONLY_SLOT_CACHE = new ConcurrentHashMap<>();

    private static List<String> computeNamedParallelSlots(ControllerSet set, ResourceLocation animationId,
        String family) {
        // 用 TreeMap（key = 槽位名小写、value = 首次见到的拼写）而不是 TreeSet：
        // JSON 声明优先保留原始大小写，`resolveParallelSlotKey` 要用它去查 ControllerSet；
        // 排序键统一小写，使同一个槽位无论来自 JSON 还是脚本都排在同一个下标。
        java.util.TreeMap<String, String> slots = new java.util.TreeMap<>();
        if (set != null) {
            collectNamedParallelSlots(set.controllers.keySet(), family, slots);
            collectNamedParallelSlots(set.declaredNames, family, slots);
        }
        collectScriptNamedParallelSlots(animationId, family, slots);
        return slots.isEmpty() ? Collections.emptyList() : new ArrayList<>(slots.values());
    }

    /** 收集形如 {@code (player.)?<family>_<非数字开头>} 的槽位名（取 {@code <family>_} 之后的部分）。
     *  key 用小写形式去重，value 保留首次见到的拼写。 */
    private static void collectNamedParallelSlots(Iterable<String> controllerNames, String family,
        java.util.Map<String, String> out) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern
            .compile("^(?:player\\.)?" + java.util.regex.Pattern.quote(family) + "_([^0-9].*)$");
        for (String name : controllerNames) {
            if (name == null) {
                continue;
            }
            java.util.regex.Matcher matcher = pattern.matcher(name);
            if (matcher.matches()) {
                String suffix = matcher.group(1);
                out.putIfAbsent(suffix.toLowerCase(java.util.Locale.ROOT), suffix);
            }
        }
    }

    /** 控制脚本（{@code @player_ctrl_<槽位>.molang}）里声明的具名并行槽位。
     *  脚本槽位名在登记时已统一为小写（见 {@link MolangScriptRegistry}），这里只按族筛选。 */
    private static void collectScriptNamedParallelSlots(ResourceLocation animationId, String family,
        java.util.Map<String, String> out) {
        if (animationId == null) {
            return;
        }
        List<String> scriptSlots = MolangScriptRegistry.controlSlots(animationId);
        if (scriptSlots.isEmpty()) {
            return;
        }
        collectNamedParallelSlots(scriptSlots, family, out);
    }

    /**
     * 把 {@link #namedParallelSlots} 给出的槽位名解析成 ControllerSet 里的实际键名；
     * 该槽位不存在时返回 null（池控制器分到它就只能不出动画）。
     */
    public static String resolveParallelSlotKey(ResourceLocation animationId, String family, String slot) {
        ControllerSet set = animationId == null ? null : CONTROLLERS.get(animationId);
        if (set == null || family == null || slot == null) {
            return null;
        }
        String prefixed = "player." + family + "_" + slot;
        if (set.controllers.containsKey(prefixed) || set.declaredNames.contains(prefixed)) {
            return prefixed;
        }
        String shortName = family + "_" + slot;
        if (set.controllers.containsKey(shortName) || set.declaredNames.contains(shortName)) {
            return shortName;
        }
        return null;
    }

    /**
     * Returns true if any of the given OpenYSM controllers actually handles sneak,
     * i.e. references sneak in its state animations, transition/animation
     * conditions or entry/exit statements.  Used to decide whether the legacy
     * sneak/sneaking states on the main_controller should be skipped: only models
     * whose own body controller drives sneak (e.g. a player.pre_main with
     * Start_Sneak/Sneak/Sneaking states) should suppress the legacy fallback.
     * A model may own a player.main controller that only handles idle
     * — in that case legacy sneak must keep playing.
     */
    public static boolean hasControllerSneakHandling(ResourceLocation animationId, String... controllerNames) {
        ControllerSet set = CONTROLLERS.get(animationId);
        if (set == null || controllerNames == null) {
            return false;
        }
        for (String controllerName : controllerNames) {
            Controller ctrl = set.controllers.get(controllerName);
            if (ctrl == null) {
                continue;
            }
            for (State s : ctrl.states.values()) {
                for (AnimationEntry ae : s.animations) {
                    if (containsSneak(ae.animationName) || containsSneak(ae.condition)) {
                        return true;
                    }
                }
                for (Transition t : s.transitions) {
                    if (containsSneak(t.condition)) {
                        return true;
                    }
                }
                for (String stmt : s.onEntry) {
                    if (containsSneak(stmt)) {
                        return true;
                    }
                }
                for (String stmt : s.onExit) {
                    if (containsSneak(stmt)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean containsSneak(String text) {
        return text != null && text.toLowerCase(java.util.Locale.ROOT).contains("sneak");
    }

    /**
     * Post-registration scan: marks controllers as dependent on optional mods if
     * their state animations reference keyframe Molang expressions that contain
     * mod-specific variables. Catches cases where the controller's own conditions
     * don't reference the mod, but the animation keyframes it plays do
     * (e.g. pre_parallel_5 playing parallel7 which has ctrl.tac_hold_gun).
     *
     * @param animationId   the model's main animation ID
     * @param animToModIds  map of animation name → set of matching modIds
     */
    public static void scanAnimKeyframesForDeps(ResourceLocation animationId,
        java.util.Map<String, java.util.Set<String>> animToModIds) {
        if (animationId == null || animToModIds == null || animToModIds.isEmpty()) {
            return;
        }
        ControllerSet set = CONTROLLERS.get(animationId);
        if (set == null) {
            return;
        }
        for (Controller ctrl : set.controllers.values()) {
            for (State s : ctrl.states.values()) {
                for (AnimationEntry ae : s.animations) {
                    if (ae.animationName != null) {
                        java.util.Set<String> mods = animToModIds.get(ae.animationName);
                        if (mods != null && !mods.isEmpty()) {
                            ctrl.modDependencies.addAll(mods);
                            if (com.fox.ysmu.Config.DEBUG_CONTROLLER) {
                                ysmu.LOG.info("[YSMU-CTRL] Dep scan: {} now depends on {} (via animation '{}')",
                                    ctrl.name, mods, ae.animationName);
                            }
                        }
                    }
                }
            }
        }
    }

    public static void clear() {
        CONTROLLERS.clear();
        SCRIPT_ONLY_SLOT_CACHE.clear();
        WARNED.clear();
        OpenYsmPlayerControllerRuntime.clear();
        ProjectileControllerRuntime.clear();
        com.fox.ysmu.client.renderer.ArrowProjectileRenderer.clearDumpedTrees();
    }

    static void warnOnce(String key, String message) {
        if (WARNED.add(key)) {
            ysmu.LOG.warn(message);
        }
    }

    private static void parseControllerFile(ControllerSet set, byte[] bytes) {
        JsonElement element = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8));
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject root = element.getAsJsonObject();
        if (!hasObject(root, "animation_controllers")) {
            return;
        }
        JsonObject controllers = root.getAsJsonObject("animation_controllers");
        for (Map.Entry<String, JsonElement> entry : controllers.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            Controller controller = parseController(entry.getKey(), entry.getValue().getAsJsonObject());
            set.declaredNames.add(controller.name);
            if (!controller.states.isEmpty()) {
                set.controllers.put(controller.name, controller);
            }
        }
    }

    private static Controller parseController(String name, JsonObject json) {
        Controller controller = new Controller();
        controller.name = name;
        controller.initialState = getString(json, "initial_state", "");
        if (hasObject(json, "states")) {
            JsonObject states = json.getAsJsonObject("states");
            for (Map.Entry<String, JsonElement> entry : states.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                State state = parseState(entry.getKey(), entry.getValue().getAsJsonObject());
                controller.states.put(state.name, state);
            }
        }
        // 扫描所有 condition 中是否引用了可选模组的控制变量（如 ctrl.tac_*）。
        // 运行时若对应模组未加载，跳过此控制器避免无效动画和音效误触发。
        for (State s : controller.states.values()) {
            for (Transition t : s.transitions) {
                if (t.condition != null) {
                    controller.modDependencies.addAll(ModDependencyRegistry.detect(t.condition));
                }
            }
            for (AnimationEntry ae : s.animations) {
                if (ae.condition != null) {
                    controller.modDependencies.addAll(ModDependencyRegistry.detect(ae.condition));
                }
            }
        }
        return controller;
    }

    private static State parseState(String name, JsonObject json) {
        State state = new State();
        state.name = name;
        parseAnimations(state, json.get("animations"));
        parseTransitions(state, json.get("transitions"));
        parseStringArray(state.onEntry, json.get("on_entry"));
        parseStringArray(state.onExit, json.get("on_exit"));
        parseSoundEffects(state, json.get("sound_effects"));
        parseBlendTransition(state, json.get("blend_transition"));
        state.blendViaShortestPath = getBoolean(json, "blend_via_shortest_path", false);
        return state;
    }

    private static void parseAnimations(State state, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement entry : array) {
                parseAnimationEntry(state, entry);
            }
        } else {
            parseAnimationEntry(state, element);
        }
    }

    private static void parseAnimationEntry(State state, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive()) {
            state.animations.add(new AnimationEntry(element.getAsString(), ""));
        } else if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                state.animations.add(new AnimationEntry(entry.getKey(), getElementString(entry.getValue())));
            }
        }
    }

    private static void parseTransitions(State state, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (!element.isJsonArray()) {
            return;
        }
        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> transition : entry.getAsJsonObject().entrySet()) {
                state.transitions.add(new Transition(transition.getKey(), getElementString(transition.getValue())));
            }
        }
    }

    private static void parseStringArray(List<String> target, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                if (!entry.isJsonNull()) {
                    target.add(entry.getAsString());
                }
            }
        } else {
            target.add(getElementString(element));
        }
    }

    private static void parseSoundEffects(State state, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement entry : element.getAsJsonArray()) {
                addSoundEffect(state, entry);
            }
        } else {
            addSoundEffect(state, element);
        }
    }

    private static void addSoundEffect(State state, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            String effect = getString(element.getAsJsonObject(), "effect", "");
            if (StringUtils.isNotBlank(effect)) {
                state.soundEffects.add(effect);
            }
        } else {
            state.soundEffects.add(getElementString(element));
        }
    }

    private static void parseBlendTransition(State state, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            state.blendTransitionTicks = Math.max(0f, element.getAsFloat() * 20f);
        } else if (element.isJsonObject()) {
            float maxSeconds = 0f;
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                try {
                    maxSeconds = Math.max(maxSeconds, Float.parseFloat(entry.getKey()));
                } catch (NumberFormatException ignored) {}
            }
            state.blendTransitionTicks = maxSeconds * 20f;
        }
    }

    private static boolean hasObject(JsonObject json, String name) {
        return json.has(name) && json.get(name).isJsonObject();
    }

    private static String getString(JsonObject json, String name, String defaultValue) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsString() : defaultValue;
    }

    private static boolean getBoolean(JsonObject json, String name, boolean defaultValue) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsBoolean() : defaultValue;
    }

    private static String getElementString(JsonElement element) {
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

}
