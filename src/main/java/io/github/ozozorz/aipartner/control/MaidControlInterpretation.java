package io.github.ozozorz.aipartner.control;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Strict result produced by either the offline parser or the LLM dialogue-and-plan protocol. */
public record MaidControlInterpretation(
        MaidControlDialogueAct dialogueAct,
        List<MaidControlIntent> plan,
        @Nullable String responseText
) {
    public static final int MAX_PLAN_STEPS = 6;

    public MaidControlInterpretation {
        Objects.requireNonNull(dialogueAct, "dialogueAct");
        plan = List.copyOf(Objects.requireNonNull(plan, "plan"));
        if (plan.size() > MAX_PLAN_STEPS) {
            throw new IllegalArgumentException("Plan exceeds bounded workflow size");
        }
        switch (dialogueAct) {
            case PROPOSE_INTENT -> {
                if (plan.size() != 1 || responseText != null) {
                    throw new IllegalArgumentException("PROPOSE_INTENT needs exactly one offline intent");
                }
            }
            case PROPOSE_PLAN -> {
                if (plan.isEmpty() || responseText == null || responseText.isBlank()) {
                    throw new IllegalArgumentException("PROPOSE_PLAN needs actions and a natural response");
                }
            }
            case ASK_CLARIFICATION, SOCIAL_REPLY -> {
                if (!plan.isEmpty() || responseText == null || responseText.isBlank()) {
                    throw new IllegalArgumentException("Dialogue response text is required without a plan");
                }
            }
            case REJECT_UNSUPPORTED -> {
                if (!plan.isEmpty()) {
                    throw new IllegalArgumentException("Unsupported response cannot carry actions");
                }
            }
        }
    }

    /** Compatibility accessor for deterministic single-intent callers. */
    public @Nullable MaidControlIntent intent() {
        return plan.size() == 1 ? plan.getFirst() : null;
    }

    public static MaidControlInterpretation propose(MaidControlIntent intent) {
        return new MaidControlInterpretation(
                MaidControlDialogueAct.PROPOSE_INTENT,
                List.of(Objects.requireNonNull(intent, "intent")),
                null
        );
    }

    public static MaidControlInterpretation plan(List<MaidControlIntent> actions, String response) {
        return new MaidControlInterpretation(MaidControlDialogueAct.PROPOSE_PLAN, actions, response);
    }

    public static MaidControlInterpretation clarify(String question) {
        return new MaidControlInterpretation(MaidControlDialogueAct.ASK_CLARIFICATION, List.of(), question);
    }

    public static MaidControlInterpretation reject() {
        return new MaidControlInterpretation(MaidControlDialogueAct.REJECT_UNSUPPORTED, List.of(), null);
    }

    public static MaidControlInterpretation reject(String response) {
        return new MaidControlInterpretation(MaidControlDialogueAct.REJECT_UNSUPPORTED, List.of(), response);
    }

    public static MaidControlInterpretation social(String reply) {
        return new MaidControlInterpretation(MaidControlDialogueAct.SOCIAL_REPLY, List.of(), reply);
    }
}
