package io.github.ozozorz.aipartner.executor;

import io.github.ozozorz.aipartner.contract.FailureCode;

/**
 * 接收单阶段执行器的终态结果，使执行器既能独立完成契约，也能被固定流程编排器复用。
 */
public interface TaskExecutionListener {
    /**
     * 在阶段目标谓词成立时调用一次。
     */
    void onCompleted();

    /**
     * 在阶段不可恢复地失败时返回类型化原因。
     */
    void onFailed(FailureCode failureCode);
}
