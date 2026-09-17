package com.fox.ysmu.client.animation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.minecraft.util.ResourceLocation;

/**
 * 常驻变量（{@code v.roaming.*}）由动画 / 时间轴写下后必须"粘住"。
 *
 * <p>wiki（{@code /wiki/molang/var/}）：{@code variable.roaming.} 与实体变量一样，赋值后一直
 * 保持，只有再次赋值才改变；默认值只用于从未赋值的 null 初值。</p>
 *
 * <p>回归点：模型自定义配置里的复选框变量（如某模型"尾巴发光"绑定的
 * {@code v.roaming.b}）会在 {@code MolangPhysicsRuntime.begin()} 里按 ysm.json 默认值每帧
 * 回写。时间轴写下的值只落在本帧作用域，于是下一帧被默认值冲掉——同模型同变量的轮盘"变身"
 * 时间轴（{@code v.roaming.a=1-v.roaming.b; v.roaming.b=v.roaming.a;}）就只能生效一次，
 * 之后再也切不回去。</p>
 */
class RoamingWriteThroughTest {

    private static final ResourceLocation MODEL = new ResourceLocation("ysmu", "_test_roaming_write");
    private static final ResourceLocation OTHER = new ResourceLocation("ysmu", "_test_roaming_other");

    @AfterEach
    void cleanup() {
        com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime.clear();
        OpenYsmPlayerControllerRuntime.clearModelRoamingVars();
        OpenYsmPlayerControllerRuntime.resetAllUserRoamingVars();
    }

    /** 控制组：没人写过时，读到的就是 ysm.json 里的默认值。 */
    @Test
    void modelDefaultAppliesWhenNothingWroteIt() {
        OpenYsmPlayerControllerRuntime.registerModelRoamingVar(MODEL, "roaming.b");
        OpenYsmPlayerControllerRuntime.setModelRoamingDefault(MODEL, "roaming.b", 0.0d);

        Map<String, Double> vars = OpenYsmPlayerControllerRuntime.getRoamingVarsForModel(MODEL);

        assertEquals(0.0d, vars.get("roaming.b"), 1.0e-9d, "没有写入时应回落到模型默认值");
        assertFalse(OpenYsmPlayerControllerRuntime.isRoamingExplicit(MODEL, "roaming.b"));
    }

    /** 时间轴/动画写下的常驻变量必须压过模型默认值，并且下次还能改成别的值。 */
    @Test
    void animationWriteBeatsModelDefaultAndStaysWritable() {
        OpenYsmPlayerControllerRuntime.registerModelRoamingVar(MODEL, "roaming.b");
        OpenYsmPlayerControllerRuntime.setModelRoamingDefault(MODEL, "roaming.b", 0.0d);

        // 第一次"变身"：v.roaming.a=1-v.roaming.b(=0) → 1；v.roaming.b=v.roaming.a → 1
        OpenYsmPlayerControllerRuntime.noteRoamingWrite(MODEL, "v.roaming.b", 1.0d);

        assertEquals(1.0d, OpenYsmPlayerControllerRuntime.getRoamingVarsForModel(MODEL).get("roaming.b"),
            1.0e-9d, "时间轴写下的常驻变量不能被下一帧的模型默认值冲掉");
        assertTrue(OpenYsmPlayerControllerRuntime.isRoamingExplicit(MODEL, "roaming.b"));

        // 第二次"变身"：1-1=0 → 必须能切回去
        OpenYsmPlayerControllerRuntime.noteRoamingWrite(MODEL, "v.roaming.b", 0.0d);

        assertEquals(0.0d, OpenYsmPlayerControllerRuntime.getRoamingVarsForModel(MODEL).get("roaming.b"),
            1.0e-9d, "第二次写入同样要生效（否则变身只能做一次）");
    }

    /** 变量名可带 {@code v.} / {@code variable.} 前缀；写成什么名字就读到什么名字。 */
    @Test
    void prefixedNamesAreNormalized() {
        OpenYsmPlayerControllerRuntime.noteRoamingWrite(MODEL, "variable.roaming.form", 3.0d);
        assertEquals(3.0d, OpenYsmPlayerControllerRuntime.getRoamingVarsForModel(MODEL).get("roaming.form"),
            1.0e-9d);

        OpenYsmPlayerControllerRuntime.noteRoamingWrite(MODEL, "v.roaming.form", 4.0d);
        assertEquals(4.0d, OpenYsmPlayerControllerRuntime.getRoamingVarsForModel(MODEL).get("roaming.form"),
            1.0e-9d);
    }

    /** 写回按模型隔离：模型 A 的动画写常驻变量不能改到模型 B。 */
    @Test
    void writeIsScopedToTheWritingModel() {
        OpenYsmPlayerControllerRuntime.setModelRoamingDefault(MODEL, "roaming.b", 0.0d);
        OpenYsmPlayerControllerRuntime.setModelRoamingDefault(OTHER, "roaming.b", 0.0d);

        OpenYsmPlayerControllerRuntime.noteRoamingWrite(MODEL, "v.roaming.b", 1.0d);

        assertEquals(1.0d, OpenYsmPlayerControllerRuntime.getRoamingVarsForModel(MODEL).get("roaming.b"),
            1.0e-9d);
        assertEquals(0.0d, OpenYsmPlayerControllerRuntime.getRoamingVarsForModel(OTHER).get("roaming.b"),
            1.0e-9d, "别的模型的同名变量必须保持自己的默认值");
    }

    /** 非常驻变量（普通 {@code v.*}）不该被写进常驻存储。 */
    @Test
    void nonRoamingWritesAreIgnored() {
        OpenYsmPlayerControllerRuntime.noteRoamingWrite(MODEL, "v.qh", 5.0d);

        assertNull(OpenYsmPlayerControllerRuntime.getRoamingVarsForModel(MODEL).get("qh"));
        assertNull(OpenYsmPlayerControllerRuntime.PENDING_ROAMING.get("qh"));
    }

    /** 没有注册过任何变量的模型也要能收下动画写下的常驻变量。 */
    @Test
    void unregisteredModelStillStoresTheWrite() {
        OpenYsmPlayerControllerRuntime.noteRoamingWrite(OTHER, "v.roaming.form", 2.0d);

        assertEquals(2.0d, OpenYsmPlayerControllerRuntime.getRoamingVarsForModel(OTHER).get("roaming.form"),
            1.0e-9d);
    }

    /**
     * 接线测试：关键帧 / 嵌套赋值 / 时间轴指令共用的写入口
     * {@link com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime#setVariable} 必须把常驻变量
     * 同步到常驻存储 —— 只测 {@code noteRoamingWrite} 只能证明存储语义，证明不了"动画真的走它"。
     */
    @Test
    void physicsRuntimeWriteGoesThroughToRoamingStorage() {
        OpenYsmPlayerControllerRuntime.setModelRoamingDefault(MODEL, "roaming.b", 0.0d);

        com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime.runWithVariableScope(null, MODEL,
            () -> assertTrue(com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime
                .setVariable("v.roaming.b", 1.0d)));

        assertEquals(1.0d, OpenYsmPlayerControllerRuntime.getRoamingVarsForModel(MODEL).get("roaming.b"),
            1.0e-9d, "动画写下的常驻变量必须落到常驻存储，否则下一帧被默认值冲掉");
    }
}
