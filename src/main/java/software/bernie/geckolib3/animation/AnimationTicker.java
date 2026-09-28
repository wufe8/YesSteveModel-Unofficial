package software.bernie.geckolib3.animation;

import net.minecraft.client.Minecraft;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import software.bernie.geckolib3.core.manager.AnimationData;

public class AnimationTicker {

    private final java.lang.ref.WeakReference<AnimationData> data;

    public AnimationTicker(AnimationData data) {
        this.data = new java.lang.ref.WeakReference<>(data);
    }

    /** The bus must not keep an abandoned entity/factory alive. Explicit disposal stops immediately. */
    public void stop() {
        data.clear();
        cpw.mods.fml.common.FMLCommonHandler.instance().bus().unregister(this);
    }

    @SubscribeEvent
    public void tickEvent(TickEvent.ClientTickEvent event) {
        AnimationData data = this.data.get();
        if (data == null) {
            stop();
            return;
        }
        if (Minecraft.getMinecraft()
            .isGamePaused() && !data.shouldPlayWhilePaused) {
            return;
        }

        if (event.phase == TickEvent.Phase.END) {
            data.tick++;
        }
    }
}
