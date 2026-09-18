package com.fox.ysmu.client.entity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.condition.ConditionArmor;

import software.bernie.geckolib3.core.manager.AnimationData;

/**
 * 控制器注册顺序 = 骨骼通道优先级（GeckoLib 逐控制器覆盖骨骼，后注册的赢）。
 *
 * <p>wiki「并行动画」：{@code pre_parallelN} 优先级最低（会被主动画覆盖），
 * {@code parallelN} **优先级最高**，且数字越大越高；wiki「护甲动画」要求由并行动画把护甲组
 * 缩放设成 0、护甲动画再把对应组缩放改回 1（护甲必须排在并行之后）。</p>
 *
 * <p>回归点：{@code parallelN} 曾排在 {@code cap_controller}（轮盘 extra/gui 预览动画）之前，
 * 于是轮盘动画里写的 {@code AllBody.scale=1} 会盖掉 {@code parallel1} 的形态缩放
 * （{@code AllBody.scale=1-v.roaming.a} / {@code FOX.scale=v.roaming.a}）——表现为"变身前后的
 * 模型同时显示"。</p>
 */
class ControllerPriorityOrderTest {

    @Test
    void parallelIsRegisteredAfterMainAndCapButBeforeArmor() {
        AnimationData data = new AnimationData();
        new CustomPlayerEntity().registerControllers(data);
        List<String> order = new ArrayList<>(data.getAnimationControllers().keySet());

        String preParallel = "pre_parallel_1_controller";
        String main = "main_controller";
        String cap = "cap_controller";
        String parallel = "parallel_1_controller";
        String parallel7 = "parallel_7_controller";
        String armor = ConditionArmor.getSlotNameFromIndex(1) + "_controller";

        for (String name : new String[] { preParallel, main, cap, parallel, parallel7, armor }) {
            assertTrue(order.contains(name), name + " 未注册: " + order);
        }
        // pre_parallel 优先级最低 → 排在主动画之前（会被主动画覆盖）
        assertTrue(order.indexOf(preParallel) < order.indexOf(main),
            "pre_parallel 应排在主动画之前: " + order);
        // parallel 优先级最高 → 排在主动画与轮盘 cap 之后
        assertTrue(order.indexOf(main) < order.indexOf(parallel),
            "parallel 应排在主动画之后: " + order);
        assertTrue(order.indexOf(cap) < order.indexOf(parallel),
            "parallel 应排在轮盘 cap 控制器之后（否则轮盘动画会盖掉并行形态缩放）: " + order);
        // 数字越大优先级越高
        assertTrue(order.indexOf(parallel) < order.indexOf(parallel7),
            "parallel 数字越大应越靠后: " + order);
        // 护甲动画要能把并行设成 0 的护甲组缩放改回 1 → 必须排在并行之后
        assertTrue(order.indexOf(parallel7) < order.indexOf(armor),
            "护甲控制器应排在并行族之后: " + order);
    }
}
