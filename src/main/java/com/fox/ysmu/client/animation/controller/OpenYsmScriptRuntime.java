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
 * {@code sync} 需要网络包（三期），这里不触发。
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

    private static void runEvent(EntityPlayer player, ResourceLocation modelId, String event) {
        List<String> scripts = MolangScriptRegistry.eventScripts(modelId, event);
        if (scripts.isEmpty()) {
            return;
        }
        if (com.fox.ysmu.Config.DEBUG_CONTROLLER && LOGGED.add(modelId + "|" + event)) {
            ysmu.LOG.info("[YSMU-MOLANG-SCRIPT] {} {} -> {} script(s): {}", modelId, event, scripts.size(),
                MolangScriptRegistry.eventFunctionNames(modelId, event));
        }
        OpenYsmScriptScope scope = new OpenYsmScriptScope(player, null, modelId, null);
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
