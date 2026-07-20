package io.github.ozozorz.aipartner.evaluation;

import com.google.gson.annotations.SerializedName;
import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.llm.DialogueAct;
import org.jspecify.annotations.Nullable;

/**
 * 一条冻结离线中文指令及其人工金标。
 */
public record OfflineEvaluationCase(
        String id,
        String split,
        String category,
        String instruction,
        @SerializedName("gold_dialogue_act")
        DialogueAct goldDialogueAct,
        @SerializedName("gold_job_type")
        @Nullable JobType goldJobType,
        @SerializedName("gold_target")
        @Nullable String goldTarget,
        @SerializedName("gold_quantity")
        @Nullable Integer goldQuantity,
        @SerializedName("gold_radius")
        @Nullable Integer goldRadius,
        @SerializedName("should_clarify")
        boolean shouldClarify,
        @SerializedName("should_reject")
        boolean shouldReject
) {
}
