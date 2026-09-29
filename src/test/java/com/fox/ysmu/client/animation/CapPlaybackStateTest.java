package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * CAP 控制器播放簿记必须**按演员**分开（R5 的前半）。
 *
 * <p>两个玩家用同一个模型时，双方都持有自己的 {@code cap_controller} 实例，但控制器**名字**
 * 相同。原先「上一帧是否在播外部动画」与「已应用的轮盘版本」各是一个 {@code static} 变量，
 * 被所有玩家共用，于是：</p>
 * <ul>
 *   <li>A 在播 EEP 动画时置真 → B（没有外部动画）走到末尾停止分支读到 A 的状态，
 *       清掉标志并停掉 {@code cap_controller} 的音效，停的是 A 那条；</li>
 *   <li>B 的 EEP 请求刚下达（控制器仍 Stopped）会被 A 留下的标志判成"已播完"，
 *       于是在开播之前就被 {@code stopAnimation()} 掉；</li>
 *   <li>点一次轮盘只让第一个被处理的演员重载控制器，其余演员的关键帧不重置。</li>
 * </ul>
 *
 * <p>这里用两个固定 UUID 直接驱动这套簿记（不需要 Minecraft 实体），断言交错序列下两个演员
 * 互不影响 —— 这正是"两个玩家同模型同时播轮盘动画/音效"要保证的东西。</p>
 */
class CapPlaybackStateTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void oneActorsPlaybackDoesNotMakeAnotherActorLookStoppedOrPlaying() {
        CapPlaybackState state = new CapPlaybackState();
        assertTrue(state.begin(A), "A's first begin must be a rising edge");
        assertTrue(state.isPlaying(A));
        // 回归点：B 必须完全是"没在播"，而不是看到 A 的状态。
        assertFalse(state.isPlaying(B), "B must not inherit A's playing flag");
        assertFalse(state.end(B), "ending B must report 'was not playing' and must not touch A");
        assertTrue(state.isPlaying(A), "A's playback must survive B being evaluated");
    }

    @Test
    void endingOneActorDoesNotEndTheOther() {
        CapPlaybackState state = new CapPlaybackState();
        state.begin(A);
        state.begin(B);
        assertTrue(state.end(B));
        assertFalse(state.isPlaying(B));
        assertTrue(state.isPlaying(A), "B's stop must not stop A");
        assertTrue(state.end(A));
        assertFalse(state.isPlaying(A));
    }

    @Test
    void risingEdgeIsReportedOnlyOncePerContinuousPlayback() {
        CapPlaybackState state = new CapPlaybackState();
        assertTrue(state.begin(A), "first frame is the rising edge");
        assertFalse(state.begin(A), "later frames of the same playback are not a new edge");
        state.end(A);
        assertTrue(state.begin(A), "a new playback is a new edge (used for the deduplicated log)");
    }

    @Test
    void wheelVersionIsConsumedPerActor() {
        CapPlaybackState state = new CapPlaybackState();
        // 首次求值只记录当前版本、不触发重载：与旧全局标志的初值语义一致
        // （否则每个演员的第一帧都会把 cap 动画重置到 tick 0 —— 那是新的可见行为变化）。
        assertFalse(state.consumeWheelVersion(A, 0), "first evaluation must only record the version");
        assertFalse(state.consumeWheelVersion(B, 0));
        // 点一次轮盘（版本 1）：两个演员都必须各自重载一次自己的控制器。
        assertTrue(state.consumeWheelVersion(A, 1), "A must reset its controller for version 1");
        assertTrue(state.consumeWheelVersion(B, 1), "B must also reset, not be skipped as 'already seen'");
        // 同一版本内重复求值：不重复重载（否则每帧重置关键帧，音效刷屏）。
        assertFalse(state.consumeWheelVersion(A, 1));
        assertFalse(state.consumeWheelVersion(B, 1));
        // 再点一次（版本 2）：两个演员都要重载。
        assertTrue(state.consumeWheelVersion(A, 2));
        assertTrue(state.consumeWheelVersion(B, 2));
    }

    @Test
    void anActorAppearingAfterTheClickDoesNotRetroactivelyReset() {
        // 与旧全局语义一致：新出现的演员（后进服/刚被渲染到）第一次求值只记录当前版本，
        // 不因为"它没见过这次点击"而重置 —— 那次点击本来就不是它触发的。
        CapPlaybackState state = new CapPlaybackState();
        assertFalse(state.consumeWheelVersion(A, 0)); // A 的首次求值只记录
        assertTrue(state.consumeWheelVersion(A, 3), "A sees the new wheel version and resets once");
        assertFalse(state.consumeWheelVersion(B, 3), "B appearing later must not replay A's click");
        assertFalse(state.consumeWheelVersion(B, 3));
    }

    /**
     * 会话结束（{@code AnimationManager.clearAllCapPlayback}）必须把所有演员的簿记丢掉，
     * 包括"已应用的轮盘版本"：否则下一场会话的第一帧会误判成"来了个新版本"而重置一次控制器，
     * "首次求值只记录"这条规则也就不成立了。与 {@link #clearIsolatesOneActor} 的单演员清理区分。
     */
    @Test
    void clearAllDropsEveryActorSoTheNextSessionStartsFresh() {
        CapPlaybackState state = new CapPlaybackState();
        state.consumeWheelVersion(A, 5);
        state.consumeWheelVersion(B, 5);
        state.begin(A);

        state.clearAll();

        assertEquals(0, state.trackedActors());
        assertFalse(state.isPlaying(A));
        assertFalse(state.consumeWheelVersion(A, 5), "the remembered version must be gone too");
        assertFalse(state.consumeWheelVersion(B, 5));
        assertTrue(state.consumeWheelVersion(A, 6), "a genuinely new version still fires");
    }

    @Test
    void clearIsolatesOneActorAndIsUsedOnModelSwitchOrLogout() {
        CapPlaybackState state = new CapPlaybackState();
        state.begin(A);
        state.begin(B);
        state.consumeWheelVersion(B, 7);
        state.clear(A);
        assertFalse(state.isPlaying(A), "cleared actor starts fresh");
        assertEquals(1, state.trackedActors());
        assertTrue(state.isPlaying(B), "clearing A must not touch B");
        // B 记得版本 7：新版本 8 对它仍然要触发一次重载。
        assertTrue(state.consumeWheelVersion(B, 8), "B must still see a genuinely new version");
        assertFalse(state.consumeWheelVersion(B, 8), "and only once per version");
        // A 被清过（等价于换模型/登出后重新出现）：首次求值只记录，不重置。
        assertFalse(state.consumeWheelVersion(A, 8), "a cleared actor only records on its first evaluation");
        assertTrue(state.consumeWheelVersion(A, 9));
        assertTrue(state.begin(A), "a cleared actor starts a fresh playback");
    }
}
