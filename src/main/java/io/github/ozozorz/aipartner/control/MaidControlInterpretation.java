package io.github.ozozorz.aipartner.control;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** 通过本地或 LLM 严格解析后的自然语言结果。 */
public record MaidControlInterpretation(
        MaidControlDialogueAct dialogueAct,
        @Nullable MaidControlIntent intent,
        @Nullable String responseText
) {
    public MaidControlInterpretation {
        Objects.requireNonNull(dialogueAct, "dialogueAct");
        if (dialogueAct == MaidControlDialogueAct.PROPOSE_INTENT && intent == null) {
            throw new IllegalArgumentException("PROPOSE_INTENT needs an intent");
        }
        if (dialogueAct != MaidControlDialogueAct.PROPOSE_INTENT && intent != null) {
            throw new IllegalArgumentException("Only PROPOSE_INTENT may carry an intent");
        }
        if ((dialogueAct == MaidControlDialogueAct.ASK_CLARIFICATION
                || dialogueAct == MaidControlDialogueAct.SOCIAL_REPLY)
                && (responseText == null || responseText.isBlank())) {
            throw new IllegalArgumentException("Dialogue response text is required");
        }
    }

    public static MaidControlInterpretation propose(MaidControlIntent intent) {
        return new MaidControlInterpretation(MaidControlDialogueAct.PROPOSE_INTENT, intent, null);
    }

    public static MaidControlInterpretation clarify(String question) {
        return new MaidControlInterpretation(MaidControlDialogueAct.ASK_CLARIFICATION, null, question);
    }

    public static MaidControlInterpretation reject() {
        return new MaidControlInterpretation(MaidControlDialogueAct.REJECT_UNSUPPORTED, null, null);
    }

    public static MaidControlInterpretation social(String reply) {
        return new MaidControlInterpretation(MaidControlDialogueAct.SOCIAL_REPLY, null, reply);
    }
}
