package io.github.ozozorz.aipartner.client.screen;

import io.github.ozozorz.aipartner.contract.ContractStatus;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMenuAction;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.inventory.AiPartnerMenu;
import io.github.ozozorz.aipartner.job.JobType;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 展示女仆储物、装备、状态和行为按钮的客户端容器界面。
 */
public final class AiPartnerScreen extends AbstractContainerScreen<AiPartnerMenu> {
    private static final int PANEL_COLOR = 0xFF20242A;
    private static final int SUB_PANEL_COLOR = 0xFF2B3139;
    private static final int SLOT_BORDER_COLOR = 0xFF111418;
    private static final int SLOT_COLOR = 0xFF4A515C;
    private static final int LABEL_COLOR = 0xFFE8EDF2;
    private static final int MUTED_LABEL_COLOR = 0xFFB6C0CA;

    private float mouseX;
    private float mouseY;
    private Button followButton;
    private Button stayButton;
    private Button cancelButton;

    public AiPartnerScreen(AiPartnerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, AiPartnerMenu.SCREEN_WIDTH, AiPartnerMenu.SCREEN_HEIGHT);
        titleLabelX = 8;
        titleLabelY = 7;
        inventoryLabelX = AiPartnerMenu.PLAYER_LEFT;
        inventoryLabelY = 128;
    }

    @Override
    protected void init() {
        super.init();
        followButton = addRenderableWidget(createActionButton(
                PartnerMenuAction.FOLLOW,
                "gui.ai-partner.action.follow",
                leftPos + 114
        ));
        stayButton = addRenderableWidget(createActionButton(
                PartnerMenuAction.STAY,
                "gui.ai-partner.action.stay",
                leftPos + 170
        ));
        cancelButton = addRenderableWidget(createActionButton(
                PartnerMenuAction.CANCEL,
                "gui.ai-partner.action.cancel",
                leftPos + 226
        ));
        updateButtonStates();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateButtonStates();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL_COLOR);
        graphics.fill(x + 4, y + 16, x + 106, y + imageHeight - 6, SUB_PANEL_COLOR);
        graphics.fill(x + 110, y + 16, x + imageWidth - 4, y + 94, SUB_PANEL_COLOR);
        graphics.fill(x + 110, y + 124, x + imageWidth - 4, y + imageHeight - 6, SUB_PANEL_COLOR);

        drawSlotGrid(graphics, x + AiPartnerMenu.STORAGE_LEFT, y + AiPartnerMenu.STORAGE_TOP, 9, 4);
        drawSlotGrid(graphics, x + AiPartnerMenu.EQUIPMENT_LEFT, y + AiPartnerMenu.EQUIPMENT_TOP, 5, 1);
        drawSlotGrid(graphics, x + AiPartnerMenu.PLAYER_LEFT, y + AiPartnerMenu.PLAYER_TOP, 9, 3);
        drawSlotGrid(graphics, x + AiPartnerMenu.PLAYER_LEFT, y + AiPartnerMenu.PLAYER_TOP + 58, 9, 1);

        AiPartnerEntity partner = menu.partner();
        if (partner != null) {
            InventoryScreen.extractEntityInInventoryFollowsMouse(
                    graphics,
                    x + 10,
                    y + 18,
                    x + 100,
                    y + 90,
                    30,
                    0.0625F,
                    this.mouseX,
                    this.mouseY,
                    partner
            );
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, title, titleLabelX, titleLabelY, LABEL_COLOR, false);
        graphics.text(
                font,
                Component.translatable("gui.ai-partner.storage"),
                AiPartnerMenu.STORAGE_LEFT,
                7,
                LABEL_COLOR,
                false
        );
        graphics.text(
                font,
                Component.translatable("gui.ai-partner.equipment"),
                AiPartnerMenu.EQUIPMENT_LEFT,
                136,
                LABEL_COLOR,
                false
        );
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, LABEL_COLOR, false);
        graphics.text(font, modeLine(), 8, 94, MUTED_LABEL_COLOR, false);
        graphics.text(font, jobLine(), 8, 106, MUTED_LABEL_COLOR, false);
        graphics.text(font, contractLine(), 8, 118, MUTED_LABEL_COLOR, false);
        graphics.text(font, healthLine(), 8, 130, MUTED_LABEL_COLOR, false);
    }

    private Button createActionButton(PartnerMenuAction action, String translationKey, int x) {
        return Button.builder(Component.translatable(translationKey), button -> sendAction(action))
                .bounds(x, topPos + 99, 50, 18)
                .build();
    }

    private void sendAction(PartnerMenuAction action) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, action.buttonId());
        }
    }

    private void updateButtonStates() {
        if (followButton == null || stayButton == null || cancelButton == null) {
            return;
        }
        PartnerMode mode = menu.displayedMode();
        followButton.active = mode != PartnerMode.FOLLOWING;
        stayButton.active = mode != PartnerMode.STAYING;
        ContractStatus status = menu.displayedContractStatus();
        cancelButton.active = status == ContractStatus.ACCEPTED || status == ContractStatus.RUNNING;
    }

    private Component modeLine() {
        PartnerMode mode = menu.displayedMode();
        return Component.translatable(
                "gui.ai-partner.mode",
                Component.translatable("mode.ai-partner." + mode.name().toLowerCase(Locale.ROOT))
        );
    }

    private Component jobLine() {
        JobType job = menu.displayedJob();
        Component value = job == null
                ? Component.translatable("gui.ai-partner.none")
                : Component.translatable("job.ai-partner." + job.name().toLowerCase(Locale.ROOT));
        return Component.translatable("gui.ai-partner.job", value);
    }

    private Component contractLine() {
        ContractStatus status = menu.displayedContractStatus();
        Component value = status == null
                ? Component.translatable("gui.ai-partner.none")
                : Component.translatable("contract.ai-partner." + status.name().toLowerCase(Locale.ROOT));
        return Component.translatable("gui.ai-partner.contract", value);
    }

    private Component healthLine() {
        String value = String.format(Locale.ROOT, "%.1f / %.1f", menu.displayedHealth(), menu.displayedMaxHealth());
        return Component.translatable("gui.ai-partner.health", value);
    }

    private static void drawSlotGrid(
            GuiGraphicsExtractor graphics,
            int left,
            int top,
            int columns,
            int rows
    ) {
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int x = left + column * 18;
                int y = top + row * 18;
                graphics.fill(x - 1, y - 1, x + 17, y + 17, SLOT_BORDER_COLOR);
                graphics.fill(x, y, x + 16, y + 16, SLOT_COLOR);
            }
        }
    }
}
