package io.github.ozozorz.aipartner.client.screen;

import io.github.ozozorz.aipartner.control.MaidDriveMode;
import io.github.ozozorz.aipartner.control.MaidDriverSettings;
import io.github.ozozorz.aipartner.conversation.MaidConversationScreenPayload;
import io.github.ozozorz.aipartner.conversation.MaidDialogueSubmitPayload;
import io.github.ozozorz.aipartner.conversation.MaidDriverSettingsPayload;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Dedicated natural-language screen with per-maid local/LLM settings. */
public final class MaidConversationScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 196;
    private static final int PANEL_COLOR = 0xE6202630;
    private static final int PANEL_BORDER = 0xFF75839A;
    private static final int LABEL_COLOR = 0xFFF0F3F8;
    private static final int MUTED_COLOR = 0xFFB5BECC;
    private MaidConversationScreenPayload data;
    private MaidDriveMode selectedMode;
    private EditBox environmentVariableBox;
    private EditBox messageBox;
    private Button modeButton;
    private Button saveButton;
    private Button sendButton;

    public MaidConversationScreen(MaidConversationScreenPayload data) {
        super(Component.translatable("gui.ai-partner.dialogue.title"));
        this.data = data;
        this.selectedMode = MaidDriveMode.fromSavedName(data.driveMode());
    }

    public boolean isFor(UUID maidId) {
        return data.maidId().equals(maidId);
    }

    /** Applies a server acknowledgement without discarding an in-progress message. */
    public void refresh(MaidConversationScreenPayload refreshed) {
        if (!isFor(refreshed.maidId())) {
            return;
        }
        this.data = refreshed;
        this.selectedMode = MaidDriveMode.fromSavedName(refreshed.driveMode());
        if (environmentVariableBox != null) {
            environmentVariableBox.setValue(refreshed.apiKeyEnvironmentVariable());
        }
        updateControls();
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;

        environmentVariableBox = addRenderableWidget(new EditBox(
                font,
                left + 14,
                top + 70,
                PANEL_WIDTH - 28,
                20,
                Component.translatable("gui.ai-partner.dialogue.api_key_env")
        ));
        environmentVariableBox.setMaxLength(MaidDriverSettings.MAX_ENVIRONMENT_VARIABLE_LENGTH);
        environmentVariableBox.setValue(data.apiKeyEnvironmentVariable());
        environmentVariableBox.setHint(Component.translatable("gui.ai-partner.dialogue.api_key_env_hint"));
        environmentVariableBox.setResponder(ignored -> updateControls());

        messageBox = addRenderableWidget(new EditBox(
                font,
                left + 14,
                top + 116,
                PANEL_WIDTH - 28,
                20,
                Component.translatable("gui.ai-partner.dialogue.message")
        ));
        messageBox.setMaxLength(512);
        messageBox.setHint(Component.translatable("gui.ai-partner.dialogue.message_hint"));
        messageBox.setResponder(ignored -> updateControls());

        modeButton = addRenderableWidget(Button.builder(
                        Component.empty(),
                        ignored -> {
                            selectedMode = selectedMode.next();
                            updateControls();
                        }
                )
                .bounds(left + 14, top + 38, 160, 20)
                .build());
        saveButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.ai-partner.dialogue.save"),
                        ignored -> sendSettings()
                )
                .bounds(left + 184, top + 38, 162, 20)
                .build());
        sendButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.ai-partner.dialogue.send"),
                        ignored -> submitMessage()
                )
                .bounds(left + 184, top + 154, 78, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.ai-partner.dialogue.close"),
                        ignored -> onClose()
                )
                .bounds(left + 268, top + 154, 78, 20)
                .build());
        setInitialFocus(messageBox);
        updateControls();
    }

    private void updateControls() {
        if (modeButton == null || saveButton == null || sendButton == null
                || environmentVariableBox == null || messageBox == null) {
            return;
        }
        modeButton.setMessage(Component.translatable(
                "gui.ai-partner.dialogue.mode",
                Component.translatable("driver.ai-partner." + selectedMode.serializedName())
        ));
        boolean validEnvironmentVariable = MaidDriverSettings.isValidEnvironmentVariableName(
                environmentVariableBox.getValue()
        );
        saveButton.active = validEnvironmentVariable && ClientPlayNetworking.canSend(MaidDriverSettingsPayload.TYPE);
        sendButton.active = validEnvironmentVariable
                && !messageBox.getValue().isBlank()
                && ClientPlayNetworking.canSend(MaidDialogueSubmitPayload.TYPE);
    }

    private void sendSettings() {
        if (!MaidDriverSettings.isValidEnvironmentVariableName(environmentVariableBox.getValue())
                || !ClientPlayNetworking.canSend(MaidDriverSettingsPayload.TYPE)) {
            return;
        }
        ClientPlayNetworking.send(new MaidDriverSettingsPayload(
                data.maidId(),
                selectedMode.serializedName(),
                environmentVariableBox.getValue().strip()
        ));
    }

    private void submitMessage() {
        String message = messageBox.getValue().strip();
        if (message.isEmpty() || !MaidDriverSettings.isValidEnvironmentVariableName(environmentVariableBox.getValue())) {
            return;
        }
        boolean settingsChanged = !selectedMode.serializedName().equals(data.driveMode())
                || !environmentVariableBox.getValue().strip().equals(data.apiKeyEnvironmentVariable());
        if (settingsChanged) {
            sendSettings();
        }
        if (ClientPlayNetworking.canSend(MaidDialogueSubmitPayload.TYPE)) {
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
                top + 24,
                MUTED_COLOR,
                false
        );
        graphics.text(font, Component.translatable("gui.ai-partner.dialogue.api_key_env"), left + 14, top + 61, LABEL_COLOR, false);
        graphics.text(font, Component.translatable("gui.ai-partner.dialogue.message"), left + 14, top + 107, LABEL_COLOR, false);
        graphics.text(font, readinessLine(), left + 14, top + 94, MUTED_COLOR, false);
        graphics.text(
                font,
                Component.translatable(
                        "gui.ai-partner.dialogue.model",
                        font.plainSubstrByWidth(data.model(), PANEL_WIDTH - 125)
                ),
                left + 14,
                top + 178,
                MUTED_COLOR,
                false
        );
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private Component readinessLine() {
        if (selectedMode == MaidDriveMode.LOCAL) {
            return Component.translatable("gui.ai-partner.dialogue.local_ready");
        }
        if (data.requestPending()) {
            return Component.translatable("gui.ai-partner.dialogue.pending");
        }
        if (data.llmReady()) {
            return Component.translatable("gui.ai-partner.dialogue.llm_ready");
        }
        return Component.translatable(
                "gui.ai-partner.dialogue.readiness."
                        + data.readinessError().toLowerCase(Locale.ROOT)
        );
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
