package io.github.ozozorz.aipartner.conversation;

import io.github.ozozorz.aipartner.AiPartnerMod;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Bounded R-screen message bound to a fallback maid; an @ prefix may override it. */
public record MaidDialogueSubmitPayload(UUID fallbackMaidId, String message) implements CustomPacketPayload {
    public static final Type<MaidDialogueSubmitPayload> TYPE = new Type<>(AiPartnerMod.id("dialogue_submit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MaidDialogueSubmitPayload> CODEC =
            CustomPacketPayload.codec(MaidDialogueSubmitPayload::write, MaidDialogueSubmitPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(fallbackMaidId);
        buffer.writeUtf(message, MaidConversationService.MAX_MESSAGE_LENGTH);
    }

    private static MaidDialogueSubmitPayload read(RegistryFriendlyByteBuf buffer) {
        return new MaidDialogueSubmitPayload(
                buffer.readUUID(),
                buffer.readUtf(MaidConversationService.MAX_MESSAGE_LENGTH)
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
