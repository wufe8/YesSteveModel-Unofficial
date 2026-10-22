package com.fox.ysmu.client.animation.molang;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

/** YSM-wiki: molang/ref — camera rotation in degrees, 0 = pitch, 1 = yaw (not head rotation axes). */
public class QueryRotationToCameraFunction extends Function {

    public QueryRotationToCameraFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    public double get() {
        return cameraRotation(getArg(0));
    }

    public static double cameraRotation(double axis) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.renderViewEntity == null) return 0.0d;
        Entity view = mc.renderViewEntity;
        double partial = mc.timer == null ? 1.0d : mc.timer.renderPartialTicks;
        partial = Math.max(0.0d, Math.min(1.0d, partial));
        double pitch = view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * partial;
        double yaw = view.prevRotationYaw + (view.rotationYaw - view.prevRotationYaw) * partial;
        boolean front = mc.gameSettings != null && mc.gameSettings.thirdPersonView == 2;
        return component(axis, pitch, yaw, front);
    }

    static double component(double axis, double pitch, double yaw, boolean front) {
        if (axis == 0.0d) return front ? -pitch : pitch;
        if (axis == 1.0d) return front ? yaw + 180.0d : yaw;
        return 0.0d;
    }
}
