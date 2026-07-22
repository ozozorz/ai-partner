package io.github.ozozorz.aipartner.executor;

import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.core.action.MaidActions;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.job.AllowedTargets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gamerules.GameRules;
import org.jspecify.annotations.Nullable;

/**
 * `COLLECT_BLOCK` 的确定性显式状态机，负责有限搜索、寻路、破坏、拾取和目标判定。
 */
public final class CollectBlockExecutor {
    private static final int SEARCH_BUDGET_PER_TICK = 1024;
    private static final int BREAK_DURATION_TICKS = 16;
    private static final int PICKUP_TIMEOUT_TICKS = 100;
    private static final double PICKUP_SEARCH_RADIUS = 4.0;
    private static final double INTERACTION_DISTANCE_SQUARED = 9.0;

    private final AiPartnerEntity partner;
    private final MaidActions actions;
    private final Set<Long> unavailableTargets = new HashSet<>();
    private @Nullable TaskExecutionListener resultListener;
    private State state = State.IDLE;
    private TaskContract contract;
    private Block targetBlock;
    private Item targetItem;
    private BlockPos origin;
    private BlockPos targetPosition;
    private Iterator<BlockPos> searchIterator;
    private int initialTargetCount;
    private int countBeforeBreak;
    private int stateTicks;
    private int pathFailures;
    private final GameTimeDeadline timeout = new GameTimeDeadline();

    public CollectBlockExecutor(AiPartnerEntity partner) {
        this.partner = partner;
        this.actions = MaidActions.create(partner);
    }

    /**
     * 使用自定义阶段监听器启动，供固定组合任务复用采集状态机。
     */
    public void start(TaskContract taskContract, TaskExecutionListener listener) {
        start(taskContract, null, null, listener);
    }

    /**
     * 使用保存的背包基线和自定义监听器恢复组合任务采集阶段。
     */
    public void restore(
            TaskContract taskContract,
            int savedInitialTargetCount,
            long savedRemainingTimeoutTicks,
            TaskExecutionListener listener
    ) {
        start(taskContract, savedInitialTargetCount, savedRemainingTimeoutTicks, listener);
    }

    private void start(
            TaskContract taskContract,
            Integer initialCountOverride,
            Long remainingTimeoutOverride,
            TaskExecutionListener listener
    ) {
        stop();
        this.resultListener = Objects.requireNonNull(listener, "listener");
        this.contract = taskContract;
        this.targetBlock = AllowedTargets.resolveCollectibleBlock(taskContract.job().target()).orElse(null);
        this.targetItem = targetBlock == null ? null : AllowedTargets.asCollectibleItem(targetBlock).orElse(null);
        if (targetBlock == null || targetItem == null) {
            listener.onFailed(FailureCode.INTERNAL_ERROR);
            return;
        }
        this.origin = taskContract.executionAnchor().bound()
                ? BlockPos.of(taskContract.executionAnchor().originPosition())
                : partner.blockPosition().immutable();
        this.initialTargetCount = initialCountOverride == null
                ? partner.countItem(targetItem)
                : initialCountOverride;
        long fullTimeoutTicks = taskContract.failurePolicy().timeoutSeconds() * 20L;
        if (remainingTimeoutOverride == null) {
            timeout.start(partner.level().getGameTime(), fullTimeoutTicks);
        } else {
            timeout.restore(partner.level().getGameTime(), fullTimeoutTicks, remainingTimeoutOverride);
        }
        transitionTo(State.SEARCH_TARGET);
    }

    /**
     * 每个服务端 tick 推进一次状态机，不执行无界循环。
     */
    public void tick(ServerLevel level) {
        if (!isRunning()) {
            return;
        }
        if (timeout.isExpired(level.getGameTime())) {
            fail(FailureCode.TIMEOUT);
            return;
        }
        if (!level.getGameRules().get(GameRules.MOB_GRIEFING)) {
            fail(FailureCode.PERMISSION_DENIED);
            return;
        }
        if (horizontalDistanceSquared(partner.blockPosition(), origin)
                > square(contract.job().radius() + 4.0)) {
            fail(FailureCode.PERMISSION_DENIED);
            return;
        }
        if (goalSatisfied()) {
            transitionTo(State.COMPLETE);
            resultListener().onCompleted();
            return;
        }

        stateTicks++;
        switch (state) {
            case SEARCH_TARGET -> searchForTarget(level);
            case NAVIGATE -> navigateToTarget(level);
            case CHECK_TARGET -> checkTarget(level);
            case BREAK_BLOCK -> breakTarget(level);
            case PICK_UP -> waitForPickup(level);
            case CHECK_GOAL -> checkGoal();
            case IDLE, COMPLETE, FAILED -> {
                // 终态不再产生世界动作。
            }
        }
    }

