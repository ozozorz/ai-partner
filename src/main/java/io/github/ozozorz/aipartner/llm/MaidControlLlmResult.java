package io.github.ozozorz.aipartner.llm;

import io.github.ozozorz.aipartner.control.MaidControlInterpretation;
import org.jspecify.annotations.Nullable;

/** Normalized result of one gameplay control-model request. */
public record MaidControlLlmResult(
        boolean successful,
        @Nullable MaidControlInterpretation interpretation,
        String rawOutput,
        String model,
        long latencyMillis,
        int attempts,
        int inputTokens,
        int outputTokens,
        @Nullable String errorCode
) {
    public static MaidControlLlmResult failed(
            String model,
            long latencyMillis,
            int attempts,
            String rawOutput,
            String errorCode
    ) {
        return new MaidControlLlmResult(
                false,
                null,
                rawOutput,
                model,
                latencyMillis,
                attempts,
                0,
                0,
                errorCode
        );
    }
}
