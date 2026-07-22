package io.github.ozozorz.aipartner.contract;

/**
 * 指令—行为契约的完整生命周期状态。
 */
public enum ContractStatus {
    ACCEPTED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /**
     * 判断契约是否已进入不可继续执行的终态。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
