package com.fox.ysmu.client.animation.molang;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.particle.ParticleEffectUtil;
import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code query.equipped_item_any_tag(slotType, tag1, tag2...)} 与
 * {@code query.equipped_item_all_tags(...)} 的 mclib 实现（YSM-wiki: molang/ref，1.2.0）。
 *
 * <p>槽位取值见 {@link MolangEquipmentSlots}；标签匹配见 {@link ItemTagMatcher}（1.7.10 没有
 * 数据驱动的物品标签，只回答能原生回答的那部分，其余 false 并记一次性提示）。</p>
 *
 * <p><b>为什么必须注册而不是让它保持"未知函数"</b>：关键帧表达式里出现未注册函数时
 * {@code MathBuilder.createFunction} 会抛
 * {@code Function 'query.equipped_item_any_tag' couldn't be found!}，于是这条表达式解析失败、
 * 整个 animation 注册被外层 catch 丢掉（{@code Failed to register animation ...}）——
 * 参考库里那个模型整段"铁魔法"动画就是这么消失的。注册后至少能正常加载。</p>
 */
public class QueryItemTagFunction extends Function {

    /** 未映射标签的一次性提示（去命名空间、小写）。 */
    private static final Set<String> REPORTED_TAGS = Collections
        .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public QueryItemTagFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 2;
    }

    @Override
    public double get() {
        try {
            String slotType = MolangStringPool.get((int) getArg(0));
            Entity entity = ParticleEffectUtil.getCurrentEntity();
            if (!(entity instanceof EntityPlayer)) {
                return 0.0d;
            }
            ItemStack stack = MolangEquipmentSlots.get((EntityPlayer) entity, slotType);
            if (stack == null || stack.getItem() == null) {
                return 0.0d;
            }
            boolean requireAll = this.name != null && this.name.endsWith("all_tags");
            boolean matchedAny = false;
            int checked = 0;
            for (int i = 1; i < this.args.length; i++) {
                String tag = MolangStringPool.get((int) getArg(i));
                if (tag == null || tag.isEmpty()) {
                    continue;
                }
                checked++;
                boolean matched = ItemTagMatcher.matchesTag(stack, tag);
                if (matched && !requireAll) {
                    return 1.0d;
                }
                if (!matched && requireAll) {
                    return 0.0d;
                }
                matchedAny |= matched;
                reportUnsupported(tag);
            }
            // all_tags：所有标签都命中才算真；一个标签都没写时不算命中（与 OpenYSM 的空参数一致）。
            return requireAll ? (checked > 0 && matchedAny ? 1.0d : 0.0d) : 0.0d;
        } catch (Exception e) {
            return 0.0d;
        }
    }

    /**
     * 标签分类提示。两类分别对待：
     * <ul>
     *   <li><b>来源模组没装</b>（1.7.10 上最常见，如 {@code irons_spellbooks:staff}）：永远匹配不到，
     *       是"正确跳过"而不是本模组的缺口 —— 每个标签警告一次（与 BackhandCompat 的
     *       HiddenOffhandItems 同一原则）。</li>
     *   <li><b>未映射</b>（来源可用但我们没实现）：只在 {@code DebugController} 下提示一次，
     *       这是真正值得补映射的信号。</li>
     * </ul>
     */
    private static void reportUnsupported(String tag) {
        if (ItemTagMatcher.statusOf(tag) == ItemTagMatcher.Status.SUPPORTED) {
            return;
        }
        String name = ItemTagMatcher.normalize(tag);
        if (name.isEmpty() || !REPORTED_TAGS.add(name)) {
            return;
        }
        if (ItemTagMatcher.statusOf(tag) == ItemTagMatcher.Status.MOD_ABSENT) {
            ysmu.LOG.warn(
                "[YSMU-QUERY] item tag '{}' is ineffective: mod '{}' is not installed (matches nothing on 1.7.10)",
                tag.toLowerCase(Locale.ROOT), com.fox.ysmu.util.ModAvailability.namespaceOf(tag));
            return;
        }
        if (!Config.DEBUG_CONTROLLER) {
            return;
        }
        ysmu.LOG.info(
            "[YSMU-QUERY] equipped_item_*_tag: no 1.7.10 mapping for item tag '{}' (legacy has no data-driven item tags)",
            tag.toLowerCase(Locale.ROOT));
    }

    /** 测试/诊断用：已经提示过的标签数量。 */
    static int reportedTagCount() {
        return REPORTED_TAGS.size();
    }

    static void clearReportedTags() {
        REPORTED_TAGS.clear();
    }
}
