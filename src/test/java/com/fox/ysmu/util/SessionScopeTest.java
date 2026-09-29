package com.fox.ysmu.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * 「这次登出是不是本地玩家」的判定（R3 / 局域网客人登出不必再靠第二个客户端验证的那一半）。
 *
 * <p>缺陷原状：集成服务器上**任何**客人登出都会在服主客户端触发 {@code PlayerLoggedOutEvent}，
 * 而清理不区分是谁，于是客人离开会清掉服主自己的会话密钥（`clientKey`/`PASSWORD`/同步状态），
 * 服主后续的懒加载重解密全部失效。</p>
 *
 * <p>修法＝按 UUID 判定归属。测试环境无法构造真实的 {@code EntityPlayerMP}/客户端玩家，
 * 但这条判定的全部逻辑就是"两个 UUID 是否相同、本地是否已知"，因此可以用 UUID 直接钉住；
 * 真正需要实机验证的只剩"事件有没有被派发到客户端"这一件事（专用服断线走
 * ClientDisconnectionFromServerEvent，见 ClientEventHandler）。</p>
 */
class SessionScopeTest {

    private static final UUID HOST = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GUEST = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void aGuestLoggingOutIsNotTheLocalPlayer() {
        // 这就是局域网主机的场景：客人登出时本地玩家是服主 —— 必须判为"不是本地"，
        // 否则服主的会话密钥会被清掉。
        assertFalse(SessionScope.isLocalPlayer(GUEST, HOST));
    }

    @Test
    void theLocalPlayerLoggingOutIsRecognised() {
        assertTrue(SessionScope.isLocalPlayer(HOST, HOST));
    }

    @Test
    void unknownLocalPlayerCanOnlyUnderTrigger() {
        // 本地玩家未知（退出世界的瞬间 thePlayer 可能已经为 null）：只允许"少清"。
        // 少清由 ClientDisconnectionFromServerEvent 与客户端 tick 兜底；
        // 若这里放宽成"未知即当作本地"，就会重新引入"客人登出清掉服主会话"的原始缺陷。
        assertFalse(SessionScope.isLocalPlayer(HOST, null));
        assertFalse(SessionScope.isLocalPlayer(GUEST, null));
    }

    @Test
    void nullEventPlayerIsNeverTheLocalPlayer() {
        // event.player 为 null 时不该被当成"本地登出"。
        assertFalse(SessionScope.isLocalPlayer(null, HOST));
    }
}
