package com.fox.ysmu.client.animation.molang;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import net.minecraft.client.Minecraft;

import com.fox.ysmu.ysmu;

/**
 * {@code ysm.fps} 的唯一取值点：客户端当前帧率。
 *
 * <p>语义取自参考实现（OpenYSM 的 {@code YSMBinding}：{@code var("fps", ctx ->
 * Minecraft.getInstance().getFps())}）—— 即"客户端当前帧率"，在 1.7.10 里的对应物是
 * {@code Minecraft.debugFPS}（每满 1 秒由主循环刷新一次，F3 调试屏用的就是它）。</p>
 *
 * <h3>为什么必须共享一个已缓存的取数点</h3>
 * <p>原先两条求值路径各写各的，且都不对：</p>
 * <ul>
 *   <li>控制器表达式（{@code OpenYsmControllerExpressionEvaluator.ysmValue}）每次求值都
 *       {@code Minecraft.class.getDeclaredField("debugFPS")}。字符串字面量不会被 reobf
 *       重映射，而正式包运行时只有 SRG 名 {@code field_71470_ab}（见 mcp-srg.srg：
 *       {@code FD: net/minecraft/client/Minecraft/debugFPS .../Minecraft/field_71470_ab}），
 *       于是正式包里每次求值都抛 {@code NoSuchFieldException}、返回 60，同时每帧分配一个
 *       异常对象。开发环境（MCP 名）反而是对的 —— 这正是它没被测出来的原因。</li>
 *   <li>关键帧 Molang 路径（{@code MolangParser}）从没注册过 {@code ysm.fps}，未注册的
 *       非 {@code v.} 变量走 {@code newVariable()} 默认 0，于是同一条表达式在关键帧里读到 0、
 *       在控制器里读到 60。</li>
 * </ul>
 *
 * <h3>零值与启动窗口</h3>
 * <p>模型对 {@code ysm.fps} 的典型用法是帧率补偿（如 {@code 60 / ysm.fps}）。返回 0 会让
 * 这类表达式直接变成 Inf/NaN 并毁掉动画，而 {@code debugFPS} 在第一次满 1 秒刷新之前本来就是 0。</p>
 * <p>所以这里的取值规则是：<b>读不到或读到非正数时返回 60</b>（60 是"不补偿"的中性基准，
 * 也是帧率补偿表达式的单位），读到正数时返回实时帧率。这不是"到处假称 60"—— 能读到就用真实值，
 * 只有真正拿不到值（字段找不到、还没刷新、不在客户端）时才回落到中性值。</p>
 */
public final class FpsQuery {

    /** 读不到帧率时的中性基准：模型的帧率补偿表达式以 60 为单位。 */
    public static final double FALLBACK_FPS = 60.0d;

    /** MCP 名（开发环境）与 SRG 名（正式包运行环境），按此顺序尝试。 */
    static final String[] FIELD_NAMES = { "debugFPS", "field_71470_ab" };

    /** 解析缓存：{@link #resolved} 为 true 后不再做反射（无论成功还是失败）。 */
    private static volatile boolean resolved;
    private static volatile Field fpsField;

    private FpsQuery() {}

    /** 当前客户端帧率；拿不到时返回 {@link #FALLBACK_FPS}。任何线程可调用。 */
    public static double clientFps() {
        if (!resolved) {
            synchronized (FpsQuery.class) {
                if (!resolved) {
                    fpsField = findFpsField(Minecraft.class, FIELD_NAMES);
                    resolved = true;
                }
            }
        }
        return readFps(fpsField);
    }

    /**
     * 在 {@code owner} 上按 {@code names} 顺序查找静态 int 帧率字段；找不到返回 null。
     *
     * <p>捕获 {@link Throwable} 而不是 {@code Exception}：{@code getDeclaredField} 会解析该类的
     * 全部字段类型，一旦其中某个类型不可解析（例如缺少 LWJGL 的裁剪环境）会抛
     * {@link LinkageError}。取数是每帧调用的路径，任何链接期问题都只能退化成"拿不到值"。</p>
     */
    static Field findFpsField(Class<?> owner, String[] names) {
        for (String name : names) {
            try {
                Field candidate = owner.getDeclaredField(name);
                if (candidate.getType() != int.class || !Modifier.isStatic(candidate.getModifiers())) {
                    continue;
                }
                candidate.setAccessible(true);
                return candidate;
            } catch (NoSuchFieldException ignored) {
                // 换下一个名字（MCP ↔ SRG）
            } catch (Throwable t) {
                ysmu.LOG.warn("[YSMU-MOLANG] ysm.fps: cannot access {}.{}: {}", owner.getName(), name, t.toString());
            }
        }
        ysmu.LOG.warn(
            "[YSMU-MOLANG] ysm.fps: no usable FPS field on {} (tried {}); ysm.fps will report {}",
            owner.getName(),
            java.util.Arrays.toString(names),
            (int) FALLBACK_FPS);
        return null;
    }

    /** 读字段值；null/非正数/读取失败一律回落到中性基准。 */
    static double readFps(Field field) {
        if (field == null) {
            return FALLBACK_FPS;
        }
        try {
            int fps = field.getInt(null); // static 字段
            return fps > 0 ? fps : FALLBACK_FPS;
        } catch (Throwable t) {
            return FALLBACK_FPS;
        }
    }
}
