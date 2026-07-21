package io.github.ozozorz.aipartner.core.task;

import io.github.ozozorz.aipartner.contract.FailureCode;

/**
 * 有限任务向运行时报告唯一终态的窄接口。
 */
public interface MaidTaskResultSink {
    void complete();

    void fail(FailureCode failureCode);
}
