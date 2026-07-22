package io.github.ozozorz.aipartner.conversation;

import io.github.ozozorz.aipartner.AiPartnerMod;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server-authoritative data needed to render or refresh the R-key dialogue screen. */
public record MaidConversationScreenPayload(
        UUID maidId,
        String maidName,
        String driveMode,
        String apiKeyEnvironmentVariable,
        boolean llmReady,
        String readinessError,
        String model,
        boolean requestPending,
        boolean openScreen
) implements CustomPacketPayload {
    public static final Type<MaidConversationScreenPayload> TYPE = new Type<>(AiPartnerMod.id("conversation_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MaidConversationScreenPayload> CODEC =
            CustomPacketPayload.codec(MaidConversationScreenPayload::write, MaidConversationScreenPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(maidId);
        buffer.writeUtf(maidName, 64);
        buffer.writeUtf(driveMode, 8);
        buffer.writeUtf(apiKeyEnvironmentVariable, 64);
        buffer.writeBoolean(llmReady);
        buffer.writeUtf(readinessError, 32);
        buffer.writeUtf(model, 128);
        buffer.writeBoolean(requestPending);
        buffer.writeBoolean(openScreen);
    }

    private static MaidConversationScreenPayload read(RegistryFriendlyByteBuf buffer) {
        return new MaidConversationScreenPayload(
                buffer.readUUID(),
                buffer.readUtf(64),
                buffer.readUtf(8),
                buffer.readUtf(64),
                buffer.readBoolean(),
                buffer.readUtf(32),
                buffer.readUtf(128),
                buffer.readBoolean(),
                buffer.readBoolean()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
