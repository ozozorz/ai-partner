package io.github.ozozorz.aipartner.skin;

import io.github.ozozorz.aipartner.AiPartnerMod;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.service.PartnerService;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 注册皮肤上传、服务端验证、世界持久化以及追踪玩家同步。
 */
public final class MaidSkinNetworking {
    private MaidSkinNetworking() {
    }

    public static void registerServer() {
        PayloadTypeRegistry.serverboundPlay().registerLarge(
                MaidSkinUploadPayload.TYPE,
                MaidSkinUploadPayload.CODEC,
                SkinImageValidator.MAX_UPLOAD_BYTES + 1024
        );
        PayloadTypeRegistry.clientboundPlay().registerLarge(
                MaidSkinDataPayload.TYPE,
                MaidSkinDataPayload.CODEC,
                SkinImageValidator.MAX_UPLOAD_BYTES + 2048
        );
        ServerPlayNetworking.registerGlobalReceiver(
                MaidSkinUploadPayload.TYPE,
                (payload, context) -> context.server().execute(() -> handleUpload(context.player(), payload))
        );
        EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
            if (entity instanceof AiPartnerEntity partner) {
                syncTo(player, partner);
            }
        });
    }

    private static void handleUpload(ServerPlayer player, MaidSkinUploadPayload payload) {
        AiPartnerEntity partner = PartnerService.findOwnedPartner(player).orElse(null);
        if (partner == null) {
            player.sendSystemMessage(Component.translatable("message.ai-partner.not_found"));
            return;
        }
        if (payload.pngBytes().length == 0) {
            partner.setSkinHash("");
            broadcast(partner, new MaidSkinDataPayload(partner.getUUID(), "", new byte[0]), player);
            player.sendSystemMessage(Component.translatable("message.ai-partner.skin_cleared"));
            return;
        }
        try {
            SkinImageValidator.ValidatedSkin validated = SkinImageValidator.validate(payload.pngBytes());
            MaidSkinStore.save(player.level().getServer(), validated);
            partner.setSkinHash(validated.sha256());
            broadcast(
                    partner,
                    new MaidSkinDataPayload(partner.getUUID(), validated.sha256(), validated.pngBytes()),
                    player
            );
            player.sendSystemMessage(Component.translatable("message.ai-partner.skin_uploaded"));
        } catch (IllegalArgumentException exception) {
            player.sendSystemMessage(Component.translatable(
                    "message.ai-partner.skin_invalid",
                    exception.getMessage()
            ));
        } catch (IOException exception) {
            AiPartnerMod.LOGGER.error("Failed to persist uploaded maid skin", exception);
            player.sendSystemMessage(Component.translatable("message.ai-partner.skin_store_failed"));
        }
    }

    public static void syncTo(ServerPlayer player, AiPartnerEntity partner) {
        if (!ServerPlayNetworking.canSend(player, MaidSkinDataPayload.TYPE)) {
            return;
        }
        String hash = partner.getSkinHash();
        byte[] data = hash.isEmpty()
                ? new byte[0]
                : MaidSkinStore.load(player.level().getServer(), hash).orElse(new byte[0]);
        ServerPlayNetworking.send(player, new MaidSkinDataPayload(partner.getUUID(), hash, data));
    }

    private static void broadcast(
            AiPartnerEntity partner,
            MaidSkinDataPayload payload,
            ServerPlayer uploader
    ) {
        Set<ServerPlayer> recipients = new LinkedHashSet<>(PlayerLookup.tracking(partner));
        recipients.add(uploader);
        for (ServerPlayer recipient : recipients) {
            if (ServerPlayNetworking.canSend(recipient, MaidSkinDataPayload.TYPE)) {
                ServerPlayNetworking.send(recipient, payload);
            }
        }
    }
}
