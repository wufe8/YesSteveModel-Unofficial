package com.fox.ysmu.client.animation.molang;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.ResourceLocation;

import org.apache.commons.lang3.StringUtils;

/**
 * 每模型的 {@code functions/*.molang} 脚本表（YSM-wiki: molang/script）。
 * <p>
 * 模型包里 {@code functions/} 下的文件名决定它是什么：
 * <ul>
 *   <li>{@code 名字.molang} —— 一个脚本函数，只能由 {@code fn.名字(...)} 调用；</li>
 *   <li>{@code 名字@事件.molang} —— 既是名为 {@code 名字} 的函数，又订阅了 {@code 事件}
 *       （{@code player_init} / {@code player_update} / {@code sync}）；</li>
 *   <li>{@code @player_ctrl_main.molang} —— **动画控制脚本**，名字为空。它不是函数，
 *       正文按槽位登记（{@link #controlScript}），每帧由动画控制求值器执行；
 *       同时仍由 {@link MolangFunctionParser} 静态提取状态→动画映射，作为求值器不可用时的兜底。</li>
 * </ul>
 * 名字不区分大小写（wiki），所以表里统一按小写存放。
 * <p>
 * 事件处理器的执行顺序：wiki 明确说"多个函数订阅同一事件时调用顺序是随机的"，这里按
 * 文件顺序（{@code LinkedHashMap}）执行，比随机更容易复现问题。
 */
public final class MolangScriptRegistry {

    public static final String EVENT_PLAYER_INIT = "player_init";
    public static final String EVENT_PLAYER_UPDATE = "player_update";
    public static final String EVENT_SYNC = "sync";

    /** 一个模型的脚本表。 */
    private static final class Entry {

        /** 函数名（小写）→ 函数体正文。 */
        final Map<String, String> functions = new LinkedHashMap<>();
        /** 事件名 → 该事件下要执行的函数名（文件顺序）。 */
        final Map<String, List<String>> events = new LinkedHashMap<>();
        /** 动画控制脚本：槽位名（小写，如 {@code main}、{@code parallel_6}）→ 正文。 */
        final Map<String, String> controlScripts = new LinkedHashMap<>();
    }

    private static final Map<ResourceLocation, Entry> MODELS = new ConcurrentHashMap<>();

    /**
     * 脚本表内容版本号：每次 {@link #register} 覆盖某个模型、或 {@link #clear} 时递增。
     * <p>
     * 具名并行槽位表（{@code OpenYsmAnimationControllerRegistry.namedParallelSlots}）要把
     * "只有控制脚本、没有 JSON 控制器"的槽位也算进去，于是它的内容同时依赖脚本表。
     * 槽位表按帧缓存（池谓词每帧都要问），缓存的失效条件因此不能只是"控制器重新注册"——
     * 脚本可以先于/晚于控制器登记。这里给出一个全局单调版本号，缓存带上算出的版本，
     * 版本不匹配就重算。假阳性（脚本变了但槽位没变）只是多算一次，不会读错。
     */
    private static final java.util.concurrent.atomic.AtomicLong GENERATION =
        new java.util.concurrent.atomic.AtomicLong();

    private MolangScriptRegistry() {}

    // ---- 文件名解析（纯函数，便于单测） ----

    /**
     * 该 {@code .molang} 文件是否允许做**静态**"状态→动画"提取（写入
     * {@code AnimationManager.MOLANG_STATE_MAP} / {@code MOLANG_CONDITIONAL_MAP}）。
     *
     * <p>这两张表的唯一消费者是 legacy 主状态机 {@code AnimationManager.predicateMain}，键是
     * {@code walk}/{@code run}/{@code idle}/{@code sneak}… 这类**主身体状态**（提取用的
     * {@code extractCtrlStateName} 取的是守卫里**最后一个** {@code ctrl.<名字>}）。所以只有"身体层"
     * 槽位可以写：{@code main}（玩家主动画）与 {@code pre_main}（OpenYSM 的身体底层；1.7.10 侧没有
     * {@code pre_main} 控制器，模型没有 JSON 控制器时它只能靠这两张表生效，所以必须保留）。</p>
     *
     * <p>覆盖层槽位（{@code parallel_N}/{@code pre_parallel_N}/{@code use}/{@code swing}/
     * {@code hold_*}/护甲…）绝不能写：它们由 {@code applyControlScript(event, 槽位)} 每帧在**自己的
     * 控制器**上求值，动画通常只动手臂/眼睛。一旦被当成本状态的替代动画，主状态机就会被别的槽位劫持 ——
     * 实测某内置子模型的 {@code @player_ctrl_parallel_5}（碰墙抬手，守卫 {@code ctrl.run || ctrl.walk}
     * 被取成 {@code walk} 状态）把走路替换成了只有 4 根骨骼的贴墙防御姿势 {@code defWall}，
     * 表现就是"向前走路腿不动、角色直立"。普通函数 / 事件订阅文件（没有 {@code @player_ctrl_} 槽位）
     * 保持历史行为，不做限制。</p>
     */
    public static boolean allowsStaticStateMapping(String fileName) {
        String slot = controlSlotOf(fileName);
        if (slot == null) {
            return true;
        }
        return "main".equals(slot) || "pre_main".equals(slot);
    }

