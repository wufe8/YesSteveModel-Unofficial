package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.controller.RoamingVariables;

/**
 * 本机轮盘选择的作用域（{@link WheelPlaybackScope}）。
 *
 * <p>回归的是"全局 UI 状态被按演员的代码消费"这一类缺陷在轮盘分支上的三处实例：本机玩家一锁轮盘，
 * 原先**每个被渲染的演员**都会进这三条分支 —— 用同一个模型的两个玩家（多人同模型的验收场景）里，
 * 对方会替你播你选的轮盘动画、双腿被压成 idle、以及 {@code ctrl.playing_extra_animation}
 * 在他们身上被点亮。</p>
 *
 * <p>本测试覆盖纯决策部分（真值表 + 清理语义），这些不需要客户端；真正需要实机看的只有
 * "同屏两人时画面/声音是否互相干扰"。{@code localPlayerIdOrNull()} 在测试环境取不到客户端
 * （没有 LWJGL，{@code Minecraft} 的静态初始化会失败），所以它一律返回 null —— 这也正是一条
 * 要钉住的边界：**本机玩家未知时不驱动任何演员**。</p>
 */
class WheelPlaybackScopeTest {

    private static final UUID LOCAL = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REMOTE = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @BeforeEach
    @AfterEach
    void clearWheelState() {
        RoamingVariables.PENDING_ROAMING.remove("lock_wheel");
        RoamingVariables.PENDING_ROAMING.remove("wheel_anim");
        AnimationManager.setCurrentWheelAnimName(null);
    }

    private static void lockLocalWheel(String animationName) {
        RoamingVariables.PENDING_ROAMING.put("lock_wheel", 1.0);
        RoamingVariables.PENDING_ROAMING.put("wheel_anim", 1.0);
        AnimationManager.setCurrentWheelAnimName(animationName);
    }

    @Test
    void theLocalPlayerOwnsTheSelectionAndNobodyElse() {
        assertTrue(WheelPlaybackScope.appliesTo(LOCAL, LOCAL));
        // 回归点：远端演员不得消费本机轮盘选择。
        assertFalse(WheelPlaybackScope.appliesTo(REMOTE, LOCAL));
        // 本机玩家未知（退出世界瞬间、无客户端环境）→ 谁都不驱动。
        assertFalse(WheelPlaybackScope.appliesTo(LOCAL, null));
        assertFalse(WheelPlaybackScope.appliesTo(REMOTE, null));
        assertFalse(WheelPlaybackScope.appliesTo(null, LOCAL));
    }

    @Test
    void unlockedWheelDrivesNobody() {
        assertFalse(WheelPlaybackScope.isLocked());
        assertNull(WheelPlaybackScope.wheelAnimationFor(LOCAL, LOCAL));

        // 只写了动画名、没锁：仍然不驱动（旧代码要求两个 key 都 > 0）。
        RoamingVariables.PENDING_ROAMING.put("wheel_anim", 1.0);
        AnimationManager.setCurrentWheelAnimName("extra3");
        assertFalse(WheelPlaybackScope.isLocked());
        assertNull(WheelPlaybackScope.wheelAnimationFor(LOCAL, LOCAL));
    }

    @Test
    void lockedWheelDrivesOnlyTheLocalPlayer() {
        lockLocalWheel("extra3");
        assertTrue(WheelPlaybackScope.isLocked());
        assertEquals("extra3", WheelPlaybackScope.wheelAnimationFor(LOCAL, LOCAL));
        // 这是本轮修的核心：远端演员在同一个渲染 pass 里也必须拿到 null（→ 落到他们自己的 EEP 分支）。
        assertNull(WheelPlaybackScope.wheelAnimationFor(REMOTE, LOCAL));
    }

    @Test
    void lockedButNoAnimationSelectedFallsThrough() {
        // 锁开着但没选动画（解锁时只 remove 了 wheel_anim）：不驱动，落到 EEP 分支。
        RoamingVariables.PENDING_ROAMING.put("lock_wheel", 1.0);
        AnimationManager.setCurrentWheelAnimName(null);
        assertFalse(WheelPlaybackScope.isLocked());
        assertNull(WheelPlaybackScope.wheelAnimationFor(LOCAL, LOCAL));
    }

    @Test
    void lockValueZeroMeansUnlocked() {
        // 轮盘界面把锁写成 0.0（不是 remove）也算解锁。
        RoamingVariables.PENDING_ROAMING.put("lock_wheel", 0.0);
        RoamingVariables.PENDING_ROAMING.put("wheel_anim", 1.0);
        AnimationManager.setCurrentWheelAnimName("extra0");
        assertFalse(WheelPlaybackScope.isLocked());
        assertNull(WheelPlaybackScope.wheelAnimationFor(LOCAL, LOCAL));
    }

