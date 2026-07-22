package io.github.ozozorz.aipartner.core.task;

import io.github.ozozorz.aipartner.contract.ContractStatus;
import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.core.behavior.MaidBehaviorController;
import io.github.ozozorz.aipartner.core.behavior.ManualDirective;
import io.github.ozozorz.aipartner.core.event.ContractLifecycleEvent;
import io.github.ozozorz.aipartner.core.event.MaidDomainEvents;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.job.AllowedTargets;
import io.github.ozozorz.aipartner.job.JobType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端唯一活动契约的运行时，统一处理指令、任务、暂停、恢复、终态和持久化。
 */
public final class MaidTaskRuntime {
    public static final int CURRENT_DATA_VERSION = 7;
    private static final int CONTRACT_PREDICATE_FORMAT_VERSION = 2;
    private static final Logger LOGGER = LoggerFactory.getLogger(MaidTaskRuntime.class);

    private final AiPartnerEntity partner;
    private final MaidBehaviorController behaviorController;
    private final MaidTaskRegistry taskRegistry;

    private @Nullable TaskContract currentContract;
    private @Nullable MaidTask activeTask;
    private @Nullable MaidTaskContext activeContext;
    private @Nullable MaidTaskSnapshot pendingRestoreSnapshot;
    private TaskExecutionPolicy executionPolicy = TaskExecutionPolicy.DEFAULT;
    private final RecoveryBudget recoveryBudget = new RecoveryBudget();

    public MaidTaskRuntime(
            AiPartnerEntity partner,
            MaidBehaviorController behaviorController,
            MaidTaskRegistry taskRegistry
    ) {
        this.partner = Objects.requireNonNull(partner, "partner");
        this.behaviorController = Objects.requireNonNull(behaviorController, "behaviorController");
        this.taskRegistry = Objects.requireNonNull(taskRegistry, "taskRegistry");
    }

    /**
     * 应用已经通过服务端验证的契约。长期指令和有限任务在此处开始分流。
     */
    public void apply(
            TaskContract contract,
            ServerPlayer actor,
            String rawInstruction,
            TaskExecutionPolicy policy
    ) {
        if (partner.level().isClientSide()) {
            throw new IllegalStateException("Contracts may only be applied on the server");
        }
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(policy, "policy");

        if (contract.job().type() == JobType.CANCEL) {
            executeCancelContract(contract, actor, rawInstruction, policy);
            return;
        }

        cancelExisting(actor, "replaced_by_new_contract");
        currentContract = contract;
        executionPolicy = policy;
        recoveryBudget.reset();
        publish("contract_accepted", actor, rawInstruction);
        contract.markRunning();

        Optional<ManualDirective> directive = ManualDirective.fromJobType(contract.job().type());
        if (directive.isPresent()) {
            activateDirective(directive.get());
        } else {
            try {
                startRegisteredTask(contract);
            } catch (RuntimeException exception) {
                LOGGER.error("Failed to start maid task {}", contract.job().type(), exception);
                fail(FailureCode.INTERNAL_ERROR);
                return;
            }
        }
        publish("contract_running", actor, rawInstruction);
        if (!isConversationalWorkflow()) {
            partner.showSpeechBubble(feedbackFor(contract.job().type()));
        }
    }

    private void executeCancelContract(
            TaskContract cancelContract,
            ServerPlayer actor,
            String rawInstruction,
            TaskExecutionPolicy policy
    ) {
        cancelExisting(actor, rawInstruction);
        currentContract = cancelContract;
        executionPolicy = policy;
        recoveryBudget.reset();
        publish("contract_accepted", actor, rawInstruction);
        cancelContract.markRunning();
        behaviorController.clearActivity();
        partner.getNavigation().stop();
        publish("contract_running", actor, rawInstruction);
        cancelContract.markCompleted();
        publish("contract_completed", actor, rawInstruction);
        if (!isConversationalWorkflow()) {
            partner.showSpeechBubble(feedbackFor(JobType.CANCEL));
        }
    }

