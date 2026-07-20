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

