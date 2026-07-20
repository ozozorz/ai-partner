package io.github.ozozorz.aipartner.evaluation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.llm.DialogueAct;
import io.github.ozozorz.aipartner.parser.RuleJobParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Collections;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * 导出冻结数据集，并在不调用网络模型的情况下运行可复现的 Rule-BT 基线评测。
 */
public final class OfflineEvaluationService {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Gson LINE_GSON = new GsonBuilder().disableHtmlEscaping().create();

    private OfflineEvaluationService() {
    }

    /**
     * 将数据、逐条预测和汇总指标写入游戏日志目录。
     */
    public static ExportReport exportAndEvaluateRuleBaseline() throws IOException {
        OfflineEvaluationDataset.Loaded dataset = OfflineEvaluationDataset.load();
        Path outputDirectory = FabricLoader.getInstance()
                .getGameDir()
                .resolve("logs")
                .resolve("ai-partner")
                .resolve("evaluation");
        Files.createDirectories(outputDirectory);
        Path datasetPath = outputDirectory.resolve("offline_instructions_v1.jsonl");
        Path predictionPath = outputDirectory.resolve("rule_bt_predictions_v1.jsonl");
        Path metricsPath = outputDirectory.resolve("rule_bt_metrics_v1.json");

        Files.writeString(
                datasetPath,
                dataset.rawJsonl(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        StringBuilder predictions = new StringBuilder();
        LinkedHashMap<String, MutableCategoryMetrics> categoryMetrics = new LinkedHashMap<>();
        int exactMatches = 0;
        for (OfflineEvaluationCase evaluationCase : dataset.cases()) {
            Prediction prediction = predict(evaluationCase);
            predictions.append(LINE_GSON.toJson(prediction)).append(System.lineSeparator());
            MutableCategoryMetrics category = categoryMetrics.computeIfAbsent(
                    evaluationCase.category(),
                    ignored -> new MutableCategoryMetrics()
            );
            category.total++;
            if (prediction.exactMatch()) {
                exactMatches++;
                category.exactMatches++;
            }
        }
        Files.writeString(
                predictionPath,
                predictions,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        LinkedHashMap<String, CategoryMetrics> frozenCategoryMetrics = new LinkedHashMap<>();
        categoryMetrics.forEach((category, values) -> frozenCategoryMetrics.put(
                category,
                new CategoryMetrics(
                        values.total,
                        values.exactMatches,
                        ratio(values.exactMatches, values.total)
                )
        ));
        Metrics metrics = new Metrics(
                Instant.now().toString(),
                dataset.version(),
                dataset.sha256(),
                "RULE_BT",
                dataset.cases().size(),
                exactMatches,
                ratio(exactMatches, dataset.cases().size()),
                Collections.unmodifiableMap(new LinkedHashMap<>(frozenCategoryMetrics))
        );
        Files.writeString(
                metricsPath,
                GSON.toJson(metrics) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        return new ExportReport(datasetPath, predictionPath, metricsPath, metrics);
    }

    private static Prediction predict(OfflineEvaluationCase evaluationCase) {
        Optional<JobSpec> parsed = RuleJobParser.parse(evaluationCase.instruction());
        DialogueAct predictedAct = parsed.isPresent() ? DialogueAct.PROPOSE_JOB : DialogueAct.ASK_CLARIFICATION;
        JobSpec predictedJob = parsed.orElse(null);
        boolean exact = predictedAct == evaluationCase.goldDialogueAct()
                && (predictedAct != DialogueAct.PROPOSE_JOB || jobMatches(evaluationCase, predictedJob));
        return new Prediction(
                evaluationCase.id(),
                evaluationCase.split(),
                evaluationCase.category(),
                evaluationCase.instruction(),
                evaluationCase.goldDialogueAct(),
                evaluationCase.goldJobType(),
                predictedAct,
                predictedJob,
                exact
        );
    }

    private static boolean jobMatches(OfflineEvaluationCase gold, @Nullable JobSpec predicted) {
        if (predicted == null || predicted.type() != gold.goldJobType()) {
            return false;
        }
        if (predicted.type() == JobType.FOLLOW
                || predicted.type() == JobType.STAY
                || predicted.type() == JobType.CANCEL) {
            return true;
        }
        return java.util.Objects.equals(predicted.target(), gold.goldTarget())
                && java.util.Objects.equals(predicted.quantity(), gold.goldQuantity())
                && java.util.Objects.equals(predicted.radius(), gold.goldRadius());
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    /**
     * 导出完成后返回绝对路径和规则基线汇总结果。
     */
    public record ExportReport(Path datasetPath, Path predictionPath, Path metricsPath, Metrics metrics) {
    }

    public record Metrics(
            String generatedAt,
            String datasetVersion,
            String datasetSha256,
            String systemVariant,
            int total,
            int exactMatches,
            double exactMatchAccuracy,
            Map<String, CategoryMetrics> categories
    ) {
    }

    public record CategoryMetrics(int total, int exactMatches, double exactMatchAccuracy) {
    }

    private record Prediction(
            String id,
            String split,
            String category,
            String instruction,
            DialogueAct goldDialogueAct,
            @Nullable JobType goldJobType,
            DialogueAct predictedDialogueAct,
            @Nullable JobSpec predictedJob,
            boolean exactMatch
    ) {
    }

    private static final class MutableCategoryMetrics {
        private int total;
        private int exactMatches;
    }
}
