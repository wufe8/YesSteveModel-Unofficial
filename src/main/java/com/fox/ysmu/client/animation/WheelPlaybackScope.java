package com.fox.ysmu.client.animation;

import java.util.UUID;

import net.minecraft.client.Minecraft;

import com.fox.ysmu.client.animation.controller.RoamingVariables;

/**
 * 「本机轮盘选择」的作用域：它属于**本机玩家这一个演员**，不属于所有被渲染的演员。
 *
 * <h3>为什么需要一个显式的作用域</h3>
 * <p>轮盘（{@code AnimationRouletteScreen}）在选中动画时写下三样东西：全局漫游变量
 * {@code PENDING_ROAMING["wheel_anim"] = 1}、锁开关 {@code PENDING_ROAMING["lock_wheel"]}，
 * 以及 {@code AnimationManager.currentWheelAnim} 里那个动画名。这三样都只描述**本机玩家**的
 * 选择 —— 别人的轮盘在别人的客户端上。</p>
 *
 * <p>但读它们的三处代码都是**按演员**执行的（同一个渲染 pass 里对每个玩家、每帧各跑一次）：</p>
 * <ol>
 *   <li>{@code AnimationManager.predicateCap}：命中就 {@code playAnimation(本机选的动画名)}；</li>
 *   <li>{@code AnimationManager.predicateMain}：命中就把主控制器压成 {@code idle}（保持腿部姿势）；</li>
 *   <li>{@code OpenYsmControllerExpressionEvaluator.isPlayingExtraAnimation}：命中就让
 *       {@code ctrl.playing_extra_animation} 为真。</li>
 * </ol>
 *
 * <p>没有任何归属判断时，本机玩家一锁轮盘，**其他每个演员**都会跟着进这三条分支：用同一个模型的
 * 两个玩家（正是"多人同模型"的验收场景）里，对方会替你播你选的那条轮盘动画、双腿被压成 idle、
 * 以及模型里以 {@code ctrl.playing_extra_animation} 为条件的控制器状态在对方身上被点亮。
 * 这和 R5 修的 {@code capWasPlaying} 是同一类缺陷（全局状态被按演员的代码消费），
 * 只是发生在轮盘分支上。</p>
 *
 * <h3>修法为什么不改本机表现</h3>
 * <p>远端演员的轮盘动画本来就该走他们自己的 EEP：服务端把每个玩家的
 * {@code play_animation}/{@code animation} 广播到我们的客户端（{@code SyncModelInfo} →
 * {@code ExtendedModelInfo.get(对方)}），与全局轮盘状态无关。所以加归属判断后，本机玩家的行为
 * 逐字不变（本机就是 owner），远端演员回到"由他们自己的 EEP 驱动"这条正确路径上。</p>
 */
public final class WheelPlaybackScope {

    private WheelPlaybackScope() {}

    /**
     * 本机轮盘选择是否适用于这个演员：只有本机玩家能消费它。
     *
     * <p>纯函数，便于把真值表钉在测试里（测试环境没有客户端，取不到 {@code thePlayer}）。</p>
     */
    public static boolean appliesTo(UUID actorId, UUID localPlayerId) {
        return actorId != null && localPlayerId != null && localPlayerId.equals(actorId);
    }

    /**
     * 轮盘锁 + 已选动画同时成立（本机轮盘当前的"锁定播放"状态）。
     *
     * <p>两个 key 都由轮盘界面写：锁开关写在 {@code lock_wheel}，选中动画时写
     * {@code wheel_anim=1}；解锁时会 remove 掉 {@code wheel_anim}。</p>
     */
    public static boolean isLocked() {
        return RoamingVariables.PENDING_ROAMING.getOrDefault("lock_wheel", 0.0) > 0
            && RoamingVariables.PENDING_ROAMING.getOrDefault("wheel_anim", 0.0) > 0;
    }

