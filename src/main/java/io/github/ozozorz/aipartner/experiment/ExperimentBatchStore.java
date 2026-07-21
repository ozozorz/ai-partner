package io.github.ozozorz.aipartner.experiment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 原子保存批处理检查点，并以 plan_index 去重追加 episode 结果以支持崩溃后恢复。
 */
public final class ExperimentBatchStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Gson LINE_GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Path ROOT = FabricLoader.getInstance()
            .getGameDir()
            .resolve("logs")
            .resolve("ai-partner")
            .resolve("batches");

    private ExperimentBatchStore() {
    }

    public static Path batchDirectory(String batchId) {
        validateBatchId(batchId);
        return ROOT.resolve(batchId);
    }

    /**
     * 以临时文件替换方式写检查点，避免进程中断留下半截 JSON。
     */
    public static void writeCheckpoint(Checkpoint checkpoint) throws IOException {
        Path directory = batchDirectory(checkpoint.batchId());
        Files.createDirectories(directory);
        writeAtomically(directory.resolve("checkpoint.json"), GSON.toJson(checkpoint) + System.lineSeparator());
    }

    public static Checkpoint readCheckpoint(String batchId) throws IOException {
        Path path = batchDirectory(batchId).resolve("checkpoint.json");
        if (!Files.exists(path)) {
            throw new IOException("Batch checkpoint does not exist: " + batchId);
        }
        try {
            Checkpoint checkpoint = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Checkpoint.class);
            if (checkpoint == null || !batchId.equals(checkpoint.batchId()) || checkpoint.plan() == null) {
                throw new IOException("Batch checkpoint is incomplete: " + batchId);
            }
            return reconcile(checkpoint);
        } catch (JsonParseException exception) {
            throw new IOException("Batch checkpoint is invalid: " + batchId, exception);
        }
    }

    /**
     * 每个 plan_index 只落一行；若结果已先于检查点写入，恢复时不会重复执行计费请求。
     */
    public static void appendResult(String batchId, EpisodeResult result) throws IOException {
        List<EpisodeResult> existing = readResults(batchId);
        if (existing.stream().anyMatch(value -> value.planIndex() == result.planIndex())) {
            return;
        }
        Path path = batchDirectory(batchId).resolve("results.jsonl");
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                LINE_GSON.toJson(result) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    public static List<EpisodeResult> readResults(String batchId) throws IOException {
        Path path = batchDirectory(batchId).resolve("results.jsonl");
        if (!Files.exists(path)) {
            return List.of();
        }
        LinkedHashMap<Integer, EpisodeResult> unique = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            try {
                EpisodeResult result = LINE_GSON.fromJson(line, EpisodeResult.class);
                if (result == null || result.planIndex() < 0) {
                    throw new IOException("Invalid batch result at line " + lineNumber);
                }
                unique.putIfAbsent(result.planIndex(), result);
            } catch (JsonParseException exception) {
                throw new IOException("Invalid batch result JSON at line " + lineNumber, exception);
            }
        }
        return unique.values().stream().sorted(Comparator.comparingInt(EpisodeResult::planIndex)).toList();
    }

    /**
     * 从逐 episode 结果重算汇总，避免断点恢复后内存计数漂移。
     */
    public static Summary writeSummary(Checkpoint checkpoint) throws IOException {
        List<EpisodeResult> results = readResults(checkpoint.batchId());
        LinkedHashMap<String, MutableVariantSummary> mutable = new LinkedHashMap<>();
        for (EpisodeResult result : results) {
            MutableVariantSummary variant = mutable.computeIfAbsent(
                    result.systemVariant(),
                    ignored -> new MutableVariantSummary()
            );
            variant.total++;
            if (result.passed()) {
                variant.passed++;
            }
            if (result.excluded()) {
                variant.excluded++;
            }
            if (result.ibcConsistent()) {
                variant.ibcConsistent++;
            }
            if (result.disturbanceApplied()) {
                variant.disturbanceEpisodes++;
                if (result.runtimeRecoveries() > 0 && "COMPLETED".equals(result.actualOutcome())) {
                    variant.recoveredDisturbances++;
                }
            }
        }
        LinkedHashMap<String, VariantSummary> variants = new LinkedHashMap<>();
        mutable.forEach((name, value) -> variants.put(name, value.freeze()));
        int passed = results.stream().mapToInt(value -> value.passed() ? 1 : 0).sum();
        int excluded = results.stream().mapToInt(value -> value.excluded() ? 1 : 0).sum();
        int ibc = results.stream().mapToInt(value -> value.ibcConsistent() ? 1 : 0).sum();
        Summary summary = new Summary(
                "0.4",
                Instant.now().toString(),
                checkpoint.batchId(),
                checkpoint.batchKind(),
                checkpoint.status(),
                checkpoint.protocolFingerprint(),
                checkpoint.plan().size(),
                results.size(),
                passed,
                results.size() - passed - excluded,
                excluded,
                ratio(ibc, results.size() - excluded),
                Map.copyOf(variants)
        );
        writeAtomically(
                batchDirectory(checkpoint.batchId()).resolve("summary.json"),
                GSON.toJson(summary) + System.lineSeparator()
        );
        return summary;
    }

    private static Checkpoint reconcile(Checkpoint checkpoint) throws IOException {
        List<EpisodeResult> results = readResults(checkpoint.batchId());
        int contiguous = 0;
        for (EpisodeResult result : results) {
            if (result.planIndex() != contiguous) {
                break;
            }
            contiguous++;
        }
        if (contiguous == checkpoint.nextIndex()) {
            return checkpoint;
        }
        return checkpoint.withProgress(contiguous, "PAUSED", "reconciled_from_results");
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validateBatchId(String batchId) {
        if (batchId == null || !batchId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Invalid experiment batch id");
        }
    }

    private static double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 0.0 : (double) numerator / denominator;
    }

    /**
     * 可重建的实验计划项。
     */
    public record PlanItem(String systemVariant, String scenarioId, int repetition) {
    }

    /**
     * 崩溃安全检查点；nextIndex 指向尚未产生结果的第一项。
     */
    public record Checkpoint(
            String schemaVersion,
            String batchId,
            String batchKind,
            String playerId,
            String protocolFingerprint,
            List<PlanItem> plan,
            int nextIndex,
            String status,
            String createdAt,
            String updatedAt,
            String note
    ) {
        public Checkpoint {
            plan = plan == null ? List.of() : List.copyOf(plan);
        }

        public static Checkpoint create(
                String batchId,
                String batchKind,
                UUID playerId,
                String protocolFingerprint,
                List<PlanItem> plan
        ) {
            String now = Instant.now().toString();
            return new Checkpoint(
                    "0.4",
                    batchId,
                    batchKind,
                    playerId.toString(),
                    protocolFingerprint,
                    plan,
                    0,
                    "RUNNING",
                    now,
                    now,
                    "created"
            );
        }

        public Checkpoint withProgress(int nextIndex, String status, String note) {
            return new Checkpoint(
                    schemaVersion,
                    batchId,
                    batchKind,
                    playerId,
                    protocolFingerprint,
                    plan,
                    nextIndex,
                    status,
                    createdAt,
                    Instant.now().toString(),
                    note
            );
        }
    }

    /**
     * 写入 results.jsonl 的紧凑终态，既是断点事实源也是批次统计输入。
     */
    public record EpisodeResult(
            int planIndex,
            String episodeId,
            String systemVariant,
            String scenarioId,
            int repetition,
            String expectedOutcome,
            String actualOutcome,
            boolean passed,
            boolean excluded,
            boolean goalSatisfied,
            boolean safetySatisfied,
            boolean ibcConsistent,
            int runtimeRecoveries,
            boolean disturbanceApplied,
            long durationTicks
    ) {
    }

    public record Summary(
            String schemaVersion,
            String generatedAt,
            String batchId,
            String batchKind,
            String status,
            String protocolFingerprint,
            int plannedEpisodes,
            int completedEpisodes,
            int passed,
            int failed,
            int excluded,
            double ibcr,
            Map<String, VariantSummary> variants
    ) {
    }

    public record VariantSummary(
            int total,
            int passed,
            int excluded,
            double ibcr,
            int disturbanceEpisodes,
            int recoveredDisturbances
    ) {
    }

    private static final class MutableVariantSummary {
        private int total;
        private int passed;
        private int excluded;
        private int ibcConsistent;
        private int disturbanceEpisodes;
        private int recoveredDisturbances;

        private VariantSummary freeze() {
            return new VariantSummary(
                    total,
                    passed,
                    excluded,
                    ratio(ibcConsistent, total - excluded),
                    disturbanceEpisodes,
                    recoveredDisturbances
            );
        }
    }
}