    private void activateDirective(ManualDirective directive) {
        stopActiveTask();
        behaviorController.activateDirective(directive);
        partner.onManualDirectiveActivated(directive);
        if (directive == ManualDirective.STAY) {
            partner.getNavigation().stop();
        }
    }

    /**
     * 激活不属于旧 Job DSL 的长期指令，例如立即返回当前活动地点。
     */
    public void activateManualDirective(ManualDirective directive, @Nullable ServerPlayer actor, String reason) {
        cancelExisting(actor, reason);
        currentContract = null;
        stopActiveTask();
        recoveryBudget.reset();
        behaviorController.activateDirective(directive);
        partner.onManualDirectiveActivated(directive);
    }

    private void startRegisteredTask(TaskContract contract) {
        MaidTask task = taskRegistry.create(contract.job().type(), partner)
                .orElseThrow(() -> new IllegalStateException(
                        "No task registered for implemented job " + contract.job().type()
                ));
        activeTask = task;
        activeContext = new MaidTaskContext(partner, contract, resultSink(contract.contractId()));
        pendingRestoreSnapshot = null;
        behaviorController.activateTaskMode(task.displayedMode());
        task.start(requireActiveContext());
        if (activeTask == task) {
            behaviorController.activateTaskMode(task.displayedMode());
        }
    }

    /**
     * 每个服务端 tick 推进唯一活动任务，并处理 GUI 暂停和旧存档延迟恢复。
     */
    public void tick() {
        if (partner.level().isClientSide()) {
            return;
        }
        FailureCode invariantViolation = runtimeInvariantViolation();
        if (invariantViolation != FailureCode.NONE) {
            fail(invariantViolation);
            return;
        }
        if (behaviorController.isInventoryMenuOpen() || behaviorController.isTemporarilyInterrupted()) {
            if (activeTask != null) {
                activeTask.pauseForTick();
            }
            return;
        }

        MaidTask task = activeTask;
        MaidTaskContext context = activeContext;
        if (task != null
                && context != null
                && currentContract != null
                && currentContract.status() == ContractStatus.RUNNING) {
            try {
                if (pendingRestoreSnapshot != null) {
                    MaidTaskSnapshot snapshot = pendingRestoreSnapshot;
                    pendingRestoreSnapshot = null;
                    task.restore(context, snapshot);
                } else if (!task.isRunning()) {
                    task.restore(context, task.snapshot());
                }

                if (activeTask == task
                        && currentContract != null
                        && currentContract.status() == ContractStatus.RUNNING) {
                    task.tick(context);
                }
            } catch (RuntimeException exception) {
                LOGGER.error("Maid task {} failed with an unexpected runtime error", task.id(), exception);
                fail(FailureCode.INTERNAL_ERROR);
                return;
            }
            if (activeTask == task) {
                behaviorController.activateTaskMode(task.displayedMode());
            }
        }

        if (isFollowing() && partner.tickCount % 100 == 0 && partner.getOwner() == null) {
            fail(FailureCode.OWNER_OFFLINE);
        }
    }

    /**
     * 用类型化原因结束当前活动契约。
     */
    public void fail(FailureCode failureCode) {
        if (currentContract == null || currentContract.status().isTerminal()) {
            return;
        }
        currentContract.markFailed(failureCode);
        stopActiveTask();
        behaviorController.clearActivity();
        partner.getNavigation().stop();
        publish("contract_failed", null, "runtime_monitor");
        if (!isConversationalWorkflow()) {
            notifyOwner(Component.translatable("message.ai-partner.failed", failureCode.name()));
            partner.showSpeechBubble(Component.translatable("bubble.ai-partner.task_failed"));
        }
    }

