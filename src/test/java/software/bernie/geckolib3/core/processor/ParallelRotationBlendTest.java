package software.bernie.geckolib3.core.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.entity.CustomPlayerEntity;

import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.manager.AnimationData;

/**
 * wiki「并行动画」的"特殊混合动画"：{@code parallel} 族的**旋转与低优先级层相加**，
 * 其余控制器（含 {@code pre_parallel} 族）覆盖，位移/缩放一律覆盖。
 *
 * <p>回归的是"车轮只有后轮转"：模型把轮胎自转写在 {@code pre_parallel2}
 * （{@code rotation=[v.wheel_rotate,0,0]}），把前轮转向写在 {@code parallel4}
 * （{@code rotation=[0, 转向角, 0]}）。{@code parallel*} 注册在 {@code pre_parallel*} 之后，
 * 按"后写覆盖"处理时前轮的整条 rotation 向量会被 {@code parallel4} 覆盖成 X=0，
 * 于是前轮不转、后轮照转。</p>
 *
 * <p>OpenYSM 的对照实现：{@code parallel} 族注册 {@code deprecatedMode=true}，
 * {@code AnimationProcessor} 对它们走 {@code vector3f.add(value)}，其余走覆盖
 * （{@code TransitionVector3f.applyRotationBlendTo}）。</p>
 */
class ParallelRotationBlendTest {

    @Test
    void parallelFamilyRotationsAddUp() {
        // wiki 的例子：低优先级层 10 度、高优先级 parallel 层 25 度。
        assertEquals(35.0f, AnimationProcessor.combineRotation(10.0f, 25.0f, true), 1e-4f);
        // 轮胎自转（pre_parallel2 写下的 X）与转向（parallel4 写的 Y）在不同分量上，互不干扰。
        assertEquals(120.0f, AnimationProcessor.combineRotation(120.0f, 0.0f, true), 1e-4f);
        // 非 parallel 层是覆盖：主动画压掉 pre_parallel、cap 压掉主动画，靠的都是这一条。
        assertEquals(25.0f, AnimationProcessor.combineRotation(10.0f, 25.0f, false), 1e-4f);
        // 没有低优先级层时 additive 也等于自身（pointData 每帧从 0 起）。
        assertEquals(25.0f, AnimationProcessor.combineRotation(0.0f, 25.0f, true), 1e-4f);
    }

    @Test
    void onlyHighPriorityParallelControllersAreMarkedAdditive() {
        AnimationData data = new AnimationData();
        new CustomPlayerEntity().registerControllers(data);
        Map<String, AnimationController> controllers = data.getAnimationControllers();

        assertTrue(additive(controllers, "parallel_0_controller"), "parallel_0 应叠加");
        assertTrue(additive(controllers, "parallel_7_controller"), "parallel_7 应叠加");
        // 具名并行槽位池（player.parallel_<名字>）同属 parallel 族。
        assertTrue(additive(controllers, "parallel_extra_0_controller"), "parallel_extra_0 应叠加");

        // pre_parallel 族是普通覆盖（优先级最低，会被主动画覆盖），不能一起打开。
        assertFalse(additive(controllers, "pre_parallel_0_controller"), "pre_parallel_0 不应叠加");
        assertFalse(additive(controllers, "pre_parallel_7_controller"), "pre_parallel_7 不应叠加");
        assertFalse(additive(controllers, "pre_parallel_extra_0_controller"), "pre_parallel_extra_0 不应叠加");
        // 其余固定槽位都是覆盖。
        assertFalse(additive(controllers, "main_controller"), "main 不应叠加");
        assertFalse(additive(controllers, "cap_controller"), "cap 不应叠加");
        assertFalse(additive(controllers, "player.post_main"), "post_main 不应叠加");
        assertFalse(additive(controllers, "openysm_slot_extra_0_controller"), "槽位后缀池不应叠加");
    }

    private static boolean additive(Map<String, AnimationController> controllers, String name) {
        AnimationController controller = controllers.get(name);
        assertTrue(controller != null, name + " 未注册: " + controllers.keySet());
        return controller.isAdditiveRotation();
    }
}