    @Test
    void withoutALocalClientNoActorIsDriven() {
        // 测试环境/无客户端：localPlayerIdOrNull() 为 null。
        assertNull(WheelPlaybackScope.localPlayerIdOrNull());
        lockLocalWheel("extra3");
        assertFalse(WheelPlaybackScope.appliesToLocalPlayer(LOCAL));
        assertNull(WheelPlaybackScope.wheelAnimationForLocalActor(LOCAL));
        assertFalse(WheelPlaybackScope.locksMainControllerOf(LOCAL), "must not force idle for anyone");
        assertFalse(WheelPlaybackScope.countsAsExtraAnimationFor(LOCAL));
    }

    @Test
    void mainControllerIdleAndExtraAnimationFlagsFollowTheSameOwnership() {
        lockLocalWheel("extra3");
        // 这两个开关同样只对本机玩家成立（旧实现是全局的）。
        assertFalse(WheelPlaybackScope.locksMainControllerOf(REMOTE));
        assertFalse(WheelPlaybackScope.countsAsExtraAnimationFor(REMOTE));
        // countsAsExtraAnimationFor 还要求确实选了动画；锁开着但名字为空时不算。
        AnimationManager.setCurrentWheelAnimName(null);
        assertFalse(WheelPlaybackScope.countsAsExtraAnimationFor(LOCAL));
        assertTrue(WheelPlaybackScope.isLocked(), "the lock flag itself is still set");
    }

    @Test
    void sessionEndClearsTheSelectionAndAdvancesTheWheelVersion() {
        lockLocalWheel("extra3");
        assertTrue(WheelPlaybackScope.isLocked());
        assertEquals("extra3", AnimationManager.getCurrentWheelAnimName());

        WheelPlaybackScope.clearLocalSelection();

        assertFalse(WheelPlaybackScope.isLocked(), "the lock must not survive into the next session");
        assertNull(AnimationManager.getCurrentWheelAnimName(), "and neither must the animation name");
        assertTrue(
            RoamingVariables.PENDING_ROAMING.getOrDefault("lock_wheel", 0.0) == 0.0
                && RoamingVariables.PENDING_ROAMING.getOrDefault("wheel_anim", 0.0) == 0.0,
            "both roaming keys must be gone, not merely zeroed");
    }

    @Test
    void clearingCountsAsARevisionSoActorsReloadOnTheNextSelection() {
        // 清理也要推进版本号：否则下次选中"同一个动画名"时，已记住旧版本的演员不会重载控制器，
        // 音效关键帧就不重放（与手动再点一次轮盘的行为必须一致）。
        lockLocalWheel("extra3");
        int beforeClear = wheelVersion();
        WheelPlaybackScope.clearLocalSelection();
        AnimationManager.getInstance()
            .clearAllCapPlayback();
        int afterClear = wheelVersion();
        assertNotEquals(beforeClear, afterClear, "clearing must bump the version");

        // 会话结束后的簿记是全新的（这正是 AnimationManager.clearAllCapPlayback 的效果）：
        // 第一次求值只记录，不重置。
        CapPlaybackState nextSession = new CapPlaybackState();
        assertFalse(
            nextSession.consumeWheelVersion(LOCAL, afterClear),
            "the next session's first evaluation must only record the version");
        assertFalse(
            nextSession.consumeWheelVersion(REMOTE, afterClear),
            "and so must every other actor's first evaluation");

        // 再选一次同一个动画名 → 版本又变 → 已经记过版本的演员这次要重载
        // （与手动再点一次轮盘的行为一致：一次点击对每个在场演员各生效一次）。
        lockLocalWheel("extra3");
        int afterReselect = wheelVersion();
        assertNotEquals(afterClear, afterReselect, "re-selecting must bump the version again");
        assertTrue(nextSession.consumeWheelVersion(LOCAL, afterReselect));
        assertTrue(nextSession.consumeWheelVersion(REMOTE, afterReselect), "every recorded actor reloads once");
    }

    /** 轮盘版本号是私有的：这里通过"再选一次会不会变"间接观察，故用一个反射取值。 */
    private static int wheelVersion() {
        try {
            java.lang.reflect.Field f = AnimationManager.class.getDeclaredField("wheelAnimVersion");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Exception e) {
            throw new AssertionError("wheelAnimVersion must stay a private static int field", e);
        }
    }
}
