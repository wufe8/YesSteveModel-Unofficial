package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.model.CustomPlayerModel;

import software.bernie.geckolib3.core.processor.AnimationProcessor;

/**
 * {@link MolangPhysicsRuntime#syncToRuntimeState} 从"每个控制器整表遍历"改成增量
 * （只遍历最近两个 pass 写过的变量名）之后，必须保住三条语义：
 *
 * <ol>
 *   <li>写入本 pass 就能被本 pass 的控制器读到；</li>
 *   <li>**pass 与 pass 之间**的写入（{@code @sync} 脚本事件走的
 *       {@link MolangPhysicsRuntime#runWithVariableScope}）不会被下一次
 *       {@code begin()} 丢掉标记 —— 旧实现整表遍历时能看见它，增量实现也必须能，
 *       否则 {@code @sync} 写进来的变量对控制器条件就是"永不生效"；</li>
 *   <li>新建（空）的控制器 RuntimeState 仍能拿到作用域表里**前几帧**写过的值。</li>
 * </ol>
 */
class MolangScopeSyncIncrementalTest {

    /** 作用域键用的是 {@code animatable.getMainModel()}，而那个 getter 在 geo 未加载时会
     *  退化成默认模型；测试里没有 GeckoLibCache，所以两边都必须用同一个默认值，
     *  否则 pass 与 {@code runWithVariableScope} 会落到不同的 ScopeState 上。 */
    private static final ResourceLocation MODEL = CustomPlayerModel.DEFAULT_MAIN_MODEL;

    private static CustomPlayerEntity entity() {
        CustomPlayerEntity entity = new CustomPlayerEntity();
        entity.setMainModel(MODEL);
        return entity;
    }

    /** 一次模型渲染 pass：begin() → body → end()。 */
    private static void pass(CustomPlayerEntity entity, Runnable body) {
        AnimationProcessor<CustomPlayerEntity> processor = new AnimationProcessor<>(null);
        MolangPhysicsRuntime.begin(entity, 0.0d, processor);
        try {
            body.run();
        } finally {
            MolangPhysicsRuntime.end();
        }
    }

    @Test
    void writesVisibleInTheSamePassAndKeptAfterwards() {
        MolangPhysicsRuntime.clear();
        CustomPlayerEntity entity = entity();
        Map<String, Double> target = new HashMap<>();

        pass(entity, () -> {
            MolangPhysicsRuntime.setVariable("v.a", 1.0d);
            MolangPhysicsRuntime.syncToRuntimeState(target);
        });
        assertEquals(1.0d, target.get("a"), 0.0d);

        // 第二个 pass 没有再写 v.a：增量路径不能把它从 target 里抹掉。
        pass(entity, () -> MolangPhysicsRuntime.syncToRuntimeState(target));
        assertEquals(1.0d, target.get("a"), 0.0d);

        // 第三个 pass 改写：必须覆盖。
        pass(entity, () -> {
            MolangPhysicsRuntime.setVariable("v.a", 2.0d);
            MolangPhysicsRuntime.syncToRuntimeState(target);
        });
        assertEquals(2.0d, target.get("a"), 0.0d);
        MolangPhysicsRuntime.clear();
    }

    /**
     * 核心回归：{@code @sync} 写在两次渲染 pass 之间，值必须仍能到达控制器。
     * 若 begin() 直接清空脏集（而不是把它过继成"上一 pass"），这里的 b 会永远丢失。
     */
    @Test
    void writeBetweenPassesStillReachesAController() {
        MolangPhysicsRuntime.clear();
        CustomPlayerEntity entity = entity();
        Map<String, Double> target = new HashMap<>();

        pass(entity, () -> {
            MolangPhysicsRuntime.setVariable("v.a", 1.0d);
            MolangPhysicsRuntime.syncToRuntimeState(target);
        });
        assertEquals(1.0d, target.get("a"), 0.0d);
        assertNull(target.get("b"));

        // 帧间写入（脚本事件）：没有 begin()/end()，只装一个作用域。
        MolangPhysicsRuntime.runWithVariableScope(null, MODEL, () -> MolangPhysicsRuntime.setVariable("v.b", 5.0d));

        pass(entity, () -> MolangPhysicsRuntime.syncToRuntimeState(target));
        assertEquals(5.0d, target.get("b"), 0.0d,
            "@sync 写在两次 pass 之间的值必须仍能同步给已经存在的控制器");
        MolangPhysicsRuntime.clear();
    }

    /** 新建的（空）控制器：作用域表里前几帧写过的值要靠整表兜底分支灌进去。 */
    @Test
    void freshTargetGetsValuesWrittenInEarlierPasses() {
        MolangPhysicsRuntime.clear();
        CustomPlayerEntity entity = entity();

        pass(entity, () -> MolangPhysicsRuntime.setVariable("v.a", 3.0d));
        pass(entity, () -> MolangPhysicsRuntime.setVariable("v.c", 4.0d));

        Map<String, Double> fresh = new HashMap<>();
        pass(entity, () -> MolangPhysicsRuntime.syncToRuntimeState(fresh));
        assertEquals(3.0d, fresh.get("a"), 0.0d, "前两个 pass 写的值都要在");
        assertEquals(4.0d, fresh.get("c"), 0.0d);

        // 兜底分支不能污染 target 的写法：非 v.* 前缀的键不进 RuntimeState。
        pass(entity, () -> {
            MolangPhysicsRuntime.setVariable("query.wet", 1.0d);
            MolangPhysicsRuntime.syncToRuntimeState(fresh);
        });
        assertNull(fresh.get("query.wet"));
        MolangPhysicsRuntime.clear();
    }
}