    /**
     * 在任务目标谓词成立后完成当前活动契约。
     */
    public void complete() {
        if (currentContract == null || currentContract.status() != ContractStatus.RUNNING) {
            return;
        }
        if (!goalPredicatesSatisfied()) {
            fail(FailureCode.CONTRACT_VIOLATION);
            return;
        }
        TaskContract completedContract = currentContract;
        completedContract.markCompleted();
        stopActiveTask();
        behaviorController.clearActivity();
        partner.getNavigation().stop();
        publish("contract_completed", null, "goal_predicate_satisfied");
        if (!isConversationalWorkflow()) {
            notifyOwner(Component.translatable(
                    "message.ai-partner.completed",
                    completedContract.job().type().name(),
                    completedContract.job().quantity(),
                    completedContract.job().target()
            ));
            partner.showSpeechBubble(Component.translatable("bubble.ai-partner.task_completed"));
        }
    }

    /**
     * 取消当前契约，供实验扰动和外部服务复用。
     */
    public void cancel(@Nullable ServerPlayer actor, String reason) {
        cancelExisting(actor, reason);
    }

    private void cancelExisting(@Nullable ServerPlayer actor, String reason) {
        if (currentContract == null || currentContract.status().isTerminal()) {
            return;
        }
        currentContract.markCancelled();
        stopActiveTask();
        behaviorController.clearActivity();
        partner.getNavigation().stop();
        publish("contract_cancelled", actor, reason);
    }

    /**
     * 为受控实验清空运行时，但把背包和装备处理留给实体生命周期层。
     */
    public void resetForExperiment(ServerPlayer actor) {
        cancelExisting(actor, "experiment_reset");
        currentContract = null;
        stopActiveTask();
        executionPolicy = TaskExecutionPolicy.standard("EXPERIMENT_RESET");
        recoveryBudget.reset();
        behaviorController.clearActivity();
        partner.getNavigation().stop();
    }

    public Optional<TaskContract> currentContract() {
        return Optional.ofNullable(currentContract);
    }

    public boolean isFollowing() {
        return behaviorController.isFollowing()
                && currentContract != null
                && currentContract.status() == ContractStatus.RUNNING;
    }

    public boolean hasRunningContract() {
        return currentContract != null && currentContract.status() == ContractStatus.RUNNING;
    }

    public boolean hasFiniteTaskRunning() {
        return activeTask != null && hasRunningContract();
    }

    public boolean canUseAmbientMovement() {
        return !behaviorController.isInventoryMenuOpen()
                && !hasRunningContract();
    }

    public boolean acceptsPickup(Item item) {
        return activeTask != null && activeTask.acceptsPickup(item);
    }

    public int maximumLocalRetries() {
        return currentContract == null || !executionPolicy.localRecoveryEnabled()
                ? 0
                : currentContract.failurePolicy().maxLocalRetries();
    }

    public boolean runtimeMonitoringEnabled() {
        return executionPolicy.runtimeMonitoringEnabled();
    }

    public boolean localRecoveryEnabled() {
        return executionPolicy.localRecoveryEnabled();
    }

    /**
     * 记录一次由动态世界故障触发的局部恢复。
     */
    public boolean tryRecordRuntimeRecovery(String reason) {
        if (!recoveryBudget.tryConsume(maximumLocalRetries(), executionPolicy.localRecoveryEnabled())) {
            publish("runtime_recovery_exhausted", null, reason);
            return false;
        }
        publish("runtime_recovery", null, reason);
        return true;
    }

    public int runtimeRecoveryCount() {
        return recoveryBudget.consumed();
    }

    /**
     * 返回当前任务的内部状态，供状态命令和冻结实验扰动观察。
     */
    public String activeExecutionState() {
        return activeTask == null ? behaviorController.effectiveMode().name() : activeTask.executionState();
    }

    public void logRuntimeEvent(String event) {
        publish(event, null, "runtime_executor");
    }

