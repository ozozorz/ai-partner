package io.github.ozozorz.aipartner.gameplay.task;

import io.github.ozozorz.aipartner.core.task.MaidTask;
import io.github.ozozorz.aipartner.core.task.MaidTaskContext;
import io.github.ozozorz.aipartner.core.task.MaidTaskSnapshot;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.executor.DepositItemExecutor;
import net.minecraft.world.level.storage.ValueInput;

/**
 * 把现有单箱存放状态机接入统一 MaidTask 生命周期。
 */
public class DepositItemMaidTask implements MaidTask {
    public static final String ID = "deposit_item";
    private static final String MOVED_COUNT = "movedCount";
    private static final String REMAINING_TIMEOUT_TICKS = "remainingTimeoutTicks";

    private final DepositItemExecutor executor;
    private final String taskId;

    public DepositItemMaidTask(AiPartnerEntity partner) {
        this(partner, ID);
    }

    /** 允许通用物流用独立稳定 ID 复用同一个安全存箱执行器。 */
    protected DepositItemMaidTask(AiPartnerEntity partner, String taskId) {
        executor = new DepositItemExecutor(partner);
        this.taskId = taskId;
    }

    @Override
    public String id() {
        return taskId;
    }

    @Override
    public void start(MaidTaskContext context) {
        executor.start(context.contract(), new ExecutorResultAdapter(context.resultSink()));
    }

    @Override
    public void restore(MaidTaskContext context, MaidTaskSnapshot snapshot) {
        executor.restore(
                context.contract(),
                snapshot.integer(MOVED_COUNT, 0),
                snapshot.longValue(REMAINING_TIMEOUT_TICKS, fullTimeoutTicks(context.contract())),
                new ExecutorResultAdapter(context.resultSink())
        );
    }

    @Override
    public void tick(MaidTaskContext context) {
        executor.tick(context.serverLevel());
    }

    @Override
    public void pauseForTick() {
        executor.pauseForMenuTick();
    }

    @Override
    public void stop() {
        executor.stop();
    }

    @Override
    public boolean isRunning() {
        return executor.isRunning();
    }

    @Override
    public PartnerMode displayedMode() {
        return PartnerMode.DEPOSITING;
    }

    @Override
    public String executionState() {
        return "DEPOSIT_" + executor.stateName();
    }

    @Override
    public MaidTaskSnapshot snapshot() {
        return MaidTaskSnapshot.builder(2)
                .putInt(MOVED_COUNT, executor.movedCount())
                .putLong(REMAINING_TIMEOUT_TICKS, executor.remainingTimeoutTicks())
                .build();
    }

    @Override
    public MaidTaskSnapshot readLegacySnapshot(ValueInput input) {
        return MaidTaskSnapshot.builder(1)
                .putInt(MOVED_COUNT, input.getIntOr("DepositMovedCount", 0))
                .build();
    }

    private static long fullTimeoutTicks(io.github.ozozorz.aipartner.contract.TaskContract contract) {
        return contract.failurePolicy().timeoutSeconds() * 20L;
    }
}
