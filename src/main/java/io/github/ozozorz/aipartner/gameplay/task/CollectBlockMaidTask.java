package io.github.ozozorz.aipartner.gameplay.task;

import io.github.ozozorz.aipartner.core.task.MaidTask;
import io.github.ozozorz.aipartner.core.task.MaidTaskContext;
import io.github.ozozorz.aipartner.core.task.MaidTaskSnapshot;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.executor.CollectBlockExecutor;
import io.github.ozozorz.aipartner.inventory.EquipmentLease;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.ValueInput;

/**
 * 把现有原木采集状态机接入统一 MaidTask 生命周期。
 */
public final class CollectBlockMaidTask implements MaidTask {
    public static final String ID = "collect_block";
    private static final String INITIAL_TARGET_COUNT = "initialTargetCount";
    private static final String TOOL_LEASE_SOURCE_SLOT = "toolLeaseSourceSlot";
    private static final String REMAINING_TIMEOUT_TICKS = "remainingTimeoutTicks";

    private final AiPartnerEntity partner;
    private final CollectBlockExecutor executor;
    private EquipmentLease toolLease;

    public CollectBlockMaidTask(AiPartnerEntity partner) {
        this.partner = partner;
        executor = new CollectBlockExecutor(partner);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void start(MaidTaskContext context) {
        toolLease = EquipmentLease.acquire(partner, CollectBlockMaidTask::isAxe).orElse(null);
        executor.start(context.contract(), new ExecutorResultAdapter(context.resultSink()));
    }

    @Override
    public void restore(MaidTaskContext context, MaidTaskSnapshot snapshot) {
        toolLease = EquipmentLease.restore(
                partner,
                CollectBlockMaidTask::isAxe,
                snapshot.integer(TOOL_LEASE_SOURCE_SLOT, EquipmentLease.NO_SOURCE_SLOT)
        ).orElse(null);
        executor.restore(
                context.contract(),
                snapshot.integer(INITIAL_TARGET_COUNT, 0),
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
        if (toolLease != null) {
            toolLease.close();
            toolLease = null;
        }
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
        return MaidTaskSnapshot.builder(2)
                .putInt(INITIAL_TARGET_COUNT, executor.initialTargetCount())
                .putLong(REMAINING_TIMEOUT_TICKS, executor.remainingTimeoutTicks())
                .putInt(
                        TOOL_LEASE_SOURCE_SLOT,
                        toolLease == null ? EquipmentLease.NO_SOURCE_SLOT : toolLease.sourceSlot()
                )
                .build();
    }

    @Override
    public MaidTaskSnapshot readLegacySnapshot(ValueInput input) {
        return MaidTaskSnapshot.builder(1)
                .putInt(INITIAL_TARGET_COUNT, input.getIntOr("CollectInitialTargetCount", 0))
                .putInt(TOOL_LEASE_SOURCE_SLOT, EquipmentLease.NO_SOURCE_SLOT)
                .build();
    }

    @Override
    public boolean acceptsPickup(Item item) {
        return executor.accepts(item);
    }

    private static boolean isAxe(net.minecraft.world.item.ItemStack stack) {
        return !stack.isEmpty() && stack.typeHolder().is(ItemTags.AXES);
    }

    private static long fullTimeoutTicks(io.github.ozozorz.aipartner.contract.TaskContract contract) {
        return contract.failurePolicy().timeoutSeconds() * 20L;
    }
}