    /**
     * 立即清理临时目标和导航状态，不改变契约本身。
     */
    public void stop() {
        state = State.IDLE;
        contract = null;
        targetBlock = null;
        targetItem = null;
        origin = null;
        targetPosition = null;
        searchIterator = null;
        initialTargetCount = 0;
        countBeforeBreak = 0;
        stateTicks = 0;
        pathFailures = 0;
        timeout.clear();
        unavailableTargets.clear();
        resultListener = null;
    }

    /**
     * 判断拾取物是否属于当前收集契约目标。
     */
    public boolean accepts(Item item) {
        return isRunning() && targetItem == item;
    }

    public boolean isRunning() {
        return state != State.IDLE && state != State.COMPLETE && state != State.FAILED && contract != null;
    }

    /**
     * 主人操作背包时暂停超时计时，使 UI 检查和物品调整不会消耗任务预算。
     */
    public void pauseForMenuTick() {
        if (isRunning()) {
            timeout.pauseOneTick();
        }
    }

    public String stateName() {
        return state.name();
    }

    public int initialTargetCount() {
        return initialTargetCount;
    }

    /** Returns the observed target-item delta used as composite-phase contract evidence. */
    public int collectedCount() {
        return targetItem == null ? 0 : Math.max(0, partner.countItem(targetItem) - initialTargetCount);
    }

    /** 返回当前任务可持久化的剩余超时 tick。 */
    public long remainingTimeoutTicks() {
        return timeout.remainingTicks(partner.level().getGameTime());
    }

    private void searchForTarget(ServerLevel level) {
        if (searchIterator == null) {
            searchIterator = BlockPos.withinManhattan(
                    origin,
                    contract.job().radius(),
                    8,
                    contract.job().radius()
            ).iterator();
        }

        int checked = 0;
        while (searchIterator.hasNext() && checked++ < SEARCH_BUDGET_PER_TICK) {
            BlockPos candidate = searchIterator.next();
            if (unavailableTargets.contains(candidate.asLong())
                    || level.getChunkSource().getChunkNow(
                            SectionPos.blockToSectionCoord(candidate.getX()),
                            SectionPos.blockToSectionCoord(candidate.getZ())
                    ) == null) {
                continue;
            }
            if (level.getBlockState(candidate).getBlock() == targetBlock) {
                targetPosition = candidate.immutable();
                searchIterator = null;
                transitionTo(State.NAVIGATE);
                return;
            }
        }

        if (!searchIterator.hasNext()) {
            fail(FailureCode.TARGET_NOT_FOUND);
        }
    }

    private void navigateToTarget(ServerLevel level) {
        if (!targetStillExists(level)) {
            handleUnavailableTarget(FailureCode.TARGET_DISAPPEARED);
            return;
        }
        if (distanceToTargetSquared() <= INTERACTION_DISTANCE_SQUARED) {
            actions.navigation().stop();
            transitionTo(State.CHECK_TARGET);
            return;
        }
        if (stateTicks % 10 != 1) {
            return;
        }

        boolean pathStarted = actions.navigation().moveTo(targetPosition, 1.0);
        pathFailures = pathStarted ? 0 : pathFailures + 1;
        if (pathFailures > partner.getMaximumLocalRetries()) {
            handleUnavailableTarget(FailureCode.PATH_UNREACHABLE);
        }
    }

    private void checkTarget(ServerLevel level) {
        if (!targetStillExists(level)) {
            handleUnavailableTarget(FailureCode.TARGET_DISAPPEARED);
            return;
        }
        if (distanceToTargetSquared() > INTERACTION_DISTANCE_SQUARED) {
            transitionTo(State.NAVIGATE);
            return;
        }
        if (!partner.hasAxe()) {
            fail(FailureCode.MISSING_TOOL);
            return;
        }
        if (!partner.canStore(targetItem, remainingQuantity())) {
            fail(FailureCode.INVENTORY_FULL);
            return;
        }
        countBeforeBreak = partner.countItem(targetItem);
        transitionTo(State.BREAK_BLOCK);
    }

