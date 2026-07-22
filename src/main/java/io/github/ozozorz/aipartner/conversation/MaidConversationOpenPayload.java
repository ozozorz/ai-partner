package io.github.ozozorz.aipartner.conversation;

import io.github.ozozorz.aipartner.AiPartnerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Empty client request asking the server for selected-maid dialogue settings. */
public record MaidConversationOpenPayload() implements CustomPacketPayload {
    public static final Type<MaidConversationOpenPayload> TYPE = new Type<>(AiPartnerMod.id("conversation_open"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MaidConversationOpenPayload> CODEC =
            CustomPacketPayload.codec(MaidConversationOpenPayload::write, MaidConversationOpenPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        // Intentionally empty: all authoritative data is sent back by the server.
    }

    private static MaidConversationOpenPayload read(RegistryFriendlyByteBuf buffer) {
        return new MaidConversationOpenPayload();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
