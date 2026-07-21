package io.github.ozozorz.aipartner.contract;

import java.util.List;

/**
 * 以统一失败策略创建已接受契约。
 */
public final class ContractAcceptance {
    private ContractAcceptance() {
    }

    public static ContractDecision accept(
            JobSpec candidate,
            List<String> preconditions,
            List<String> goals,
            List<String> invariants
    ) {
        return ContractDecision.accepted(TaskContract.accepted(
                candidate,
                preconditions,
                goals,
                invariants,
                TaskContract.FailurePolicy.DEFAULT
        ));
    }
}
