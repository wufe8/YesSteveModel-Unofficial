package com.fox.ysmu.network.message;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;

import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.eep.ExtendedStarModels;
import com.fox.ysmu.network.PacketReadUtil;

import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class ServerboundValidationTest {

    @Test
    void truncatedPayloadCannotAllocateItsClaimedBytes() {
        ByteBuf buf = Unpooled.buffer().writeInt(2 * 1024 * 1024);
        assertThrows(IllegalArgumentException.class, () -> new C2SModelSyncPayload17().fromBytes(buf));
    }

    @Test
    void forgedLegacyCountIsRejectedBeforeAllocatingTheArray() {
        ByteBuf buf = Unpooled.buffer().writeInt(100000);
        assertThrows(IllegalArgumentException.class, () -> new SyncModelFiles().fromBytes(buf));
    }

    @Test
    void oversizedModelAndAnimationStringsAreRejected() {
        ByteBuf model = Unpooled.buffer();
        ByteBufUtils.writeUTF8String(model, String.join("", Collections.nCopies(PacketReadUtil.MAX_ID_BYTES + 1, "a")));
        ByteBufUtils.writeUTF8String(model, "ysmu:demo/texture");
        assertThrows(IllegalArgumentException.class, () -> new SetModelAndTexture().fromBytes(model));
        ByteBuf anim = Unpooled.buffer();
        ByteBufUtils.writeUTF8String(anim, String.join("", Collections.nCopies(PacketReadUtil.MAX_ANIMATION_BYTES + 1, "a")));
        assertThrows(IllegalArgumentException.class, () -> new SetPlayAnimation().fromBytes(anim));
    }

    @Test
    void syncMustNameTheCurrentSelectedModelsMainId() {
        ResourceLocation current = new ResourceLocation("ysmu", "demo");
        assertTrue(C2SMolangSync.isCurrentModel(new ResourceLocation("ysmu", "demo/main"), current));
        assertFalse(C2SMolangSync.isCurrentModel(new ResourceLocation("ysmu", "other/main"), current));
        assertFalse(C2SMolangSync.isCurrentModel(new ResourceLocation("ysmu", "demo/arm"), current));
        assertFalse(C2SMolangSync.isCurrentModel(new ResourceLocation("evil", "demo/main"), current));
    }

    @Test
    void favoritesRejectNullAndBoundTotalSerializationWithoutServerWhitelist() {
        ExtendedStarModels favorites = new ExtendedStarModels();
        favorites.addModel(null);
        assertTrue(favorites.getStarModels().isEmpty());
        String longName = String.join("", Collections.nCopies(3000, "a"));
        for (int i = 0; i < 100; i++) favorites.addModel(new ResourceLocation("ysmu", longName + i));
        ByteBuf encoded = Unpooled.buffer();
        new SyncStarModels(favorites.getStarModels()).toBytes(encoded);
        assertTrue(encoded.readableBytes() <= 24 * 1024 + 4);
        assertTrue(favorites.getStarModels().size() > 0, "client-local favorites need no server model index");
        ResourceLocation removed = favorites.getStarModels().iterator().next();
        favorites.removeModel(removed);
        favorites.addModel(new ResourceLocation("ysmu", "replacement"));
        assertTrue(favorites.containModel(new ResourceLocation("ysmu", "replacement")));
    }
}