    /**
     * 写入 v0.5 通用运行时数据，并保留 v0.4 字段供迁移期工具读取。
     */
    public void save(ValueOutput output) {
        output.putInt("AiPartnerDataVersion", CURRENT_DATA_VERSION);
        behaviorController.save(output);
        output.putString("CurrentOrderSource", executionPolicy.sourceId());
        output.putString("CurrentSystemVariant", executionPolicy.sourceId());
        output.putInt("RuntimeMonitoringEnabled", executionPolicy.runtimeMonitoringEnabled() ? 1 : 0);
        output.putInt("LocalRecoveryEnabled", executionPolicy.localRecoveryEnabled() ? 1 : 0);
        output.putInt("RuntimeRecoveryCount", recoveryBudget.consumed());

        output.putInt("CollectInitialTargetCount", 0);
        output.putInt("DepositMovedCount", 0);
        output.putString("CompositePhase", "IDLE");
        if (activeTask != null) {
            // 世界可能在实体首次 tick 前再次保存；此时必须保留尚未应用的恢复快照。
            MaidTaskSnapshot snapshot = pendingRestoreSnapshot != null
                    ? pendingRestoreSnapshot
                    : activeTask.snapshot();
            output.putString("ActiveTaskId", activeTask.id());
            snapshot.write(output);
            activeTask.writeLegacySnapshot(output, snapshot);
        }

        if (currentContract != null) {
            saveContract(output, currentContract);
        }
    }

    /**
     * 加载 v0.5 数据；缺少新字段时自动使用 v0.4 契约和执行器进度字段。
     */
    public void load(ValueInput input) {
        stopActiveTask();
        currentContract = null;
        behaviorController.load(input);
        recoveryBudget.restore(input.getIntOr("RuntimeRecoveryCount", 0));

        String sourceId = input.getStringOr(
                "CurrentOrderSource",
                input.getStringOr("CurrentSystemVariant", TaskExecutionPolicy.DEFAULT.sourceId())
        );
        TaskExecutionPolicy legacyPolicy = TaskExecutionPolicy.fromLegacySource(sourceId);
        int monitoringValue = input.getIntOr("RuntimeMonitoringEnabled", -1);
        int recoveryValue = input.getIntOr("LocalRecoveryEnabled", -1);
        executionPolicy = monitoringValue < 0 || recoveryValue < 0
                ? legacyPolicy
                : new TaskExecutionPolicy(sourceId, monitoringValue != 0, recoveryValue != 0);

        Optional<String> savedContractId = input.getString("ContractId");
        if (savedContractId.isEmpty()) {
            behaviorController.clearActivity();
            return;
        }

        try {
            currentContract = restoreContract(input, savedContractId.get());
        } catch (IllegalArgumentException exception) {
            currentContract = null;
            behaviorController.clearActivity();
            return;
        }

        if (currentContract.status().isTerminal()) {
            behaviorController.clearActivity();
            return;
        }
        if (currentContract.status() != ContractStatus.RUNNING) {
            behaviorController.clearActivity();
            return;
        }

        Optional<ManualDirective> directive = ManualDirective.fromJobType(currentContract.job().type());
        if (directive.isPresent()) {
            behaviorController.activateDirective(directive.get());
            partner.onManualDirectiveActivated(directive.get());
            if (directive.get() == ManualDirective.STAY) {
                partner.getNavigation().stop();
            }
            return;
        }

        MaidTask task = createRestoredTask(input, currentContract.job().type()).orElse(null);
        if (task == null) {
            currentContract.markFailed(FailureCode.INTERNAL_ERROR);
            behaviorController.clearActivity();
            return;
        }
        activeTask = task;
        activeContext = new MaidTaskContext(
                partner,
                currentContract,
                resultSink(currentContract.contractId())
        );
        boolean hasV05Snapshot = input.getString("ActiveTaskId").isPresent();
        pendingRestoreSnapshot = hasV05Snapshot
                ? MaidTaskSnapshot.read(input)
                : task.readLegacySnapshot(input);
        behaviorController.activateTaskMode(task.restoredDisplayedMode(pendingRestoreSnapshot));
    }

    private Optional<MaidTask> createRestoredTask(ValueInput input, JobType jobType) {
        Optional<String> expectedId = taskRegistry.taskId(jobType);
        Optional<String> savedId = input.getString("ActiveTaskId");
        if (savedId.isPresent()
                && expectedId.isPresent()
                && expectedId.get().equals(savedId.get())) {
            Optional<MaidTask> restored = taskRegistry.create(savedId.get(), partner);
            if (restored.isPresent()) {
                return restored;
            }
        }
        return taskRegistry.create(jobType, partner);
    }

