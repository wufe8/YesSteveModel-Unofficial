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
 *       内容由 {@link MolangFunctionParser} 静态提取成状态→动画映射，这里不收。</li>
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
    }

    private static final Map<ResourceLocation, Entry> MODELS = new ConcurrentHashMap<>();

    private MolangScriptRegistry() {}

    // ---- 文件名解析（纯函数，便于单测） ----

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
     * @param functions 函数名（小写）→ 正文
     * @param events    事件名（小写）→ 该事件下按序执行的函数名
     */
    public static void register(ResourceLocation modelId, Map<String, String> functions,
        Map<String, List<String>> events) {
        if (modelId == null || (functions.isEmpty() && events.isEmpty())) {
            return;
        }
        Entry entry = new Entry();
        entry.functions.putAll(functions);
        for (Map.Entry<String, List<String>> event : events.entrySet()) {
            entry.events.put(event.getKey(), new ArrayList<>(event.getValue()));
        }
        MODELS.put(modelId, entry);
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
    }

    /** 把字节正文解码成字符串（脚本是 UTF-8 文本）。 */
    public static String decode(byte[] data) {
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }
}
