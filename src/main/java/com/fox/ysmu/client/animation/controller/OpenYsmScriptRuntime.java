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
        if (player == null || modelId == null || !MolangScriptRegistry.hasScripts(modelId)) {
            return;
        }
        String key = player.getUniqueID() + "|" + modelId;
        if (INITIALISED.add(key)) {
            runEvent(player, modelId, MolangScriptRegistry.EVENT_PLAYER_INIT);
        }
        runEvent(player, modelId, MolangScriptRegistry.EVENT_PLAYER_UPDATE);
    }

    /**
     * 触发某个玩家某个模型的 {@code sync} 事件（wiki: molang/script「主动同步」）。
     *
     * <p>由下行广播包调用：所有客户端（含发起者）用**同样的参数**跑该模型的 {@code sync} 脚本，
     * 于是随机数/预置变量这类"各客户端不一致"的值被拉齐。触发时机自然晚于
     * {@code player_init}/{@code player_update}（那两个在渲染帧里跑）。</p>
     *
     * @param player  发起者在本地世界里的实体；找不到/模型不在本地时调用方应跳过
     * @param modelId 发起者当前的主模型
     * @param arguments 同步参数（最多 16 个，服务端已截断）
     */
    public static void runSyncScripts(EntityPlayer player, ResourceLocation modelId, List<Double> arguments) {
        if (player == null || modelId == null) {
            return;
        }
        runEvent(player, modelId, MolangScriptRegistry.EVENT_SYNC, arguments);
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
        LOGGED.clear();
    }

    /** 玩家登出：不必留着"已初始化"标记。 */
    public static void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        String prefix = playerId.toString() + "|";
        INITIALISED.removeIf(key -> key.startsWith(prefix));
    }
}