    /** 写入当前完整契约格式；包内可见以支持字段级持久化测试。 */
    static void saveContract(ValueOutput output, TaskContract contract) {
        output.putString("ContractId", contract.contractId().toString());
        output.putString("ContractJobType", contract.job().type().name());
        output.putString("ContractTarget", contract.job().target());
        output.putInt("ContractQuantity", contract.job().quantity());
        output.putInt("ContractRadius", contract.job().radius());
        output.putString("ContractStatus", contract.status().name());
        output.putString("ContractFailureCode", contract.failureCode().name());
        output.putLong("ContractAcceptedAt", contract.acceptedAtEpochMillis());
        output.putInt("ContractMaxLocalRetries", contract.failurePolicy().maxLocalRetries());
        output.putInt("ContractMaxLlmReplans", contract.failurePolicy().maxLlmReplans());
        output.putInt("ContractTimeoutSeconds", contract.failurePolicy().timeoutSeconds());
        output.putInt("ContractPredicateFormatVersion", CONTRACT_PREDICATE_FORMAT_VERSION);
        output.putInt("ContractExecutionAnchorBound", contract.executionAnchor().bound() ? 1 : 0);
        if (contract.executionAnchor().bound()) {
            output.putString("ContractOwnerId", contract.executionAnchor().ownerId().toString());
            output.putString("ContractOriginDimension", contract.executionAnchor().dimensionId());
            output.putLong("ContractOriginPosition", contract.executionAnchor().originPosition());
        }
        writePredicateList(output, "ContractPrecondition", contract.preconditions());
        writePredicateList(output, "ContractGoalPredicate", contract.goalPredicates());
        writePredicateList(output, "ContractInvariant", contract.invariants());
    }

    /** 恢复当前或旧版契约格式；未知的新谓词格式会失败关闭。 */
    static TaskContract restoreContract(ValueInput input, String savedContractId) {
        JobSpec job = new JobSpec(
                JobType.valueOf(input.getStringOr("ContractJobType", JobType.CANCEL.name())),
                input.getStringOr("ContractTarget", ""),
                input.getIntOr("ContractQuantity", 0),
                input.getIntOr("ContractRadius", 0)
        );
        TaskContract.FailurePolicy defaultPolicy = TaskContract.FailurePolicy.DEFAULT;
        TaskContract.FailurePolicy policy = new TaskContract.FailurePolicy(
                input.getIntOr("ContractMaxLocalRetries", defaultPolicy.maxLocalRetries()),
                input.getIntOr("ContractMaxLlmReplans", defaultPolicy.maxLlmReplans()),
                input.getIntOr("ContractTimeoutSeconds", defaultPolicy.timeoutSeconds())
        );
        int predicateFormat = input.getIntOr("ContractPredicateFormatVersion", 0);
        if (predicateFormat == 0) {
            return TaskContract.restored(
                    UUID.fromString(savedContractId),
                    job,
                    input.getLongOr("ContractAcceptedAt", System.currentTimeMillis()),
                    ContractStatus.valueOf(input.getStringOr("ContractStatus", ContractStatus.FAILED.name())),
                    FailureCode.valueOf(input.getStringOr("ContractFailureCode", FailureCode.INTERNAL_ERROR.name())),
                    policy
            );
        }
        if (predicateFormat != 1 && predicateFormat != CONTRACT_PREDICATE_FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported contract predicate format " + predicateFormat);
        }
        List<String> preconditions = readPredicateList(input, "ContractPrecondition");
        List<String> goals = readPredicateList(input, "ContractGoalPredicate");
        List<String> invariants = readPredicateList(input, "ContractInvariant");
        if (predicateFormat == 1) {
            return TaskContract.restored(
                    UUID.fromString(savedContractId),
                    job,
                    preconditions,
                    goals,
                    invariants,
                    input.getLongOr("ContractAcceptedAt", System.currentTimeMillis()),
                    ContractStatus.valueOf(input.getStringOr("ContractStatus", ContractStatus.FAILED.name())),
                    FailureCode.valueOf(input.getStringOr("ContractFailureCode", FailureCode.INTERNAL_ERROR.name())),
                    policy
            );
        }
        TaskContract.ExecutionAnchor anchor = input.getIntOr("ContractExecutionAnchorBound", 0) == 0
                ? TaskContract.ExecutionAnchor.unbound()
                : TaskContract.ExecutionAnchor.bound(
                        UUID.fromString(input.getStringOr("ContractOwnerId", "")),
                        input.getStringOr("ContractOriginDimension", ""),
                        input.getLongOr("ContractOriginPosition", 0L)
                );
        return TaskContract.restored(
                UUID.fromString(savedContractId),
                job,
                preconditions,
                goals,
                invariants,
                input.getLongOr("ContractAcceptedAt", System.currentTimeMillis()),
                ContractStatus.valueOf(input.getStringOr("ContractStatus", ContractStatus.FAILED.name())),
                FailureCode.valueOf(input.getStringOr("ContractFailureCode", FailureCode.INTERNAL_ERROR.name())),
                policy,
                anchor
        );
    }

