package io.github.ozozorz.aipartner.work.supply;

import io.github.ozozorz.aipartner.core.action.CraftItemAction;
import io.github.ozozorz.aipartner.core.action.MaidActions;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.work.MaidWorkContext;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * 在具体工作前执行“个人制作—寻找工作台—制作并放置工作台—3×3 制作”的有界准备状态机。
 */
public final class MaidWorkSupplyController {
    private static final int SEARCH_BUDGET_PER_TICK = 384;
    private static final int MAX_VERTICAL_SEARCH_RADIUS = 6;
    private static final int RETRY_COOLDOWN_TICKS = 80;
    private static final int MAX_PATH_FAILURES = 3;
    private static final double TABLE_INTERACTION_DISTANCE_SQUARED = 9.0;

    private final AiPartnerEntity partner;
    private final MaidActions actions;
    private final Set<BlockPos> ignoredTables = new HashSet<>();

    private State state = State.IDLE;
    private String activeRequirementKey = "";
    private @Nullable Iterator<BlockPos> tableScan;
    private @Nullable BlockPos tablePosition;
    private int stateTicks;
    private int pathFailures;
    private int cooldownTicks;

    public MaidWorkSupplyController(AiPartnerEntity partner, MaidActions actions) {
        this.partner = Objects.requireNonNull(partner, "partner");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    /** 推进一次准备流程；READY 才允许工作规则继续使用目标。 */
    public PreparationStatus tick(MaidWorkContext context, WorkSupplyRequirement requirement) {
        if (!activeRequirementKey.equals(requirement.key())) {
            reset(false);
            activeRequirementKey = requirement.key();
        }
        if (requirement.isSatisfied(partner)) {
            reset(true);
            return PreparationStatus.READY;
        }

        stateTicks++;
        return switch (state) {
            case IDLE -> planPersonalCraft(context, requirement);
            case SEARCH_TABLE -> searchTable(context, requirement);
            case CRAFT_TABLE -> craftTable(context);
            case PLACE_TABLE -> placeTable(context);
            case NAVIGATE_TABLE -> navigateTable(context);
            case CRAFT_AT_TABLE -> craftAtTable(context, requirement);
            case COOLDOWN -> tickCooldown();
        };
    }

    public boolean isPreparing() {
        return state != State.IDLE;
    }

    public String executionState() {
        return state.name();
    }

    /** 工作刚产生新材料后立刻重新规划，避免徒手伐木仍等待完整失败冷却。 */
    public void retryWithNewMaterials() {
        if (state == State.COOLDOWN) {
            state = State.IDLE;
            cooldownTicks = 0;
            stateTicks = 0;
        }
    }

    /** 切换工作、任务中断或实体销毁时丢弃所有可重算准备状态。 */
    public void reset() {
        reset(true);
        activeRequirementKey = "";
    }

    private PreparationStatus planPersonalCraft(
            MaidWorkContext context,
            WorkSupplyRequirement requirement
    ) {
        CraftItemAction.CraftResult personal = actions.craftItem().craftNext(
                context.level(),
                requirement.craftTargets(),
                CraftItemAction.CraftingGrid.PERSONAL_2X2
        );
        if (personal.status() == CraftItemAction.CraftStatus.CRAFTED) {
            stateTicks = 0;
            return PreparationStatus.PREPARING;
        }
        if (!actions.craftItem().canPlanAny(
                context.level(),
                requirement.craftTargets(),
                CraftItemAction.CraftingGrid.WORKBENCH_3X3
        )) {
            return enterCooldown();
        }
        beginTableSearch(context);
        return PreparationStatus.PREPARING;
    }

    private PreparationStatus searchTable(
            MaidWorkContext context,
            WorkSupplyRequirement requirement
    ) {
        if (requirement.isSatisfied(partner)) {
            reset(true);
            return PreparationStatus.READY;
        }
        if (tableScan == null) {
            beginTableSearch(context);
        }
        int checked = 0;
        while (tableScan != null && tableScan.hasNext() && checked++ < SEARCH_BUDGET_PER_TICK) {
            BlockPos candidate = tableScan.next();
            if (!context.isLegal(candidate)
                    || ignoredTables.contains(candidate)
                    || context.level().getChunkSource().getChunkNow(
                            SectionPos.blockToSectionCoord(candidate.getX()),
                            SectionPos.blockToSectionCoord(candidate.getZ())
                    ) == null) {
                continue;
            }
            if (context.level().getBlockState(candidate).is(Blocks.CRAFTING_TABLE)) {
                tablePosition = candidate.immutable();
                tableScan = null;
                transition(State.NAVIGATE_TABLE);
                return PreparationStatus.PREPARING;
            }
        }
        if (tableScan != null && tableScan.hasNext()) {
            return PreparationStatus.PREPARING;
        }
        tableScan = null;
        transition(containsStorageItem(Items.CRAFTING_TABLE) ? State.PLACE_TABLE : State.CRAFT_TABLE);
        return PreparationStatus.PREPARING;
    }

    private PreparationStatus craftTable(MaidWorkContext context) {
        if (containsStorageItem(Items.CRAFTING_TABLE)) {
            transition(State.PLACE_TABLE);
            return PreparationStatus.PREPARING;
        }
        CraftItemAction.CraftResult result = actions.craftItem().craftNext(
                context.level(),
                java.util.List.of(Items.CRAFTING_TABLE),
                CraftItemAction.CraftingGrid.PERSONAL_2X2
        );
        if (result.status() == CraftItemAction.CraftStatus.CRAFTED) {
            return PreparationStatus.PREPARING;
        }
        return enterCooldown();
    }

    private PreparationStatus placeTable(MaidWorkContext context) {
        if (!context.level().getGameRules().get(GameRules.MOB_GRIEFING)) {
            return enterCooldown();
        }
        BlockPos placement = findTablePlacement(context);
        if (placement == null) {
            return enterCooldown();
        }
        ItemStack table = actions.inventory().takeOne(Items.CRAFTING_TABLE);
        if (table.isEmpty()) {
            transition(State.CRAFT_TABLE);
            return PreparationStatus.PREPARING;
        }
        if (!actions.placeBlock().place(
                context.level(),
                placement,
                Blocks.CRAFTING_TABLE.defaultBlockState()
        )) {
            actions.inventory().add(table);
            return enterCooldown();
        }
        tablePosition = placement.immutable();
        transition(State.NAVIGATE_TABLE);
        return PreparationStatus.PREPARING;
    }

    private PreparationStatus navigateTable(MaidWorkContext context) {
        if (!isTableValid(context)) {
            ignoreCurrentTable();
            beginTableSearch(context);
            return PreparationStatus.PREPARING;
        }
        if (partner.distanceToSqr(tablePosition.getCenter()) <= TABLE_INTERACTION_DISTANCE_SQUARED) {
            actions.navigation().stop();
            transition(State.CRAFT_AT_TABLE);
            return PreparationStatus.PREPARING;
        }
        if (stateTicks % 10 != 1) {
            return PreparationStatus.PREPARING;
        }
        boolean started = actions.navigation().moveTo(tablePosition, 0.9);
        pathFailures = started ? 0 : pathFailures + 1;
        if (pathFailures > partner.getWorkPathRetryLimit(MAX_PATH_FAILURES)) {
            ignoreCurrentTable();
            beginTableSearch(context);
        }
        return PreparationStatus.PREPARING;
    }

    private PreparationStatus craftAtTable(
            MaidWorkContext context,
            WorkSupplyRequirement requirement
    ) {
        if (!isTableValid(context)
                || partner.distanceToSqr(tablePosition.getCenter()) > TABLE_INTERACTION_DISTANCE_SQUARED) {
            transition(State.NAVIGATE_TABLE);
            return PreparationStatus.PREPARING;
        }
        CraftItemAction.CraftResult result = actions.craftItem().craftNext(
                context.level(),
                requirement.craftTargets(),
                CraftItemAction.CraftingGrid.WORKBENCH_3X3
        );
        if (result.status() == CraftItemAction.CraftStatus.CRAFTED) {
            stateTicks = 0;
            return PreparationStatus.PREPARING;
        }
        if (requirement.isSatisfied(partner)) {
            reset(true);
            return PreparationStatus.READY;
        }
        return enterCooldown();
    }

    private void beginTableSearch(MaidWorkContext context) {
        int radius = context.boundary().radius();
        tableScan = BlockPos.withinManhattan(
                context.boundary().position(),
                radius,
                Math.min(MAX_VERTICAL_SEARCH_RADIUS, radius),
                radius
        ).iterator();
        tablePosition = null;
        pathFailures = 0;
        transition(State.SEARCH_TABLE);
    }

    private @Nullable BlockPos findTablePlacement(MaidWorkContext context) {
        BlockPos origin = partner.blockPosition();
        return BlockPos.withinManhattanStream(origin, 2, 1, 2)
                .filter(position -> position.getY() == origin.getY())
                .filter(context::isLegal)
                .filter(position -> context.level().getChunkSource().getChunkNow(
                        SectionPos.blockToSectionCoord(position.getX()),
                        SectionPos.blockToSectionCoord(position.getZ())
                ) != null)
                .filter(position -> context.level().getBlockState(position).canBeReplaced())
                .filter(position -> Blocks.CRAFTING_TABLE.defaultBlockState().canSurvive(
                        context.level(),
                        position
                ))
                .filter(position -> context.level().isUnobstructed(
                        Blocks.CRAFTING_TABLE.defaultBlockState(),
                        position,
                        CollisionContext.of(partner)
                ))
                .filter(position -> context.level().getBlockState(position.below()).isFaceSturdy(
                        context.level(),
                        position.below(),
                        Direction.UP
                ))
                .map(BlockPos::immutable)
                .findFirst()
                .orElse(null);
    }

    private boolean isTableValid(MaidWorkContext context) {
        return tablePosition != null
                && context.isLegal(tablePosition)
                && context.level().getBlockState(tablePosition).is(Blocks.CRAFTING_TABLE);
    }

    private void ignoreCurrentTable() {
        if (tablePosition != null) {
            ignoredTables.add(tablePosition);
        }
        tablePosition = null;
    }

    private boolean containsStorageItem(net.minecraft.world.item.Item item) {
        return partner.getInventory().getItems().stream().anyMatch(stack -> stack.is(item));
    }

    private PreparationStatus tickCooldown() {
        if (--cooldownTicks <= 0) {
            transition(State.IDLE);
        }
        return PreparationStatus.UNAVAILABLE;
    }

    private PreparationStatus enterCooldown() {
        actions.navigation().stop();
        cooldownTicks = RETRY_COOLDOWN_TICKS;
        transition(State.COOLDOWN);
        return PreparationStatus.UNAVAILABLE;
    }

    private void transition(State next) {
        state = next;
        stateTicks = 0;
    }

    private void reset(boolean stopSupplyNavigation) {
        if (stopSupplyNavigation && state == State.NAVIGATE_TABLE) {
            actions.navigation().stop();
        }
        state = State.IDLE;
        tableScan = null;
        tablePosition = null;
        ignoredTables.clear();
        stateTicks = 0;
        pathFailures = 0;
        cooldownTicks = 0;
    }

    public enum PreparationStatus {
        READY,
        PREPARING,
        UNAVAILABLE
    }

    private enum State {
        IDLE,
        SEARCH_TABLE,
        CRAFT_TABLE,
        PLACE_TABLE,
        NAVIGATE_TABLE,
        CRAFT_AT_TABLE,
        COOLDOWN
    }
}
