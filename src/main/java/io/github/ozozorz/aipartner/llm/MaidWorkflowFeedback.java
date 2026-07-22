package io.github.ozozorz.aipartner.llm;

import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.core.event.MaidWorkflowLifecycleEvent;
import java.util.List;
import java.util.UUID;

/** Bounded server-authoritative evidence supplied for replanning or result narration. */
public record MaidWorkflowFeedback(
        UUID workflowId,
        String event,
        String status,
        int stepIndex,
        int stepCount,
        int replansUsed,
        FailureCode failureCode,
        String originalRequest,
        String detail,
        List<String> evidence
) {
    public MaidWorkflowFeedback {
        evidence = List.copyOf(evidence.size() <= 16 ? evidence : evidence.subList(evidence.size() - 16, evidence.size()));
    }

    /** Converts an immutable domain event without trusting any model-provided result text. */
    public static MaidWorkflowFeedback from(MaidWorkflowLifecycleEvent event) {
        return new MaidWorkflowFeedback(
                event.workflowId(),
                event.event(),
                event.status().name(),
                event.stepIndex(),
                event.stepCount(),
                event.replansUsed(),
                event.failureCode(),
                event.originalRequest(),
                event.detail(),
                event.evidence()
        );
    }
}
