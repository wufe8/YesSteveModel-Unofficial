package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

/**
 * roaming 变量的**裸名别名**不能覆盖模型自己的变量。
 *
 * <p>背景（用户实测的遗留 bug）：roaming 注入会把 {@code roaming.bq_qx2} 同时写成
 * {@code v.roaming.bq_qx2} 与**裸名** {@code v.bq_qx2}。而模型的脚本自己也在用
 * {@code v.bq_qx2} 存它的计算结果（{@code v.bq_qx2 = v.roaming.bq_qx2 ? ... }）。两个来源
 * 往同一个键里写 ⇒ 值在注入值(2)与模型计算值(1)之间交替，表现是表情/部件闪烁，且只在
 * {@code evals/frame == 1} 时肉眼可见（两个 pass 时总有一次 pass 会把值收敛到模型的计算值）。</p>
 *
 * <p>别名本身不能删：{@code v.roaming.x} 与 {@code v.x} 在 YSM 里是两个不同的变量
 * （wiki: molang/var），但第一方 wine_fox 的 {@code 14_momo} 在关键帧里只写裸名
 * （{@code "scale": "1-v.smallfox_size"}，而滑块是 {@code v.roaming.smallfox_size}），
 * 删掉别名会直接弄坏它。所以改成**自校准**：注入了裸名之后，如果下一轮发现那个键不是我们
 * 注入的值，就说明模型自己会写它，从此不再注入该别名。</p>
 */
class RoamingBareAliasTest {

    /** 模型自己会写裸名 ⇒ 别名让位（否则就是 osc 的根源）。 */
    @Test
    void bareAliasYieldsToTheModelOwnVariable() {
        ResourceLocation model = new ResourceLocation("ysmu_test", "owns_bare");
        Map<String, Double> scope = new HashMap<>();

        // 第 1 轮：还不知道模型会写它，正常注入
        OpenYsmPlayerControllerRuntime.injectRoamingVar(scope, "v.", "roaming.bq_qx2", 2.0D, model);
        assertEquals(2.0D, scope.get("v.bq_qx2"), 0.0D);
        assertEquals(2.0D, scope.get("v.roaming.bq_qx2"), 0.0D);

        // 模型的脚本写它自己的变量
        scope.put("v.bq_qx2", 1.0D);

        // 第 2 轮：自校准发现键被改写过 ⇒ 别名不再注入，roaming 本体照旧
        OpenYsmPlayerControllerRuntime.injectRoamingVar(scope, "v.", "roaming.bq_qx2", 2.0D, model);
        assertEquals(1.0D, scope.get("v.bq_qx2"), 0.0D,
            "模型自己的变量不能被裸名别名覆盖（这就是 v.bq_qx* 震荡的根源）");
        assertEquals(2.0D, scope.get("v.roaming.bq_qx2"), 0.0D, "roaming 本体仍然要注入");

        // 第 3 轮：即使 roaming 值变了，也不去碰模型自己的那个键
        OpenYsmPlayerControllerRuntime.injectRoamingVar(scope, "v.", "roaming.bq_qx2", 1.0D, model);
        assertEquals(1.0D, scope.get("v.bq_qx2"), 0.0D);
    }

    /** 只读裸名的模型（14_momo 那种）必须继续拿到别名 —— 别名是给它们用的。 */
    @Test
    void bareAliasKeepsWorkingForModelsThatOnlyReadIt() {
        ResourceLocation model = new ResourceLocation("ysmu_test", "reads_bare");
        Map<String, Double> scope = new HashMap<>();

        OpenYsmPlayerControllerRuntime.injectRoamingVar(scope, "v.", "roaming.smallfox_size", 0.25D, model);
        assertEquals(0.25D, scope.get("v.smallfox_size"), 0.0D);

        // 模型没动过这个键 ⇒ 滑块改值要跟得上
        OpenYsmPlayerControllerRuntime.injectRoamingVar(scope, "v.", "roaming.smallfox_size", 0.5D, model);
        assertEquals(0.5D, scope.get("v.smallfox_size"), 0.0D, "只读裸名的模型必须继续拿到别名");
    }

    /** 没有 roaming. 前缀的变量本来就没有裸名别名，行为不变。 */
    @Test
    void plainNameHasNoAlias() {
        ResourceLocation model = new ResourceLocation("ysmu_test", "plain");
        Map<String, Double> scope = new HashMap<>();
        OpenYsmPlayerControllerRuntime.injectRoamingVar(scope, "v.", "scroll_speed", 3.0D, model);
        assertEquals(3.0D, scope.get("v.scroll_speed"), 0.0D);
        assertFalse(scope.containsKey("v.roaming.scroll_speed"));
    }

    /** 控制器 map（keyPrefix ""）同样跳过被模型拥有的裸名 —— 自校准的信号来自 Molang 作用域。 */
    @Test
    void controllerMapAlsoSkipsTheOwnedBareName() {
        ResourceLocation model = new ResourceLocation("ysmu_test", "controller");
        Map<String, Double> scope = new HashMap<>();
        OpenYsmPlayerControllerRuntime.injectRoamingVar(scope, "v.", "roaming.x", 2.0D, model);
        scope.put("v.x", 1.0D);
        OpenYsmPlayerControllerRuntime.injectRoamingVar(scope, "v.", "roaming.x", 2.0D, model);

        Map<String, Double> controller = new HashMap<>();
        OpenYsmPlayerControllerRuntime.injectRoamingVar(controller, "", "roaming.x", 2.0D, model);
        assertFalse(controller.containsKey("x"), "模型自己拥有的裸名不该出现在控制器 map 里");
        assertEquals(2.0D, controller.get("roaming.x"), 0.0D);
    }
}
