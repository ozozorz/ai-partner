package io.github.ozozorz.aipartner.client.conversation;

import io.github.ozozorz.aipartner.client.screen.MaidConversationScreen;
import io.github.ozozorz.aipartner.conversation.MaidConversationOpenPayload;
import io.github.ozozorz.aipartner.conversation.MaidConversationScreenPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** 注册默认 R 键并接收服务端确认的对话目标。 */
public final class MaidConversationClient {
    private static final KeyMapping OPEN_DIALOGUE = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.ai-partner.open_dialogue",
            GLFW.GLFW_KEY_R,
            KeyMapping.Category.GAMEPLAY
    ));

    private MaidConversationClient() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                MaidConversationScreenPayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    if (context.client().screen instanceof MaidConversationScreen screen
                            && screen.isFor(payload.maidId())) {
                        screen.refresh(payload);
                    } else if (payload.openScreen()) {
                        context.client().setScreen(new MaidConversationScreen(payload));
                    }
                })
        );
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_DIALOGUE.consumeClick()) {
                if (client.player != null
                        && client.screen == null
                        && ClientPlayNetworking.canSend(MaidConversationOpenPayload.TYPE)) {
                    ClientPlayNetworking.send(new MaidConversationOpenPayload());
                }
            }
        });
    }
}
