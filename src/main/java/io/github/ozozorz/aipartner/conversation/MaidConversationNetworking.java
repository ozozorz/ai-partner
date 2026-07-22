package io.github.ozozorz.aipartner.conversation;

import io.github.ozozorz.aipartner.control.MaidDriveMode;
import io.github.ozozorz.aipartner.control.MaidDriverSettings;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.llm.MaidControlLlmGateway;
import io.github.ozozorz.aipartner.service.PartnerService;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Registers bounded dialogue/settings payloads and revalidates all mutations on the server. */
public final class MaidConversationNetworking {
    private MaidConversationNetworking() {
    }

    public static void registerServer() {
        PayloadTypeRegistry.serverboundPlay().register(MaidConversationOpenPayload.TYPE, MaidConversationOpenPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidDriverSettingsPayload.TYPE, MaidDriverSettingsPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidDialogueSubmitPayload.TYPE, MaidDialogueSubmitPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MaidConversationScreenPayload.TYPE, MaidConversationScreenPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(
                MaidConversationOpenPayload.TYPE,
                (payload, context) -> context.server().execute(() -> sendScreenData(context.player()))
        );
        ServerPlayNetworking.registerGlobalReceiver(
                MaidDriverSettingsPayload.TYPE,
                (payload, context) -> context.server().execute(() -> updateSettings(context.player(), payload))
        );
        ServerPlayNetworking.registerGlobalReceiver(
                MaidDialogueSubmitPayload.TYPE,
                (payload, context) -> context.server().execute(() -> MaidConversationService.submit(
                        context.player(),
                        payload.fallbackMaidId(),
                        payload.message()
                ))
        );
    }

    /** Sends settings for the currently selected maid after server-side ownership lookup. */
    public static void sendScreenData(ServerPlayer player) {
        AiPartnerEntity maid = PartnerService.findOwnedPartner(player).orElse(null);
        if (maid == null) {
            player.sendSystemMessage(Component.translatable("message.ai-partner.not_found"));
            return;
        }
        sendScreenData(player, maid, true);
    }

    private static void sendScreenData(ServerPlayer player, AiPartnerEntity maid, boolean openScreen) {
        if (!ServerPlayNetworking.canSend(player, MaidConversationScreenPayload.TYPE)) {
            player.sendSystemMessage(Component.translatable("message.ai-partner.conversation.channel_unavailable"));
            return;
        }
        MaidControlLlmGateway gateway = MaidControlLlmGateway.getInstance();
        String readinessError = gateway.readinessError(maid.getLlmApiKeyEnvironmentVariable());
        ServerPlayNetworking.send(player, new MaidConversationScreenPayload(
                maid.getUUID(),
                maid.getName().getString(),
                maid.getDriveMode().serializedName(),
                maid.getLlmApiKeyEnvironmentVariable(),
                readinessError == null,
                readinessError == null ? "" : readinessError,
                gateway.model(),
                MaidConversationService.hasPending(player.getUUID(), maid.getUUID()),
                openScreen
        ));
    }

    private static void updateSettings(ServerPlayer player, MaidDriverSettingsPayload payload) {
        AiPartnerEntity maid = findOwnedById(player, payload.maidId());
        if (maid == null) {
            player.sendSystemMessage(Component.translatable("message.ai-partner.conversation.target_unavailable"));
            return;
        }
        MaidDriveMode mode = MaidDriveMode.parse(payload.driveMode()).orElse(null);
        if (mode == null || !MaidDriverSettings.isValidEnvironmentVariableName(payload.apiKeyEnvironmentVariable())) {
            player.sendSystemMessage(Component.translatable("message.ai-partner.driver.invalid_settings"));
            sendScreenData(player, maid, false);
            return;
        }
        maid.setDriveMode(mode);
        maid.setLlmApiKeyEnvironmentVariable(payload.apiKeyEnvironmentVariable());
        MaidConversationService.cancelPending(player.getUUID());
        player.sendSystemMessage(Component.translatable(
                "message.ai-partner.driver.updated",
                maid.getName(),
                mode.serializedName(),
                maid.getLlmApiKeyEnvironmentVariable()
        ));
        sendScreenData(player, maid, false);
    }

    private static AiPartnerEntity findOwnedById(ServerPlayer player, UUID maidId) {
        return PartnerService.findOwnedPartners(player).stream()
                .filter(maid -> maid.getUUID().equals(maidId))
                .findFirst()
                .orElse(null);
    }
}
