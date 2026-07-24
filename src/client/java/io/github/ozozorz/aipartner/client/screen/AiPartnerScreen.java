package io.github.ozozorz.aipartner.client.screen;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMenuAction;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.inventory.AiPartnerMenu;
import io.github.ozozorz.aipartner.life.ActivityLocationType;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 展示女仆实体、三种模式、技能化工作、装备和 35 格储物的菜单。
 */
public final class AiPartnerScreen extends AbstractContainerScreen<AiPartnerMenu> {
    private static final int WORK_MODES_PER_PAGE = 6;
    private static final int WORK_PAGE_COUNT = (MaidWorkMode.values().length + WORK_MODES_PER_PAGE - 1)
            / WORK_MODES_PER_PAGE;
    private static final int PANEL_COLOR = 0xFF20242A;
    private static final int SUB_PANEL_COLOR = 0xFF2B3139;
    private static final int SLOT_BORDER_COLOR = 0xFF111418;
    private static final int SLOT_COLOR = 0xFF4A515C;
    private static final int LABEL_COLOR = 0xFFE8EDF2;
    private static final int MUTED_LABEL_COLOR = 0xFFB6C0CA;

    private final Map<MaidWorkMode, Button> directWorkButtons = new EnumMap<>(MaidWorkMode.class);
    private final List<Button> schedulePanelButtons = new ArrayList<>();
    private float mouseX;
    private float mouseY;
    private Button followButton;
    private Button stayButton;
    private Button workButton;
    private Button scheduleButton;
    private Button homeBoundButton;
    private Button radiusDecreaseButton;
    private Button radiusIncreaseButton;
    private Button workLocationClearButton;
    private Button leisureLocationClearButton;
    private Button sleepLocationClearButton;
    private Button workModeButton;
    private Button panelToggleButton;
    private Button previousWorkPageButton;
    private Button nextWorkPageButton;
    private boolean workPanelOpen;
    private int workPage;

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
        directWorkButtons.clear();
        schedulePanelButtons.clear();
        followButton = addRenderableWidget(createModeButton(
                PartnerMenuAction.FOLLOW,
                "gui.ai-partner.action.follow",
                8
        ));
        stayButton = addRenderableWidget(createModeButton(
                PartnerMenuAction.STAY,
                "gui.ai-partner.action.stay",
                41
        ));
        workButton = addRenderableWidget(createModeButton(
                PartnerMenuAction.WORK,
                "gui.ai-partner.action.work",
                74
        ));
        workModeButton = addRenderableWidget(createSideButton(
                PartnerMenuAction.CYCLE_WORK_MODE,
                "gui.ai-partner.work_mode_button",
                8,
                132,
                96
        ));
        panelToggleButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.ai-partner.panel.show_work"),
                        ignored -> {
                            workPanelOpen = !workPanelOpen;
                            updatePanelVisibility();
                        }
                )
                .bounds(leftPos + 290, topPos + 20, 84, 18)
                .build());
        scheduleButton = addSchedulePanelButton(createSideButton(
                PartnerMenuAction.CYCLE_SCHEDULE,
                "gui.ai-partner.schedule.day_shift",
                290,
                42,
                84
        ));
        homeBoundButton = addSchedulePanelButton(createSideButton(
                PartnerMenuAction.TOGGLE_HOME_BOUND,
                "gui.ai-partner.home_bound.on",
                290,
                62,
                40
        ));
        addSchedulePanelButton(createSideButton(
                PartnerMenuAction.RETURN_HOME,
                "gui.ai-partner.action.home",
                334,
                62,
                40
        ));
        workLocationClearButton = addLocationButtons(
                PartnerMenuAction.SET_WORK_LOCATION,
                PartnerMenuAction.CLEAR_WORK_LOCATION,
                84
        );
        leisureLocationClearButton = addLocationButtons(
                PartnerMenuAction.SET_LEISURE_LOCATION,
                PartnerMenuAction.CLEAR_LEISURE_LOCATION,
                104
        );
        sleepLocationClearButton = addLocationButtons(
                PartnerMenuAction.SET_SLEEP_LOCATION,
                PartnerMenuAction.CLEAR_SLEEP_LOCATION,
                124
        );
        radiusDecreaseButton = addSchedulePanelButton(createSideButton(
                PartnerMenuAction.DECREASE_RADIUS,
                "gui.ai-partner.radius.decrease",
                290,
                146,
                40
        ));
        radiusIncreaseButton = addSchedulePanelButton(createSideButton(
                PartnerMenuAction.INCREASE_RADIUS,
                "gui.ai-partner.radius.increase",
                334,
                146,
                40
        ));

        for (MaidWorkMode mode : MaidWorkMode.values()) {
            int row = mode.ordinal() % WORK_MODES_PER_PAGE;
            Button button = Button.builder(
                            Component.translatable("work_mode.ai-partner." + mode.serializedName()),
                            ignored -> sendMenuButton(mode.menuButtonId())
                    )
                    .bounds(leftPos + 290, topPos + 42 + row * 20, 84, 18)
                    .build();
            directWorkButtons.put(mode, addRenderableWidget(button));
        }
        previousWorkPageButton = addRenderableWidget(Button.builder(
                        Component.literal("<"),
                        ignored -> {
                            workPage = Math.max(0, workPage - 1);
                            updatePanelVisibility();
                        }
                )
                .bounds(leftPos + 290, topPos + 166, 40, 18)
                .build());
        nextWorkPageButton = addRenderableWidget(Button.builder(
                        Component.literal(">"),
                        ignored -> {
                            workPage = Math.min(WORK_PAGE_COUNT - 1, workPage + 1);
                            updatePanelVisibility();
                        }
                )
                .bounds(leftPos + 334, topPos + 166, 40, 18)
                .build());
        updatePanelVisibility();
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
        graphics.fill(x + 110, y + 16, x + 280, y + 94, SUB_PANEL_COLOR);
        graphics.fill(x + 110, y + 124, x + 280, y + imageHeight - 6, SUB_PANEL_COLOR);
        graphics.fill(x + 286, y + 16, x + 378, y + imageHeight - 6, SUB_PANEL_COLOR);

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
                    y + 84,
                    27,
                    0.0625F,
                    mouseX,
                    mouseY,
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
                157,
                LABEL_COLOR,
                false
        );
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, LABEL_COLOR, false);
        graphics.text(font, modeLine(), 8, 87, MUTED_LABEL_COLOR, false);
        graphics.text(font, healthLine(), 8, 97, MUTED_LABEL_COLOR, false);
        graphics.text(font, growthLine(), 8, 190, MUTED_LABEL_COLOR, false);
        if (workPanelOpen) {
            graphics.text(
                    font,
                    Component.translatable("gui.ai-partner.work_page", workPage + 1, WORK_PAGE_COUNT),
                    290,
                    188,
                    MUTED_LABEL_COLOR,
                    false
            );
        } else {
            graphics.text(font, activityLine(), 290, 170, MUTED_LABEL_COLOR, false);
            graphics.text(font, radiusLine(), 290, 181, MUTED_LABEL_COLOR, false);
            graphics.text(font, skillsLine(), 290, 192, MUTED_LABEL_COLOR, false);
            graphics.text(font, memoryLine(), 290, 203, MUTED_LABEL_COLOR, false);
        }
    }

    private Button createModeButton(PartnerMenuAction action, String translationKey, int relativeX) {
        return Button.builder(Component.translatable(translationKey), button -> sendAction(action))
                .bounds(leftPos + relativeX, topPos + 110, 30, 18)
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
        addSchedulePanelButton(createSideButton(
                setAction,
                "gui.ai-partner.action." + setAction.name().toLowerCase(Locale.ROOT),
                290,
                relativeY,
                40
        ));
        return addSchedulePanelButton(createSideButton(
                clearAction,
                "gui.ai-partner.action." + clearAction.name().toLowerCase(Locale.ROOT),
                334,
                relativeY,
                40
        ));
    }

    private Button addSchedulePanelButton(Button button) {
        schedulePanelButtons.add(button);
        return addRenderableWidget(button);
    }

    /**
     * 在固定侧栏中切换生活日程与工作技能组合分页。
     */
    private void updatePanelVisibility() {
        workPage = Math.clamp(workPage, 0, WORK_PAGE_COUNT - 1);
        schedulePanelButtons.forEach(button -> button.visible = !workPanelOpen);
        directWorkButtons.forEach((mode, button) -> button.visible = workPanelOpen
                && mode.ordinal() / WORK_MODES_PER_PAGE == workPage);
        if (previousWorkPageButton != null) {
            previousWorkPageButton.visible = workPanelOpen;
            previousWorkPageButton.active = workPage > 0;
        }
        if (nextWorkPageButton != null) {
            nextWorkPageButton.visible = workPanelOpen;
            nextWorkPageButton.active = workPage < WORK_PAGE_COUNT - 1;
        }
        if (panelToggleButton != null) {
            panelToggleButton.setMessage(Component.translatable(
                    workPanelOpen
                            ? "gui.ai-partner.panel.show_schedule"
                            : "gui.ai-partner.panel.show_work"
            ));
        }
    }

    private void sendAction(PartnerMenuAction action) {
        sendMenuButton(action.buttonId());
    }

    private void sendMenuButton(int buttonId) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
        }
    }

    private void updateButtonStates() {
        if (followButton == null
                || stayButton == null
                || workButton == null
                || scheduleButton == null
                || homeBoundButton == null
                || radiusDecreaseButton == null
                || radiusIncreaseButton == null
                || workLocationClearButton == null
                || leisureLocationClearButton == null
                || sleepLocationClearButton == null
                || workModeButton == null
                || directWorkButtons.size() != MaidWorkMode.values().length) {
            return;
        }
        PartnerMode mode = menu.displayedMode();
        followButton.active = mode != PartnerMode.FOLLOW;
        stayButton.active = mode != PartnerMode.STAY;
        workButton.active = mode != PartnerMode.WORK;
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
        directWorkButtons.forEach((profile, button) -> button.active = profile != menu.displayedWorkMode());
        updatePanelVisibility();
    }

    private Component modeLine() {
        return Component.translatable(
                "gui.ai-partner.mode",
                Component.translatable(
                        "mode.ai-partner." + menu.displayedMode().name().toLowerCase(Locale.ROOT)
                )
        );
    }

    private Component healthLine() {
        String value = String.format(Locale.ROOT, "%.1f/%.1f", menu.displayedHealth(), menu.displayedMaxHealth());
        return Component.translatable("gui.ai-partner.health", value);
    }

    private Component activityLine() {
        Component activity = Component.translatable(
                "gui.ai-partner.activity."
                        + menu.displayedScheduleActivity().name().toLowerCase(Locale.ROOT)
        );
        int remainingTicks = menu.displayedTicksUntilScheduleChange();
        return remainingTicks < 0
                ? Component.translatable("gui.ai-partner.activity.continuous", activity)
                : Component.translatable("gui.ai-partner.activity", activity, remainingTicks / 20);
    }

    private Component radiusLine() {
        return Component.translatable("gui.ai-partner.radius", menu.displayedActivityRadius());
    }

    private Component skillsLine() {
        return Component.translatable("gui.ai-partner.skills", menu.displayedSkillCount());
    }

    private Component memoryLine() {
        return Component.translatable(
                "gui.ai-partner.container_memories",
                menu.displayedContainerMemoryCount()
        );
    }

    private Component growthLine() {
        return Component.translatable(
                "gui.ai-partner.growth_affection_compact",
                menu.displayedGrowthLevel(),
                menu.displayedGrowthExperience(),
                menu.displayedAffection()
        );
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
