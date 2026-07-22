package io.github.ozozorz.aipartner.contract;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 由服务器编译并拥有最终解释权的 IBC 契约。
 */
public final class TaskContract {
    public static final int MAX_PREDICATES_PER_SECTION = 64;
    private static final int MAX_PREDICATE_LENGTH = 512;
    private final UUID contractId;
    private final JobSpec job;
    private final List<String> preconditions;
    private final List<String> goalPredicates;
    private final List<String> invariants;
    private final FailurePolicy failurePolicy;
    private final long acceptedAtEpochMillis;
    private ContractStatus status;
    private FailureCode failureCode;

    private TaskContract(
            UUID contractId,
            JobSpec job,
            List<String> preconditions,
            List<String> goalPredicates,
            List<String> invariants,
            FailurePolicy failurePolicy,
            long acceptedAtEpochMillis,
            ContractStatus status,
            FailureCode failureCode
    ) {
        this.contractId = Objects.requireNonNull(contractId, "contractId");
        this.job = Objects.requireNonNull(job, "job");
        this.preconditions = immutablePredicates(preconditions, "preconditions");
        this.goalPredicates = immutablePredicates(goalPredicates, "goalPredicates");
        this.invariants = immutablePredicates(invariants, "invariants");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        this.acceptedAtEpochMillis = acceptedAtEpochMillis;
        this.status = Objects.requireNonNull(status, "status");
        this.failureCode = Objects.requireNonNull(failureCode, "failureCode");
    }

    /**
     * 创建一个已通过执行前验证的正式契约。
     */
    public static TaskContract accepted(
            JobSpec job,
            List<String> preconditions,
            List<String> goalPredicates,
            List<String> invariants,
            FailurePolicy failurePolicy
    ) {
        return new TaskContract(
                UUID.randomUUID(),
                job,
                preconditions,
                goalPredicates,
                invariants,
                failurePolicy,
                System.currentTimeMillis(),
                ContractStatus.ACCEPTED,
                FailureCode.NONE
        );
    }

    /**
     * 从实体存档恢复上次契约，防止服务器重启后语言状态与行为模式失配。
     */
    public static TaskContract restored(
            UUID contractId,
            JobSpec job,
            long acceptedAtEpochMillis,
            ContractStatus status,
            FailureCode failureCode
    ) {
        return restored(
                contractId,
                job,
                acceptedAtEpochMillis,
                status,
                failureCode,
                FailurePolicy.DEFAULT
        );
    }

    /**
     * 从 v0.5 存档恢复包含执行边界的契约。
     */
    public static TaskContract restored(
            UUID contractId,
            JobSpec job,
            long acceptedAtEpochMillis,
            ContractStatus status,
            FailureCode failureCode,
            FailurePolicy failurePolicy
    ) {
        return new TaskContract(
                contractId,
                job,
                List.of("owner_is_online"),
                List.of(),
                List.of("do_not_attack_friendly_entities"),
                failurePolicy,
                acceptedAtEpochMillis,
                status,
                failureCode
        );
    }

    /**
     * 从当前存档格式恢复完整契约描述，保证重启前后的审计谓词保持一致。
     */
    public static TaskContract restored(
            UUID contractId,
            JobSpec job,
            List<String> preconditions,
            List<String> goalPredicates,
            List<String> invariants,
            long acceptedAtEpochMillis,
            ContractStatus status,
            FailureCode failureCode,
            FailurePolicy failurePolicy
    ) {
        return new TaskContract(
                contractId,
                job,
                preconditions,
                goalPredicates,
                invariants,
                failurePolicy,
                acceptedAtEpochMillis,
                status,
                failureCode
        );
    }

    private static List<String> immutablePredicates(List<String> predicates, String fieldName) {
        Objects.requireNonNull(predicates, fieldName);
        if (predicates.size() > MAX_PREDICATES_PER_SECTION) {
            throw new IllegalArgumentException(fieldName + " exceeds persistence limit");
        }
        for (String predicate : predicates) {
            if (predicate == null || predicate.isBlank() || predicate.length() > MAX_PREDICATE_LENGTH) {
                throw new IllegalArgumentException(fieldName + " contains an invalid predicate");
            }
        }
        return List.copyOf(predicates);
    }

    /**
     * 将已接受契约切换为执行中。
     */
    public void markRunning() {
        requireStatus(ContractStatus.ACCEPTED);
        status = ContractStatus.RUNNING;
    }

    /**
     * 仅在目标谓词成立后标记任务完成。
     */
    public void markCompleted() {
        requireStatus(ContractStatus.RUNNING);
        status = ContractStatus.COMPLETED;
        failureCode = FailureCode.NONE;
    }

    /**
     * 用类型化故障结束契约。
     */
    public void markFailed(FailureCode code) {
        if (status.isTerminal()) {
            return;
        }
        status = ContractStatus.FAILED;
        failureCode = Objects.requireNonNull(code, "code");
    }

    /**
     * 取消尚未结束的契约。
     */
    public void markCancelled() {
        if (status.isTerminal()) {
            return;
        }
        status = ContractStatus.CANCELLED;
        failureCode = FailureCode.CANCELLED_BY_PLAYER;
    }

    private void requireStatus(ContractStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Expected contract status " + expected + " but was " + status);
        }
    }

    public UUID contractId() {
        return contractId;
    }

    public JobSpec job() {
        return job;
    }

    public List<String> preconditions() {
        return preconditions;
    }

    public List<String> goalPredicates() {
        return goalPredicates;
    }

    public List<String> invariants() {
        return invariants;
    }

    public FailurePolicy failurePolicy() {
        return failurePolicy;
    }

    public long acceptedAtEpochMillis() {
        return acceptedAtEpochMillis;
    }

    public ContractStatus status() {
        return status;
    }

    public FailureCode failureCode() {
        return failureCode;
    }

    /**
     * 契约的有限重试、重规划与超时策略。
     */
    public record FailurePolicy(int maxLocalRetries, int maxLlmReplans, int timeoutSeconds) {
        public static final FailurePolicy DEFAULT = new FailurePolicy(2, 1, 90);

        public FailurePolicy {
            if (maxLocalRetries < 0 || maxLlmReplans < 0 || timeoutSeconds <= 0) {
                throw new IllegalArgumentException("Failure policy values are out of range");
            }
        }
    }
}
