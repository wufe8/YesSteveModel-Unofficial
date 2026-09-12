package com.fox.ysmu.client.animation.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime;
import com.fox.ysmu.client.animation.molang.MolangScriptInterpreter;
import com.fox.ysmu.client.animation.molang.MolangScriptRegistry;

import software.bernie.geckolib3.core.event.predicate.AnimationEvent;

/**
 * 游戏侧的 {@link MolangScriptInterpreter.MolangScriptScope}：把脚本的变量读写接到控制器求值器
 * 与 {@link MolangPhysicsRuntime}，函数调用转发到控制器的函数表（{@code ysm.*} / {@code math.*}
 * 都在那里），函数体来自 {@link MolangScriptRegistry}。
 * <p>
 * 放在 controller 包里是有意的：{@link OpenYsmControllerExpressionEvaluator.Context} 与它的
 * {@code Argument} 都是包内可见，复用它们就不必把 {@code q.*}/{@code ysm.*}/{@code ctrl.*}
 * 那几百行查询逻辑再写一遍。
 * <p>
 * {@code state == null}（没有控制器状态机）意味着 {@code v.*} 走共享作用域、{@code anim_time} 之类
 * 返回 0 —— 与 `.molang` 条件求值是同一条路径。事件触发点没有 {@code AnimationEvent} 时
 * {@code event} 允许为 null，此时依赖动画自身的查询按 0 处理。
 */
public final class OpenYsmScriptScope implements MolangScriptInterpreter.MolangScriptScope {

    private final ResourceLocation modelId;
    private final OpenYsmControllerExpressionEvaluator.Context context;
    private final List<Double> arguments;

    public OpenYsmScriptScope(EntityPlayer player, AnimationEvent<?> event, ResourceLocation modelId,
        List<Double> arguments) {
        this.modelId = modelId;
        this.arguments = arguments == null ? Collections.emptyList() : arguments;
        this.context = new OpenYsmControllerExpressionEvaluator.Context(event, player, null);
    }

    @Override
    public double variableValue(String name) {
        return this.context.variableValue(name);
    }

    /**
     * 脚本的赋值写回：与控制器的 {@code onEntry}/{@code onExit} 走**同一条**路径
     * （{@code executeStatement}），否则"脚本写了 v.x，骨骼却读不到"。
     */
    @Override
    public void setVariableValue(String name, double value) {
        String normalized = name.startsWith("variable.") ? "v." + name.substring("variable.".length()) : name;
        if (!normalized.startsWith("v.")) {
            return;
        }
        MolangPhysicsRuntime.setVariable(normalized, value);
        String varName = normalized.substring(2);
        if (varName.startsWith("roaming.")) {
            OpenYsmPlayerControllerRuntime.PENDING_ROAMING.put(varName, value);
            // 按当前模型标记显式设置，避免同名变量跨模型串值。
            OpenYsmPlayerControllerRuntime.markRoamingExplicit(this.modelId, varName);
        }
    }

    @Override
    public double functionValue(String name, List<MolangScriptInterpreter.Argument> args) {
        List<OpenYsmControllerExpressionEvaluator.Argument> converted =
            new ArrayList<>(args == null ? 0 : args.size());
        if (args != null) {
            for (MolangScriptInterpreter.Argument argument : args) {
                converted.add(
                    argument.isString()
                        ? OpenYsmControllerExpressionEvaluator.Argument.string(argument.asString())
                        : OpenYsmControllerExpressionEvaluator.Argument.number(argument.asNumber()));
            }
        }
        return this.context.functionValue(name, converted);
    }

    @Override
    public double argument(int index) {
        return index >= 0 && index < this.arguments.size() ? this.arguments.get(index) : 0.0d;
    }

    @Override
    public int argumentCount() {
        return this.arguments.size();
    }

    /** {@code fn.*}：从模型的脚本表里取函数体（wiki「链式调用」）。 */
    @Override
    public String functionScript(String name) {
        return MolangScriptRegistry.functionScript(this.modelId, name.toLowerCase(Locale.ROOT));
    }
}
