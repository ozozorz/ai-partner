package io.github.ozozorz.aipartner.experiment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.ozozorz.aipartner.config.AiPartnerConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 在预实验通过工程完整性审计后写入只增不改的 v0.4 主实验冻结锁。
 */
public final class ExperimentFreezeService {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final Path LOCK_PATH = FabricLoader.getInstance()
            .getGameDir()
            .resolve("logs")
            .resolve("ai-partner")
            .resolve("frozen-v0.4.json");

    private ExperimentFreezeService() {
    }

    /**
     * 检查预实验覆盖、终态记录和扰动恢复后创建冻结锁；已有锁绝不被静默覆盖。
     */
    public static FreezeLock freeze(String pretestBatchId) throws IOException {
        ExperimentProtocol.Snapshot snapshot = ExperimentProtocol.verifyAndSnapshot(AiPartnerConfig.get());
        Audit audit = auditPretest(pretestBatchId);
        if (Files.exists(LOCK_PATH)) {
            FreezeLock existing = readLock();
            if (!existing.snapshot().fingerprint().equals(snapshot.fingerprint())) {
                throw new IllegalStateException("Existing v0.4 freeze lock has a different protocol fingerprint");
            }
            return existing;
        }
        FreezeLock lock = new FreezeLock("0.4", Instant.now().toString(), pretestBatchId, snapshot, audit);
        writeAtomically(GSON.toJson(lock) + System.lineSeparator());
        return lock;
    }

    /**
     * 主实验启动和断点恢复前复核当前资源未偏离冻结锁。
     */
    public static FreezeLock requireCurrentLock() throws IOException {
        if (!Files.exists(LOCK_PATH)) {
            throw new IllegalStateException("Run and audit a PRETEST batch, then freeze v0.4 before the main experiment");
        }
        FreezeLock lock = readLock();
        ExperimentProtocol.Snapshot current = ExperimentProtocol.verifyAndSnapshot(AiPartnerConfig.get());
        if (!lock.snapshot().fingerprint().equals(current.fingerprint())) {
            throw new IllegalStateException("Prompt, dataset, scenarios, model settings, or safety boundary changed after freeze");
        }
        return lock;
    }

    public static Path lockPath() {
        return LOCK_PATH;
    }

    private static Audit auditPretest(String batchId) throws IOException {
        ExperimentBatchStore.Checkpoint checkpoint = ExperimentBatchStore.readCheckpoint(batchId);
        List<ExperimentBatchStore.EpisodeResult> results = ExperimentBatchStore.readResults(batchId);
        if (!"PRETEST".equals(checkpoint.batchKind())
                || !"COMPLETED".equals(checkpoint.status())
                || results.size() != checkpoint.plan().size()) {
            throw new IllegalStateException("Pretest batch is not complete");
        }
        long ruleCount = results.stream().filter(value -> value.systemVariant().equals("RULE_BT")).count();
        long schemaCount = results.stream().filter(value -> value.systemVariant().equals("LLM_SCHEMA")).count();
        long ibcCount = results.stream().filter(value -> value.systemVariant().equals("MAID_IBC")).count();
        long rulePassed = results.stream()
                .filter(value -> value.systemVariant().equals("RULE_BT") && value.passed())
                .count();
        long schemaPassed = results.stream()
                .filter(value -> value.systemVariant().equals("LLM_SCHEMA") && value.passed())
                .count();
        long ibcPassed = results.stream()
                .filter(value -> value.systemVariant().equals("MAID_IBC") && value.passed())
                .count();
        long excluded = results.stream().filter(ExperimentBatchStore.EpisodeResult::excluded).count();
        long disturbed = results.stream().filter(ExperimentBatchStore.EpisodeResult::disturbanceApplied).count();
        long ibcRecoveries = results.stream()
                .filter(value -> value.systemVariant().equals("MAID_IBC"))
                .filter(value -> value.scenarioId().equals("recoverable_target_change"))
                .filter(value -> value.runtimeRecoveries() > 0)
                .count();
        if (ruleCount != 18 || schemaCount < 4 || ibcCount < 4) {
            throw new IllegalStateException("Pretest coverage is incomplete");
        }
        if (rulePassed != 18 || schemaPassed < 4 || ibcPassed < 4) {
            throw new IllegalStateException("Pretest terminal judgments do not meet the freeze gate");
        }
        if (excluded > 0) {
            throw new IllegalStateException("Pretest contains model/service operational errors");
        }
        if (disturbed == 0 || ibcRecoveries == 0) {
            throw new IllegalStateException("Pretest did not exercise and recover from the registered disturbance");
        }
        long consistent = results.stream().filter(ExperimentBatchStore.EpisodeResult::ibcConsistent).count();
        long missingTerminalRecords = results.stream()
                .filter(value -> value.actualOutcome() == null || value.actualOutcome().isBlank())
                .count();
        if (consistent != results.size() || missingTerminalRecords > 0) {
            throw new IllegalStateException("Pretest contains inconsistent behavior or missing terminal records");
        }
        return new Audit(
                results.size(),
                (int) ruleCount,
                (int) schemaCount,
                (int) ibcCount,
                (int) rulePassed,
                (int) schemaPassed,
                (int) ibcPassed,
                (int) disturbed,
                (int) ibcRecoveries,
                (double) consistent / results.size(),
                (int) missingTerminalRecords
        );
    }

    private static FreezeLock readLock() throws IOException {
        try {
            FreezeLock lock = GSON.fromJson(Files.readString(LOCK_PATH, StandardCharsets.UTF_8), FreezeLock.class);
            if (lock == null || lock.snapshot() == null || lock.audit() == null) {
                throw new IOException("v0.4 freeze lock is incomplete");
            }
            return lock;
        } catch (JsonParseException exception) {
            throw new IOException("v0.4 freeze lock is invalid", exception);
        }
    }

    private static void writeAtomically(String content) throws IOException {
        Files.createDirectories(LOCK_PATH.getParent());
        Path temporary = LOCK_PATH.resolveSibling(LOCK_PATH.getFileName() + ".tmp");
        Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        try {
            Files.move(temporary, LOCK_PATH, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, LOCK_PATH);
        }
    }

    /**
     * 主实验锁同时保存冻结资源快照和预实验审计证据。
     */
    public record FreezeLock(
            String schemaVersion,
            String frozenAt,
            String pretestBatchId,
            ExperimentProtocol.Snapshot snapshot,
            Audit audit
    ) {
    }

    /**
     * 冻结前必须满足的预实验工程质量门槛，不用于比较系统效果优劣。
     */
    public record Audit(
            int totalEpisodes,
            int ruleBtEpisodes,
            int llmSchemaEpisodes,
            int maidIbcEpisodes,
            int ruleBtPassed,
            int llmSchemaPassed,
            int maidIbcPassed,
            int disturbedEpisodes,
            int maidIbcRecoveredDisturbances,
            double ibcr,
            int missingTerminalRecords
    ) {
    }
}
