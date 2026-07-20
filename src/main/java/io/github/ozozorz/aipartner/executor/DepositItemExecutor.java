package io.github.ozozorz.aipartner.executor;

import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.job.AllowedTargets;
import io.github.ozozorz.aipartner.job.ContainerTargets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * `DEPOSIT_ITEM` 的显式状态机，只向主人可访问的普通单箱移动白名单物品。
 */
public final class DepositItemExecutor {
    private static final int SEARCH_BUDGET_PER_TICK = 1024;
    private static final int DEPOSIT_DELAY_TICKS = 10;
    private static final double INTERACTION_DISTANCE_SQUARED = 9.0;

    private final AiPartnerEntity partner;
    private final Set<Long> unavailableContainers = new HashSet<>();
    private State state = State.IDLE;
    private TaskContract contract;
    private Item targetItem;
    private BlockPos origin;
    private BlockPos containerPosition;
    private Iterator<BlockPos> searchIterator;
    private int stateTicks;
    private int pathFailures;
    private int movedCount;
    private long deadlineGameTime;
    private boolean sawFullContainer;

    public DepositItemExecutor(AiPartnerEntity partner) {
        this.partner = partner;
    }

    /**
     * 启动新的存放契约。
     */
    public void start(TaskContract taskContract) {
        start(taskContract, 0);
    }

    /**
     * 从实体存档恢复已经完成的部分存放数量。
     */
    public void restore(TaskContract taskContract, int savedMovedCount) {
        start(taskContract, savedMovedCount);
    }

    private void start(TaskContract taskContract, int initialMovedCount) {
        stop();
        contract = taskContract;
        targetItem = AllowedTargets.resolveDepositableItem(taskContract.job().target()).orElse(null);
        if (targetItem == null) {
            partner.failActiveContract(FailureCode.INTERNAL_ERROR);
            return;
        }
        origin = partner.blockPosition().immutable();
        movedCount = Math.max(0, initialMovedCount);
        deadlineGameTime = partner.level().getGameTime() + taskContract.failurePolicy().timeoutSeconds() * 20L;
        transitionTo(State.SEARCH_CONTAINER);
    }

    /**
     * 有界推进一次箱子搜索、寻路或存放动作。
     */
    public void tick(ServerLevel level) {
        if (!isRunning()) {
            return;
        }
        if (level.getGameTime() >= deadlineGameTime) {
            fail(FailureCode.TIMEOUT);
            return;
        }
        if (horizontalDistanceSquared(partner.blockPosition(), origin)
                > square(contract.job().radius() + 4.0)) {
            fail(FailureCode.PERMISSION_DENIED);
            return;
        }
        if (movedCount >= contract.job().quantity()) {
            complete();
            return;
        }

        stateTicks++;
        switch (state) {
            case SEARCH_CONTAINER -> searchForContainer(level);
            case NAVIGATE -> navigateToContainer(level);
            case CHECK_CONTAINER -> checkContainer(level);
            case DEPOSIT -> depositItems(level);
            case CHECK_GOAL -> checkGoal();
            case IDLE, COMPLETE, FAILED -> {
                // 终态不再修改实体背包或容器。
            }
        }
    }

    public boolean isRunning() {
        return state != State.IDLE && state != State.COMPLETE && state != State.FAILED && contract != null;
    }

    /**
     * 主人操作背包时暂停超时计时，使 UI 检查和物品调整不会消耗任务预算。
     */
    public void pauseForMenuTick() {
        if (isRunning()) {
            deadlineGameTime++;
        }
    }

    public int movedCount() {
        return movedCount;
    }

    /**
     * 取消或替换任务时清理临时状态。
     */
    public void stop() {
        state = State.IDLE;
        contract = null;
        targetItem = null;
        origin = null;
        containerPosition = null;
        searchIterator = null;
        stateTicks = 0;
        pathFailures = 0;
        movedCount = 0;
        deadlineGameTime = 0L;
        sawFullContainer = false;
        unavailableContainers.clear();
    }

    private void searchForContainer(ServerLevel level) {
        ServerPlayer owner = owner();
        if (owner == null) {
            fail(FailureCode.OWNER_OFFLINE);
            return;
        }
        if (searchIterator == null) {
            searchIterator = BlockPos.withinManhattan(
                    origin,
                    contract.job().radius(),
                    6,
                    contract.job().radius()
            ).iterator();
        }

        int checked = 0;
        while (searchIterator.hasNext() && checked++ < SEARCH_BUDGET_PER_TICK) {
            BlockPos candidate = searchIterator.next();
            if (unavailableContainers.contains(candidate.asLong())) {
                continue;
            }
            Container container = ContainerTargets.resolve(level, candidate, owner).orElse(null);
            if (container == null) {
                continue;
            }
            int remaining = contract.job().quantity() - movedCount;
            if (!ContainerTargets.hasCapacity(container, new ItemStack(targetItem), remaining)) {
                unavailableContainers.add(candidate.asLong());
                sawFullContainer = true;
                continue;
            }
            containerPosition = candidate.immutable();
            searchIterator = null;
            transitionTo(State.NAVIGATE);
            return;
        }

        if (!searchIterator.hasNext()) {
            fail(sawFullContainer ? FailureCode.CONTAINER_FULL : FailureCode.TARGET_NOT_FOUND);
        }
    }

