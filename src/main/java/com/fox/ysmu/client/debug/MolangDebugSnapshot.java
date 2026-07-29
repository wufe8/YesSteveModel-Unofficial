package com.fox.ysmu.client.debug;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;

import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * 捕获并格式化 Molang 变量快照，用于调试命令和后续的 debug overlay。
 * 所有方法仅应在客户端调用。
 */
public final class MolangDebugSnapshot {

    private MolangDebugSnapshot() {}

    /** 聊天前缀 */
    public static final String CHAT_PREFIX = "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r";

    /**
     * 读取单个变量的当前值。
     *
     * @param name 变量名，如 {@code "ysm.person_view"}、{@code "query.health"}、{@code "v.roaming.bq_eye"}
     * @return 变量值，如果未知则返回 {@code NaN}
     */
    public static double queryVariable(String name) {
        // 1. 静态注册变量 (query.* / ysm.* / math.*)
        software.bernie.geckolib3.core.molang.LazyVariable var =
            MolangParser.VARIABLES.get(name);
        if (var != null) {
            return var.get();
        }
        // 2. v.* 变量 — 检查 PENDING_ROAMING（GUI/轮盘设置的漫游值）
        if (name.startsWith("v.")) {
            String key = name.substring(2);
            Double roamingVal = OpenYsmPlayerControllerRuntime.PENDING_ROAMING.get(key);
            if (roamingVal != null) return roamingVal;
            // 也查一下 MolangParser.VARIABLES（InstrutionExecutor 写入的）
            software.bernie.geckolib3.core.molang.LazyVariable gVar =
                MolangParser.VARIABLES.get(name);
            if (gVar != null) return gVar.get();
            return Double.NaN;
        }
        // 3. ctrl.* 控制器状态
        if (name.startsWith("ctrl.")) {
            return queryCtrlState(name.substring("ctrl.".length()));
        }
        return Double.NaN;
    }

    /**
     * 批量读取一组变量。
     */
    public static Map<String, Double> queryVariables(List<String> names) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String name : names) {
            double value = queryVariable(name);
            if (!Double.isNaN(value)) {
                result.put(name, value);
            }
        }
        return result;
    }

    /**
     * 查询匹配通配符模式的所有变量。
     * 支持: {@code "ysm.*"}, {@code "query.is_*"}, {@code "v.roaming.*"}
     */
    public static Map<String, Double> queryWildcard(String pattern) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (!pattern.endsWith("*")) {
            double v = queryVariable(pattern);
            if (!Double.isNaN(v)) result.put(pattern, v);
            return result;
        }
        String prefix = pattern.substring(0, pattern.length() - 1);
        // 扫描 MolangParser.VARIABLES 中的所有注册变量
        for (Map.Entry<String, software.bernie.geckolib3.core.molang.LazyVariable> entry
            : MolangParser.VARIABLES.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey(), entry.getValue().get());
            }
        }
        // v.roaming.* 通配符 — 扫描 PENDING_ROAMING
        if ("v.roaming.*".equals(pattern) || "v.*".equals(pattern)) {
            String roamingPrefix = "v.roaming.*".equals(pattern) ? "v.roaming." : "v.";
            for (Map.Entry<String, Double> entry
                : OpenYsmPlayerControllerRuntime.PENDING_ROAMING.entrySet()) {
                result.put(roamingPrefix + entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    // ---- 聊天输出 ----

    /**
     * 在聊天框输出一组变量值。
     */
    public static void printToChat(Map<String, Double> values) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return;
        if (values.isEmpty()) {
            player.addChatMessage(new ChatComponentText(
                CHAT_PREFIX + " §eNo variables matched."));
            return;
        }
        player.addChatMessage(new ChatComponentText(
            CHAT_PREFIX + " §a" + values.size() + " variable(s):"));
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            player.addChatMessage(new ChatComponentText(
                "  §e" + entry.getKey() + "§r = " + formatValue(entry.getValue())));
        }
    }

    /**
     * 在聊天框输出单个变量值。
     */
    public static void printSingleToChat(String name) {
        double value = queryVariable(name);
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return;
        if (Double.isNaN(value)) {
            player.addChatMessage(new ChatComponentText(
                CHAT_PREFIX + " §e" + name + "§r = §7<unknown>"));
        } else {
            player.addChatMessage(new ChatComponentText(
                CHAT_PREFIX + " §e" + name + "§r = " + formatValue(value)));
        }
    }

    // ---- 内部辅助 ----

    private static String formatValue(double value) {
        if (Double.isNaN(value)) return "§7NaN";
        if (Double.isInfinite(value)) return "§c" + (value > 0 ? "+∞" : "-∞");
        if (Math.abs(value) < 0.000001) return "§70.0§r (false)";
        if (Math.abs(value - 1.0) < 0.000001) return "§a1.0§r (true)";
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return "§b" + (long) value + ".0";
        }
        return String.format("§b%.6f", value);
    }

    private static double queryCtrlState(String name) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return Double.NaN;
        if ("death".equals(name)) return player.isDead ? 1.0 : 0.0;
        if ("sleep".equals(name)) return player.isPlayerSleeping() ? 1.0 : 0.0;
        if ("sneak".equals(name) || "sneaking".equals(name)) return player.isSneaking() ? 1.0 : 0.0;
        if ("fly".equals(name)) return player.capabilities.isFlying ? 1.0 : 0.0;
        if ("jump".equals(name)) return (!player.onGround && !player.isInWater()) ? 1.0 : 0.0;
        if ("ride".equals(name)) return player.isRiding() ? 1.0 : 0.0;
        if ("swim".equals(name)) return player.isInWater() ? 1.0 : 0.0;
        if ("idle".equals(name)) return 1.0;
        return Double.NaN;
    }
}
