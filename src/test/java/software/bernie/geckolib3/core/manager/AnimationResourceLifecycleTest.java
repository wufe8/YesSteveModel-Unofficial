package software.bernie.geckolib3.core.manager;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.animation.VirtualBone;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;

class AnimationResourceLifecycleTest {
    private static final class Actor implements IAnimatable {
        final AnimationFactory factory = new AnimationFactory(this);
        public AnimationFactory getFactory() { return factory; }
        public void registerControllers(AnimationData data) {
            data.addAnimationController(new AnimationController<Actor>(this, "test", 0, event -> PlayState.STOP));
        }
    }

    private static Field field(String name) throws Exception {
        Field field = AnimationController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    void geometryReleasePreservesClockAndOtherGeometry() throws Exception {
        Actor actor = new Actor();
        AnimationData data = actor.factory.getOrCreateAnimationData(1);
        AnimationController controller = data.getAnimationControllers().get("test");
        IBone retired = new VirtualBone("old");
        IBone retained = new VirtualBone("other");
        Map<String, IBone> index = new HashMap<>();
        index.put("old", retired);
        field("boneNameToBone").set(controller, index);
        data.getBoneSnapshotCollection().put("old", Pair.of(retired, new BoneSnapshot(retired)));
        data.getBoneSnapshotCollection().put("other", Pair.of(retained, new BoneSnapshot(retained)));
        Animation animation = new Animation();
        field("currentAnimation").set(controller, animation);
        data.tick = 120;
        data.startTick = 20.0;
        data.releaseGeometry(Collections.singleton(retired), index);
        assertEquals(120, data.tick);
        assertEquals(20.0, data.startTick);
        assertSame(animation, field("currentAnimation").get(controller));
        assertFalse(data.getBoneSnapshotCollection().containsKey("old"));
        assertSame(retained, data.getBoneSnapshotCollection().get("other").getLeft());
        assertTrue(((Map<?, ?>) field("boneNameToBone").get(controller)).isEmpty());
    }

    @Test
    void animationEvictionClearsCustomTimeReferenceOnlyForMatchingResource() throws Exception {
        Actor actor = new Actor();
        AnimationData first = actor.factory.getOrCreateAnimationData(1);
        AnimationData second = actor.factory.getOrCreateAnimationData(2);
        ResourceLocation firstId = new ResourceLocation("ysmu_test", "lifecycle_first");
        first.bindAnimationFile(firstId);
        second.bindAnimationFile(new ResourceLocation("ysmu_test", "lifecycle_second"));
        Animation animation = new Animation();
        for (AnimationData data : new AnimationData[] { first, second }) {
            AnimationController controller = data.getAnimationControllers().get("test");
            field("currentAnimation").set(controller, animation);
            field("lastAnimTimeAnimation").set(controller, animation);
            field("lastAnimTimeTick").setDouble(controller, 100);
            field("lastActualTick").setDouble(controller, 110);
        }
        AnimationData.releaseAnimationFile(firstId);
        AnimationController released = first.getAnimationControllers().get("test");
        assertNull(field("currentAnimation").get(released));
        assertNull(field("lastAnimTimeAnimation").get(released));
        assertEquals(-1.0, field("lastAnimTimeTick").getDouble(released));
        assertEquals(-1.0, field("lastActualTick").getDouble(released));
        assertSame(animation, field("currentAnimation").get(second.getAnimationControllers().get("test")));
    }

    @Test
    void disposedFactoryCreatesFreshPlaybackData() {
        Actor actor = new Actor();
        AnimationData before = actor.factory.getOrCreateAnimationData(1);
        before.tick = 200;
        actor.factory.dispose();
        AnimationData after = actor.factory.getOrCreateAnimationData(1);
        assertNotSame(before, after);
        assertEquals(0, after.tick);
        assertEquals(1, after.getAnimationControllers().size());
    }
}
