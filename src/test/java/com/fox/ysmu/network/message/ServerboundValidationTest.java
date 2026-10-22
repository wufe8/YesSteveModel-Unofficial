package com.fox.ysmu.network.message;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.UUID;

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
    void legacyClientboundFileRejectsForgedAndTruncatedLengthsBeforeAllocation() {
        for (int length : new int[] { -1, Integer.MAX_VALUE, 2 * 1024 * 1024 + 1, 48 }) {
            ByteBuf buf = Unpooled.buffer().writeInt(length);
            assertThrows(IllegalArgumentException.class, () -> new SendModelFile().fromBytes(buf));
        }
        ByteBuf oversized = Unpooled.buffer().writeInt(2 * 1024 * 1024 + 1)
            .writeZero(2 * 1024 * 1024 + 1);
        assertThrows(IllegalArgumentException.class, () -> new SendModelFile().fromBytes(oversized));
    }

    @Test
    void legacyClientboundFilePreservesNormalAndPasswordPayloads() {
        for (int length : new int[] { 0, 48, 1900 * 1024 }) {
            byte[] data = new byte[length];
            if (length > 0) data[length - 1] = 42;
            ByteBuf encoded = Unpooled.buffer();
            new SendModelFile(data).toBytes(encoded);
            SendModelFile decoded = new SendModelFile();
            decoded.fromBytes(encoded);
            ByteBuf roundTrip = Unpooled.buffer();
            decoded.toBytes(roundTrip);
            assertEquals(length, roundTrip.readInt());
            byte[] actual = new byte[length];
            roundTrip.readBytes(actual);
            assertArrayEquals(data, actual);
        }
    }

    @Test
    void legacyPasswordPacketBoundsItsDeclaredLength() {
        UUID id = UUID.randomUUID();
        for (int length : new int[] { -1, Integer.MAX_VALUE, 4096 + 1, 32 }) {
            ByteBuf buf = Unpooled.buffer()
                .writeLong(id.getMostSignificantBits())
                .writeLong(id.getLeastSignificantBits())
                .writeInt(length);
            assertThrows(IllegalArgumentException.class, () -> new SendModelPassword().fromBytes(buf));
        }
        ByteBuf oversized = Unpooled.buffer()
            .writeLong(id.getMostSignificantBits())
            .writeLong(id.getLeastSignificantBits())
            .writeInt(4096 + 1)
            .writeZero(4096 + 1);
        assertThrows(IllegalArgumentException.class, () -> new SendModelPassword().fromBytes(oversized));
    }

    @Test
    void remainingSimpleClientboundFieldsRejectForgedLengthsBeforeAllocation() {
        for (int length : new int[]{-1, Integer.MAX_VALUE, PacketReadUtil.MAX_ID_BYTES + 1, 48}) {
            ByteBuf path = Unpooled.buffer().writeInt(length);
            ByteBuf version = Unpooled.buffer().writeInt(0).writeInt(length);
            ByteBuf particle = Unpooled.buffer().writeInt(length);
            try {
                assertThrows(IllegalArgumentException.class, () -> new SyncGamePath().fromBytes(path));
                assertThrows(IllegalArgumentException.class, () -> new SyncGamePath().fromBytes(version));
                assertThrows(IllegalArgumentException.class, () -> new SpawnParticleCommand().fromBytes(particle));
            } finally {
                path.release();
                version.release();
                particle.release();
            }
        }
        ByteBuf oversized = Unpooled.buffer().writeInt(PacketReadUtil.MAX_ID_BYTES + 1)
            .writeZero(PacketReadUtil.MAX_ID_BYTES + 1);
        try {
            assertThrows(IllegalArgumentException.class, () -> new SpawnParticleCommand().fromBytes(oversized));
        } finally {
            oversized.release();
        }
    }

    @Test
    void gamePathAndParticleKeepTheirFixedIntStringWireFormat() {
        ByteBuf path = Unpooled.buffer();
        ByteBuf particle = Unpooled.buffer();
        ByteBuf roundTrip = Unpooled.buffer();
        try {
            SyncGamePath source = new SyncGamePath("H:/游戏/assets", "32");
            source.toBytes(path);
            assertEquals(source.getGamePath().getBytes(java.nio.charset.StandardCharsets.UTF_8).length, path.getInt(0));
            SyncGamePath decoded = new SyncGamePath();
            decoded.fromBytes(path);
            assertEquals(source.getGamePath(), decoded.getGamePath());
            assertEquals(source.getAssetVersion(), decoded.getAssetVersion());
            assertFalse(path.isReadable());

            new SpawnParticleCommand("minecraft:splash", 1, 2, 3, .1, .2, .3, .5f, 8).toBytes(particle);
            byte[] original = new byte[particle.readableBytes()];
            particle.getBytes(0, original);
            SpawnParticleCommand decodedParticle = new SpawnParticleCommand();
            decodedParticle.fromBytes(particle);
            decodedParticle.toBytes(roundTrip);
            byte[] actual = new byte[roundTrip.readableBytes()];
            roundTrip.readBytes(actual);
            assertArrayEquals(original, actual);
        } finally {
            path.release();
            particle.release();
            roundTrip.release();
        }
    }

    @Test
    void queryVariableCountAndNamesAreBoundedByActualBytes() {
        for (int count : new int[]{-1, Integer.MAX_VALUE, 1}) {
            ByteBuf truncated = Unpooled.buffer().writeInt(count);
            try {
                assertThrows(IllegalArgumentException.class, () -> new PacketQueryMolangVar().fromBytes(truncated));
            } finally {
                truncated.release();
            }
        }
        ByteBuf oversizedName = Unpooled.buffer().writeInt(1);
        ByteBuf valid = Unpooled.buffer();
        try {
            ByteBufUtils.writeUTF8String(oversizedName, String.join("", Collections.nCopies(PacketReadUtil.MAX_ID_BYTES + 1, "a")));
            assertThrows(IllegalArgumentException.class, () -> new PacketQueryMolangVar().fromBytes(oversizedName));
            java.util.List<String> names = java.util.Arrays.asList("q.health", "v.test", "");
            new PacketQueryMolangVar(names).toBytes(valid);
            PacketQueryMolangVar decoded = new PacketQueryMolangVar();
            decoded.fromBytes(valid);
            assertEquals(names, decoded.getVarNames());
            assertFalse(valid.isReadable());
        } finally {
            oversizedName.release();
            valid.release();
        }
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
