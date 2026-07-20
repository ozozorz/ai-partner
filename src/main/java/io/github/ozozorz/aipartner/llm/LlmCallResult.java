package io.github.ozozorz.aipartner.llm;

import org.jspecify.annotations.Nullable;

/**
 * 模型调用的可记录结果，包含延迟、Token、原始输出和结构化解释。
 */
public record LlmCallResult(
        boolean successful,
        @Nullable LlmInterpretation interpretation,
        String rawOutput,
        String model,
        String promptHash,
        long latencyMillis,
        int inputTokens,
        int outputTokens,
        @Nullable String errorCode
) {
    /**
     * 创建不会泄露异常细节的失败结果。
     */
    public static LlmCallResult failed(
            String model,
            String promptHash,
            long latencyMillis,
            String rawOutput,
            String errorCode
    ) {
        return new LlmCallResult(
                false,
                null,
                rawOutput,
                model,
                promptHash,
                latencyMillis,
                0,
                0,
                errorCode
        );
    }
}

