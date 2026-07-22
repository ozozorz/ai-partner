package io.github.ozozorz.aipartner.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ozozorz.aipartner.job.JobType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证契约只能沿受控生命周期进入完成、失败或取消终态。
 */
class TaskContractTest {
    @Test
    void acceptedContractCanRunAndComplete() {
        TaskContract contract = newAcceptedContract();

        contract.markRunning();
        contract.markCompleted();

        assertEquals(ContractStatus.COMPLETED, contract.status());
        assertEquals(FailureCode.NONE, contract.failureCode());
    }

    @Test
    void cannotClaimCompletionBeforeExecutionStarts() {
        TaskContract contract = newAcceptedContract();

        assertThrows(IllegalStateException.class, contract::markCompleted);
        assertEquals(ContractStatus.ACCEPTED, contract.status());
    }

    @Test
    void runtimeFailureStoresTypedReason() {
        TaskContract contract = newAcceptedContract();

        contract.markRunning();
        contract.markFailed(FailureCode.PATH_UNREACHABLE);

        assertEquals(ContractStatus.FAILED, contract.status());
        assertEquals(FailureCode.PATH_UNREACHABLE, contract.failureCode());
    }

    @Test
    void restoredContractKeepsPersistedFailurePolicy() {
        TaskContract.FailurePolicy policy = new TaskContract.FailurePolicy(4, 150);

        TaskContract contract = TaskContract.restored(
                java.util.UUID.randomUUID(),
                JobSpec.basic(JobType.FOLLOW),
                123L,
                ContractStatus.RUNNING,
                FailureCode.NONE,
                policy
        );

        assertEquals(policy, contract.failurePolicy());
    }

    @Test
    void restoredContractKeepsCompleteAuditPredicates() {
        List<String> preconditions = List.of("owner_is_online", "inventory_has_capacity(8)");
        List<String> goals = List.of("maid_inventory_delta(minecraft:oak_log) >= 8");
        List<String> invariants = List.of("only_break_target_block", "distance_from_origin <= 16");

        TaskContract contract = TaskContract.restored(
                java.util.UUID.randomUUID(),
                new JobSpec(JobType.COLLECT_BLOCK, "minecraft:oak_log", 8, 16),
                preconditions,
                goals,
                invariants,
                123L,
                ContractStatus.RUNNING,
                FailureCode.NONE,
                TaskContract.FailurePolicy.DEFAULT
        );

        assertEquals(preconditions, contract.preconditions());
        assertEquals(goals, contract.goalPredicates());
        assertEquals(invariants, contract.invariants());
    }

    @Test
    void rejectsUnpersistablePredicateText() {
        assertThrows(IllegalArgumentException.class, () -> TaskContract.accepted(
                JobSpec.basic(JobType.FOLLOW),
                List.of(" "),
                List.of("maintain_distance_to_owner"),
                List.of("do_not_modify_world"),
                TaskContract.FailurePolicy.DEFAULT
        ));
    }

    private static TaskContract newAcceptedContract() {
        return TaskContract.accepted(
                JobSpec.basic(JobType.FOLLOW),
                List.of("owner_is_online"),
                List.of("maintain_distance_to_owner"),
                List.of("do_not_modify_world"),
                TaskContract.FailurePolicy.DEFAULT
        );
    }
}
