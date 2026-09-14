package com.fox.ysmu.client.animation.molang;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.world.IBlockAccess;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.particle.ParticleEffectUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code query.relative_block_has_any_tag(xOffset, yOffset, zOffset, tag1, tag2...)} 的 mclib 实现。
 *
 * <p>YSM-wiki: molang/ref —— 相对坐标以玩家下半身为原点（与
 * {@link RelativeBlockNameFunction} 同一套取整），任一轴不得超过 8 格；命中任意一个标签即返回
 * true（1.0）。</p>
 *
 * <p><b>1.7.10 没有数据驱动的方块标签</b>，这里只把能原生回答的标签映射出来：
 * {@code minecraft:replaceable}（"这格可以被替换/是不是空气"）→
 * {@link Block#isReplaceable} / {@link Block#isAir}。其余标签一律 false，并在
 * {@code DebugController} 打开时为每个标签打一条一次性提示，方便以后按实际用到的标签补映射
 * （不要在这里假装匹配，那只会让模型分支以错误的方式生效）。</p>
 */
public class QueryBlockTagFunction extends Function {

    /** wiki：相对坐标任一轴不得超过 8 格。 */
    private static final double MAX_RANGE = 8.0d;

    /** 已提示过的标签名（去命名空间、小写），避免每帧刷屏。 */
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
            double dx = getArg(0);
            double dy = getArg(1);
            double dz = getArg(2);
            if (Math.abs(dx) > MAX_RANGE || Math.abs(dy) > MAX_RANGE || Math.abs(dz) > MAX_RANGE) {
                return 0.0d;
            }
            Entity entity = ParticleEffectUtil.getCurrentEntity();
            if (entity == null || entity.worldObj == null) {
                return 0.0d;
            }
            // 与 ysm.relative_block_name 一致：以玩家下半身为原点（1.7.10 的 posY 是脚底 + yOffset）。
            int x = (int) Math.round((entity.posX + dx) - 0.5d);
            int y = (int) Math.round((entity.boundingBox.minY + dy) - 0.5d);
            int z = (int) Math.round((entity.posZ + dz) - 0.5d);
            Block block = entity.worldObj.getBlock(x, y, z);
            if (block == null) {
                return 0.0d;
            }
            for (int i = 3; i < this.args.length; i++) {
                String tag = MolangStringPool.get((int) getArg(i));
                if (tag == null || tag.isEmpty()) {
                    continue;
                }
                if (matchesTag(tag, block, entity.worldObj, x, y, z)) {
                    return 1.0d;
                }
                reportUnsupported(tag);
            }
            return 0.0d;
        } catch (Exception e) {
            return 0.0d;
        }
    }

    /** 把标签名映射到 1.7.10 能原生回答的方块属性上；不认识的标签返回 false。
     *  包内可见 + 只依赖 {@link IBlockAccess}（可为 null，空气/石头等实现不会解引用），便于单测。 */
    static boolean matchesTag(String tag, Block block, IBlockAccess world, int x, int y, int z) {
        if (block == null) {
            return false;
        }
        String name = stripNamespace(tag.toLowerCase(Locale.ROOT));
        if ("replaceable".equals(name)) {
            return block.isReplaceable(world, x, y, z) || block.isAir(world, x, y, z);
        }
        return false;
    }

    /**
     * 未命中标签的提示，分两类（与 {@code equipped_item_*_tag} 同一原则）：
     * 来源模组没装 → 永远匹配不到，警告一次（正确跳过，不是本模组的缺口）；来源可用但没映射
     * → 才是真缺口，只在 {@code DebugController} 下提示一次。
     */
    private static void reportUnsupported(String tag) {
        String name = stripNamespace(tag.toLowerCase(Locale.ROOT));
        if (!REPORTED_TAGS.add(name)) {
            return;
        }
        if (com.fox.ysmu.util.ModAvailability.isUnavailable(tag)) {
            ysmu.LOG.warn(
                "[YSMU-QUERY] block tag '{}' is ineffective: mod '{}' is not installed (matches nothing on 1.7.10)",
                tag, com.fox.ysmu.util.ModAvailability.namespaceOf(tag));
            return;
        }
        if (!Config.DEBUG_CONTROLLER) {
            return;
        }
        ysmu.LOG.info(
            "[YSMU-QUERY] relative_block_has_any_tag: no 1.7.10 mapping for block tag '{}' (only minecraft:replaceable is supported)",
            tag);
    }

    private static String stripNamespace(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }
}
