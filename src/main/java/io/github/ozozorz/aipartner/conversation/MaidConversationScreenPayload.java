package io.github.ozozorz.aipartner.conversation;

import io.github.ozozorz.aipartner.AiPartnerMod;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端发送给 R 键对话界面的最小目标信息。
 */
public record MaidConversationScreenPayload(
        UUID maidId,
        String maidName,
        boolean openScreen
) implements CustomPacketPayload {
    public static final Type<MaidConversationScreenPayload> TYPE = new Type<>(AiPartnerMod.id("conversation_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MaidConversationScreenPayload> CODEC =
            CustomPacketPayload.codec(MaidConversationScreenPayload::write, MaidConversationScreenPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(maidId);
        buffer.writeUtf(maidName, 64);
        buffer.writeBoolean(openScreen);
    }

    private static MaidConversationScreenPayload read(RegistryFriendlyByteBuf buffer) {
        return new MaidConversationScreenPayload(
                buffer.readUUID(),
                buffer.readUtf(64),
                buffer.readBoolean()
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
