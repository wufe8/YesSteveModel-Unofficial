package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime;

import org.junit.jupiter.api.Test;

/**
 * 常驻变量的**模型隔离**：控制器路径的全局回退读不能把别的模型的值端过来。
 *
 * <p>背景（审计发现的第二个洞）：关键帧路径（{@code ScopedMolangVariable} →
 * {@code getGlobalScopedValue}）会按写入来源过滤跨模型残留，返回 0；而控制器路径
 * （{@code sharedVariableValue}）在自身状态与共享作用域都未命中时，直接读**全局**
 * {@code PENDING_ROAMING} 这张跨模型扁平表，零模型判断 —— 于是模型 A 在轮盘里设的
 * {@code roaming.x} 会被模型 B 的控制器条件读到。这里钉住收紧后的判定。</p>
 */
class RoamingIsolationTest {

    private static final ResourceLocation MODEL_A = new ResourceLocation("ysmu_test", "isolation_a");
    private static final ResourceLocation MODEL_B = new ResourceLocation("ysmu_test", "isolation_b");

    /** 声明过的名字属于本模型；两种写法都认；别的模型不认。 */
    @Test
    void declaredRoamingNameBelongsToItsModelOnly() {
        OpenYsmPlayerControllerRuntime.registerModelRoamingVar(MODEL_A, "roaming.x");

        assertTrue(OpenYsmPlayerControllerRuntime.isRoamingNameForModel(MODEL_A, "roaming.x"));
        assertTrue(OpenYsmPlayerControllerRuntime.isRoamingNameForModel(MODEL_A, "x"),
            "裸名写法也要认（模型声明时用哪种写就存哪种）");
        assertFalse(OpenYsmPlayerControllerRuntime.isRoamingNameForModel(MODEL_B, "roaming.x"),
            "模型 B 没声明这个名字，不能读模型 A 的全局值");
        assertFalse(OpenYsmPlayerControllerRuntime.isRoamingNameForModel(MODEL_B, "x"));
    }

    /** 模型自己显式设置过的名字（脚本/控制器 onEntry 写回）也算属于它。 */
    @Test
    void explicitlySetNameBelongsToItsModelOnly() {
        OpenYsmPlayerControllerRuntime.markRoamingExplicit(MODEL_A, "roaming.y");

        assertTrue(OpenYsmPlayerControllerRuntime.isRoamingNameForModel(MODEL_A, "roaming.y"));
        assertFalse(OpenYsmPlayerControllerRuntime.isRoamingNameForModel(MODEL_B, "roaming.y"));
    }

    /** 全局轮盘变量（lock_wheel / wheel_anim）不属于任何模型，对所有模型生效。 */
    @Test
    void globalWheelNamesWorkForEveryModel() {
        assertTrue(OpenYsmPlayerControllerRuntime.isRoamingNameForModel(MODEL_A, "lock_wheel"));
        assertTrue(OpenYsmPlayerControllerRuntime.isRoamingNameForModel(MODEL_B, "wheel_anim"));
        assertTrue(OpenYsmPlayerControllerRuntime.isRoamingNameForModel(null, "lock_wheel"),
            "拿不到模型上下文时全局变量仍应可读");
    }

    /** 拿不到模型上下文时不能放行任意名字（否则回退又变成"谁都能读"）。 */
    @Test
    void withoutModelContextNothingElseIsAllowed() {
        assertFalse(OpenYsmPlayerControllerRuntime.isRoamingNameForModel(null, "roaming.x"));
        assertFalse(OpenYsmPlayerControllerRuntime.isRoamingNameForModel(null, "x"));
        assertFalse(OpenYsmPlayerControllerRuntime.isRoamingNameForModel(MODEL_A, null));
    }

    /**
     * 无模型上下文时标记"显式设置"不能退化成全局标记：那会让该名字对所有模型都表现为
     * 用户显式设置（isRoamingExplicit 先查全局集合），是跨模型串值的经典入口。
     * 只有真正全局的轮盘名字才允许。
     */
    @Test
    void nullModelContextDoesNotMarkGlobally() {
        OpenYsmPlayerControllerRuntime.markRoamingExplicit(null, "roaming.leaky");
        assertFalse(OpenYsmPlayerControllerRuntime.isRoamingExplicit(MODEL_A, "roaming.leaky"),
            "拿不到模型上下文时设的值不该对所有模型表现为显式设置");
        assertFalse(OpenYsmPlayerControllerRuntime.isRoamingNameForModel(MODEL_B, "roaming.leaky"));

        OpenYsmPlayerControllerRuntime.markRoamingExplicit(null, "lock_wheel");
        assertTrue(OpenYsmPlayerControllerRuntime.isRoamingExplicit(MODEL_A, "lock_wheel"),
            "全局轮盘变量仍然允许全局标记");
    }

    /**
     * 全局 {@code v.*} 的读回策略：帧外写入（无模型归属）必须挡住；系统注册变量（无来源记录）
     * 保持放行；别的模型的残留挡住；自己写的放行。
     */
    @Test
    void globalVarReadPolicy() {
        assertTrue(MolangPhysicsRuntime.isGlobalVarReadable(null, "某个模型"),
            "无来源记录 = 系统注册变量，旧行为放行");
        assertFalse(MolangPhysicsRuntime.isGlobalVarReadable("<unscoped>", "某个模型"),
            "帧外写入的全局值不属于任何模型，不能端给正在渲染的模型");
        assertTrue(MolangPhysicsRuntime.isGlobalVarReadable("<unscoped>", null),
            "帧外写入 → 帧外读取的往返必须继续可用（模型初始化 / 指令 / Molang 求值）");
        assertTrue(MolangPhysicsRuntime.isGlobalVarReadable("模型A", "模型A"));
        assertFalse(MolangPhysicsRuntime.isGlobalVarReadable("模型A", "模型B"),
            "别的模型写的残留要挡住（关键帧路径原有的隔离）");
        assertTrue(MolangPhysicsRuntime.isGlobalVarReadable("模型A", null),
            "没有模型上下文时不做模型比较");
    }
}
