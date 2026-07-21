package io.github.ozozorz.aipartner.gameplay.task;

import io.github.ozozorz.aipartner.core.task.MaidTask;
import io.github.ozozorz.aipartner.core.task.MaidTaskContext;
import io.github.ozozorz.aipartner.core.task.MaidTaskSnapshot;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.executor.CollectBlockExecutor;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 把现有原木采集状态机接入统一 MaidTask 生命周期。
 */
public final class CollectBlockMaidTask implements MaidTask {
    public static final String ID = "collect_block";
    private static final String INITIAL_TARGET_COUNT = "initialTargetCount";

    private final CollectBlockExecutor executor;

    public CollectBlockMaidTask(AiPartnerEntity partner) {
        executor = new CollectBlockExecutor(partner);
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
                snapshot.integer(INITIAL_TARGET_COUNT, 0),
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
        return PartnerMode.COLLECTING;
    }

    @Override
    public String executionState() {
        return "COLLECT_" + executor.stateName();
    }

    @Override
    public MaidTaskSnapshot snapshot() {
        return MaidTaskSnapshot.builder(1)
                .putInt(INITIAL_TARGET_COUNT, executor.initialTargetCount())
                .build();
    }

    @Override
    public MaidTaskSnapshot readLegacySnapshot(ValueInput input) {
        return MaidTaskSnapshot.builder(1)
                .putInt(INITIAL_TARGET_COUNT, input.getIntOr("CollectInitialTargetCount", 0))
                .build();
    }

    @Override
    public void writeLegacySnapshot(ValueOutput output, MaidTaskSnapshot snapshot) {
        output.putInt("CollectInitialTargetCount", snapshot.integer(INITIAL_TARGET_COUNT, 0));
    }

    @Override
    public boolean acceptsPickup(Item item) {
        return executor.accepts(item);
    }
}
