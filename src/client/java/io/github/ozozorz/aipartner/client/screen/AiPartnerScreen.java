package io.github.ozozorz.aipartner.client.screen;

import io.github.ozozorz.aipartner.contract.ContractStatus;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMenuAction;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.inventory.AiPartnerMenu;
import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.life.ActivityLocationType;
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
    private Button scheduleButton;
    private Button homeBoundButton;
    private Button radiusDecreaseButton;
    private Button radiusIncreaseButton;
    private Button workLocationClearButton;
    private Button leisureLocationClearButton;
    private Button sleepLocationClearButton;
    private Button workModeButton;
    private Button combatPolicyButton;

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
        scheduleButton = addRenderableWidget(createSideButton(
                PartnerMenuAction.CYCLE_SCHEDULE,
                "gui.ai-partner.schedule.day_shift",
                290,
                20,
                84
        ));
        homeBoundButton = addRenderableWidget(createSideButton(
                PartnerMenuAction.TOGGLE_HOME_BOUND,
                "gui.ai-partner.home_bound.on",
                290,
                42,
                84
        ));
        addRenderableWidget(createSideButton(
                PartnerMenuAction.RETURN_HOME,
                "gui.ai-partner.action.home",
                290,
                64,
                84
        ));
        workLocationClearButton = addLocationButtons(
                PartnerMenuAction.SET_WORK_LOCATION,
                PartnerMenuAction.CLEAR_WORK_LOCATION,
                100
        );
        leisureLocationClearButton = addLocationButtons(
                PartnerMenuAction.SET_LEISURE_LOCATION,
                PartnerMenuAction.CLEAR_LEISURE_LOCATION,
                122
        );
        sleepLocationClearButton = addLocationButtons(
                PartnerMenuAction.SET_SLEEP_LOCATION,
                PartnerMenuAction.CLEAR_SLEEP_LOCATION,
                144
        );
        radiusDecreaseButton = addRenderableWidget(createSideButton(
                PartnerMenuAction.DECREASE_RADIUS,
                "gui.ai-partner.radius.decrease",
                290,
                174,
                40
        ));
        radiusIncreaseButton = addRenderableWidget(createSideButton(
                PartnerMenuAction.INCREASE_RADIUS,
                "gui.ai-partner.radius.increase",
                334,
                174,
                40
        ));
        workModeButton = addRenderableWidget(createSideButton(
                PartnerMenuAction.CYCLE_WORK_MODE,
                "gui.ai-partner.work_mode_button",
                8,
                174,
                96
        ));
        combatPolicyButton = addRenderableWidget(createSideButton(
                PartnerMenuAction.CYCLE_COMBAT_POLICY,
                "gui.ai-partner.combat_policy_button",
                8,
                196,
                96
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
        graphics.fill(x + 286, y + 16, x + imageWidth - 4, y + imageHeight - 6, SUB_PANEL_COLOR);

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
        graphics.text(
                font,
                Component.translatable("gui.ai-partner.schedule_panel"),
                290,
                7,
                LABEL_COLOR,
                false
        );
        graphics.text(font, activityLine(), 290, 88, MUTED_LABEL_COLOR, false);
        graphics.text(font, radiusLine(), 290, 164, MUTED_LABEL_COLOR, false);
        graphics.text(font, growthLine(), 290, 199, MUTED_LABEL_COLOR, false);
        graphics.text(font, affectionLine(), 290, 211, MUTED_LABEL_COLOR, false);
    }

    private Button createActionButton(PartnerMenuAction action, String translationKey, int x) {
        return Button.builder(Component.translatable(translationKey), button -> sendAction(action))
                .bounds(x, topPos + 99, 50, 18)
                .build();
    }

    private Button createSideButton(
            PartnerMenuAction action,
            String translationKey,
            int relativeX,
            int relativeY,
            int width
    ) {
        return Button.builder(Component.translatable(translationKey), button -> sendAction(action))
                .bounds(leftPos + relativeX, topPos + relativeY, width, 18)
                .build();
    }

    private Button addLocationButtons(PartnerMenuAction setAction, PartnerMenuAction clearAction, int relativeY) {
        addRenderableWidget(createSideButton(
                setAction,
                "gui.ai-partner.action." + setAction.name().toLowerCase(Locale.ROOT),
                290,
                relativeY,
                40
        ));
        return addRenderableWidget(createSideButton(
                clearAction,
                "gui.ai-partner.action." + clearAction.name().toLowerCase(Locale.ROOT),
                334,
                relativeY,
                40
        ));
    }

    private void sendAction(PartnerMenuAction action) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, action.buttonId());
        }
    }

    private void updateButtonStates() {
        if (followButton == null
                || stayButton == null
                || cancelButton == null
                || scheduleButton == null
                || homeBoundButton == null
                || radiusDecreaseButton == null
                || radiusIncreaseButton == null
                || workLocationClearButton == null
                || leisureLocationClearButton == null
                || sleepLocationClearButton == null
                || workModeButton == null
                || combatPolicyButton == null) {
            return;
        }
        PartnerMode mode = menu.displayedMode();
        followButton.active = mode != PartnerMode.FOLLOWING;
        stayButton.active = mode != PartnerMode.STAYING;
        ContractStatus status = menu.displayedContractStatus();
        cancelButton.active = status == ContractStatus.ACCEPTED
                || status == ContractStatus.RUNNING
                || mode == PartnerMode.RETURNING_HOME;
        scheduleButton.setMessage(Component.translatable(
                "gui.ai-partner.schedule." + menu.displayedScheduleType().name().toLowerCase(Locale.ROOT)
        ));
        homeBoundButton.setMessage(Component.translatable(
                menu.displayedHomeBound()
                        ? "gui.ai-partner.home_bound.on"
                        : "gui.ai-partner.home_bound.off"
        ));
        radiusDecreaseButton.active = menu.displayedActivityRadius() > 1;
        radiusIncreaseButton.active = menu.displayedActivityRadius() < menu.displayedMaximumActivityRadius();
        int locationMask = menu.displayedLocationMask();
        workLocationClearButton.active = hasConfiguredLocation(locationMask, ActivityLocationType.WORK);
        leisureLocationClearButton.active = hasConfiguredLocation(locationMask, ActivityLocationType.LEISURE);
        sleepLocationClearButton.active = hasConfiguredLocation(locationMask, ActivityLocationType.SLEEP);
        workModeButton.setMessage(Component.translatable(
                "gui.ai-partner.work_mode_button",
                Component.translatable("work_mode.ai-partner." + menu.displayedWorkMode().serializedName())
        ));
        combatPolicyButton.setMessage(Component.translatable(
                "gui.ai-partner.combat_policy_button",
                Component.translatable(
                        "combat_policy.ai-partner." + menu.displayedCombatPolicy().serializedName()
                )
        ));
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

    private Component activityLine() {
        int remainingTicks = menu.displayedTicksUntilScheduleChange();
        Component activity = Component.translatable(
                "gui.ai-partner.activity."
                        + menu.displayedScheduleActivity().name().toLowerCase(Locale.ROOT)
        );
        if (remainingTicks < 0) {
            return Component.translatable("gui.ai-partner.activity.continuous", activity);
        }
        int seconds = remainingTicks / 20;
        return Component.translatable(
                "gui.ai-partner.activity",
                activity,
                seconds
        );
    }

    private Component radiusLine() {
        return Component.translatable("gui.ai-partner.radius", menu.displayedActivityRadius());
    }

    private Component growthLine() {
        return Component.translatable(
                "gui.ai-partner.growth",
                menu.displayedGrowthLevel(),
                menu.displayedGrowthExperience()
        );
    }

    private Component affectionLine() {
        return Component.translatable("gui.ai-partner.affection", menu.displayedAffection());
    }

    private static boolean hasConfiguredLocation(int mask, ActivityLocationType type) {
        return (mask & (1 << type.ordinal())) != 0;
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
