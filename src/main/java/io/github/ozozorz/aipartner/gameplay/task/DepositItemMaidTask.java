package io.github.ozozorz.aipartner.gameplay.task;

import io.github.ozozorz.aipartner.core.task.MaidTask;
import io.github.ozozorz.aipartner.core.task.MaidTaskContext;
import io.github.ozozorz.aipartner.core.task.MaidTaskSnapshot;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.executor.DepositItemExecutor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 把现有单箱存放状态机接入统一 MaidTask 生命周期。
 */
public final class DepositItemMaidTask implements MaidTask {
    public static final String ID = "deposit_item";
    private static final String MOVED_COUNT = "movedCount";

    private final DepositItemExecutor executor;

    public DepositItemMaidTask(AiPartnerEntity partner) {
        executor = new DepositItemExecutor(partner);
    }

    @Override
    public String id() {
        return ID;
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
        return MaidTaskSnapshot.builder(1)
                .putInt(MOVED_COUNT, executor.movedCount())
                .build();
    }

    @Override
    public MaidTaskSnapshot readLegacySnapshot(ValueInput input) {
        return MaidTaskSnapshot.builder(1)
                .putInt(MOVED_COUNT, input.getIntOr("DepositMovedCount", 0))
                .build();
    }

    @Override
    public void writeLegacySnapshot(ValueOutput output, MaidTaskSnapshot snapshot) {
        output.putInt("DepositMovedCount", snapshot.integer(MOVED_COUNT, 0));
    }
}
