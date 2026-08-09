package com.fox.ysmu.client.animation.molang;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;
import com.fox.ysmu.eep.ExtendedModelInfo;

import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * /ysm reset 的客户端清理入口。只清"用户可设/帧间保留"的 Molang 状态，
 * 绝不触碰模型定义（MODEL_ROAMING_VARS / MODEL_ROAMING_DEFAULTS）——
 * 那些是模型 ysm.json 注册的"说明书"，清掉会破坏模型功能。
 *
 * 分层：
 * - {@link #resetSelf()}：只清当前本地玩家自己的状态（按 playerId + 当前模型过滤），
 *   由客户端命令 {@code /ysmclient reset} 或服务端 {@code /ysm reset <selector>}
 *   转发的 {@code PacketResetMolang} 调用。
 * - {@link #resetAll()}：清全部玩家状态（/ysm reset @a 时每个客户端都执行）。
 *
 * 与模型文件缓存无关，因此 reset 不需要重载模型。
 */
public final class MolangReset {

    private MolangReset() {}

    /**
     * 重置本地玩家自己的 Molang 变量状态。
     * 在客户端主线程调用（/ysmclient reset 或收到 PacketResetMolang 后）。
     */
    public static void resetSelf() {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return;
        }
        // 1. 清帧间物理/变量状态（ScopeState）—— 只清自己的
        MolangPhysicsRuntime.clearPlayer(player.getUniqueID());
        // 2. 清控制器运行时状态 —— 只清自己的
        OpenYsmPlayerControllerRuntime.clearPlayer(player.getUniqueID());
        // 3. 清自己当前模型的用户漫游变量（回落模型默认值）
        ResourceLocation modelId = getLocalModelId(player);
        if (modelId != null) {
            OpenYsmPlayerControllerRuntime.resetUserRoamingVars(modelId);
        }
        // 4. 清 MolangParser.VARIABLES 里的 v.* 残留（computeIfAbsent 或动画写入的），
        //    保留 query.*/ysm.*/math.*/ctrl.* 等注册项（每帧重填或常量）。
        clearParserVariablesByPrefix("v.");
        // 5. 清掉本玩家帧快照（overlay 数据源），让 overlay 立即反映重置后的状态
        MolangPhysicsRuntime.clearLastFrameSnapshot();
    }

    /**
     * 重置全部 Molang 变量用户状态（/ysm reset @a）。
     * 全服场景下全局 map 可放心全清（清的是所有人的用户设置）。
     */
    public static void resetAll() {
        // 帧间物理/变量状态 + 控制器状态 + 骨骼矩阵快照全清
        MolangPhysicsRuntime.clear();
        OpenYsmPlayerControllerRuntime.clear();
        // 全部用户漫游变量清零（保留模型定义）
        OpenYsmPlayerControllerRuntime.resetAllUserRoamingVars();
        // 清 MolangParser.VARIABLES 里的 v.* 残留
        clearParserVariablesByPrefix("v.");
    }

    /** 取本地玩家当前模型 ID（可能为 null）。 */
    private static ResourceLocation getLocalModelId(EntityPlayer player) {
        ExtendedModelInfo eep = ExtendedModelInfo.get(player);
        return eep == null ? null : eep.getModelId();
    }

    /** 清掉 MolangParser.VARIABLES 中指定前缀的条目（如 "v."）。 */
    private static void clearParserVariablesByPrefix(String prefix) {
        List<String> toRemove = new ArrayList<>();
        for (String key : MolangParser.VARIABLES.keySet()) {
            if (key.startsWith(prefix)) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            MolangParser.VARIABLES.remove(key);
        }
    }
}
