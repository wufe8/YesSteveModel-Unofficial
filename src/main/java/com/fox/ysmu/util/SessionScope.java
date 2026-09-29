package com.fox.ysmu.util;

import java.util.UUID;

/**
 * 「这条玩家事件说的是不是我本机那个玩家」——R3 里最容易写错、而且写错方向不对称的一步。
 *
 * <h3>为什么需要单独一个判定</h3>
 * <p>{@code PlayerLoggedOutEvent} 由**服务端**玩家列表路径发出
 * （{@code ServerConfigurationManager.playerLoggedOut} → {@code firePlayerLoggedOut}），
 * 而事件里带的是服务端的 {@code EntityPlayerMP}：它与客户端渲染用的 {@code mc.thePlayer}
 * **不是同一个对象**，所以不能按实例比较，必须按 UUID。集成服务器/局域网主机上，
 * **任何**客人登出都会在服主的客户端触发同一个事件；原先的清理不区分是谁登出，
 * 于是客人离开会把服主自己的会话密钥一起清掉。</p>
 *
 * <h3>失败方向是不对称的，这一点刻意保留</h3>
 * <p>本方法只可能在 {@code thePlayer} 已经为 null（或 UUID 不匹配）时返回 false —— 也就是
 * 只可能**少清**，不可能把别人的登出当好本地登出。少清的那一侧由另外两条路径兜住：
 * {@code ClientDisconnectionFromServerEvent}（远端断线唯一可靠通知）与客户端 tick 的
 * 「上一 tick 还在世界、这一 tick 世界与玩家都为 null」兜底。
 * 反过来若在这里放宽（例如本地玩家未知时默认当作本地登出），就会重新引入
 * "客人登出清掉服主会话"这个原始缺陷。</p>
 */
public final class SessionScope {

    private SessionScope() {}

    /**
     * @param loggedOutPlayer UUID of the player in the logout event (server-side object)
     * @param localPlayer     UUID of {@code Minecraft.getMinecraft().thePlayer}, or null if unknown
     * @return true only when both are known and identical
     */
    public static boolean isLocalPlayer(UUID loggedOutPlayer, UUID localPlayer) {
        return localPlayer != null && localPlayer.equals(loggedOutPlayer);
    }
}
