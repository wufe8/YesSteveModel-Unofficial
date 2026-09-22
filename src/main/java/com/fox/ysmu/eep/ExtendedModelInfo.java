package com.fox.ysmu.eep; // 建议放在 eep 包下

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.common.IExtendedEntityProperties;

import com.fox.ysmu.Config;
import com.fox.ysmu.util.ModelIdUtil;
import com.fox.ysmu.ysmu;

public class ExtendedModelInfo implements IExtendedEntityProperties {

    // 1. 唯一的标识符
    public final static String EXT_PROP_NAME = "ysmu_ModelInfo";

    // 用于网络同步和服务器端逻辑
    private final EntityPlayer player;

    // 2. 将原 ModelInfoCapability 的字段和方法直接移到这里
    private ResourceLocation modelId = new ResourceLocation(
        ysmu.MODID,
        ModelIdUtil.getInternalModelId(Config.DEFAULT_MODEL_ID));
    private ResourceLocation selectTexture = ModelIdUtil.getSubModelId(modelId, Config.DEFAULT_MODEL_TEXTURE);
    private String animation = "idle";
    private boolean playAnimation = false;
    private boolean dirty; // dirty 标志可以保留，用于客户端渲染逻辑判断是否需要更新

    public ExtendedModelInfo(EntityPlayer player) {
        this.player = player;
    }

    /**
     * 换模型（可同时换贴图）。**模型变了就把待播动画一起作废。**
     *
     * <p>{@code play_animation} + {@code animation} 记的是"轮盘/指令在这个模型上触发的
     * 那条动画"，而动画名是按*当前模型文件*里的名字查的：同名动画在另一个模型里完全可以
     * 是别的东西。不一起清掉的实测症状：在 A 上按过轮盘"变身"后切到 B，EEP 仍带着
     * {@code play_animation=true} + {@code extra0}，切模型这条 dirty 广播把它原样发给
     * 客户端，B 就自己播了一遍"变身"，并顺手改掉 B 的 {@code v.roaming.a/b}。</p>
     *
     * <p>放在这个唯一的赋值点上：客户端 GUI（{@code ModelButton.doPress} 的乐观更新）
     * 与服务端 {@code SetModelAndTexture} 都走这里，两条路各自清一次，不存在"谁先到"的
     * 时序问题。贴图按钮会把当前模型原样传进来，因此换贴图不会误伤正在播的动画。</p>
     *
     * <p>{@code modelId == null} 直接拒绝：模型 id 是这份状态的键，NBT 同步
     * （{@link #saveNBTData}）和渲染（{@code CustomPlayerRenderer}）都对它直接
     * {@code toString()} / 比较，写进 null 等于埋一个跨线程的 NPE，而不是"回默认模型"。
     * 调用方都保证非空，网络层也会先拦掉空 id。</p>
     */
    public void setModelAndTexture(ResourceLocation modelId, ResourceLocation selectTexture) {
        if (modelId == null) {
            return;
        }
        ResourceLocation oldBase = ModelIdUtil.getModelIdFromSubId(this.modelId);
        ResourceLocation newBase = ModelIdUtil.getModelIdFromSubId(modelId);
        if (!oldBase.equals(newBase)) {
            stopAnimation();
        }
        this.modelId = modelId;
        this.selectTexture = selectTexture;
        markDirty();
    }

    public void copyFrom(ExtendedModelInfo source) {
        this.modelId = source.modelId;
        this.selectTexture = source.selectTexture;
        this.animation = source.animation;
        this.playAnimation = source.playAnimation;
        markDirty();
    }

    public ResourceLocation getModelId() {
        return modelId;
    }

    public ResourceLocation getSelectTexture() {
        return selectTexture;
    }

    public void setSelectTexture(ResourceLocation selectTexture) {
        this.selectTexture = selectTexture;
        markDirty();
    }

    public void playAnimation(String animation) {
        this.animation = animation;
        this.playAnimation = true;
        markDirty();
    }

    public void stopAnimation() {
        this.playAnimation = false;
        markDirty();
    }

    public String getAnimation() {
        return animation;
    }

    public boolean isPlayAnimation() {
        return playAnimation;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    // 3. 静态辅助方法
    /**
     * 将 EEP 注册到玩家身上
     */
    public static void register(EntityPlayer player) {
        player.registerExtendedProperties(EXT_PROP_NAME, new ExtendedModelInfo(player));
    }

    /**
     * 从玩家身上获取 EEP 实例
     */
    public static ExtendedModelInfo get(EntityPlayer player) {
        return (ExtendedModelInfo) player.getExtendedProperties(EXT_PROP_NAME);
    }

    // 4. 实现 IExtendedEntityProperties 接口的方法

    /**
     * 将数据保存到 NBT
     * 这个方法会把所有字段打包到一个独立的 NBTTagCompound 中，避免命名冲突
     */
    @Override
    public void saveNBTData(NBTTagCompound compound) {
        NBTTagCompound properties = new NBTTagCompound();
        properties.setString("model_id", this.modelId.toString());
        properties.setString("select_texture", this.selectTexture.toString());
        properties.setString("animation", this.animation);
        properties.setBoolean("play_animation", this.playAnimation);

        compound.setTag(EXT_PROP_NAME, properties);
    }

    /**
     * 从 NBT 读取数据
     */
    @Override
    public void loadNBTData(NBTTagCompound compound) {
        if (compound.hasKey(EXT_PROP_NAME)) {
            NBTTagCompound properties = compound.getCompoundTag(EXT_PROP_NAME);
            this.modelId = new ResourceLocation(properties.getString("model_id"));
            this.selectTexture = new ResourceLocation(properties.getString("select_texture"));
            this.animation = properties.getString("animation");
            this.playAnimation = properties.getBoolean("play_animation");
        }
    }

    @Override
    public void init(Entity entity, World world) {
        // 初始化时调用
    }
}
