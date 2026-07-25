package com.fox.ysmu.compat;

import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * AdventureBackpack2 背部可穿戴物品渲染兼容层
 *
 * <p>YSMU 在 {@code RenderPlayerEvent.Pre} 中取消了原版玩家渲染，
 * 导致 AdventureBackpack2 在 {@code RenderPlayerEvent.Specials.Pre}
 * 中注册的背包/直升机背包渲染不会触发。此兼容层在 YSMU 的 GeckoLib
 * 模型渲染完成后，手动调用 AdventureBackpack2 的可穿戴物品渲染。</p>
 *
 * <p>通过反射调用 {@code Wearing.getWearingWearable(player)} 和
 * {@code IBackWearableItem} 接口方法，无编译依赖。</p>
 */
@SideOnly(Side.CLIENT)
public final class AdventureBackpackCompat {

    private static final boolean MOD_LOADED = Loader.isModLoaded("adventurebackpack");

    // Reflected class/method handles
    private static Class<?> wearingClass;
    private static Method getWearingWearableMethod;

    private static boolean reflectionFailed = false;

    static {
        if (MOD_LOADED) {
            try {
                wearingClass = Class.forName("com.darkona.adventurebackpack.util.Wearing");
                getWearingWearableMethod = wearingClass.getMethod("getWearingWearable", EntityPlayer.class);
            } catch (Exception e) {
                reflectionFailed = true;
            }
        }
    }

    private AdventureBackpackCompat() {}

    /**
     * AdventureBackpack2 是否已加载且反射初始化成功。
     */
    public static boolean isAvailable() {
        return MOD_LOADED && !reflectionFailed;
    }

    /**
     * 获取玩家穿戴的背部物品（直升机背包/普通背包/喷气背包等）。
     *
     * @return ItemStack 或 null（未穿戴）
     */
    public static ItemStack getWearableItem(EntityPlayer player) {
        if (!isAvailable() || player == null) return null;
        try {
            Object result = getWearingWearableMethod.invoke(null, player);
            return (ItemStack) result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 在 YSMU 的 GeckoLib 模型渲染完成后，渲染背部可穿戴物品。
     * 应在 CustomPlayerRenderer.doRender() 中 {@code super.doRender(...)} 之后调用。
     */
    public static void renderWearable(EntityPlayer player, double x, double y, double z, float partialTicks) {
        ItemStack wearable = getWearableItem(player);
        if (wearable == null || wearable.getItem() == null) return;

        try {
            // Get model and texture via IBackWearableItem interface
            ItemStack copy = wearable.copy();
            Object item = copy.getItem();
            Class<?> iface = Class.forName("com.darkona.adventurebackpack.item.IBackWearableItem");
            if (!iface.isInstance(item)) return;

            Method getModelMethod = iface.getMethod("getWearableModel", ItemStack.class);
            Method getTextureMethod = iface.getMethod("getWearableTexture", ItemStack.class);

            ModelBiped model = (ModelBiped) getModelMethod.invoke(item, copy);
            ResourceLocation texture = (ResourceLocation) getTextureMethod.invoke(item, copy);

            if (model == null || texture == null) return;

            // Body rotation is handled by model.render() internally using bipedBody rotations.
            // Do NOT set rotateAngleY here — the GL rotation below already handles yaw.
            model.bipedBody.rotateAngleX = 0.0F;
            model.bipedBody.rotateAngleY = 0.0F;
            model.bipedBody.rotateAngleZ = 0.0F;

            // Render the wearable model at the player's position
            GL11.glPushMatrix();

            // Translate to player position (same as vanilla RenderLivingEntity)
            GL11.glTranslated(x, y, z);
            GL11.glRotatef(180.0F - player.renderYawOffset, 0.0F, 1.0F, 0.0F);

            // Minecraft model coordinate system flip (vanilla RenderLivingEntity does this)
            GL11.glScalef(-1.0F, -1.0F, 1.0F);

            // Shift wearable up by about half a player model height (~1 GL unit)
            // so the backpack sits on the back (torso area) instead of at the feet.
            // 玩家朝向为基准 分别offset: 左右, 上下, 前后, 且均正负符号翻转
            GL11.glTranslatef(0.0F, -1.5F, -0.1F);

            // Apply sneak offset (player height adjustment when sneaking)
            float sneakOffset = player.isSneaking() ? 0.125F : 0.0F;
            GL11.glTranslatef(0.0F, sneakOffset, 0.0F);

            // Bind wearable texture
            Minecraft.getMinecraft().renderEngine.bindTexture(texture);

            GL11.glEnable(GL12.GL_RESCALE_NORMAL);

            // Apply configurable scale
            float scale = (float) com.fox.ysmu.Config.WEARABLE_RENDER_SCALE;
            GL11.glScalef(scale, scale, scale);

            // Render the model
            model.render(player, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0625F);

            GL11.glPopMatrix();

        } catch (Exception ignored) {
            // Reflection or rendering failed silently
        }
    }
}