    /**
     * 这个演员此刻应当由本机轮盘选择驱动的动画名；没有（不是本机玩家 / 未锁 / 没选）则返回 null。
     *
     * <p>返回 null 与旧代码"gate 不成立时落到 EEP 分支"的行为一致，调用方只需判空。</p>
     */
    public static String wheelAnimationFor(UUID actorId, UUID localPlayerId) {
        if (!appliesTo(actorId, localPlayerId) || !isLocked()) {
            return null;
        }
        return AnimationManager.getCurrentWheelAnimName();
    }

    /** {@link #wheelAnimationFor} 的本机取数版：自己去找 {@code thePlayer}。 */
    public static String wheelAnimationForLocalActor(UUID actorId) {
        return wheelAnimationFor(actorId, localPlayerIdOrNull());
    }

    /** 本机轮盘的锁定播放是否应当作用于这个演员（用于让主控制器保持 idle 腿姿势）。 */
    public static boolean locksMainControllerOf(UUID actorId) {
        return appliesToLocalPlayer(actorId) && isLocked();
    }

    /** {@code ctrl.playing_extra_animation} 的轮盘部分是否适用于这个演员。 */
    public static boolean countsAsExtraAnimationFor(UUID actorId) {
        return appliesToLocalPlayer(actorId)
            && isLocked()
            && AnimationManager.getCurrentWheelAnimName() != null;
    }

    /** {@link #appliesTo} 的本机取数版。 */
    public static boolean appliesToLocalPlayer(UUID actorId) {
        return appliesTo(actorId, localPlayerIdOrNull());
    }

    /**
     * 本机会话结束时清掉轮盘选择：锁、已选标记、动画名。
     *
     * <p>为什么要清：这三样都跨世界存活（{@code PENDING_ROAMING} 是静态表，动画名是静态字段），
     * 而"锁定播放哪条轮盘动画"显然是一次会话里的选择。不清的话，带着锁退出世界再进另一个
     * 服务器/存档，本机玩家（以及远端演员，在加归属判断之前）会立刻开始播上一个世界里选的那条
     * 动画 —— 玩家在新会话里从没做过这个选择。{@code /ysm reset} 与轮盘解锁走的是同一条清理
     * （{@link RoamingVariables#resetUserRoamingVars} / 界面的 remove），这里只是把它接到
     * "会话结束"这个边界上。</p>
     *
     * <p>清空时同时推进动画版本号（{@code setCurrentWheelAnimName(null)} 内部自增），
     * 这样清空之后重新选中会是一次"新版本"，每个演员各自重载一次控制器 —— 与手动点轮盘一致。</p>
     */
    public static void clearLocalSelection() {
        // 锁与"已选"标记在漫游表里；动画名本身在 AnimationManager 的静态字段里，不在表里。
        RoamingVariables.PENDING_ROAMING.remove("lock_wheel");
        RoamingVariables.PENDING_ROAMING.remove("wheel_anim");
        AnimationManager.setCurrentWheelAnimName(null);
    }

    /**
     * 本机玩家 UUID；拿不到就返回 null（此时 {@link #appliesTo} 一律为假 → 不做任何轮盘驱动）。
     *
     * <p>捕获 {@link Throwable} 而不是 {@link NullPointerException}：客户端类在"没有客户端实例"
     * 的环境里连静态初始化都会以 {@code NoClassDefFoundError} 失败（测试 JVM 没有 LWJGL 就是这样）。
     * 那正是"本机玩家未知"，与 {@code thePlayer == null}（退出世界的瞬间）应走同一条安全分支：
     * 不驱动任何人 —— 少驱动的那一侧由各自 EEP 里的动画状态兜住，而放宽成"未知即本机"会让远端
     * 演员重新被本机轮盘驱动（就是本类要修的缺陷）。</p>
     */
    static UUID localPlayerIdOrNull() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc == null || mc.thePlayer == null ? null : mc.thePlayer.getUniqueID();
        } catch (Throwable t) {
            return null;
        }
    }
}
