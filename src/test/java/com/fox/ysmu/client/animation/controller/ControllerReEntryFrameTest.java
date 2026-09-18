package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 控制器的"模型再入"判定必须按**渲染帧**计，不能按**模型渲染 pass** 计。
 *
 * <p>回归点（实测）：模型选择页(Alt+Y)一帧要渲染十几到二十几个模型。原来帧计数在
 * {@code MolangPhysicsRuntime.begin()} 里推进（每个模型 pass 一次），于是同一个并行控制器两次
 * 处理之间差十几二十"帧" → {@code isReEntry} 每帧误判 → {@code sameAnim=false} +
 * {@code enteredTick} 被重置 → {@code setAnimationPreservingTick} 把 tick 钉在 0、时间轴游标每帧
 * 重启 → 眼睛/耳朵/尾巴/表情/物理状态每帧回到初值：预览页模型抖动 + 眼睛逐帧眨动。
 * 关掉预览页（每帧只有 2~3 个 pass）、或打开背包（同样只有几个 pass）就正常。</p>
 */
class ControllerReEntryFrameTest {

    /** 同一渲染帧内渲染 N 个模型，不能让"停放帧数"看起来像 N 帧。 */
    @Test
    void manyModelPassesInOneFrameDoNotLookLikeManyFrames() {
        // 先走几帧，保证"上一次处理"的帧号 > 0（0 是"从未处理过"的哨兵，不参与再入判定）
        for (int i = 0; i < 3; i++) {
            OpenYsmPlayerControllerRuntime.advanceRenderFrame();
        }
        // 这个控制器本帧刚被处理过
        int lastActiveFrame = OpenYsmPlayerControllerRuntime.frameCounter();

        // 同帧内其它模型（模型选择页 20 个按钮 + 左侧预览 + HUD + 第一人称……）逐个渲染
        for (int i = 0; i < 25; i++) {
            OpenYsmPlayerControllerRuntime.beginModelPass();
        }

        assertFalse(OpenYsmPlayerControllerRuntime.isReEntry(lastActiveFrame),
            "同一帧内的多次模型 pass 不能被当成'停放了很多帧'（否则并行控制器每帧重启 → 预览页抖动）");
    }

    /** 真的停放超过 10 个渲染帧（模型被换走又换回来）仍然要判定为再入。 */
    @Test
    void parkedForMoreThanTenRenderFramesIsReEntry() {
        OpenYsmPlayerControllerRuntime.advanceRenderFrame();
        int lastActiveFrame = OpenYsmPlayerControllerRuntime.frameCounter();

        for (int i = 0; i < 11; i++) {
            OpenYsmPlayerControllerRuntime.advanceRenderFrame();
        }

        assertTrue(OpenYsmPlayerControllerRuntime.isReEntry(lastActiveFrame),
            "停放超过 10 帧仍要能识别为再入（模型换走再换回来要重载关键帧并重启时间轴）");
    }

    /** 边界：刚好 10 帧不算再入，11 帧才算。 */
    @Test
    void thresholdIsTenFrames() {
        OpenYsmPlayerControllerRuntime.advanceRenderFrame();
        int lastActiveFrame = OpenYsmPlayerControllerRuntime.frameCounter();

        for (int i = 0; i < 10; i++) {
            OpenYsmPlayerControllerRuntime.advanceRenderFrame();
        }
        assertFalse(OpenYsmPlayerControllerRuntime.isReEntry(lastActiveFrame), "10 帧仍属连续渲染");

        OpenYsmPlayerControllerRuntime.advanceRenderFrame();
        assertTrue(OpenYsmPlayerControllerRuntime.isReEntry(lastActiveFrame), "第 11 帧起算再入");
    }

    /** 从未处理过的控制器（lastActiveFrame==0）不算再入。 */
    @Test
    void neverProcessedControllerIsNotAReEntry() {
        for (int i = 0; i < 50; i++) {
            OpenYsmPlayerControllerRuntime.advanceRenderFrame();
        }
        assertFalse(OpenYsmPlayerControllerRuntime.isReEntry(0));
    }
}
