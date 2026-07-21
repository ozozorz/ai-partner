package io.github.ozozorz.aipartner.skin;

import io.github.ozozorz.aipartner.AiPartnerMod;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端向追踪客户端分发已经校验和重新编码的皮肤数据。
 */
public record MaidSkinDataPayload(UUID maidId, String hash, byte[] pngBytes) implements CustomPacketPayload {
    public static final Type<MaidSkinDataPayload> TYPE = new Type<>(AiPartnerMod.id("skin_data"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MaidSkinDataPayload> CODEC = CustomPacketPayload.codec(
            MaidSkinDataPayload::write,
            MaidSkinDataPayload::read
    );

    public MaidSkinDataPayload {
        pngBytes = pngBytes.clone();
    }

    @Override
    public byte[] pngBytes() {
        return pngBytes.clone();
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(maidId);
        buffer.writeUtf(hash, 64);
        buffer.writeByteArray(pngBytes);
    }

    private static MaidSkinDataPayload read(RegistryFriendlyByteBuf buffer) {
        return new MaidSkinDataPayload(
                buffer.readUUID(),
                buffer.readUtf(64),
                buffer.readByteArray(SkinImageValidator.MAX_UPLOAD_BYTES)
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
