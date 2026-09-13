package com.fox.ysmu.client.animation.molang;

import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.world.biome.BiomeGenBase;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import com.fox.ysmu.client.particle.ParticleEffectUtil;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import software.bernie.geckolib3.core.molang.MolangStringPool;

/**
 * {@code ysm.dump_*} 系列调试函数的 mclib 实现（YSM-wiki: molang/ref 1.2.0）。
 *
 * <table>
 * <tr><td>{@code ysm.dump_equipped_item(slotType)}</td><td>槽位物品 id / 耐久 / 附魔</td></tr>
 * <tr><td>{@code ysm.dump_relative_block(dx, dy, dz)}</td><td>相对偏移处方块 id（任一轴 ≤ 8）</td></tr>
 * <tr><td>{@code ysm.dump_mods}</td><td>已装模组 id 列表</td></tr>
 * <tr><td>{@code ysm.dump_effects}</td><td>实体身上的药水效果</td></tr>
 * <tr><td>{@code ysm.dump_biome}</td><td>所处群系名与 id</td></tr>
 * </table>
 *
 * <p>全部只在动画调试模式下输出（详见 {@link MolangDebugOutput}），返回值恒为 0。</p>
 *
 * <p><b>已知差异</b>：wiki 的 {@code dump_biome} 还会输出群系**标签**，1.7.10 没有群系标签；
 * {@code dump_equipped_item} 的附魔用 1.7.10 的 {@code Enchantment.enchantmentsList}
 * 反查名字，模组附魔可能没有可读名，此时退化成 {@code id=N}。</p>
 */
public class YsmDebugDumpFunction extends Function {

    /** wiki：相对坐标任一轴不得超过 8 格。 */
    private static final double MAX_RANGE = 8.0d;

    /** dump_mods 最多列多少个模组，防止大型整合包把聊天框写满。 */
    private static final int MAX_MODS = 40;

    public YsmDebugDumpFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 0;
    }

    @Override
    public double get() {
        try {
            if (!MolangDebugOutput.isEnabled()) {
                return 0.0d;
            }
            Entity entity = ParticleEffectUtil.getCurrentEntity();
            String function = this.name == null ? "" : this.name;
            if (function.endsWith("dump_equipped_item")) {
                dumpEquippedItem(entity);
            } else if (function.endsWith("dump_relative_block")) {
                dumpRelativeBlock(entity);
            } else if (function.endsWith("dump_mods")) {
                dumpMods();
            } else if (function.endsWith("dump_effects")) {
                dumpEffects(entity);
            } else if (function.endsWith("dump_biome")) {
                dumpBiome(entity);
            }
        } catch (Exception ignored) {
            // 调试函数不能把表达式求值带崩。
        }
        return 0.0d;
    }

    private void dumpEquippedItem(Entity entity) {
        if (!(entity instanceof EntityPlayer)) {
            return;
        }
        String slotType = MolangStringPool.get((int) getArg(0));
        EntityPlayer player = (EntityPlayer) entity;
        ItemStack stack = MolangEquipmentSlots.get(player, slotType);
        if (stack == null || stack.getItem() == null) {
            MolangDebugOutput.emit("equipped_item(" + slotType + ") = <empty>");
            return;
        }
        String name = Item.itemRegistry == null ? null : Item.itemRegistry.getNameForObject(stack.getItem());
        StringBuilder message = new StringBuilder();
        message.append("equipped_item(")
            .append(slotType)
            .append(") = ")
            .append(name == null ? stack.getUnlocalizedName() : name)
            .append(" size=")
            .append(stack.stackSize)
            .append(" damage=")
            .append(stack.getItemDamageForDisplay())
            .append('/')
            .append(stack.getMaxDamage());
        if (stack.isItemEnchanted()) {
            message.append(" ench=")
                .append(stack.getEnchantmentTagList());
        }
        MolangDebugOutput.emit(message.toString());
    }

    private void dumpRelativeBlock(Entity entity) {
        double dx = getArg(0);
        double dy = getArg(1);
        double dz = getArg(2);
        if (entity == null || entity.worldObj == null) {
            return;
        }
        if (Math.abs(dx) > MAX_RANGE || Math.abs(dy) > MAX_RANGE || Math.abs(dz) > MAX_RANGE) {
            MolangDebugOutput.emit("relative_block(" + dx + ", " + dy + ", " + dz + ") = <out of range>");
            return;
        }
        // 与 RelativeBlockNameFunction / QueryBlockTagFunction 同一套取整：以脚底为原点。
        int x = (int) Math.round((entity.posX + dx) - 0.5d);
        int y = (int) Math.round((entity.boundingBox.minY + dy) - 0.5d);
        int z = (int) Math.round((entity.posZ + dz) - 0.5d);
        Block block = entity.worldObj.getBlock(x, y, z);
        String name = block == null || Block.blockRegistry == null
            ? null
            : Block.blockRegistry.getNameForObject(block);
        MolangDebugOutput.emit("relative_block(" + dx + ", " + dy + ", " + dz + ") @(" + x + "," + y + "," + z
            + ") = " + (name == null ? "<none>" : name) + " meta=" + entity.worldObj.getBlockMetadata(x, y, z));
    }

    private void dumpMods() {
        Map<String, ModContainer> mods = Loader.instance() == null ? null : Loader.instance().getIndexedModList();
        if (mods == null) {
            return;
        }
        StringBuilder message = new StringBuilder("mods(").append(mods.size()).append(") = ");
        int shown = 0;
        for (Map.Entry<String, ModContainer> entry : mods.entrySet()) {
            if (shown >= MAX_MODS) {
                message.append(", ...");
                break;
            }
            if (shown > 0) {
                message.append(", ");
            }
            message.append(entry.getKey());
            shown++;
        }
        MolangDebugOutput.emit(message.toString());
    }

    private void dumpEffects(Entity entity) {
        if (!(entity instanceof EntityLivingBase)) {
            return;
        }
        StringBuilder message = new StringBuilder("effects = ");
        boolean first = true;
        for (PotionEffect effect : ((EntityLivingBase) entity).getActivePotionEffects()) {
            if (!first) {
                message.append(", ");
            }
            message.append(effect.getEffectName())
                .append(" lvl=")
                .append(effect.getAmplifier() + 1)
                .append(" ticks=")
                .append(effect.getDuration());
            first = false;
        }
        MolangDebugOutput.emit(first ? "effects = <none>" : message.toString());
    }

    private void dumpBiome(Entity entity) {
        if (entity == null || entity.worldObj == null) {
            return;
        }
        int x = (int) Math.floor(entity.posX);
        int z = (int) Math.floor(entity.posZ);
        net.minecraft.world.biome.BiomeGenBase biome = entity.worldObj.getBiomeGenForCoords(x, z);
        if (biome == null) {
            MolangDebugOutput.emit("biome = <unknown>");
            return;
        }
        emitBiome(biome, x, z);
    }

    /** 拆出来只为让输出格式可单测（1.7.10 的 biomeName/biomeID 都是 public 字段）。 */
    static String biomeLine(BiomeGenBase biome, int x, int z) {
        return "biome = " + biome.biomeName + " (id=" + biome.biomeID + ") at(" + x + "," + z + ")";
    }

    private void emitBiome(BiomeGenBase biome, int x, int z) {
        MolangDebugOutput.emit(biomeLine(biome, x, z));
    }
}
