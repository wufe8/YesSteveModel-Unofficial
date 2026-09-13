package com.fox.ysmu.client.animation.molang;

import java.util.function.Supplier;

import net.minecraft.util.ResourceLocation;

/**
 * 动画控制脚本的执行入口（wiki: molang/script「动画控制」）。
 *
 * <p>{@code functions/<任意描述>@player_ctrl_<槽位>.molang} 每帧执行一次，用
 * {@code ctrl.set_animation} 指定动画、用 {@code return ctrl.state_*} 返回本帧谓词。
 * 本类只做"取脚本正文 → 包一层 {@link AnimationControlScope} → 交给解释器跑一遍"，
 * 宿主作用域由调用方提供（游戏里是 {@code OpenYsmScriptScope}），因此可以脱离
 * Minecraft 单测。</p>
 *
 * <p>返回 {@code null} 表示"这个槽位没有控制脚本"（调用方保持原有逻辑）。脚本存在但
 * 正文为空时返回一个空的 {@link AnimationControlResult}（谓词视为 {@code NONE}，
 * 即不动内置逻辑）—— 宁可退回静态映射，也不要因为脚本有问题就把动画掐掉。</p>
 */
public final class AnimationControlScripts {

    private AnimationControlScripts() {}

    /**
     * 求值一个槽位的动画控制脚本。
     *
     * @param modelId     模型（主资源 id）
     * @param slot        槽位名，如 {@code main} / {@code pre_main} / {@code parallel_6}
     * @param innerScope  宿主作用域的工厂（每次求值新建一个，避免状态串帧）
     * @return 求值结果；没有脚本时返回 {@code null}
     */
    public static AnimationControlResult evaluate(ResourceLocation modelId, String slot,
        Supplier<MolangScriptInterpreter.MolangScriptScope> innerScope) {
        String body = MolangScriptRegistry.controlScript(modelId, slot);
        if (body == null) {
            return null;
        }
        String prepared = MolangScriptInterpreter.prepare(body);
        if (prepared.trim()
            .isEmpty()) {
            return new AnimationControlResult();
        }
        AnimationControlScope scope = new AnimationControlScope(innerScope.get());
        double predicate;
        try {
            predicate = MolangScriptInterpreter.evaluate(prepared, scope);
        } catch (RuntimeException e) {
            // 脚本报错不能改变动画：按"无操作"处理，让静态映射/内置谓词继续工作。
            return new AnimationControlResult();
        }
        AnimationControlResult result = scope.result();
        result.setAction(AnimationControlScope.actionOf(predicate));
        return result;
    }

    /**
     * 简化入口：宿主作用域已经建好、不需要每次重建时使用（测试与工具路径）。
     */
    public static AnimationControlResult evaluate(ResourceLocation modelId, String slot,
        MolangScriptInterpreter.MolangScriptScope inner) {
        return evaluate(modelId, slot, () -> inner);
    }
}
