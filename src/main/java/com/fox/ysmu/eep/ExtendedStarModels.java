package com.fox.ysmu.eep; // 放在 eep 包下

import java.util.Set;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.common.IExtendedEntityProperties;

import com.google.common.collect.Sets;

import cpw.mods.fml.common.FMLLog;

public class ExtendedStarModels implements IExtendedEntityProperties {

    // 1. 唯一的标识符
    public final static String EXT_PROP_NAME = "ysmu_StarModels";

    // 2. 将原 StarModelsCapability 的字段和方法直接移到这里
    private Set<ResourceLocation> starModels = Sets.newHashSet();
    private static final int MAX_FAVORITES = 4096;
    private static final int MAX_ENCODED_BYTES = 24 * 1024;
    private int encodedBytes;

    private static int encodedSize(ResourceLocation id) {
        return id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 2;
    }

    public synchronized void addModel(ResourceLocation modelId) {
        if (modelId == null || starModels.contains(modelId) || starModels.size() >= MAX_FAVORITES) return;
        int size = encodedSize(modelId);
        if (size > com.fox.ysmu.network.PacketReadUtil.MAX_ID_BYTES + 2 || size > MAX_ENCODED_BYTES - encodedBytes) return;
        starModels.add(modelId);
        encodedBytes += size;
    }

    public void copyFrom(ExtendedStarModels source) {
        // 创建一个新的 Set 副本，而不是直接引用，这很重要！
        setStarModels(source.getStarModels());
    }

    public synchronized void removeModel(ResourceLocation modelId) {
        if (modelId != null && starModels.remove(modelId)) encodedBytes -= encodedSize(modelId);
    }

    public synchronized boolean containModel(ResourceLocation modelId) {
        return starModels.contains(modelId);
    }

    public synchronized Set<ResourceLocation> getStarModels() {
        return Sets.newHashSet(starModels);
    }

    public synchronized void setStarModels(Set<ResourceLocation> models) {
        clear();
        if (models != null) for (ResourceLocation modelId : models) addModel(modelId);
    }

    public synchronized void clear() {
        starModels.clear();
        encodedBytes = 0;
    }

    // 3. 静态辅助方法
    /**
     * 将 EEP 注册到玩家身上
     */
    public static void register(EntityPlayer player) {
        player.registerExtendedProperties(EXT_PROP_NAME, new ExtendedStarModels());
    }

    /**
     * 从玩家身上获取 EEP 实例
     */
    public static ExtendedStarModels get(EntityPlayer player) {
        return (ExtendedStarModels) player.getExtendedProperties(EXT_PROP_NAME);
    }

    // 4. 实现 IExtendedEntityProperties 接口的方法

    /**
     * 将数据保存到 NBT
     */
    @Override
    public synchronized void saveNBTData(NBTTagCompound compound) {
        NBTTagList listTag = new NBTTagList();
        for (ResourceLocation modelId : starModels) {
            if (modelId != null) listTag.appendTag(new NBTTagString(modelId.toString()));
        }
        compound.setTag(EXT_PROP_NAME, listTag);
    }

    /**
     * 从 NBT 读取数据
     */
    @Override
    public synchronized void loadNBTData(NBTTagCompound compound) {
        if (compound.hasKey(EXT_PROP_NAME)) {
            NBTTagList listTag = (NBTTagList) compound.getTag(EXT_PROP_NAME);
            clear();
            for (int i = 0; i < listTag.tagCount(); i++) {
                String modelIdStr = listTag.getStringTagAt(i);
                try {
                    addModel(new ResourceLocation(modelIdStr));
                } catch (Exception e) {
                    FMLLog.warning("[ysmu] Failed to load Starred Model ResourceLocation from NBT: " + modelIdStr);
                }
            }
        }
    }

    @Override
    public void init(Entity entity, World world) {
        // 初始化时调用，此处无需操作
    }
}
