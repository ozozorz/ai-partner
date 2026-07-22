package io.github.ozozorz.aipartner.workflow;

import io.github.ozozorz.aipartner.control.MaidActionRegistry;
import io.github.ozozorz.aipartner.control.MaidControlIntent;
import io.github.ozozorz.aipartner.job.JobType;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable, bounded plan contract proposed by an interpreter and owned by one player. */
public record MaidWorkflowSpec(
        UUID workflowId,
        UUID ownerId,
        String sourceId,
        String originalRequest,
        List<MaidControlIntent> steps,
        int maxReplans,
        int timeoutSeconds
) {
    public static final int MAX_STEPS = 6;
    public static final int MAX_REPLANS = 1;
    public static final int MAX_TIMEOUT_SECONDS = 600;

    public MaidWorkflowSpec {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(ownerId, "ownerId");
        sourceId = bounded(sourceId, 64);
        originalRequest = bounded(originalRequest, 512);
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (sourceId.isBlank() || steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("Workflow must have a source and 1..6 steps");
        }
        if (maxReplans < 0 || maxReplans > MAX_REPLANS
                || timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("Workflow recovery or timeout boundary is invalid");
        }
        for (int index = 0; index < steps.size(); index++) {
            MaidControlIntent step = Objects.requireNonNull(steps.get(index), "workflow step");
            MaidActionRegistry.contractFor(step);
            if (index < steps.size() - 1
                    && step instanceof MaidControlIntent.RunTask runTask
                    && (runTask.job().type() == JobType.FOLLOW || runTask.job().type() == JobType.STAY)) {
                throw new IllegalArgumentException("Persistent FOLLOW/STAY must be the final workflow step");
            }
        }
    }

    /** Creates the standard bounded contract for an LLM-proposed plan. */
    public static MaidWorkflowSpec llm(UUID ownerId, String originalRequest, List<MaidControlIntent> steps) {
        return new MaidWorkflowSpec(
                UUID.randomUUID(),
                ownerId,
                "LLM_NL",
                originalRequest,
                steps,
                MAX_REPLANS,
                MAX_TIMEOUT_SECONDS
        );
    }

    /**
     * Returns true only when every still-required semantic goal appears unchanged and in order
     * in a replacement plan. Extra preparatory actions are allowed, but replanning cannot erase,
     * weaken, mutate, or reorder an unfinished obligation.
     */
    public static boolean preservesOrderedGoals(
            List<MaidControlIntent> replacement,
            List<MaidControlIntent> pendingGoals
    ) {
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(pendingGoals, "pendingGoals");
        int goalIndex = 0;
        for (MaidControlIntent action : replacement) {
            if (goalIndex < pendingGoals.size() && pendingGoals.get(goalIndex).equals(action)) {
                goalIndex++;
            }
        }
        return goalIndex == pendingGoals.size();
    }

    private static String bounded(String value, int maximumLength) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        return normalized.length() <= maximumLength ? normalized : normalized.substring(0, maximumLength);
    }
}
