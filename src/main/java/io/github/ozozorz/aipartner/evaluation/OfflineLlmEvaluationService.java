package io.github.ozozorz.aipartner.evaluation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.ozozorz.aipartner.AiPartnerMod;
import io.github.ozozorz.aipartner.config.AiPartnerConfig;
import io.github.ozozorz.aipartner.evaluation.OfflineMetricsCalculator.Metrics;
import io.github.ozozorz.aipartner.experiment.ExperimentProtocol;
import io.github.ozozorz.aipartner.llm.LlmCallResult;
import io.github.ozozorz.aipartner.llm.LlmGateway;
import io.github.ozozorz.aipartner.world.WorldStateSummary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * 顺序运行冻结 72 指令的固定模型评测，并实施逐请求限速、重试、断点与保守费用预留。
 */
public final class OfflineLlmEvaluationService {
    private static final OfflineLlmEvaluationService INSTANCE = new OfflineLlmEvaluationService();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Gson LINE_GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int RESERVED_INPUT_TOKENS_PER_ATTEMPT = 4096;
    private static final WorldStateSummary FROZEN_OFFLINE_WORLD_STATE = new WorldStateSummary(
            "minecraft:overworld",
            "0.00,64.00,0.00",
            "0.00,64.00,0.00",
            0.0,
            20.0F,
            20.0F,
            true,
            "IDLE",
            "NONE",
            "NONE",
            true
    );
    private static final Path ROOT = FabricLoader.getInstance()
            .getGameDir()
            .resolve("logs")
            .resolve("ai-partner")
            .resolve("evaluation")
            .resolve("model-runs");

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("ai-partner-offline-evaluation").factory()
    );
    private @Nullable ActiveRun active;
    private @Nullable ScheduledFuture<?> scheduled;
    private @Nullable CompletableFuture<LlmCallResult> inFlight;

    private OfflineLlmEvaluationService() {
    }

    public static OfflineLlmEvaluationService getInstance() {
        return INSTANCE;
    }

    /**
     * 启动前 limit 条（1-72）；预实验可用小 limit，正式离线评测使用 72。
     */
    public synchronized OperationResult start(
            int limit,
            double costCapUsd,
            @Nullable String requestedRunId
    ) {
        if (active != null) {
            return OperationResult.failed("Another offline evaluation is active");
        }
        if (limit < 1 || limit > 72) {
            return OperationResult.failed("Offline evaluation limit must be between 1 and 72");
        }
        if (!(costCapUsd > 0.0) || costCapUsd > 100.0) {
            return OperationResult.failed("Cost cap must be greater than 0 and at most 100 USD");
        }
        try {
            ExperimentProtocol.Snapshot snapshot = ExperimentProtocol.verifyAndSnapshot(AiPartnerConfig.get());
            if (!LlmGateway.getInstance().isEnabled()) {
                return OperationResult.failed("Frozen LLM is not configured");
            }
            OfflineEvaluationDataset.Loaded dataset = OfflineEvaluationDataset.load();
            List<OfflineEvaluationCase> cases = dataset.cases().subList(0, limit);
            String runId = requestedRunId == null || requestedRunId.isBlank()
                    ? generateRunId()
                    : requestedRunId;
            validateRunId(runId);
            Path directory = ROOT.resolve(runId);
            if (Files.exists(directory)) {
                return OperationResult.failed("Offline run id already exists; use resume");
            }
            Files.createDirectories(directory);
            Manifest manifest = new Manifest(
                    "0.4",
                    Instant.now().toString(),
                    runId,
                    snapshot.fingerprint(),
                    dataset.version(),
                    dataset.sha256(),
                    LlmGateway.getInstance().model(),
                    LlmGateway.getInstance().promptHash(),
                    ExperimentProtocol.definition().temperature(),
                    ExperimentProtocol.definition().llmTimeoutSeconds(),
                    ExperimentProtocol.definition().offlineRequestsPerMinute(),
                    ExperimentProtocol.definition().offlineMaxAttemptsPerCase(),
                    costCapUsd,
                    ExperimentProtocol.definition().accountingInputUsdPerMillionTokens(),
                    ExperimentProtocol.definition().accountingOutputUsdPerMillionTokens(),
                    RESERVED_INPUT_TOKENS_PER_ATTEMPT,
                    ExperimentProtocol.definition().maxOutputTokens(),
                    cases.stream().map(OfflineEvaluationCase::id).toList()
            );
            Checkpoint checkpoint = Checkpoint.create(runId);
            writeJson(directory.resolve("manifest.json"), manifest);
            writeJson(directory.resolve("checkpoint.json"), checkpoint);
            active = new ActiveRun(directory, dataset, List.copyOf(cases), manifest, checkpoint, new LinkedHashMap<>());
            scheduleNext(0L);
            return OperationResult.succeeded(runId, limit, 0, costCapUsd);
        } catch (IOException | RuntimeException exception) {
            active = null;
            return OperationResult.failed(exception.getMessage());
        }
    }

    /**
     * 根据 manifest、predictions.jsonl 和 checkpoint 恢复未完成的固定运行。
     */
    public synchronized OperationResult resume(String runId) {
        if (active != null) {
            return OperationResult.failed("Another offline evaluation is active");
        }
        try {
            validateRunId(runId);
            Path directory = ROOT.resolve(runId);
            Manifest manifest = readJson(directory.resolve("manifest.json"), Manifest.class);
            Checkpoint checkpoint = readJson(directory.resolve("checkpoint.json"), Checkpoint.class);
            if ("COMPLETED".equals(checkpoint.status()) || "CANCELLED".equals(checkpoint.status())) {
                return OperationResult.failed("Offline run is already terminal: " + checkpoint.status());
            }
            ExperimentProtocol.Snapshot snapshot = ExperimentProtocol.verifyAndSnapshot(AiPartnerConfig.get());
            if (!snapshot.fingerprint().equals(manifest.protocolFingerprint())) {
                return OperationResult.failed("Frozen protocol changed since this offline run started");
            }
            if (!LlmGateway.getInstance().isEnabled()) {
                return OperationResult.failed("Frozen LLM is not configured");
            }
            OfflineEvaluationDataset.Loaded dataset = OfflineEvaluationDataset.load();
            if (!dataset.sha256().equals(manifest.datasetSha256())) {
                return OperationResult.failed("Offline dataset changed since this run started");
            }
            Map<String, OfflineEvaluationCase> byId = new LinkedHashMap<>();
            dataset.cases().forEach(value -> byId.put(value.id(), value));
            List<OfflineEvaluationCase> cases = manifest.caseIds().stream().map(id -> {
                OfflineEvaluationCase value = byId.get(id);
                if (value == null) {
                    throw new IllegalStateException("Missing frozen case " + id);
                }
                return value;
            }).toList();
            LinkedHashMap<String, OfflineModelPrediction> predictions = readPredictions(directory);
            validatePredictionPrefix(cases, predictions);
            String pendingCaseId = null;
            int pendingCaseAttempts = 0;
            if (predictions.size() < cases.size()
                    && checkpoint.currentCaseId() != null
                    && checkpoint.currentCaseId().equals(cases.get(predictions.size()).id())) {
                pendingCaseId = checkpoint.currentCaseId();
                pendingCaseAttempts = checkpoint.currentCaseAttempts();
            }
            Checkpoint reconciled = checkpoint.withProcessed(
                    predictions.size(),
                    pendingCaseId,
                    pendingCaseAttempts,
                    "RUNNING",
                    "resumed"
            );
            writeJson(directory.resolve("checkpoint.json"), reconciled);
            active = new ActiveRun(directory, dataset, cases, manifest, reconciled, predictions);
            scheduleNext(0L);
            return OperationResult.succeeded(
                    runId,
                    cases.size(),
                    predictions.size(),
                    manifest.costCapUsd()
            );
        } catch (IOException | RuntimeException exception) {
            active = null;
            return OperationResult.failed(exception.getMessage());
        }
    }

    /**
     * 取消后保留所有已写预测和费用预留，不删除可审计数据。
     */
    public synchronized OperationResult cancel() {
        if (active == null) {
            return OperationResult.failed("No active offline evaluation");
        }
        cancelPendingWork();
        try {
            active.checkpoint = active.checkpoint.withStatus("CANCELLED", "cancelled_by_user");
            writeJson(active.directory.resolve("checkpoint.json"), active.checkpoint);
            writeMetrics(active);
            OperationResult result = OperationResult.succeeded(
                    active.manifest.runId(),
                    active.cases.size(),
                    active.predictions.size(),
                    active.manifest.costCapUsd()
            );
            active = null;
            return result;
        } catch (IOException exception) {
            return OperationResult.failed(exception.getMessage());
        }
    }

    public synchronized Status status() {
        if (active == null) {
            return new Status(false, "", "IDLE", 0, 0, 0, 0.0, 0.0);
        }
        return new Status(
                true,
                active.manifest.runId(),
                active.checkpoint.status(),
                active.predictions.size(),
                active.cases.size(),
                active.checkpoint.totalAttempts(),
                active.checkpoint.reservedCostUsd(),
                active.manifest.costCapUsd()
        );
    }

    /**
     * 服务器停止时保存暂停状态并取消网络请求，避免后台线程继续计费。
     */
    public synchronized void pauseForServerStop() {
        if (active == null) {
            return;
        }
        cancelPendingWork();
        try {
            active.checkpoint = active.checkpoint.withStatus("PAUSED", "server_stopping");
            writeJson(active.directory.resolve("checkpoint.json"), active.checkpoint);
            writeMetrics(active);
        } catch (IOException exception) {
            AiPartnerMod.LOGGER.error("Failed to checkpoint offline evaluation during server stop", exception);
        }
        active = null;
    }

    private synchronized void runNext() {
        scheduled = null;
        if (active == null || !"RUNNING".equals(active.checkpoint.status()) || inFlight != null) {
            return;
        }
        if (active.predictions.size() >= active.cases.size()) {
            finishCompleted();
            return;
        }
        OfflineEvaluationCase evaluationCase = active.cases.get(active.predictions.size());
        int priorAttempts = evaluationCase.id().equals(active.checkpoint.currentCaseId())
                ? active.checkpoint.currentCaseAttempts()
                : 0;
        double reserve = reservedCostPerAttempt(active.manifest);
        if (active.checkpoint.reservedCostUsd() + reserve > active.manifest.costCapUsd() + 1.0e-9) {
            pauseAtCostCap();
            return;
        }

        int attempt = priorAttempts + 1;
        active.checkpoint = active.checkpoint.beforeAttempt(evaluationCase.id(), attempt, reserve);
        try {
            writeJson(active.directory.resolve("checkpoint.json"), active.checkpoint);
        } catch (IOException exception) {
            failRun("checkpoint_before_request_failed");
            return;
        }
        long started = System.currentTimeMillis();
        inFlight = LlmGateway.getInstance().interpretOnce(
                evaluationCase.instruction(),
                FROZEN_OFFLINE_WORLD_STATE
        );
        inFlight.whenComplete((result, throwable) -> scheduler.execute(
                () -> handleAttempt(evaluationCase, attempt, started, result, throwable)
        ));
    }

    private synchronized void handleAttempt(
            OfflineEvaluationCase evaluationCase,
            int attempt,
            long startedEpochMillis,
            @Nullable LlmCallResult result,
            @Nullable Throwable throwable
    ) {
        inFlight = null;
        if (active == null || !evaluationCase.id().equals(active.checkpoint.currentCaseId())) {
            return;
        }
        LlmCallResult effective = result;
        if (effective == null) {
            effective = LlmCallResult.failed(
                    active.manifest.model(),
                    active.manifest.promptHash(),
                    Math.max(0L, System.currentTimeMillis() - startedEpochMillis),
                    1,
                    "",
                    throwable == null ? "UNKNOWN" : "CALLBACK_ERROR"
            );
        }
        double observedCost = observedCost(effective, active.manifest);
        active.checkpoint = active.checkpoint.afterAttempt(
                effective.inputTokens(),
                effective.outputTokens(),
                observedCost
        );
        try {
            appendLine(active.directory.resolve("attempts.jsonl"), new AttemptRecord(
                    Instant.now().toString(),
                    evaluationCase.id(),
                    attempt,
                    effective.successful(),
                    effective.errorCode(),
                    effective.latencyMillis(),
                    effective.inputTokens(),
                    effective.outputTokens(),
                    observedCost,
                    active.checkpoint.reservedCostUsd(),
                    effective.rawOutput()
            ));
            writeJson(active.directory.resolve("checkpoint.json"), active.checkpoint);
        } catch (IOException exception) {
            failRun("attempt_log_failed");
            return;
        }

        boolean retry = !effective.successful()
                && attempt < active.manifest.maxAttemptsPerCase()
                && isRetryable(effective.errorCode());
        if (retry) {
            scheduleRateLimitedNext();
            return;
        }

        OfflineModelPrediction prediction = toPrediction(evaluationCase, effective, attempt, observedCost);
        try {
            appendLine(active.directory.resolve("predictions.jsonl"), prediction);
            active.predictions.put(evaluationCase.id(), prediction);
            active.checkpoint = active.checkpoint.withProcessed(
                    active.predictions.size(),
                    null,
                    0,
                    "RUNNING",
                    "case_complete"
            );
            writeJson(active.directory.resolve("checkpoint.json"), active.checkpoint);
            writeMetrics(active);
        } catch (IOException exception) {
            failRun("prediction_checkpoint_failed");
            return;
        }
        scheduleRateLimitedNext();
    }

    private void scheduleRateLimitedNext() {
        long interval = Math.max(1L, 60_000L / active.manifest.requestsPerMinute());
        scheduleNext(interval);
    }

    private synchronized void scheduleNext(long delayMillis) {
        if (active == null) {
            return;
        }
        scheduled = scheduler.schedule(this::runNext, delayMillis, TimeUnit.MILLISECONDS);
    }

    private synchronized void finishCompleted() {
        if (active == null) {
            return;
        }
        try {
            active.checkpoint = active.checkpoint.withStatus("COMPLETED", "all_cases_complete");
            writeJson(active.directory.resolve("checkpoint.json"), active.checkpoint);
            writeMetrics(active);
        } catch (IOException exception) {
            AiPartnerMod.LOGGER.error("Failed to finalize offline evaluation", exception);
            return;
        }
        active = null;
    }

    private synchronized void pauseAtCostCap() {
        if (active == null) {
            return;
        }
        try {
            active.checkpoint = active.checkpoint.withStatus("PAUSED_COST_CAP", "cost_cap_reached_before_request");
            writeJson(active.directory.resolve("checkpoint.json"), active.checkpoint);
            writeMetrics(active);
        } catch (IOException exception) {
            AiPartnerMod.LOGGER.error("Failed to persist cost-capped offline evaluation", exception);
        }
        active = null;
    }

    private synchronized void failRun(String reason) {
        if (active == null) {
            return;
        }
        try {
            active.checkpoint = active.checkpoint.withStatus("ERROR", reason);
            writeJson(active.directory.resolve("checkpoint.json"), active.checkpoint);
            writeMetrics(active);
        } catch (IOException exception) {
            AiPartnerMod.LOGGER.error("Failed to persist offline evaluation error", exception);
        }
        active = null;
    }

    private static OfflineModelPrediction toPrediction(
            OfflineEvaluationCase evaluationCase,
            LlmCallResult result,
            int attempts,
            double observedCost
    ) {
        return new OfflineModelPrediction(
                evaluationCase.id(),
                evaluationCase.split(),
                evaluationCase.category(),
                evaluationCase.instruction(),
                evaluationCase.goldDialogueAct(),
                evaluationCase.goldJobType(),
                evaluationCase.goldTarget(),
                evaluationCase.goldQuantity(),
                evaluationCase.goldRadius(),
                evaluationCase.shouldClarify(),
                evaluationCase.shouldReject(),
                result.successful(),
                result.interpretation() == null ? null : result.interpretation().dialogueAct(),
                result.interpretation() == null ? null : result.interpretation().candidateJob(),
                result.errorCode(),
                result.model(),
                result.promptHash(),
                attempts,
                result.latencyMillis(),
                result.inputTokens(),
                result.outputTokens(),
                observedCost,
                result.rawOutput()
        );
    }

    private static void writeMetrics(ActiveRun run) throws IOException {
        Metrics metrics = OfflineMetricsCalculator.calculate(
                run.manifest.runId(),
                run.dataset.version(),
                run.dataset.sha256(),
                run.cases.size(),
                List.copyOf(run.predictions.values()),
                run.checkpoint.reservedCostUsd(),
                run.checkpoint.observedEstimatedCostUsd()
        );
        writeJson(run.directory.resolve("metrics.json"), metrics);
    }

    private static LinkedHashMap<String, OfflineModelPrediction> readPredictions(Path directory) throws IOException {
        Path path = directory.resolve("predictions.jsonl");
        LinkedHashMap<String, OfflineModelPrediction> predictions = new LinkedHashMap<>();
        if (!Files.exists(path)) {
            return predictions;
        }
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            try {
                OfflineModelPrediction prediction = LINE_GSON.fromJson(line, OfflineModelPrediction.class);
                if (prediction == null || prediction.id() == null) {
                    throw new IOException("Invalid prediction at line " + lineNumber);
                }
                predictions.putIfAbsent(prediction.id(), prediction);
            } catch (JsonParseException exception) {
                throw new IOException("Invalid prediction JSON at line " + lineNumber, exception);
            }
        }
        return predictions;
    }

    /**
     * 断点文件以 predictions.jsonl 为已完成事实源，并要求其严格对应冻结 case 前缀。
     */
    private static void validatePredictionPrefix(
            List<OfflineEvaluationCase> cases,
            LinkedHashMap<String, OfflineModelPrediction> predictions
    ) throws IOException {
        if (predictions.size() > cases.size()) {
            throw new IOException("Offline predictions exceed the frozen case plan");
        }
        int index = 0;
        for (String predictionId : predictions.keySet()) {
            if (!cases.get(index).id().equals(predictionId)) {
                throw new IOException("Offline predictions are not a prefix of the frozen case plan");
            }
            index++;
        }
    }

    private static boolean isRetryable(@Nullable String errorCode) {
        if (errorCode == null) {
            return false;
        }
        return errorCode.equals("TIMEOUT")
                || errorCode.equals("NETWORK_ERROR")
                || errorCode.equals("INVALID_MODEL_OUTPUT")
                || errorCode.equals("HTTP_429")
                || errorCode.startsWith("HTTP_5");
    }

    private static double reservedCostPerAttempt(Manifest manifest) {
        return RESERVED_INPUT_TOKENS_PER_ATTEMPT * manifest.inputUsdPerMillionTokens() / 1_000_000.0
                + manifest.reservedOutputTokensPerAttempt() * manifest.outputUsdPerMillionTokens() / 1_000_000.0;
    }

    private static double observedCost(LlmCallResult result, Manifest manifest) {
        return result.inputTokens() * manifest.inputUsdPerMillionTokens() / 1_000_000.0
                + result.outputTokens() * manifest.outputUsdPerMillionTokens() / 1_000_000.0;
    }

    private synchronized void cancelPendingWork() {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
        if (inFlight != null) {
            inFlight.cancel(true);
            inFlight = null;
        }
    }

    private static <T> T readJson(Path path, Class<T> type) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("Missing offline evaluation file " + path.getFileName());
        }
        try {
            T value = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), type);
            if (value == null) {
                throw new IOException("Empty offline evaluation file " + path.getFileName());
            }
            return value;
        } catch (JsonParseException exception) {
            throw new IOException("Invalid offline evaluation file " + path.getFileName(), exception);
        }
    }

    private static void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(
                temporary,
                GSON.toJson(value) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void appendLine(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                LINE_GSON.toJson(value) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    private static void validateRunId(String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid offline evaluation run id");
        }
    }

    private static String generateRunId() {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        return "offline-v04-" + timestamp + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private static final class ActiveRun {
        private final Path directory;
        private final OfflineEvaluationDataset.Loaded dataset;
        private final List<OfflineEvaluationCase> cases;
        private final Manifest manifest;
        private Checkpoint checkpoint;
        private final LinkedHashMap<String, OfflineModelPrediction> predictions;

        private ActiveRun(
                Path directory,
                OfflineEvaluationDataset.Loaded dataset,
                List<OfflineEvaluationCase> cases,
                Manifest manifest,
                Checkpoint checkpoint,
                LinkedHashMap<String, OfflineModelPrediction> predictions
        ) {
            this.directory = directory;
            this.dataset = dataset;
            this.cases = cases;
            this.manifest = manifest;
            this.checkpoint = checkpoint;
            this.predictions = predictions;
        }
    }

    private record Manifest(
            String schemaVersion,
            String createdAt,
            String runId,
            String protocolFingerprint,
            String datasetVersion,
            String datasetSha256,
            String model,
            String promptHash,
            double temperature,
            int timeoutSeconds,
            int requestsPerMinute,
            int maxAttemptsPerCase,
            double costCapUsd,
            double inputUsdPerMillionTokens,
            double outputUsdPerMillionTokens,
            int reservedInputTokensPerAttempt,
            int reservedOutputTokensPerAttempt,
            List<String> caseIds
    ) {
        private Manifest {
            caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
        }
    }

    private record Checkpoint(
            String schemaVersion,
            String runId,
            String status,
            int processedCases,
            @Nullable String currentCaseId,
            int currentCaseAttempts,
            int totalAttempts,
            int inputTokens,
            int outputTokens,
            double reservedCostUsd,
            double observedEstimatedCostUsd,
            String updatedAt,
            String note
    ) {
        private static Checkpoint create(String runId) {
            return new Checkpoint("0.4", runId, "RUNNING", 0, null, 0, 0, 0, 0, 0.0, 0.0,
                    Instant.now().toString(), "created");
        }

        private Checkpoint beforeAttempt(String caseId, int caseAttempts, double reserve) {
            return new Checkpoint(schemaVersion, runId, "RUNNING", processedCases, caseId, caseAttempts,
                    totalAttempts + 1, inputTokens, outputTokens, reservedCostUsd + reserve,
                    observedEstimatedCostUsd, Instant.now().toString(), "request_reserved");
        }

        private Checkpoint afterAttempt(int addedInput, int addedOutput, double addedObservedCost) {
            return new Checkpoint(schemaVersion, runId, status, processedCases, currentCaseId, currentCaseAttempts,
                    totalAttempts, inputTokens + addedInput, outputTokens + addedOutput, reservedCostUsd,
                    observedEstimatedCostUsd + addedObservedCost, Instant.now().toString(), "attempt_complete");
        }

        private Checkpoint withProcessed(
                int processed,
                @Nullable String caseId,
                int caseAttempts,
                String status,
                String note
        ) {
            return new Checkpoint(schemaVersion, runId, status, processed, caseId, caseAttempts, totalAttempts,
                    inputTokens, outputTokens, reservedCostUsd, observedEstimatedCostUsd,
                    Instant.now().toString(), note);
        }

        private Checkpoint withStatus(String status, String note) {
            return withProcessed(processedCases, currentCaseId, currentCaseAttempts, status, note);
        }
    }

    private record AttemptRecord(
            String timestamp,
            String caseId,
            int attempt,
            boolean successful,
            @Nullable String errorCode,
            long latencyMillis,
            int inputTokens,
            int outputTokens,
            double observedEstimatedCostUsd,
            double cumulativeReservedCostUsd,
            String rawOutput
    ) {
    }

    public record OperationResult(
            boolean successful,
            String error,
            String runId,
            int plannedCases,
            int completedCases,
            double costCapUsd
    ) {
        private static OperationResult succeeded(
                String runId,
                int plannedCases,
                int completedCases,
                double costCapUsd
        ) {
            return new OperationResult(true, "", runId, plannedCases, completedCases, costCapUsd);
        }

        private static OperationResult failed(@Nullable String error) {
            return new OperationResult(false, error == null ? "unknown_error" : error, "", 0, 0, 0.0);
        }
    }

    public record Status(
            boolean active,
            String runId,
            String status,
            int completedCases,
            int plannedCases,
            int totalAttempts,
            double reservedCostUsd,
            double costCapUsd
    ) {
    }
}
