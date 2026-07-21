package io.github.ozozorz.aipartner.evaluation;

import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.llm.DialogueAct;
import org.jspecify.annotations.Nullable;

/**
 * 一条冻结指令的模型预测、金标和调用成本记录。
 */
public record OfflineModelPrediction(
        String id,
        String split,
        String category,
        String instruction,
        DialogueAct goldDialogueAct,
        @Nullable JobType goldJobType,
        @Nullable String goldTarget,
        @Nullable Integer goldQuantity,
        @Nullable Integer goldRadius,
        boolean shouldClarify,
        boolean shouldReject,
        boolean validJson,
        @Nullable DialogueAct predictedDialogueAct,
        @Nullable JobSpec predictedJob,
        @Nullable String errorCode,
        String model,
        String promptHash,
        int attempts,
        long latencyMillis,
        int inputTokens,
        int outputTokens,
        double observedEstimatedCostUsd,
        String rawOutput
) {
}
