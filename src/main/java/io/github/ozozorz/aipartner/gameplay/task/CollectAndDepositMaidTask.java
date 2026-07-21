package io.github.ozozorz.aipartner.gameplay.task;

import io.github.ozozorz.aipartner.core.task.MaidTask;
import io.github.ozozorz.aipartner.core.task.MaidTaskContext;
import io.github.ozozorz.aipartner.core.task.MaidTaskSnapshot;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import io.github.ozozorz.aipartner.executor.CollectAndDepositExecutor;
import io.github.ozozorz.aipartner.executor.CollectBlockExecutor;
import io.github.ozozorz.aipartner.executor.DepositItemExecutor;
import io.github.ozozorz.aipartner.inventory.EquipmentLease;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 固定执行“采集后存放”的两阶段任务，阶段顺序不能由输入端改变。
 */
public final class CollectAndDepositMaidTask implements MaidTask {
    public static final String ID = "collect_and_deposit";
    private static final String PHASE = "phase";
    private static final String COLLECT_INITIAL_TARGET_COUNT = "collectInitialTargetCount";
    private static final String DEPOSIT_MOVED_COUNT = "depositMovedCount";
    private static final String TOOL_LEASE_SOURCE_SLOT = "toolLeaseSourceSlot";

    private final AiPartnerEntity partner;
    private final CollectBlockExecutor collectExecutor;
    private final DepositItemExecutor depositExecutor;
    private final CollectAndDepositExecutor executor;
    private EquipmentLease toolLease;

    public CollectAndDepositMaidTask(AiPartnerEntity partner) {
        this.partner = partner;
        collectExecutor = new CollectBlockExecutor(partner);
        depositExecutor = new DepositItemExecutor(partner);
        executor = new CollectAndDepositExecutor(partner, collectExecutor, depositExecutor);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void start(MaidTaskContext context) {
        toolLease = EquipmentLease.acquire(partner, CollectAndDepositMaidTask::isAxe).orElse(null);
        executor.start(context.contract(), new ExecutorResultAdapter(context.resultSink()));
    }

    @Override
    public void restore(MaidTaskContext context, MaidTaskSnapshot snapshot) {
        CollectAndDepositExecutor.Phase savedPhase = CollectAndDepositExecutor.Phase.fromName(snapshot.string(
                PHASE,
                CollectAndDepositExecutor.Phase.COLLECTING.name()
        ));
        if (savedPhase == CollectAndDepositExecutor.Phase.COLLECTING) {
            toolLease = EquipmentLease.restore(
                    partner,
                    CollectAndDepositMaidTask::isAxe,
                    snapshot.integer(TOOL_LEASE_SOURCE_SLOT, EquipmentLease.NO_SOURCE_SLOT)
            ).orElse(null);
        }
        executor.restore(
                context.contract(),
                savedPhase,
                snapshot.integer(COLLECT_INITIAL_TARGET_COUNT, 0),
                snapshot.integer(DEPOSIT_MOVED_COUNT, 0),
                new ExecutorResultAdapter(context.resultSink())
        );
    }

    @Override
    public void tick(MaidTaskContext context) {
        CollectAndDepositExecutor.Phase before = executor.phase();
        executor.tick(context.serverLevel());
        if (before == CollectAndDepositExecutor.Phase.COLLECTING
                && executor.phase() != CollectAndDepositExecutor.Phase.COLLECTING) {
            releaseToolLease();
        }
    }

    @Override
    public void pauseForTick() {
        executor.pauseForMenuTick();
    }

    @Override
    public void stop() {
        executor.stop();
        releaseToolLease();
    }

    @Override
    public boolean isRunning() {
        return executor.isRunning();
    }

    @Override
    public PartnerMode displayedMode() {
        return switch (executor.phase()) {
            case COLLECTING -> PartnerMode.COLLECTING;
            case DEPOSITING -> PartnerMode.DEPOSITING;
            case IDLE, COMPLETE, FAILED -> PartnerMode.IDLE;
        };
    }

    @Override
    public PartnerMode restoredDisplayedMode(MaidTaskSnapshot snapshot) {
        CollectAndDepositExecutor.Phase restoredPhase = CollectAndDepositExecutor.Phase.fromName(
                snapshot.string(PHASE, CollectAndDepositExecutor.Phase.COLLECTING.name())
        );
        return restoredPhase == CollectAndDepositExecutor.Phase.DEPOSITING
                ? PartnerMode.DEPOSITING
                : PartnerMode.COLLECTING;
    }

    @Override
    public String executionState() {
        return "COMPOSITE_" + executor.phase().name() + "_" + executor.activeStateName();
    }

    @Override
    public MaidTaskSnapshot snapshot() {
        return MaidTaskSnapshot.builder(1)
                .putString(PHASE, executor.phase().name())
                .putInt(COLLECT_INITIAL_TARGET_COUNT, executor.collectInitialTargetCount())
                .putInt(DEPOSIT_MOVED_COUNT, executor.depositMovedCount())
                .putInt(
                        TOOL_LEASE_SOURCE_SLOT,
                        toolLease == null ? EquipmentLease.NO_SOURCE_SLOT : toolLease.sourceSlot()
                )
                .build();
    }

    @Override
    public MaidTaskSnapshot readLegacySnapshot(ValueInput input) {
        return MaidTaskSnapshot.builder(1)
                .putString(PHASE, input.getStringOr(
                        "CompositePhase",
                        CollectAndDepositExecutor.Phase.COLLECTING.name()
                ))
                .putInt(COLLECT_INITIAL_TARGET_COUNT, input.getIntOr("CollectInitialTargetCount", 0))
                .putInt(DEPOSIT_MOVED_COUNT, input.getIntOr("DepositMovedCount", 0))
                .putInt(TOOL_LEASE_SOURCE_SLOT, EquipmentLease.NO_SOURCE_SLOT)
                .build();
    }

    @Override
    public void writeLegacySnapshot(ValueOutput output, MaidTaskSnapshot snapshot) {
        output.putString("CompositePhase", snapshot.string(
                PHASE,
                CollectAndDepositExecutor.Phase.COLLECTING.name()
        ));
        output.putInt(
                "CollectInitialTargetCount",
                snapshot.integer(COLLECT_INITIAL_TARGET_COUNT, 0)
        );
        output.putInt("DepositMovedCount", snapshot.integer(DEPOSIT_MOVED_COUNT, 0));
    }

    @Override
    public boolean acceptsPickup(Item item) {
        return executor.phase() == CollectAndDepositExecutor.Phase.COLLECTING
                && collectExecutor.accepts(item);
    }

    private void releaseToolLease() {
        if (toolLease != null) {
            toolLease.close();
            toolLease = null;
        }
    }

    private static boolean isAxe(net.minecraft.world.item.ItemStack stack) {
        return !stack.isEmpty() && stack.typeHolder().is(ItemTags.AXES);
    }
}
