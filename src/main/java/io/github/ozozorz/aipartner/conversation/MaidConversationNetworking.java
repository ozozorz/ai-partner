package io.github.ozozorz.aipartner.conversation;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.service.PartnerService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 注册本地对话数据包，并在服务端重新验证目标所有权。
 */
public final class MaidConversationNetworking {
    private MaidConversationNetworking() {
    }

    public static void registerServer() {
        PayloadTypeRegistry.serverboundPlay().register(MaidConversationOpenPayload.TYPE, MaidConversationOpenPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(MaidDialogueSubmitPayload.TYPE, MaidDialogueSubmitPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MaidConversationScreenPayload.TYPE, MaidConversationScreenPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(
                MaidConversationOpenPayload.TYPE,
                (payload, context) -> context.server().execute(() -> sendScreenData(context.player()))
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

    /**
     * 查找当前选中的女仆并发送最小化的对话界面数据。
     */
    public static void sendScreenData(ServerPlayer player) {
        AiPartnerEntity maid = PartnerService.findOwnedPartner(player).orElse(null);
        if (maid == null) {
            player.sendSystemMessage(Component.translatable("message.ai-partner.not_found"));
            return;
        }
        if (!ServerPlayNetworking.canSend(player, MaidConversationScreenPayload.TYPE)) {
            player.sendSystemMessage(Component.translatable("message.ai-partner.conversation.channel_unavailable"));
            return;
        }
        ServerPlayNetworking.send(player, new MaidConversationScreenPayload(
                maid.getUUID(),
                maid.getName().getString(),
                true
        ));
    }
}