    /**
     * 这个文件名对应的**函数名**（小写）；它不是一个可调用函数时返回 null。
     * <p>
     * 规则：wiki 的函数名只能由字母/下划线/数字组成，所以含 {@code @} 的文件名只有
     * "名字@已知事件"才算函数（见 {@link #eventOf}）。其余带 {@code @} 的都是**动画控制脚本**
     * —— 参考库里既有 {@code @player_ctrl_pre_main.molang}，也有加了描述前缀的
     * {@code car_stuff@player_ctrl_parallel_6.molang} 这种写法，两者都只做状态→动画的静态提取，
     * 不能登记成函数（否则描述前缀可能顶掉同名的真函数）。
     */
    public static String functionNameOf(String fileName) {
        String base = baseName(fileName);
        if (base.isEmpty()) {
            return null;
        }
        int at = base.lastIndexOf('@');
        if (at < 0) {
            return base.toLowerCase(Locale.ROOT);
        }
        if (at == 0 || eventOf(base) == null) {
            return null;
        }
        return base.substring(0, at)
            .toLowerCase(Locale.ROOT);
    }

    /**
     * 这个文件名订阅的**事件名**（小写）；不是事件订阅返回 null。
     * <p>
     * 只有 {@code 名字@已知事件} 才算订阅：{@code @player_ctrl_main} 没有名字，
     * {@code 名字@其他后缀} 只是普通函数（后缀是名字的一部分）。
     */
    public static String eventOf(String fileName) {
        String base = baseName(fileName);
        int at = base.lastIndexOf('@');
        if (at <= 0 || at == base.length() - 1) {
            return null;
        }
        String event = base.substring(at + 1)
            .toLowerCase(Locale.ROOT);
        return isKnownEvent(event) ? event : null;
    }

    /** 是否是 YSMU 会触发的事件（{@code sync} 需要网络包，暂未接入）。 */
    public static boolean isKnownEvent(String event) {
        return EVENT_PLAYER_INIT.equals(event) || EVENT_PLAYER_UPDATE.equals(event) || EVENT_SYNC.equals(event);
    }

    /** 动画控制脚本在后缀里的固定前缀：{@code <任意描述>@player_ctrl_<槽位>.molang}。 */
    static final String CONTROL_PREFIX = "player_ctrl_";

    /**
     * 这个文件名是不是**动画控制脚本**；是的话返回它控制的**槽位名**（小写），否则返回 null。
     *
     * <p>wiki: molang/script「动画控制」—— 文件名即控制器名把 {@code .} 换成 {@code _ctrl_}，
     * 所以控制器 {@code player.main} → {@code player_ctrl_main}、{@code player.parallel_6} →
     * {@code player_ctrl_parallel_6}。带描述前缀的写法（{@code car_stuff@player_ctrl_parallel_6}）
     * 同样合法，取最后一个 {@code @} 之后的部分。</p>
     *
     * <p>事件订阅（{@code 名字@player_init}）不是控制脚本：它的后缀不满足
     * {@code player_ctrl_} 前缀。</p>
     */
    public static String controlSlotOf(String fileName) {
        String base = baseName(fileName);
        int at = base.lastIndexOf('@');
        if (at < 0 || at == base.length() - 1) {
            return null;
        }
        String suffix = base.substring(at + 1);
        if (!suffix.regionMatches(true, 0, CONTROL_PREFIX, 0, CONTROL_PREFIX.length())) {
            return null;
        }
        String slot = suffix.substring(CONTROL_PREFIX.length())
            .trim();
        return slot.isEmpty() ? null : slot.toLowerCase(Locale.ROOT);
    }

