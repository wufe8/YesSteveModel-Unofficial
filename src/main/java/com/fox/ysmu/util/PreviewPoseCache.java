package com.fox.ysmu.util;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

/**
 * 预览缩略图的**姿态签名缓存**：记住每个模型上一次"真正画出去"时的姿态签名，
 * 签名相同就说明这一帧画出来会和上一帧逐像素相同（预览动画播完后只有定期的眨眼会变），
 * 于是可以跳过几何提交、复用 FBO 里已有的画面。
 *
 * <p>调用方见 {@link RenderUtil#renderEntityInInventory}；签名的计算（骨骼的
 * 旋转/位移/缩放/隐藏 + 几何对象与贴图身份）也在那里。
 *
 * <p>单独一个类而不是塞进 {@code RenderUtil}：{@code RenderUtil} 的静态初始化会加载
 * GL/LWJGL（`org.lwjgl.LWJGLException`），单测 JVM 里没有，所以判定逻辑放在这里才可测。
 */
public final class PreviewPoseCache {

    /** 只在渲染线程访问，普通 HashMap 即可。 */
    private static final Map<ResourceLocation, Long> POSES = new HashMap<>();

    private PreviewPoseCache() {}

    /**
     * @return {@code true} = 与上次真正画出去时相同 → 这一帧不必提交几何；
     *         {@code false} = 需要画，并记下新签名。缓存里没有该模型的条目时一律返回
     *         {@code false}（第一次烘焙 / FBO 刚被重建 / 刚作废，都必须画）。
     */
    public static boolean shouldSkip(ResourceLocation modelId, long signature) {
        Long previous = POSES.get(modelId);
        if (previous != null && previous.longValue() == signature) {
            return true;
        }
        POSES.put(modelId, Long.valueOf(signature));
        return false;
    }

    /** 作废某模型的签名：FBO 被重建、翻页/换模型、悬停或 gui 动画切换后调用，
     *  下一次渲染必然执行（否则会留下空白或过期的缩略图）。 */
    public static void invalidate(ResourceLocation modelId) {
        if (modelId != null) {
            POSES.remove(modelId);
        }
    }

    /** 全部作废（模型重载 / 断线时用）。 */
    public static void clear() {
        POSES.clear();
    }

    /** 当前记录的模型数（诊断用）。 */
    public static int size() {
        return POSES.size();
    }
}
