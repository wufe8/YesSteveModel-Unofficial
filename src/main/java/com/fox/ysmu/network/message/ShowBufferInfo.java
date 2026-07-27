package com.fox.ysmu.network.message;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

import javax.management.ObjectName;

import net.minecraft.util.ChatComponentText;

import com.fox.ysmu.ysmu;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: show Direct Buffer and native memory diagnostic info in chat.
 * Triggered by /ysm buffer.
 */
public class ShowBufferInfo implements IMessage {

    public ShowBufferInfo() {}

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<ShowBufferInfo, IMessage> {
        @Override
        public IMessage onMessage(ShowBufferInfo msg, MessageContext ctx) {
            if (ctx.side != Side.CLIENT) return null;
            try {
                final String prefix = "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r ";

                // 1. Direct Buffer Pool (java.nio:type=BufferPool,name=direct)
                ObjectName directPool = new ObjectName("java.nio:type=BufferPool,name=direct");
                long directMemoryUsed = ((Number) ManagementFactory.getPlatformMBeanServer()
                    .getAttribute(directPool, "MemoryUsed")).longValue();
                long directTotalCap = ((Number) ManagementFactory.getPlatformMBeanServer()
                    .getAttribute(directPool, "TotalCapacity")).longValue();
                int directCount = ((Number) ManagementFactory.getPlatformMBeanServer()
                    .getAttribute(directPool, "Count")).intValue();

                // 2. Mapped Buffer Pool (memory-mapped files)
                long mappedMemoryUsed = 0;
                try {
                    ObjectName mappedPool = new ObjectName("java.nio:type=BufferPool,name=mapped");
                    mappedMemoryUsed = (Long) ManagementFactory.getPlatformMBeanServer()
                        .getAttribute(mappedPool, "MemoryUsed");
                } catch (Exception ignored) {}

                // 3. Heap memory usage
                MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
                MemoryUsage heapUsage = memBean.getHeapMemoryUsage();
                long heapUsed = heapUsage.getUsed();
                long heapMax = heapUsage.getMax();

                // 4. Native Memory Tracking summary (if available via jcmd equivalent)
                // We can't easily run jcmd from here, but we can read /proc (Linux only)

                // Format output
                String directMB = String.format("%.1f", directMemoryUsed / (1024.0 * 1024.0));
                String directCapMB = String.format("%.1f", directTotalCap / (1024.0 * 1024.0));
                String mappedMB = String.format("%.1f", mappedMemoryUsed / (1024.0 * 1024.0));
                String heapMB = String.format("%.1f", heapUsed / (1024.0 * 1024.0));
                String heapMaxMB = String.format("%.1f", heapMax / (1024.0 * 1024.0));

                // Show Direct Buffer info
                String line1 = prefix + "\u00a7eDirect Buffer\u00a7r: "
                    + "\u00a7b" + directMB + " MB\u00a7r used"
                    + " (\u00a77" + directCount + " buffers\u00a7r, cap "
                    + "\u00a77" + directCapMB + " MB\u00a7r)";

                // Show Mapped Buffer info
                String line2 = prefix + "\u00a7eMapped Buffer\u00a7r: "
                    + "\u00a7b" + mappedMB + " MB\u00a7r";

                // Show Heap info
                String line3 = prefix + "\u00a7eHeap\u00a7r: "
                    + "\u00a7b" + heapMB + " MB\u00a7r / "
                    + "\u00a7b" + heapMaxMB + " MB\u00a7r";

                net.minecraft.client.Minecraft.getMinecraft().thePlayer
                    .addChatMessage(new ChatComponentText(line1));
                net.minecraft.client.Minecraft.getMinecraft().thePlayer
                    .addChatMessage(new ChatComponentText(line2));
                net.minecraft.client.Minecraft.getMinecraft().thePlayer
                    .addChatMessage(new ChatComponentText(line3));

                // Also log for detailed analysis
                ysmu.LOG.info("[YSM-BUFFER] Direct: {} MB ({} buffers, cap {} MB), "
                    + "Mapped: {} MB, Heap: {} MB / {} MB",
                    directMB, directCount, directCapMB,
                    mappedMB, heapMB, heapMaxMB);

            } catch (Exception e) {
                ysmu.LOG.error("[YSM-BUFFER] Failed to read buffer info", e);
                if (net.minecraft.client.Minecraft.getMinecraft().thePlayer != null) {
                    net.minecraft.client.Minecraft.getMinecraft().thePlayer
                        .addChatMessage(new ChatComponentText(
                            "\u00a76\u00a7l[\u00a7aYSM\u00a76\u00a7l]\u00a7r "
                            + "\u00a7cError reading buffer info: " + e.getMessage()));
                }
            }
            return null;
        }
    }
}
