package io.github.ozozorz.aipartner.gameplay.task;

import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.core.task.MaidTaskResultSink;
import io.github.ozozorz.aipartner.executor.TaskExecutionListener;
import java.util.Objects;

/**
 * 把底层执行器回调适配到通用任务终态接口。
 */
final class ExecutorResultAdapter implements TaskExecutionListener {
    private final MaidTaskResultSink resultSink;

    ExecutorResultAdapter(MaidTaskResultSink resultSink) {
        this.resultSink = Objects.requireNonNull(resultSink, "resultSink");
    }

    @Override
    public void onCompleted() {
        resultSink.complete();
    }

    @Override
    public void onFailed(FailureCode failureCode) {
        resultSink.fail(failureCode);
    }
}
