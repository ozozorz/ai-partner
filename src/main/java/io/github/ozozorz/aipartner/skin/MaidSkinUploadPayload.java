package io.github.ozozorz.aipartner.skin;

import io.github.ozozorz.aipartner.AiPartnerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 客户端上传皮肤或以空字节请求清除皮肤的有界网络载荷。
 */
public record MaidSkinUploadPayload(byte[] pngBytes) implements CustomPacketPayload {
    public static final Type<MaidSkinUploadPayload> TYPE = new Type<>(AiPartnerMod.id("skin_upload"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MaidSkinUploadPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.byteArray(SkinImageValidator.MAX_UPLOAD_BYTES),
            MaidSkinUploadPayload::pngBytes,
            MaidSkinUploadPayload::new
    );

    public MaidSkinUploadPayload {
        pngBytes = pngBytes.clone();
    }

    @Override
    public byte[] pngBytes() {
        return pngBytes.clone();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
