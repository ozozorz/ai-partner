package io.github.ozozorz.aipartner.workflow;

/** Persisted lifecycle state of a bounded semantic-action workflow. */
public enum MaidWorkflowStatus {
    RUNNING,
    WAITING_ACTION,
    WAITING_REPLAN,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isActive() {
        return this == RUNNING || this == WAITING_ACTION || this == WAITING_REPLAN;
    }
}
