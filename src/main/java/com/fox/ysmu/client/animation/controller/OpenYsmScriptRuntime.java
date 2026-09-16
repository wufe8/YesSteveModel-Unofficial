package com.fox.ysmu.client.animation.controller;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.animation.molang.MolangScriptInterpreter;
import com.fox.ysmu.client.animation.molang.MolangScriptRegistry;
import com.fox.ysmu.ysmu;

/**
 * {@code functions/*.molang} 事件订阅的触发点（YSM-wiki: molang/script）。
 * <p>
 * 触发时机与顺序：{@code player_init}（玩家切到该模型 / 实体加载）先在
 * {@code player_update}（每次更新玩家动画之前）之前跑；wiki 还要求
 * {@code v.roaming} 的同步早于 {@code player_init} —— 调用点在
 * {@code MolangPhysicsRuntime.begin()} 的漫游变量注入之后，正好满足。
 * {@code sync} 由下行广播包触发（见 {@link #runSyncScripts}）。
 * <p>
 * 脚本执行失败只记一次警告：脚本是模型作者写的，一条有问题的语句不应该影响渲染。
 */
public final class OpenYsmScriptRuntime {

    /** 已经跑过 {@code player_init} 的 (玩家, 模型) 组合。 */
    private static final Set<String> INITIALISED = ConcurrentHashMap.newKeySet();
    /** 每个玩家上一帧渲染用的模型：用来识别"切换到另一个模型"，见 {@link #shouldRunPlayerInit}。 */
    private static final java.util.Map<UUID, ResourceLocation> LAST_RENDERED_MODEL =
        new ConcurrentHashMap<>();
    /** 每个 (模型, 事件) 只打一条诊断日志。 */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private OpenYsmScriptRuntime() {}

    /**
     * 每帧在漫游变量注入之后调用一次（{@code CustomPlayerModel.setMolangQueries} →
     * {@code MolangPhysicsRuntime.begin}）。
     *
     * @param player  渲染的玩家；预览（player == null）不跑脚本
     * @param modelId 当前主模型
     */
    public static void runFrameScripts(EntityPlayer player, ResourceLocation modelId) {
        if (player == null || modelId == null) {
            return;
        }
        // 注意：即使这个模型没有任何脚本，也要更新"上一帧模型"并重新武装旧模型的 init 标记 ——
        // 否则 A → B(无脚本) → A 回到 A 时 init 不会重跑（wiki：player_init 的触发时机是
        // "玩家切换到该模型或玩家实体加载时"，切换回来属于"切换到该模型"）。
        boolean justSwitchedToThisModel =
            shouldRunPlayerInit(INITIALISED, LAST_RENDERED_MODEL, player.getUniqueID(), modelId);
        if (!MolangScriptRegistry.hasScripts(modelId)) {
            return;
        }
        if (justSwitchedToThisModel) {
            runEvent(player, modelId, MolangScriptRegistry.EVENT_PLAYER_INIT);
        }
        runEvent(player, modelId, MolangScriptRegistry.EVENT_PLAYER_UPDATE);
    }

    /**
     * 记录该玩家这一帧的模型，并回答"本帧是否应当触发 {@code player_init}"。
     *
     * <p>语义（wiki: molang/script「事件订阅」—— {@code player_init} 在"玩家切换到该模型或玩家
     * 实体加载时"触发）：</p>
     * <ul>
     *   <li>第一次看到 (玩家, 模型) → 触发；</li>
     *   <li>同一模型连续渲染 → 不重复触发；</li>
     *   <li>玩家切到别的模型时，把**旧模型**的已初始化标记清掉，于是 A→B→A 回到 A 会重新触发
     *       {@code player_init}（init 里建立的随机种子/预置变量因此不会停留在上一轮的值）。</li>
     * </ul>
     *
     * <p>抽成静态纯函数（表和 map 由调用方传入）是为了能在没有 Minecraft 环境的单测里覆盖
     * 切换序列。</p>
     */
    static boolean shouldRunPlayerInit(Set<String> initialised,
        java.util.Map<UUID, ResourceLocation> lastRenderedModel, UUID playerId, ResourceLocation modelId) {
        if (initialised == null || lastRenderedModel == null || playerId == null || modelId == null) {
            return false;
        }
        ResourceLocation previous = lastRenderedModel.put(playerId, modelId);
        if (previous != null && !previous.equals(modelId)) {
            initialised.remove(playerId + "|" + previous);
        }
        return initialised.add(playerId + "|" + modelId);
    }

