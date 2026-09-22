package com.fox.ysmu.eep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

/**
 * 换模型必须把"待播动画"一起作废。
 *
 * <p>{@code play_animation} + {@code animation} 是*按动画名*记的触发记录，而动画名只在
 * 触发它的那个模型文件里有意义。实测症状：在 A 上按过轮盘"变身"（{@code extra0}）后切到
 * B，服务端 EEP 仍带 {@code play_animation=true}，切模型那条 dirty 广播把它原样发给客户端，
 * B 于是自己播了一遍"变身"，连带改掉 B 的 {@code v.roaming.a/b}。</p>
 *
 * <p>只换贴图不算换模型；{@code main} / {@code arm} 两种子 id 指向同一个模型，也不能算换。</p>
 */
class ExtendedModelInfoModelSwitchTest {

    private static final ResourceLocation MODEL_A = new ResourceLocation("ysmu_test", "switch_a/main");
    private static final ResourceLocation MODEL_B = new ResourceLocation("ysmu_test", "switch_b/main");
    private static final ResourceLocation TEX = new ResourceLocation("ysmu_test", "switch_a/skin");

    private static ExtendedModelInfo pendingOnModelA() {
        ExtendedModelInfo eep = new ExtendedModelInfo(null);
        eep.setModelAndTexture(MODEL_A, TEX);
        eep.playAnimation("extra0");
        assertTrue(eep.isPlayAnimation(), "precondition: the animation is pending on model A");
        return eep;
    }

    @Test
    void switchingModelDropsThePendingAnimation() {
        ExtendedModelInfo eep = pendingOnModelA();

        eep.setModelAndTexture(MODEL_B, new ResourceLocation("ysmu_test", "switch_b/skin"));

        assertFalse(eep.isPlayAnimation(), "a trigger owned by model A must not reach model B");
    }

    @Test
    void textureOnlyChangeKeepsThePendingAnimation() {
        ExtendedModelInfo eep = pendingOnModelA();

        eep.setModelAndTexture(MODEL_A, new ResourceLocation("ysmu_test", "switch_a/skin_white"));

        assertTrue(eep.isPlayAnimation(), "changing the texture is not changing the model");
    }

    @Test
    void subIdsOfOneModelAreTheSameModel() {
        ExtendedModelInfo eep = pendingOnModelA();

        eep.setModelAndTexture(new ResourceLocation("ysmu_test", "switch_a/arm"), TEX);

        assertTrue(eep.isPlayAnimation(), "main/arm are the same model");
    }

    @Test
    void baseIdAndSubIdOfOneModelAreTheSameModel() {
        ExtendedModelInfo eep = pendingOnModelA();

        eep.setModelAndTexture(new ResourceLocation("ysmu_test", "switch_a"), TEX);

        assertTrue(eep.isPlayAnimation(), "a bare model id names the same model as its /main sub-id");
    }

    @Test
    void switchingTwiceStillLeavesNoAnimationBehind() {
        ExtendedModelInfo eep = pendingOnModelA();

        eep.setModelAndTexture(MODEL_B, new ResourceLocation("ysmu_test", "switch_b/skin"));
        eep.setModelAndTexture(MODEL_A, TEX);

        assertFalse(eep.isPlayAnimation(), "the trigger must not come back with the old model");
    }

    /** 空 id 不是"回默认模型"：写进 null 会在 saveNBTData() / 渲染路径上 NPE。 */
    @Test
    void nullModelIdIsRefusedInsteadOfPoisoningTheState() {
        ExtendedModelInfo eep = pendingOnModelA();

        eep.setModelAndTexture(null, TEX);

        assertEquals(MODEL_A, eep.getModelId(), "the model must be left untouched");
        assertTrue(eep.isPlayAnimation(), "and so must the animation");
    }
}
