package com.fox.ysmu.client.animation.molang;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import com.fox.ysmu.client.particle.ParticleEffectUtil;

import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code ysm.effect_level(id1, id2...)} 的 mclib 实现。
 *
 * <p>YSM-wiki: molang/ref（1.2.0，2.5.0 起支持多个 id 并把等级**求和**）—— 返回渲染实体身上的
 * 状态效果等级。OpenYSM 的 {@code EffectLevel} 用的是
 * {@code mobEffectInstance.getAmplifier() + 1}，即 <b>1 级为 1</b>（不是 0），这里保持同一语义。</p>
 *
 * <p>1.7.10 没有 {@code ForgeRegistries.MOB_EFFECTS}，用 1.19+ 的 snake_case 药水名 →
 * 1.7.10 {@link Potion#id}（1.7.10 的 id 是 1..23，0 号是空位）的映射表，
 * 再经 {@link EntityLivingBase#getActivePotionEffect(Potion)} 取效果。</p>
 *
 * <p>已知限制：wiki 里 {@code effect_level} 也能读**箭矢**上的效果（药水箭）。箭矢渲染走
 * {@code ProjectileControllerRuntime}，其 {@code ysm.*} 变量只从 {@code MolangParser.VARIABLES}
 * 里读，没有药水上下文，因此这里只覆盖玩家/生物渲染路径。</p>
 */
public class YsmEffectLevelFunction extends Function {

    /** 1.19+ 药水名（snake_case）→ 1.7.10 {@link Potion#id}。 */
    private static final Map<String, Integer> EFFECT_IDS = new HashMap<>();

    static {
        EFFECT_IDS.put("speed", 1);
        EFFECT_IDS.put("slowness", 2);
        EFFECT_IDS.put("haste", 3);
        EFFECT_IDS.put("mining_fatigue", 4);
        EFFECT_IDS.put("strength", 5);
        EFFECT_IDS.put("instant_health", 6);
        EFFECT_IDS.put("instant_damage", 7);
        EFFECT_IDS.put("jump_boost", 8);
        EFFECT_IDS.put("nausea", 9);
        EFFECT_IDS.put("regeneration", 10);
        EFFECT_IDS.put("resistance", 11);
        EFFECT_IDS.put("fire_resistance", 12);
        EFFECT_IDS.put("water_breathing", 13);
        EFFECT_IDS.put("invisibility", 14);
        EFFECT_IDS.put("blindness", 15);
        EFFECT_IDS.put("night_vision", 16);
        EFFECT_IDS.put("hunger", 17);
        EFFECT_IDS.put("weakness", 18);
        EFFECT_IDS.put("poison", 19);
        EFFECT_IDS.put("wither", 20);
        EFFECT_IDS.put("health_boost", 21);
        EFFECT_IDS.put("absorption", 22);
        EFFECT_IDS.put("saturation", 23);
        // 旧名/别名：1.20.5 之前的 resistance、1.19 之前的 jump。
        EFFECT_IDS.put("damage_resistance", 11);
        EFFECT_IDS.put("jump", 8);
    }

    public YsmEffectLevelFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    public double get() {
        try {
            Entity entity = ParticleEffectUtil.getCurrentEntity();
            if (!(entity instanceof EntityLivingBase)) {
                return 0.0d;
            }
            EntityLivingBase living = (EntityLivingBase) entity;
            int total = 0;
            for (int i = 0; i < this.args.length; i++) {
                Potion potion = potionFor(MolangStringPool.get((int) getArg(i)));
                if (potion == null) {
                    continue;
                }
                PotionEffect effect = living.getActivePotionEffect(potion);
                if (effect != null) {
                    total += effect.getAmplifier() + 1;
                }
            }
            return total;
        } catch (Exception e) {
            return 0.0d;
        }
    }

    /** 药水名 → 1.7.10 {@link Potion}；未知名字或该 id 上没有药水时返回 {@code null}。 */
    static Potion potionFor(String effectName) {
        Integer id = effectId(effectName);
        if (id == null || id < 0 || id >= Potion.potionTypes.length) {
            return null;
        }
        return Potion.potionTypes[id];
    }

    /** 只做名字→id 映射（不触碰 {@link Potion}），便于单测。 */
    static Integer effectId(String effectName) {
        if (effectName == null) {
            return null;
        }
        String name = MolangEquipmentSlots.stripNamespace(effectName).trim().toLowerCase(Locale.ROOT);
        return name.isEmpty() ? null : EFFECT_IDS.get(name);
    }
}
