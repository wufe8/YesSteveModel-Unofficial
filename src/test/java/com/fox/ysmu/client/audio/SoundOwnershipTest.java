package com.fox.ysmu.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 音效归属隔离：控制器名与音效名在玩家之间**天然重名**（每个玩家都有
 * {@code cap_controller}/{@code main_controller}，同一模型的两个玩家音效名也相同）。
 *
 * <p>原实现把「控制器 → 音效」与「音效 → 音源」都按裸名字索引，于是：A 还在播的关键帧音效
 * 会被 B 的同名播放"先停同名再播"停掉，B 的控制器停止又会把 A 正在播的音效一起停掉。
 * 修复办法是给两者都加上归属槽位（{@link YSMSoundManager#ownerKey}：模型持有者 UUID，
 * 预览/无主调用用本地槽位）。</p>
 *
 * <p>本测试直接驱动归属记账（不打音源：测试环境没有 Minecraft 实例，播放步骤被安全跳过），
 * 断言一个槽位的登记/停止**绝不**影响另一个槽位。</p>
 */
class SoundOwnershipTest {

    private static final String OWNER_A = "player-a";
    private static final String OWNER_B = "player-b";

    @BeforeEach
    void cleanSlate() {
        YSMSoundManager.clearOwnerSlot(OWNER_A);
        YSMSoundManager.clearOwnerSlot(OWNER_B);
        YSMSoundManager.clearOwnerSlot(YSMSoundManager.LOCAL_OWNER);
    }

    @Test
    void nullOwnerMapsToTheLocalSlot() {
        // 预览实体没有 player：刻意保持"本地一格"的既有预览语义。
        assertEquals(YSMSoundManager.LOCAL_OWNER, YSMSoundManager.ownerKey(null));
    }

    @Test
    void twoOwnersKeepSeparateControllerMappingsForTheSameControllerName() {
        YSMSoundManager.onSoundKeyframe(OWNER_A, "cap_controller", "sound_a", null);
        YSMSoundManager.onSoundKeyframe(OWNER_B, "cap_controller", "sound_b", null);

        assertEquals("sound_a", YSMSoundManager.controllerSoundsOf(OWNER_A)
            .get("cap_controller"));
        assertEquals("sound_b", YSMSoundManager.controllerSoundsOf(OWNER_B)
            .get("cap_controller"));
    }

    @Test
    void stoppingOneOwnersControllerLeavesTheOtherIntact() {
        YSMSoundManager.onSoundKeyframe(OWNER_A, "cap_controller", "sound_a", null);
        YSMSoundManager.onSoundKeyframe(OWNER_B, "cap_controller", "sound_b", null);

        YSMSoundManager.stopControllerByOwnerSlot(OWNER_B, "cap_controller");

        assertTrue(
            YSMSoundManager.controllerSoundsOf(OWNER_B)
                .isEmpty(),
            "the stopping owner's mapping must be dropped");
        assertEquals(
            "sound_a",
            YSMSoundManager.controllerSoundsOf(OWNER_A)
                .get("cap_controller"),
            "the other owner must keep its own controller mapping");
    }

    @Test
    void previewSlotIsIndependentOfRealPlayers() {
        // GUI 预览用本地槽位：它的登记/清理不得碰到真实玩家的映射。
        YSMSoundManager.onSoundKeyframe(YSMSoundManager.LOCAL_OWNER, "cap_controller", "hover", null);
        YSMSoundManager.onSoundKeyframe(OWNER_A, "cap_controller", "sound_a", null);

        YSMSoundManager.stopControllerByOwnerSlot(YSMSoundManager.LOCAL_OWNER, "cap_controller");

        assertTrue(YSMSoundManager.controllerSoundsOf(YSMSoundManager.LOCAL_OWNER)
            .isEmpty());
        assertEquals("sound_a", YSMSoundManager.controllerSoundsOf(OWNER_A)
            .get("cap_controller"));
    }

    @Test
    void clearOwnerSlotOnlyClearsThatOwner() {
        YSMSoundManager.onSoundKeyframe(OWNER_A, "main_controller", "idle_a", null);
        YSMSoundManager.onSoundKeyframe(OWNER_B, "main_controller", "idle_b", null);

        YSMSoundManager.clearOwnerSlot(OWNER_A);

        assertTrue(
            YSMSoundManager.controllerSoundsOf(OWNER_A)
                .isEmpty());
        assertFalse(
            YSMSoundManager.controllerSoundsOf(OWNER_B)
                .isEmpty(),
            "logout of one player must not clear another player's sound bookkeeping");
    }

    @Test
    void activeSourceBookkeepingIsScopedPerOwner() {
        // 无音源可打（测试环境没有 Minecraft 实例），所以这里断言的是"记账不会跨槽位泄漏"：
        // 停止一个槽位的音效不会动到另一个槽位的活跃集合。
        assertEquals(0, YSMSoundManager.activeSoundNamesOf(OWNER_A).size());
        YSMSoundManager.stopSound(OWNER_A, "swing");
        YSMSoundManager.stopAll(OWNER_B);
        assertTrue(YSMSoundManager.activeSoundNamesOf(OWNER_B)
            .isEmpty());
    }

    /**
     * 审查给出的**原始形状**：两个演员用**同一个模型**、同一个控制器名、同一个音效名。
     * 旧实现的防抖键是 {@code modelId::controller::sound}，不含归属，于是 B 的关键帧会被
     * A 刚触发的那条在 100 ms 冷却里吃掉 —— B 自己的播放根本不开始；
     * 而 {@code CONTROLLER_SOUNDS} 按裸控制器名索引，A 的映射也是被 B 覆盖的那一个。
     */
    @Test
    void twoActorsWithTheSameModelControllerAndSoundDoNotCollide() {
        net.minecraft.util.ResourceLocation sharedModel =
            new net.minecraft.util.ResourceLocation("ysmu", "a_model_shared_by_both/main");
        YSMSoundManager.onSoundKeyframe(OWNER_A, "cap_controller", "swing", sharedModel);
        YSMSoundManager.onSoundKeyframe(OWNER_B, "cap_controller", "swing", sharedModel);

        assertEquals(
            "swing",
            YSMSoundManager.controllerSoundsOf(OWNER_A)
                .get("cap_controller"),
            "A must keep its own mapping");
        assertEquals(
            "swing",
            YSMSoundManager.controllerSoundsOf(OWNER_B)
                .get("cap_controller"),
            "B must be recorded too - the debounce key must include the owner, not just the model");
    }

    @Test
    void keyframeDebounceIsPerOwner() {
        // 同一模型的两个玩家同一帧各自触发同一条关键帧音效：不能互相防抖掉。
        YSMSoundManager.onSoundKeyframe(OWNER_A, "cap_controller", "swing", null);
        YSMSoundManager.onSoundKeyframe(OWNER_B, "cap_controller", "swing", null);
        // 两者都被登记（若防抖键不含归属，第二次会被 100ms 冷却吃掉而完全不登记）。
        assertEquals("swing", YSMSoundManager.controllerSoundsOf(OWNER_A)
            .get("cap_controller"));
        assertEquals("swing", YSMSoundManager.controllerSoundsOf(OWNER_B)
            .get("cap_controller"));
    }
}
