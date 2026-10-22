package com.fox.ysmu.client.renderer;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.ClientProxy;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.model.CustomPlayerModel;

import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.resource.GeckoLibCache;

class CustomPlayerRendererLifecycleTest {

    private static Field field(Class<?> type, String name) throws Exception {
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        return (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
    }

    /** Bypass the renderer constructor's RenderManager/GL initialization, not its release methods. */
    private static CustomPlayerRenderer renderer(CustomPlayerModel provider) throws Exception {
        CustomPlayerRenderer renderer = (CustomPlayerRenderer) unsafe().allocateInstance(CustomPlayerRenderer.class);
        field(CustomPlayerRenderer.class, "modelProvider").set(renderer, provider);
        field(CustomPlayerRenderer.class, "animatable").set(renderer, new CustomPlayerEntity());
        field(CustomPlayerRenderer.class, "lastPlayerModel").set(renderer, new HashMap<UUID, ResourceLocation>());
        field(CustomPlayerRenderer.class, "missingGeoModelLogged").set(renderer, new HashSet<String>());
        return renderer;
    }

    private static void select(CustomPlayerRenderer renderer, CustomPlayerModel provider,
        ResourceLocation id, GeoModel model) throws Exception {
        GeckoLibCache.getInstance().getGeoModels().put(id, model);
        provider.getModel(id);
        field(CustomPlayerRenderer.class, "geoModel").set(renderer, model);
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualReleaseCallbackClearsRendererAndProviderByObjectIdentity() throws Exception {
        ResourceLocation firstId = new ResourceLocation("ysmu", "renderer_release_first/main");
        ResourceLocation secondId = new ResourceLocation("ysmu", "renderer_release_second/main");
        CustomPlayerModel provider = new CustomPlayerModel();
        CustomPlayerRenderer renderer = renderer(provider);
        Field installed = field(ClientProxy.class, "CUSTOM_PLAYER_RENDERER");
        Object previous = installed.get(null);
        installed.set(null, renderer);
        try {
            GeoModel retired = new GeoModel();
            GeoModel replacement = new GeoModel();
            GeoBone head = new GeoBone();
            head.name = "Head";
            replacement.topLevelBones.add(head);
            select(renderer, provider, firstId, retired);
            select(renderer, provider, secondId, replacement);
            CustomPlayerModel.onGeoModelReleased(retired);
            assertSame(replacement, renderer.getGeoModel(), "a stale release cannot clear a replacement");
            assertSame(replacement, provider.getCurrentModel());

            Class<?> poseType = Class.forName(CustomPlayerModel.class.getName() + "$HeadPoseOffset");
            java.lang.reflect.Constructor<?> pose = poseType.getDeclaredConstructor(float.class, float.class);
            pose.setAccessible(true);
            ((Map<Object, Object>) field(CustomPlayerModel.class, "headPoseOffsets").get(provider))
                .put(head, pose.newInstance(.25f, .5f));
            head.setRotationX(.25f);
            head.setRotationY(.5f);
            CustomPlayerModel.onGeoModelReleased(replacement);
            assertNull(renderer.getGeoModel());
            assertNull(provider.getCurrentModel());
            assertTrue(provider.getAnimationProcessor().getModelRendererList().isEmpty());
            assertTrue(((Map<?, ?>) field(CustomPlayerModel.class, "headPoseOffsets").get(provider)).isEmpty());
            assertEquals(0f, head.getRotationX());
            CustomPlayerModel.onGeoModelReleased(replacement); // idempotent
            CustomPlayerModel.onGeoModelReleased(null);
        } finally {
            installed.set(null, previous);
            GeckoLibCache.getInstance().getGeoModels().remove(firstId);
            GeckoLibCache.getInstance().getGeoModels().remove(secondId);
            CustomPlayerModel.clearInjectedCache(firstId);
            CustomPlayerModel.clearInjectedCache(secondId);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullRuntimeClearResetsRendererEvenWhenNoAssetCallbackOwnsItsLastModel() throws Exception {
        CustomPlayerModel provider = new CustomPlayerModel();
        CustomPlayerRenderer renderer = renderer(provider);
        CustomPlayerEntity actor = renderer.getCustomPlayerEntity();
        actor.setPlayer((EntityPlayerMP) unsafe().allocateInstance(EntityPlayerMP.class));
        actor.setMainModel(new ResourceLocation("ysmu", "old/main"));
        actor.setTexture(new ResourceLocation("ysmu", "old/skin.png"));
        Map<UUID, ResourceLocation> lastModels = (Map<UUID, ResourceLocation>)
            field(CustomPlayerRenderer.class, "lastPlayerModel").get(renderer);
        lastModels.put(UUID.randomUUID(), new ResourceLocation("ysmu", "old/main"));
        field(CustomPlayerRenderer.class, "geoModel").set(renderer, new GeoModel());
        Field installed = field(ClientProxy.class, "CUSTOM_PLAYER_RENDERER");
        Object previous = installed.get(null);
        installed.set(null, renderer);
        try {
            Method clear = ClientModelManager.class.getDeclaredMethod("clearRuntimeModelCaches");
            clear.setAccessible(true);
            clear.invoke(null);
            assertNull(renderer.getGeoModel());
            assertNull(provider.getCurrentModel());
            assertTrue(lastModels.isEmpty());
            assertNull(actor.getPlayer());
            assertNull(field(CustomPlayerEntity.class, "mainModel").get(actor));
            assertNull(field(CustomPlayerEntity.class, "texture").get(actor));
            renderer.clearModelSession(); // idempotent
        } finally {
            installed.set(null, previous);
        }
    }
}
