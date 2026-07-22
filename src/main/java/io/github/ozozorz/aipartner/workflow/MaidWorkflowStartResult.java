package io.github.ozozorz.aipartner.workflow;

import io.github.ozozorz.aipartner.contract.FailureCode;

/** Admission result for a workflow before any of its steps is dispatched. */
public record MaidWorkflowStartResult(boolean accepted, FailureCode failureCode, String detail) {
    public static MaidWorkflowStartResult success() {
        return new MaidWorkflowStartResult(true, FailureCode.NONE, "workflow_accepted");
    }

    public static MaidWorkflowStartResult rejected(FailureCode code, String detail) {
        return new MaidWorkflowStartResult(false, code, detail);
    }
}
