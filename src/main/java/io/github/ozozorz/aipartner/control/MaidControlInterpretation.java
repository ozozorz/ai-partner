package io.github.ozozorz.aipartner.control;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 本地解析器产生的单意图结果；世界变更仍由服务端契约层验证。
 */
public record MaidControlInterpretation(
        MaidControlDialogueAct dialogueAct,
        @Nullable MaidControlIntent intent,
        @Nullable String responseText
) {
    public MaidControlInterpretation {
        Objects.requireNonNull(dialogueAct, "dialogueAct");
        switch (dialogueAct) {
            case PROPOSE_INTENT -> {
                if (intent == null || responseText != null) {
                    throw new IllegalArgumentException("PROPOSE_INTENT needs exactly one intent");
                }
            }
            case ASK_CLARIFICATION, SOCIAL_REPLY -> {
                if (intent != null || responseText == null || responseText.isBlank()) {
                    throw new IllegalArgumentException("Dialogue text is required without an intent");
                }
            }
            case REJECT_UNSUPPORTED -> {
                if (intent != null) {
                    throw new IllegalArgumentException("Unsupported response cannot carry an intent");
                }
            }
        }
    }

    public static MaidControlInterpretation propose(MaidControlIntent intent) {
        return new MaidControlInterpretation(
                MaidControlDialogueAct.PROPOSE_INTENT,
                Objects.requireNonNull(intent, "intent"),
                null
        );
    }

    public static MaidControlInterpretation clarify(String question) {
        return new MaidControlInterpretation(MaidControlDialogueAct.ASK_CLARIFICATION, null, question);
    }

    public static MaidControlInterpretation reject() {
        return new MaidControlInterpretation(MaidControlDialogueAct.REJECT_UNSUPPORTED, null, null);
    }

    public static MaidControlInterpretation reject(String response) {
        return new MaidControlInterpretation(MaidControlDialogueAct.REJECT_UNSUPPORTED, null, response);
    }

    public static MaidControlInterpretation social(String reply) {
        return new MaidControlInterpretation(MaidControlDialogueAct.SOCIAL_REPLY, null, reply);
    }
}