    /** 将完整契约谓词写为有界、稳定的索引字段。 */
    private static void writePredicateList(ValueOutput output, String prefix, List<String> predicates) {
        output.putInt(prefix + "Count", predicates.size());
        for (int index = 0; index < predicates.size(); index++) {
            output.putString(prefix + index, predicates.get(index));
        }
    }

    /** 从当前格式读取谓词；损坏或超界的计数会使契约恢复失败关闭。 */
    private static List<String> readPredicateList(ValueInput input, String prefix) {
        int count = input.getIntOr(prefix + "Count", -1);
        if (count < 0 || count > TaskContract.MAX_PREDICATES_PER_SECTION) {
            throw new IllegalArgumentException("Invalid persisted predicate count for " + prefix);
        }
        List<String> predicates = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String predicate = input.getStringOr(prefix + index, "");
            if (predicate.isBlank()) {
                throw new IllegalArgumentException("Invalid persisted predicate for " + prefix);
            }
            predicates.add(predicate);
        }
        return List.copyOf(predicates);
    }

    private MaidTaskResultSink resultSink(UUID expectedContractId) {
        return new MaidTaskResultSink() {
            @Override
            public void complete() {
                if (isCurrentContract(expectedContractId)) {
                    MaidTaskRuntime.this.complete();
                }
            }

            @Override
            public void fail(FailureCode failureCode) {
                if (isCurrentContract(expectedContractId)) {
                    MaidTaskRuntime.this.fail(failureCode);
                }
            }
        };
    }

    private boolean isCurrentContract(UUID expectedContractId) {
        return currentContract != null
                && currentContract.contractId().equals(expectedContractId)
                && currentContract.status() == ContractStatus.RUNNING;
    }

    /** Evaluates persisted owner, dimension, origin, and world-safety invariants every server tick. */
    private FailureCode runtimeInvariantViolation() {
        if (currentContract == null || currentContract.status() != ContractStatus.RUNNING) {
            return FailureCode.NONE;
        }
        if (!partner.isAlive()) {
            return FailureCode.PARTNER_DIED;
        }
        TaskContract.ExecutionAnchor anchor = currentContract.executionAnchor();
        if (anchor.bound()) {
            if (!(partner.getOwner() instanceof ServerPlayer owner)) {
                return FailureCode.OWNER_OFFLINE;
            }
            if (!anchor.ownerId().equals(owner.getUUID())) {
                return FailureCode.PERMISSION_DENIED;
            }
            if (!anchor.dimensionId().equals(partner.level().dimension().identifier().toString())
                    || partner.level() != owner.level()) {
                return FailureCode.DIFFERENT_DIMENSION;
            }
            if (currentContract.job().radius() > 0
                    && horizontalDistanceSquared(
                    partner.blockPosition(),
                    BlockPos.of(anchor.originPosition())
            ) > square(currentContract.job().radius())) {
                return FailureCode.CONTRACT_VIOLATION;
            }
        }
        JobType type = currentContract.job().type();
        if ((type == JobType.COLLECT_BLOCK || type == JobType.COLLECT_AND_DEPOSIT)
                && (!(partner.level() instanceof ServerLevel level)
                || !level.getGameRules().get(GameRules.MOB_GRIEFING))) {
            return FailureCode.PERMISSION_DENIED;
        }
        if ((type == JobType.COLLECT_BLOCK || type == JobType.COLLECT_AND_DEPOSIT)
                && !partner.hasAxe()) {
            return FailureCode.MISSING_TOOL;
        }
        return FailureCode.NONE;
    }

    /** Independently certifies task-specific goal evidence before changing a contract to COMPLETED. */
    private boolean goalPredicatesSatisfied() {
        if (currentContract == null) {
            return false;
        }
        JobType type = currentContract.job().type();
        if (type == JobType.CANCEL) {
            return true;
        }
        if (activeTask == null) {
            return type == JobType.FOLLOW || type == JobType.STAY;
        }
        MaidTaskSnapshot snapshot = activeTask.snapshot();
        return switch (type) {
            case COLLECT_BLOCK -> AllowedTargets.resolveCollectibleBlock(currentContract.job().target())
                    .flatMap(AllowedTargets::asCollectibleItem)
                    .map(item -> partner.countItem(item)
                            - snapshot.integer("initialTargetCount", 0) >= currentContract.job().quantity())
                    .orElse(false);
            case DEPOSIT_ITEM, TRANSFER_ITEM ->
                    snapshot.integer("movedCount", 0) >= currentContract.job().quantity();
            case COLLECT_AND_DEPOSIT ->
                    snapshot.integer("collectedCount", 0) >= currentContract.job().quantity()
                            && snapshot.integer("depositMovedCount", 0) >= currentContract.job().quantity();
            case FOLLOW, STAY, CANCEL -> true;
        };
    }

    private static double horizontalDistanceSquared(BlockPos first, BlockPos second) {
        double x = first.getX() - second.getX();
        double z = first.getZ() - second.getZ();
        return x * x + z * z;
    }

    private static double square(double value) {
        return value * value;
    }

    private MaidTaskContext requireActiveContext() {
        if (activeContext == null) {
            throw new IllegalStateException("Task runtime has no active context");
        }
        return activeContext;
    }

    private void stopActiveTask() {
        MaidTask task = activeTask;
        activeTask = null;
        activeContext = null;
        pendingRestoreSnapshot = null;
        if (task != null) {
            task.stop();
        }
    }

    private void publish(
            String event,
            @Nullable ServerPlayer actor,
            String detail
    ) {
        if (currentContract != null) {
            MaidDomainEvents.publish(new ContractLifecycleEvent(
                    event,
                    executionPolicy.sourceId(),
                    partner,
                    actor,
                    currentContract,
                    detail
            ));
        }
    }

    private void notifyOwner(Component message) {
        if (partner.getOwner() instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(message);
        }
    }

    private boolean isConversationalWorkflow() {
        return executionPolicy.sourceId().startsWith("LLM")
                && executionPolicy.sourceId().contains("WORKFLOW");
    }

    private static Component feedbackFor(JobType jobType) {
        return switch (jobType) {
            case FOLLOW -> Component.translatable("bubble.ai-partner.follow");
            case STAY -> Component.translatable("bubble.ai-partner.stay");
            case CANCEL -> Component.translatable("bubble.ai-partner.cancel");
            case COLLECT_BLOCK -> Component.translatable("bubble.ai-partner.collect");
            case DEPOSIT_ITEM -> Component.translatable("bubble.ai-partner.deposit");
            case COLLECT_AND_DEPOSIT -> Component.translatable("bubble.ai-partner.collect_and_deposit");
            case TRANSFER_ITEM -> Component.translatable("bubble.ai-partner.transfer");
        };
    }
}