    private void navigateToContainer(ServerLevel level) {
        if (!containerStillUsable(level)) {
            retryWithAnotherContainer();
            return;
        }
        if (distanceToContainerSquared() <= INTERACTION_DISTANCE_SQUARED) {
            partner.getNavigation().stop();
            transitionTo(State.CHECK_CONTAINER);
            return;
        }
        if (stateTicks % 10 != 1) {
            return;
        }
        boolean pathStarted = partner.getNavigation().moveTo(
                containerPosition.getX() + 0.5,
                containerPosition.getY(),
                containerPosition.getZ() + 0.5,
                1,
                1.0
        );
        pathFailures = pathStarted ? 0 : pathFailures + 1;
        if (pathFailures > contract.failurePolicy().maxLocalRetries()) {
            retryWithAnotherContainer();
        }
    }

    private void checkContainer(ServerLevel level) {
        Container container = resolveCurrentContainer(level);
        if (container == null) {
            retryWithAnotherContainer();
            return;
        }
        if (distanceToContainerSquared() > INTERACTION_DISTANCE_SQUARED) {
            transitionTo(State.NAVIGATE);
            return;
        }
        int remaining = contract.job().quantity() - movedCount;
        if (partner.countItem(targetItem) < remaining) {
            fail(FailureCode.MISSING_ITEM);
            return;
        }
        if (!ContainerTargets.hasCapacity(container, new ItemStack(targetItem), remaining)) {
            sawFullContainer = true;
            retryWithAnotherContainer();
            return;
        }
        transitionTo(State.DEPOSIT);
    }

    private void depositItems(ServerLevel level) {
        if (stateTicks < DEPOSIT_DELAY_TICKS) {
            return;
        }
        Container container = resolveCurrentContainer(level);
        if (container == null) {
            retryWithAnotherContainer();
            return;
        }

        int remainingNeeded = contract.job().quantity() - movedCount;
        for (int slot = 0; slot < partner.getInventory().getContainerSize() && remainingNeeded > 0; slot++) {
            ItemStack source = partner.getInventory().getItem(slot);
            if (source.isEmpty() || source.getItem() != targetItem) {
                continue;
            }
            ItemStack offered = source.copyWithCount(Math.min(source.getCount(), remainingNeeded));
            int inserted = ContainerTargets.insert(container, offered);
            if (inserted <= 0) {
                continue;
            }
            source.shrink(inserted);
            if (source.isEmpty()) {
                partner.getInventory().setItem(slot, ItemStack.EMPTY);
            } else {
                partner.getInventory().setChanged();
            }
            movedCount += inserted;
            remainingNeeded -= inserted;
            partner.updateDepositProgress(movedCount);
        }

        if (movedCount >= contract.job().quantity()) {
            transitionTo(State.CHECK_GOAL);
        } else if (partner.countItem(targetItem) <= 0) {
            fail(FailureCode.MISSING_ITEM);
        } else {
            sawFullContainer = true;
            retryWithAnotherContainer();
        }
    }

    private void checkGoal() {
        if (movedCount >= contract.job().quantity()) {
            complete();
        } else {
            transitionTo(State.SEARCH_CONTAINER);
        }
    }

    private void complete() {
        transitionTo(State.COMPLETE);
        partner.completeActiveContract();
    }

    private boolean containerStillUsable(ServerLevel level) {
        return resolveCurrentContainer(level) != null;
    }

    private Container resolveCurrentContainer(ServerLevel level) {
        ServerPlayer owner = owner();
        return containerPosition == null || owner == null
                ? null
                : ContainerTargets.resolve(level, containerPosition, owner).orElse(null);
    }

    private ServerPlayer owner() {
        return partner.getOwner() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
    }

    private double distanceToContainerSquared() {
        return partner.distanceToSqr(
                containerPosition.getX() + 0.5,
                containerPosition.getY() + 0.5,
                containerPosition.getZ() + 0.5
        );
    }

    private void retryWithAnotherContainer() {
        if (containerPosition != null) {
            unavailableContainers.add(containerPosition.asLong());
        }
        containerPosition = null;
        searchIterator = null;
        pathFailures = 0;
        transitionTo(State.SEARCH_CONTAINER);
    }

    private void fail(FailureCode failureCode) {
        transitionTo(State.FAILED);
        partner.failActiveContract(failureCode);
    }

    private void transitionTo(State nextState) {
        state = nextState;
        stateTicks = 0;
        partner.logRuntimeEvent("deposit_state_" + nextState.name().toLowerCase());
    }

    private static double horizontalDistanceSquared(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static double square(double value) {
        return value * value;
    }

    /**
     * 存放任务的有限执行状态集合。
     */
    private enum State {
        IDLE,
        SEARCH_CONTAINER,
        NAVIGATE,
        CHECK_CONTAINER,
        DEPOSIT,
        CHECK_GOAL,
        COMPLETE,
        FAILED
    }
}
