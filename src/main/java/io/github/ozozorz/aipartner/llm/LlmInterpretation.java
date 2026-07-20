package io.github.ozozorz.aipartner.llm;

import io.github.ozozorz.aipartner.contract.JobSpec;
import org.jspecify.annotations.Nullable;

/**
 * 通过严格结构校验后的模型语义结果。
 */
public record LlmInterpretation(
        DialogueAct dialogueAct,
        @Nullable JobSpec candidateJob,
        @Nullable String clarificationQuestion
) {
}

