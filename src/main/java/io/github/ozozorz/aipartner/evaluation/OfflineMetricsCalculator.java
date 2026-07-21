package io.github.ozozorz.aipartner.evaluation;

import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.llm.DialogueAct;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 按预注册定义计算 JVR、意图准确率、槽位宏 F1、CCR、URR 与 FRR。
 */
public final class OfflineMetricsCalculator {
    private OfflineMetricsCalculator() {
    }

    /**
     * 对已产生的预测计算整体指标；部分预实验会明确标记 partial=true。
     */
    public static Metrics calculate(
            String runId,
            String datasetVersion,
            String datasetSha256,
            int plannedCases,
            List<OfflineModelPrediction> predictions,
            double reservedCostUsd,
            double observedEstimatedCostUsd
    ) {
        int valid = 0;
        int intentCorrect = 0;
        int clarifyCorrect = 0;
        int clarifyGold = 0;
        int rejectCorrect = 0;
        int rejectGold = 0;
        int executableGold = 0;
        int falseRejects = 0;
        int exactMatches = 0;
        SlotCounter target = new SlotCounter();
        SlotCounter quantity = new SlotCounter();
        SlotCounter radius = new SlotCounter();
        LinkedHashMap<String, MutableCategoryMetrics> categories = new LinkedHashMap<>();

        for (OfflineModelPrediction prediction : predictions) {
            MutableCategoryMetrics category = categories.computeIfAbsent(
                    prediction.category(),
                    ignored -> new MutableCategoryMetrics()
            );
            category.total++;
            if (prediction.validJson()) {
                valid++;
                category.validJson++;
            }
            boolean intent = intentMatches(prediction);
            if (intent) {
                intentCorrect++;
                category.intentCorrect++;
            }
            if (prediction.shouldClarify()) {
                clarifyGold++;
                if (prediction.predictedDialogueAct() == DialogueAct.ASK_CLARIFICATION) {
                    clarifyCorrect++;
                }
            }
            if (prediction.shouldReject()) {
                rejectGold++;
                if (prediction.predictedDialogueAct() == DialogueAct.REJECT_UNSUPPORTED) {
                    rejectCorrect++;
                }
            }
            if (prediction.goldDialogueAct() == DialogueAct.PROPOSE_JOB) {
                executableGold++;
                if (prediction.predictedDialogueAct() == DialogueAct.REJECT_UNSUPPORTED) {
                    falseRejects++;
                }
            }

            JobSpec predictedJob = prediction.predictedDialogueAct() == DialogueAct.PROPOSE_JOB
                    ? prediction.predictedJob()
                    : null;
            target.add(normalize(prediction.goldTarget()), normalize(predictedJob == null ? null : predictedJob.target()));
            quantity.add(prediction.goldQuantity(), normalizeNumber(predictedJob == null ? 0 : predictedJob.quantity()));
            radius.add(prediction.goldRadius(), normalizeNumber(predictedJob == null ? 0 : predictedJob.radius()));
            if (intent && slotsMatch(prediction, predictedJob)) {
                exactMatches++;
                category.exactMatches++;
            }
        }

        LinkedHashMap<String, CategoryMetrics> frozenCategories = new LinkedHashMap<>();
        categories.forEach((name, value) -> frozenCategories.put(name, new CategoryMetrics(
                value.total,
                ratio(value.validJson, value.total),
                ratio(value.intentCorrect, value.total),
                ratio(value.exactMatches, value.total)
        )));
        LinkedHashMap<String, SlotMetrics> slots = new LinkedHashMap<>();
        slots.put("target", target.freeze());
        slots.put("quantity", quantity.freeze());
        slots.put("radius", radius.freeze());
        double slotMacroF1 = slots.values().stream().mapToDouble(SlotMetrics::f1).average().orElse(0.0);
        return new Metrics(
                "0.4",
                Instant.now().toString(),
                runId,
                datasetVersion,
                datasetSha256,
                plannedCases,
                predictions.size(),
                predictions.size() < plannedCases,
                ratio(valid, predictions.size()),
                ratio(intentCorrect, predictions.size()),
                slotMacroF1,
                Map.copyOf(slots),
                ratio(clarifyCorrect, clarifyGold),
                ratio(rejectCorrect, rejectGold),
                ratio(falseRejects, executableGold),
                ratio(exactMatches, predictions.size()),
                reservedCostUsd,
                observedEstimatedCostUsd,
                Map.copyOf(frozenCategories)
        );
    }

    private static boolean intentMatches(OfflineModelPrediction prediction) {
        if (prediction.predictedDialogueAct() != prediction.goldDialogueAct()) {
            return false;
        }
        if (prediction.goldDialogueAct() != DialogueAct.PROPOSE_JOB) {
            return true;
        }
        return prediction.predictedJob() != null
                && prediction.predictedJob().type() == prediction.goldJobType();
    }

    private static boolean slotsMatch(OfflineModelPrediction gold, @Nullable JobSpec predicted) {
        if (gold.goldDialogueAct() != DialogueAct.PROPOSE_JOB) {
            return true;
        }
        return predicted != null
                && java.util.Objects.equals(normalize(gold.goldTarget()), normalize(predicted.target()))
                && java.util.Objects.equals(gold.goldQuantity(), normalizeNumber(predicted.quantity()))
                && java.util.Objects.equals(gold.goldRadius(), normalizeNumber(predicted.radius()));
    }

    private static @Nullable String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static @Nullable Integer normalizeNumber(int value) {
        return value <= 0 ? null : value;
    }

    private static double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 0.0 : (double) numerator / denominator;
    }

    public record Metrics(
            String schemaVersion,
            String generatedAt,
            String runId,
            String datasetVersion,
            String datasetSha256,
            int plannedCases,
            int completedCases,
            boolean partial,
            double jvr,
            double intentAccuracy,
            double slotMacroF1,
            Map<String, SlotMetrics> slots,
            double ccr,
            double urr,
            double frr,
            double exactMatchAccuracy,
            double reservedCostUsd,
            double observedEstimatedCostUsd,
            Map<String, CategoryMetrics> categories
    ) {
    }

    public record SlotMetrics(int truePositive, int falsePositive, int falseNegative, double precision, double recall, double f1) {
    }

    public record CategoryMetrics(int total, double jvr, double intentAccuracy, double exactMatchAccuracy) {
    }

    private static final class SlotCounter {
        private int truePositive;
        private int falsePositive;
        private int falseNegative;

        private void add(@Nullable Object gold, @Nullable Object predicted) {
            if (gold == null && predicted == null) {
                return;
            }
            if (gold != null && gold.equals(predicted)) {
                truePositive++;
                return;
            }
            if (predicted != null) {
                falsePositive++;
            }
            if (gold != null) {
                falseNegative++;
            }
        }

        private SlotMetrics freeze() {
            double precision = ratio(truePositive, truePositive + falsePositive);
            double recall = ratio(truePositive, truePositive + falseNegative);
            double f1 = precision + recall == 0.0 ? 0.0 : 2.0 * precision * recall / (precision + recall);
            return new SlotMetrics(truePositive, falsePositive, falseNegative, precision, recall, f1);
        }
    }

    private static final class MutableCategoryMetrics {
        private int total;
        private int validJson;
        private int intentCorrect;
        private int exactMatches;
    }
}