    private void breakTarget(ServerLevel level) {
        if (!targetStillExists(level)) {
            handleUnavailableTarget(FailureCode.TARGET_DISAPPEARED);
            return;
        }
        if (distanceToTargetSquared() > INTERACTION_DISTANCE_SQUARED) {
            transitionTo(State.NAVIGATE);
            return;
        }
        if (stateTicks % 5 == 1) {
            actions.breakBlock().swingMainHand();
        }
        if (stateTicks < BREAK_DURATION_TICKS) {
            return;
        }

        boolean destroyed = actions.breakBlock().destroyWithDrops(level, targetPosition);
        if (!destroyed) {
            handleUnavailableTarget(FailureCode.PERMISSION_DENIED);
            return;
        }
        transitionTo(State.PICK_UP);
    }

    /**
     * 等待通用拾取层收取原版战利品表生成的掉落实体。
     */
    private void waitForPickup(ServerLevel level) {
        if (goalSatisfied() || partner.countItem(targetItem) > countBeforeBreak) {
            actions.navigation().stop();
            transitionTo(State.CHECK_GOAL);
            return;
        }
        if (!partner.canStore(targetItem, remainingQuantity())) {
            fail(FailureCode.INVENTORY_FULL);
            return;
        }
        ItemEntity drop = actions.pickupItem().findNearest(
                level,
                targetPosition,
                PICKUP_SEARCH_RADIUS,
                candidate -> candidate.getItem().is(targetItem)
        ).orElse(null);
        if (drop != null && stateTicks % 5 == 1) {
            actions.pickupItem().moveTo(drop, 1.1);
        } else if (drop == null && stateTicks % 10 == 1) {
            // 掉落实体可能仍处于生成后的短暂弹跳阶段，先回到破坏点等待下一次扫描。
            actions.navigation().moveTo(targetPosition, 1.0);
        }
        if (stateTicks >= PICKUP_TIMEOUT_TICKS) {
            actions.navigation().stop();
            handleUnavailableTarget(FailureCode.TIMEOUT);
        }
    }

    private void checkGoal() {
        if (goalSatisfied()) {
            transitionTo(State.COMPLETE);
            resultListener().onCompleted();
        } else {
            transitionTo(State.SEARCH_TARGET);
        }
    }

    private boolean goalSatisfied() {
        return targetItem != null
                && partner.countItem(targetItem) - initialTargetCount >= contract.job().quantity();
    }

    /**
     * 返回完成契约还需收集的数量，用于在每次世界修改前持续验证完整剩余容量。
     */
    private int remainingQuantity() {
        int collected = Math.max(0, partner.countItem(targetItem) - initialTargetCount);
        return Math.max(1, contract.job().quantity() - collected);
    }

    private boolean targetStillExists(ServerLevel level) {
        return targetPosition != null && level.getBlockState(targetPosition).getBlock() == targetBlock;
    }

    private double distanceToTargetSquared() {
        return partner.distanceToSqr(
                targetPosition.getX() + 0.5,
                targetPosition.getY() + 0.5,
                targetPosition.getZ() + 0.5
        );
    }

    private void retryWithAnotherTarget() {
        if (targetPosition != null) {
            unavailableTargets.add(targetPosition.asLong());
        }
        targetPosition = null;
        searchIterator = null;
        pathFailures = 0;
        transitionTo(State.SEARCH_TARGET);
    }

    /**
     * 目标失效时在统一恢复预算内重新搜索，预算耗尽后使用原失败码终止。
     */
    private void handleUnavailableTarget(FailureCode failureCode) {
        if (!partner.tryRecordRuntimeRecovery()) {
            fail(failureCode);
            return;
        }
        retryWithAnotherTarget();
    }

    private void fail(FailureCode failureCode) {
        transitionTo(State.FAILED);
        resultListener().onFailed(failureCode);
    }

    private void transitionTo(State nextState) {
        state = nextState;
        stateTicks = 0;
    }

    private static double horizontalDistanceSquared(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static double square(double value) {
        return value * value;
    }

    private TaskExecutionListener resultListener() {
        if (resultListener == null) {
            throw new IllegalStateException("Collect executor has no result listener");
        }
        return resultListener;
    }

    /**
     * 收集任务的有限执行状态集合。
     */
    private enum State {
        IDLE,
        SEARCH_TARGET,
        NAVIGATE,
        CHECK_TARGET,
        BREAK_BLOCK,
        PICK_UP,
        CHECK_GOAL,
        COMPLETE,
        FAILED
    }
}