    /**
     * 触发某个玩家某个模型的 {@code sync} 事件（wiki: molang/script「主动同步」）。
     *
     * <p>由下行广播包调用：所有客户端（含发起者）用**同样的参数**跑该模型的 {@code sync} 脚本，
     * 于是随机数/预置变量这类"各客户端不一致"的值被拉齐。触发时机自然晚于
     * {@code player_init}/{@code player_update}（那两个在渲染帧里跑）。</p>
     *
     * <p>它在渲染帧之外执行，所以必须显式带上（发起者玩家, 模型）的变量作用域 ——
     * 否则脚本里的 {@code v.*} 赋值会因为 {@code MolangPhysicsRuntime} 没有帧上下文而丢失
     * （见 {@code runWithVariableScope}）。</p>
     *
     * @param player  发起者在本地世界里的实体；找不到/模型不在本地时调用方应跳过
     * @param modelId 发起者当前的主模型
     * @param arguments 同步参数（最多 16 个，服务端已截断）
     */
    public static void runSyncScripts(EntityPlayer player, ResourceLocation modelId, List<Double> arguments) {
        if (player == null || modelId == null) {
            return;
        }
        // @sync 由主线程调度任务在渲染帧之外触发：没有帧上下文时
        // MolangPhysicsRuntime.setVariable 返回 false，脚本里的 v.* 赋值会被全部丢弃。
        // 这里临时挂上（发起者玩家, 该模型）的变量作用域，写回落进下一帧 begin() 读的
        // 同一份 ScopeState —— 既保住跨帧可见性，也不会写进当前正在渲染的其他玩家/模型。
        com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime.runWithVariableScope(player, modelId,
            () -> runEvent(player, modelId, MolangScriptRegistry.EVENT_SYNC, arguments));
    }

    private static void runEvent(EntityPlayer player, ResourceLocation modelId, String event) {
        runEvent(player, modelId, event, null);
    }

    private static void runEvent(EntityPlayer player, ResourceLocation modelId, String event, List<Double> arguments) {
        List<String> scripts = MolangScriptRegistry.eventScripts(modelId, event);
        if (scripts.isEmpty()) {
            return;
        }
        if (com.fox.ysmu.Config.DEBUG_CONTROLLER && LOGGED.add(modelId + "|" + event)) {
            ysmu.LOG.info("[YSMU-MOLANG-SCRIPT] {} {} -> {} script(s): {}", modelId, event, scripts.size(),
                MolangScriptRegistry.eventFunctionNames(modelId, event));
        }
        OpenYsmScriptScope scope = new OpenYsmScriptScope(player, null, modelId, arguments);
        for (String script : scripts) {
            try {
                MolangScriptInterpreter.evaluate(script, scope);
            } catch (RuntimeException e) {
                if (LOGGED.add(modelId + "|" + event + "|failed")) {
                    ysmu.LOG.warn("[YSMU-MOLANG-SCRIPT] {} {} failed to execute (first failure only)", modelId,
                        event, e);
                }
            }
        }
    }

    /** 模型缓存全量刷新时调用：脚本表换了，{@code player_init} 必须重跑。 */
    public static void clear() {
        INITIALISED.clear();
        LAST_RENDERED_MODEL.clear();
        LOGGED.clear();
    }

    /** 玩家登出：不必留着"已初始化"标记。 */
    public static void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        String prefix = playerId.toString() + "|";
        INITIALISED.removeIf(key -> key.startsWith(prefix));
        LAST_RENDERED_MODEL.remove(playerId);
    }
}