    /** 去掉目录与 {@code .molang} 扩展名，保留大小写（模型包里的名字可能不是全小写）。 */
    private static String baseName(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return "";
        }
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.toLowerCase(Locale.ROOT)
            .endsWith(".molang")) {
            name = name.substring(0, name.length() - ".molang".length());
        }
        return name.trim();
    }

    // ---- 登记与查询 ----

    /**
     * 把解析阶段收集好的脚本表登记到某个模型上（模型缓存刷新时覆盖）。
     *
     * @param functions      函数名（小写）→ 正文
     * @param events         事件名（小写）→ 该事件下按序执行的函数名
     * @param controlScripts 动画控制脚本：槽位名（小写）→ 正文
     */
    public static void register(ResourceLocation modelId, Map<String, String> functions,
        Map<String, List<String>> events, Map<String, String> controlScripts) {
        if (modelId == null || (functions.isEmpty() && events.isEmpty()
            && (controlScripts == null || controlScripts.isEmpty()))) {
            return;
        }
        Entry entry = new Entry();
        entry.functions.putAll(functions);
        for (Map.Entry<String, List<String>> event : events.entrySet()) {
            entry.events.put(event.getKey(), new ArrayList<>(event.getValue()));
        }
        if (controlScripts != null) {
            for (Map.Entry<String, String> control : controlScripts.entrySet()) {
                if (control.getKey() != null && control.getValue() != null) {
                    entry.controlScripts.put(control.getKey()
                        .toLowerCase(Locale.ROOT), control.getValue());
                }
            }
        }
        MODELS.put(modelId, entry);
        GENERATION.incrementAndGet();
    }

    /** 动画控制脚本正文（{@code @player_ctrl_<slot>.molang}）；没有返回 null。 */
    public static String controlScript(ResourceLocation modelId, String slot) {
        if (modelId == null || slot == null) {
            return null;
        }
        Entry entry = MODELS.get(modelId);
        return entry == null ? null
            : entry.controlScripts.get(
                slot.toLowerCase(Locale.ROOT));
    }

    /** 该模型声明了动画控制脚本的槽位名（小写，按文件顺序）。 */
    public static List<String> controlSlots(ResourceLocation modelId) {
        if (modelId == null) {
            return Collections.emptyList();
        }
        Entry entry = MODELS.get(modelId);
        if (entry == null || entry.controlScripts.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(entry.controlScripts.keySet());
    }

    /** 供解释器调用：取函数体正文；没有这个函数返回 null。 */
    public static String functionScript(ResourceLocation modelId, String name) {
        if (modelId == null || name == null) {
            return null;
        }
        Entry entry = MODELS.get(modelId);
        return entry == null ? null
            : entry.functions.get(
                name.toLowerCase(Locale.ROOT));
    }

    /** 某个事件下要执行的函数体（按文件顺序）。 */
    public static List<String> eventScripts(ResourceLocation modelId, String event) {
        if (modelId == null || event == null) {
            return Collections.emptyList();
        }
        Entry entry = MODELS.get(modelId);
        if (entry == null) {
            return Collections.emptyList();
        }
        List<String> names = entry.events.get(event);
        if (names == null || names.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> bodies = new ArrayList<>(names.size());
        for (String name : names) {
            String body = entry.functions.get(name);
            if (body != null) {
                bodies.add(body);
            }
        }
        return bodies;
    }

    /** 该事件订阅的函数名（日志用）。 */
    public static List<String> eventFunctionNames(ResourceLocation modelId, String event) {
        if (modelId == null || event == null) {
            return Collections.emptyList();
        }
        Entry entry = MODELS.get(modelId);
        if (entry == null) {
            return Collections.emptyList();
        }
        List<String> names = entry.events.get(event);
        return names == null ? Collections.emptyList() : new ArrayList<>(names);
    }

    /** 该模型是否登记过任何脚本（用于快速跳过每帧的脚本阶段）。 */
    public static boolean hasScripts(ResourceLocation modelId) {
        return modelId != null && MODELS.containsKey(modelId);
    }

    public static void clear() {
        MODELS.clear();
        GENERATION.incrementAndGet();
    }

    /** 脚本表内容版本号（见 {@link #GENERATION}）：供按帧缓存的槽位表判断是否过期。 */
    public static long generation() {
        return GENERATION.get();
    }

    /** 把字节正文解码成字符串（脚本是 UTF-8 文本）。 */
    public static String decode(byte[] data) {
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }
}
