package com.fox.ysmu.client.animation.molang;

import java.util.AbstractList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.world.IBlockAccess;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import com.fox.ysmu.client.particle.ParticleEffectUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code query.relative_block_has_any_tag} / {@code query.relative_block_has_all_tags} 的 mclib 实现。
 *
 * <p>YSM-wiki: molang/ref —— 相对坐标以玩家下半身为原点（与
 * {@link RelativeBlockNameFunction} 同一套取整），任一轴不得超过 8 格；any 命中任一个标签，all 要求所有标签命中。</p>
 *
 * <p><b>1.7.10 没有数据驱动的方块标签</b>，这里只把能原生回答的标签映射出来：
 * {@code minecraft:replaceable}（"这格可以被替换/是不是空气"）→
 * {@link Block#isReplaceable} / {@link Block#isAir}。其余标签一律 false，并为每个完整标签名无条件
 * WARN 一次，方便以后按实际用到的标签补映射
 * （不要在这里假装匹配，那只会让模型分支以错误的方式生效）。</p>
 */
public class QueryBlockTagFunction extends Function {

    /** wiki：相对坐标任一轴不得超过 8 格。 */
    private static final double MAX_RANGE = 8.0d;

    /** 已提示过的完整标签名（保留命名空间、小写），避免每帧刷屏。 */
    private static final Set<String> REPORTED_TAGS = Collections
        .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public QueryBlockTagFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 4;
    }

    @Override
    public double get() {
        try {
            // Preserve the existing any-tag query's lazy arguments: no tags are read before
            // the block exists, and later tags are not evaluated after a successful match.
            List<String> tags = new AbstractList<String>() {
                @Override
                public String get(int index) {
                    return MolangStringPool.get((int) QueryBlockTagFunction.this.getArg(index + 3));
                }

                @Override
                public int size() { return args.length - 3; }
            };
            return matches(ParticleEffectUtil.getCurrentEntity(), getArg(0), getArg(1), getArg(2), tags,
                name.endsWith("all_tags")) ? 1.0d : 0.0d;
        } catch (Exception ignored) {
            return 0.0d;
        }
    }

    public static boolean matches(Entity entity, double dx, double dy, double dz, List<String> tags,
        boolean requireAll) {
        if (!RelativeBlockNameFunction.validOffsets(dx, dy, dz, MAX_RANGE)) return false;
        if (entity == null || entity.worldObj == null) return false;
        try {
            int x = (int) Math.round(entity.posX + dx - 0.5d);
            int y = (int) Math.round(entity.boundingBox.minY + dy - 0.5d);
            int z = (int) Math.round(entity.posZ + dz - 0.5d);
            return matchesTags(entity.worldObj.getBlock(x, y, z), entity.worldObj, x, y, z, tags, requireAll);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean matchesTags(Block block, IBlockAccess world, int x, int y, int z, List<String> tags,
        boolean requireAll) {
        if (block == null || tags == null || tags.isEmpty()) return false;
        boolean all = true;
        for (String tag : tags) {
            // Keep the pre-existing any-tag alias behavior; only the new all-tags path enforces full ids.
            boolean supported = requireAll ? isSupportedTag(tag) : isLegacySupportedTag(tag);
            boolean matched = supported && matchesTag(tag, block, world, x, y, z);
            reportUnsupported(tag, supported);
            if (!requireAll && matched) return true;
            all &= matched;
        }
        return requireAll && all;
    }

    /** 把标签名映射到 1.7.10 能原生回答的方块属性上；不认识的标签返回 false。
     *  包内可见 + 只依赖 {@link IBlockAccess}（可为 null，空气/石头等实现不会解引用），便于单测。 */
    static boolean matchesTag(String tag, Block block, IBlockAccess world, int x, int y, int z) {
        if (block == null) {
            return false;
        }
        if (isLegacySupportedTag(tag)) {
            return block.isReplaceable(world, x, y, z) || block.isAir(world, x, y, z);
        }
        return false;
    }

    private static boolean isSupportedTag(String tag) {
        if (tag == null) return false;
        String key = tag.trim().toLowerCase(Locale.ROOT);
        return "replaceable".equals(key) || "minecraft:replaceable".equals(key);
    }

    private static boolean isLegacySupportedTag(String tag) {
        if (tag == null) return false;
        // Compatibility only: the existing any-tag query historically ignores the namespace.
        String key = tag.toLowerCase(Locale.ROOT);
        int colon = key.indexOf(':');
        return "replaceable".equals(colon < 0 ? key : key.substring(colon + 1));
    }

    private static void reportUnsupported(String tag, boolean supported) {
        if (tag == null || tag.trim().isEmpty() || supported) return;
        String key = tag.trim().toLowerCase(Locale.ROOT);
        // YSM-wiki: molang/ref — no data-driven block tags in 1.7.10; never invent a matching tag.
        if (REPORTED_TAGS.add(key)) ysmu.LOG.warn(
            "[YSMU-COMPAT] no 1.7.10 mapping for block tag '{}' (only minecraft:replaceable is supported); treating as false",
            key);
    }
}
