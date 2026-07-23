package io.github.ozozorz.aipartner.client.screen;

import io.github.ozozorz.aipartner.conversation.MaidConversationScreenPayload;
import io.github.ozozorz.aipartner.conversation.MaidDialogueSubmitPayload;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * 只使用本地规则解析的自然语言输入界面。
 */
public final class MaidConversationScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 132;
    private static final int PANEL_COLOR = 0xE6202630;
    private static final int PANEL_BORDER = 0xFF75839A;
    private static final int LABEL_COLOR = 0xFFF0F3F8;
    private static final int MUTED_COLOR = 0xFFB5BECC;
    private MaidConversationScreenPayload data;
    private EditBox messageBox;
    private Button sendButton;

    public MaidConversationScreen(MaidConversationScreenPayload data) {
        super(Component.translatable("gui.ai-partner.dialogue.title"));
        this.data = data;
    }

    public boolean isFor(UUID maidId) {
        return data.maidId().equals(maidId);
    }

    /**
     * 更新服务端确认的目标，同时保留用户尚未提交的文本。
     */
    public void refresh(MaidConversationScreenPayload refreshed) {
        if (isFor(refreshed.maidId())) {
            data = refreshed;
        }
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;

        messageBox = addRenderableWidget(new EditBox(
                font,
                left + 14,
                top + 60,
                PANEL_WIDTH - 28,
                20,
                Component.translatable("gui.ai-partner.dialogue.message")
        ));
        messageBox.setMaxLength(512);
        messageBox.setHint(Component.translatable("gui.ai-partner.dialogue.message_hint"));
        messageBox.setResponder(ignored -> updateControls());

        sendButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.ai-partner.dialogue.send"),
                        ignored -> submitMessage()
                )
                .bounds(left + 184, top + 94, 78, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.ai-partner.dialogue.close"),
                        ignored -> onClose()
                )
                .bounds(left + 268, top + 94, 78, 20)
                .build());
        setInitialFocus(messageBox);
        updateControls();
    }

    private void updateControls() {
        if (sendButton != null && messageBox != null) {
            sendButton.active = !messageBox.getValue().isBlank()
                    && ClientPlayNetworking.canSend(MaidDialogueSubmitPayload.TYPE);
        }
    }

    private void submitMessage() {
        String message = messageBox.getValue().strip();
        if (!message.isEmpty() && ClientPlayNetworking.canSend(MaidDialogueSubmitPayload.TYPE)) {
            ClientPlayNetworking.send(new MaidDialogueSubmitPayload(data.maidId(), message));
            onClose();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation() && messageBox != null && messageBox.isFocused()) {
            submitMessage();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        graphics.fill(0, 0, width, height, 0x88000000);
        graphics.fill(left - 1, top - 1, left + PANEL_WIDTH + 1, top + PANEL_HEIGHT + 1, PANEL_BORDER);
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_COLOR);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        int titleX = left + (PANEL_WIDTH - font.width(title)) / 2;
        graphics.text(font, title, titleX, top + 10, LABEL_COLOR, false);
        graphics.text(
                font,
                Component.translatable("gui.ai-partner.dialogue.target", data.maidName()),
                left + 14,
                top + 27,
                MUTED_COLOR,
                false
        );
        graphics.text(
                font,
                Component.translatable("gui.ai-partner.dialogue.local_ready"),
                left + 14,
                top + 39,
                MUTED_COLOR,
                false
        );
        graphics.text(
                font,
                Component.translatable("gui.ai-partner.dialogue.message"),
                left + 14,
                top + 50,
                LABEL_COLOR,
                false
        );
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
