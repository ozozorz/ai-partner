package io.github.ozozorz.aipartner.llm;

import io.github.ozozorz.aipartner.control.MaidControlDialogueAct;
import io.github.ozozorz.aipartner.control.MaidControlInterpretation;
import org.jspecify.annotations.Nullable;

/**
 * Trusted reason for asking the model to produce a protocol response, including the
 * server-side shape constraint that is checked independently of the prompt.
 */
public enum MaidLlmRequestKind {
    PLAYER_MESSAGE(null, 0, MaidControlInterpretation.MAX_PLAN_STEPS),
    WORKFLOW_REPLAN(MaidControlDialogueAct.PROPOSE_PLAN, 1, MaidControlInterpretation.MAX_PLAN_STEPS),
    WORKFLOW_OUTCOME(MaidControlDialogueAct.SOCIAL_REPLY, 0, 0);

    private final @Nullable MaidControlDialogueAct requiredDialogueAct;
    private final int minimumPlanSteps;
    private final int maximumPlanSteps;

    MaidLlmRequestKind(
            @Nullable MaidControlDialogueAct requiredDialogueAct,
            int minimumPlanSteps,
            int maximumPlanSteps
    ) {
        this.requiredDialogueAct = requiredDialogueAct;
        this.minimumPlanSteps = minimumPlanSteps;
        this.maximumPlanSteps = maximumPlanSteps;
    }

    public @Nullable MaidControlDialogueAct requiredDialogueAct() {
        return requiredDialogueAct;
    }

    public int minimumPlanSteps() {
        return minimumPlanSteps;
    }

    public int maximumPlanSteps() {
        return maximumPlanSteps;
    }

    /** Verifies that a schema-valid model result is also valid for this server request kind. */
    public boolean accepts(MaidControlInterpretation interpretation) {
        if (requiredDialogueAct != null && interpretation.dialogueAct() != requiredDialogueAct) {
            return false;
        }
        int planSize = interpretation.plan().size();
        return planSize >= minimumPlanSteps && planSize <= maximumPlanSteps;
    }
}
