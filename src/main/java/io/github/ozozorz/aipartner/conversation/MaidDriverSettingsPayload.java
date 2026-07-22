package io.github.ozozorz.aipartner.conversation;

import io.github.ozozorz.aipartner.AiPartnerMod;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client request that changes one owned maid's interpreter mode. */
public record MaidDriverSettingsPayload(
        UUID maidId,
        String driveMode
) implements CustomPacketPayload {
    public static final Type<MaidDriverSettingsPayload> TYPE = new Type<>(AiPartnerMod.id("driver_settings"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MaidDriverSettingsPayload> CODEC =
            CustomPacketPayload.codec(MaidDriverSettingsPayload::write, MaidDriverSettingsPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(maidId);
        buffer.writeUtf(driveMode, 8);
    }

    private static MaidDriverSettingsPayload read(RegistryFriendlyByteBuf buffer) {
        return new MaidDriverSettingsPayload(
                buffer.readUUID(),
                buffer.readUtf(8)
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
