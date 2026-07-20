package io.github.ozozorz.aipartner.contract;

/**
 * 执行前验证结果；被拒绝时 contract 为 null，执行器不得收到任务。
 */
public record ContractDecision(
        boolean accepted,
        TaskContract contract,
        FailureCode failureCode,
        String messageKey
) {
    /**
     * 构造接受结果。
     */
    public static ContractDecision accepted(TaskContract contract) {
        return new ContractDecision(true, contract, FailureCode.NONE, "message.ai-partner.accepted");
    }

    /**
     * 构造拒绝结果。
     */
    public static ContractDecision rejected(FailureCode failureCode, String messageKey) {
        return new ContractDecision(false, null, failureCode, messageKey);
    }
}

