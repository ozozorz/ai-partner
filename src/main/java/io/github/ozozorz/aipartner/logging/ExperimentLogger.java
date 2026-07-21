package io.github.ozozorz.aipartner.logging;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import io.github.ozozorz.aipartner.AiPartnerMod;
import io.github.ozozorz.aipartner.contract.ContractDecision;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.experiment.ExperimentSessionRegistry;
import io.github.ozozorz.aipartner.experiment.ExperimentScenarioJudge;
import io.github.ozozorz.aipartner.experiment.VariantExecutionService;
import io.github.ozozorz.aipartner.llm.LlmCallResult;
import io.github.ozozorz.aipartner.world.WorldStateSummary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * 以单独守护线程顺序写入 JSONL，避免实验日志磁盘 I/O 阻塞服务器 tick。
 */
public final class ExperimentLogger {
    private static final ExperimentLogger INSTANCE = new ExperimentLogger();

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon(true).name("ai-partner-experiment-log").factory()
    );
    private final Path logPath = FabricLoader.getInstance()
            .getGameDir()
            .resolve("logs")
            .resolve("ai-partner")
            .resolve("episodes.jsonl");

    private ExperimentLogger() {
    }

    public static ExperimentLogger getInstance() {
        return INSTANCE;
    }

    /**
     * 记录契约生命周期事件和当时的最小世界状态。
     */
    public void logContractEvent(
            String event,
            String systemVariant,
            AiPartnerEntity partner,
            @Nullable ServerPlayer actor,
            TaskContract contract,
            String rawInstruction
    ) {
        ServerPlayer statePlayer = actor != null
                ? actor
                : partner.getOwner() instanceof ServerPlayer owner ? owner : null;
        ExperimentSessionRegistry.Context context = statePlayer == null
                ? null
                : ExperimentSessionRegistry.current(statePlayer).orElse(null);
        LogEvent entry = new LogEvent(
                Instant.now().toString(),
                context == null ? contract.contractId() : context.episodeId(),
                contract.contractId(),
                event,
                systemVariant,
                context == null ? null : context.batchId(),
                context == null ? null : context.scenarioId(),
                context == null ? null : context.expectedOutcome(),
                statePlayer == null ? 0L : statePlayer.level().getSeed(),
                context == null ? null : context.dimension(),
                actor == null ? null : actor.getUUID(),
                partner.getUUID(),
                rawInstruction,
                contract.job().type().name(),
                contract.job().target(),
                contract.job().quantity(),
                contract.job().radius(),
                contract.status().name(),
                contract.failureCode().name(),
                statePlayer == null ? null : WorldStateSummary.capture(partner, statePlayer)
        );
        writer.execute(() -> appendLine(gson.toJson(entry), context == null ? null : context.batchId()));
    }

    /**
     * 记录模型请求结果，包括 Prompt 哈希、Token、延迟、原始输出和解析状态。
     */
    public void logLlmInteraction(
            AiPartnerEntity partner,
            ServerPlayer player,
            String rawInstruction,
            WorldStateSummary worldState,
            LlmCallResult result
    ) {
        logLlmInteraction("MAID_IBC", partner, player, rawInstruction, worldState, result);
    }

    /**
     * 记录指定实验变体的模型请求，防止 B1、P 与 A2 被错误合并到同一标签。
     */
    public void logLlmInteraction(
            String systemVariant,
            AiPartnerEntity partner,
            ServerPlayer player,
            String rawInstruction,
            WorldStateSummary worldState,
            LlmCallResult result
    ) {
        UUID requestId = UUID.randomUUID();
        ExperimentSessionRegistry.Context context = ExperimentSessionRegistry.current(player).orElse(null);
        LlmEvent entry = new LlmEvent(
                Instant.now().toString(),
                context == null ? requestId : context.episodeId(),
                requestId,
                "llm_response",
                systemVariant,
                context == null ? null : context.batchId(),
                context == null ? null : context.scenarioId(),
                context == null ? null : context.expectedOutcome(),
                player.level().getSeed(),
                context == null ? player.level().dimension().identifier().toString() : context.dimension(),
                player.getUUID(),
                partner.getUUID(),
                rawInstruction,
                worldState,
                result.model(),
                result.promptHash(),
                result.rawOutput(),
                result.interpretation(),
                result.successful(),
                result.errorCode(),
                result.latencyMillis(),
                result.attempts(),
                result.inputTokens(),
                result.outputTokens()
        );
        writer.execute(() -> appendLine(gson.toJson(entry), context == null ? null : context.batchId()));
    }

    /**
     * 记录候选任务的执行前验证结果，包括被拒绝且没有正式契约的交互。
     */
    public void logValidationDecision(
            String systemVariant,
            AiPartnerEntity partner,
            ServerPlayer player,
            String rawInstruction,
            @Nullable JobSpec candidate,
            @Nullable ContractDecision decision,
            String outcome
    ) {
        ExperimentSessionRegistry.Context context = ExperimentSessionRegistry.current(player).orElse(null);
        UUID decisionId = decision != null && decision.contract() != null
                ? decision.contract().contractId()
                : UUID.randomUUID();
        ValidationEvent entry = new ValidationEvent(
                Instant.now().toString(),
                context == null ? decisionId : context.episodeId(),
                decisionId,
                "contract_validation",
                systemVariant,
                context == null ? null : context.batchId(),
                context == null ? null : context.scenarioId(),
                context == null ? null : context.expectedOutcome(),
                player.level().getSeed(),
                context == null ? player.level().dimension().identifier().toString() : context.dimension(),
                player.getUUID(),
                partner.getUUID(),
                rawInstruction,
                candidate,
                decision != null && decision.accepted(),
                decision == null ? outcome : decision.failureCode().name(),
                WorldStateSummary.capture(partner, player)
        );
        writer.execute(() -> appendLine(gson.toJson(entry), context == null ? null : context.batchId()));
    }

    /**
     * 记录场景重置和运行中扰动，使没有正式契约的世界操作也能与 episode 对齐。
     */
    public void logScenarioEvent(
            String event,
            ServerPlayer player,
            ExperimentSessionRegistry.Context context,
            String outcome
    ) {
        ScenarioEvent entry = new ScenarioEvent(
                Instant.now().toString(),
                context.episodeId(),
                event,
                context.batchId(),
                context.scenarioId(),
                context.expectedOutcome(),
                context.worldSeed(),
                context.dimension(),
                player.getUUID(),
                context.anchor().getX(),
                context.anchor().getY(),
                context.anchor().getZ(),
                outcome
        );
        writer.execute(() -> appendLine(gson.toJson(entry), context.batchId()));
    }

    /**
     * 写入独立终态判定、IBCR、恢复次数与模型用量，作为每个 episode 的唯一汇总行。
     */
    public void logEpisodeResult(
            ServerPlayer player,
            AiPartnerEntity partner,
            ExperimentSessionRegistry.Context context,
            String instruction,
            VariantExecutionService.SubmissionResult submission,
            ExperimentScenarioJudge.Verdict verdict,
            long durationTicks
    ) {
        LlmCallResult model = submission.modelResult();
        TaskContract contract = submission.contract();
        EpisodeResultEvent entry = new EpisodeResultEvent(
                "0.4",
                Instant.now().toString(),
                context.episodeId(),
                "episode_result",
                context.batchId(),
                context.batchKind(),
                context.planIndex(),
                context.repetition(),
                context.scenarioId(),
                submission.variant().name(),
                context.protocolFingerprint(),
                context.worldSeed(),
                context.dimension(),
                player.getUUID(),
                partner.getUUID(),
                instruction,
                submission.dialogueAct() == null ? null : submission.dialogueAct().name(),
                submission.candidateJob(),
                submission.accepted(),
                submission.scheduled(),
                submission.outcome(),
                contract == null ? null : contract.contractId(),
                contract == null ? "NONE" : contract.status().name(),
                contract == null ? "NONE" : contract.failureCode().name(),
                verdict.expectedOutcome(),
                verdict.actualOutcome(),
                verdict.passed(),
                verdict.excludedOperationalError(),
                verdict.goalSatisfied(),
                verdict.safetySatisfied(),
                verdict.safetyViolations(),
                verdict.ibcConsistent(),
                verdict.runtimeRecoveries(),
                verdict.disturbanceApplied(),
                verdict.navigationDone(),
                verdict.terminalExecutionState(),
                durationTicks,
                model == null ? null : model.model(),
                model == null ? null : model.promptHash(),
                model == null ? 0L : model.latencyMillis(),
                model == null ? 0 : model.attempts(),
                model == null ? 0 : model.inputTokens(),
                model == null ? 0 : model.outputTokens(),
                WorldStateSummary.capture(partner, player)
        );
        writer.execute(() -> appendLine(gson.toJson(entry), context.batchId()));
    }

    /**
     * 记录批次启动、恢复、完成或中止，不依赖某个 episode 上下文。
     */
    public void logBatchEvent(
            String batchId,
            String batchKind,
            String status,
            int nextIndex,
            int total,
            String protocolFingerprint,
            String details
    ) {
        BatchEvent entry = new BatchEvent(
                "0.4",
                Instant.now().toString(),
                "batch_" + status.toLowerCase(java.util.Locale.ROOT),
                batchId,
                batchKind,
                status,
                nextIndex,
                total,
                protocolFingerprint,
                details
        );
        writer.execute(() -> appendLine(gson.toJson(entry), batchId));
    }

    /**
     * 在服务器停止阶段等待之前排队的日志写入完成。
     */
    public void flush() {
        try {
            writer.submit(() -> {
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            AiPartnerMod.LOGGER.warn("Timed out while flushing AI Partner experiment logs", exception);
        }
    }

    private void appendLine(String json, @Nullable String batchId) {
        try {
            Files.createDirectories(logPath.getParent());
            appendTo(logPath, json);
            if (batchId != null && batchId.matches("[A-Za-z0-9._-]{1,64}")) {
                Path batchPath = logPath.getParent()
                        .resolve("batches")
                        .resolve(batchId)
                        .resolve("events.jsonl");
                Files.createDirectories(batchPath.getParent());
                appendTo(batchPath, json);
            }
        } catch (IOException exception) {
            AiPartnerMod.LOGGER.error("Failed to append AI Partner experiment log", exception);
        }
    }

    private static void appendTo(Path path, String json) throws IOException {
        Files.writeString(
                path,
                json + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    private record LogEvent(
            String timestamp,
            @SerializedName("episode_id")
            UUID episodeId,
            @SerializedName("contract_id")
            UUID contractId,
            String event,
            @SerializedName("system_variant")
            String systemVariant,
            @SerializedName("batch_id")
            @Nullable String batchId,
            @SerializedName("scenario_id")
            @Nullable String scenarioId,
            @SerializedName("expected_outcome")
            @Nullable String expectedOutcome,
            @SerializedName("world_seed")
            long worldSeed,
            @Nullable String dimension,
            @SerializedName("player_id")
            @Nullable UUID playerId,
            @SerializedName("partner_id")
            UUID partnerId,
            @SerializedName("raw_instruction")
            String rawInstruction,
            @SerializedName("job_type")
            String jobType,
            String target,
            int quantity,
            int radius,
            @SerializedName("contract_status")
            String contractStatus,
            @SerializedName("failure_code")
            String failureCode,
            @SerializedName("world_state")
            @Nullable WorldStateSummary worldState
    ) {
    }

    private record LlmEvent(
            String timestamp,
            @SerializedName("episode_id")
            UUID episodeId,
            @SerializedName("request_id")
            UUID requestId,
            String event,
            @SerializedName("system_variant")
            String systemVariant,
            @SerializedName("batch_id")
            @Nullable String batchId,
            @SerializedName("scenario_id")
            @Nullable String scenarioId,
            @SerializedName("expected_outcome")
            @Nullable String expectedOutcome,
            @SerializedName("world_seed")
            long worldSeed,
            String dimension,
            @SerializedName("player_id")
            UUID playerId,
            @SerializedName("partner_id")
            UUID partnerId,
            @SerializedName("raw_instruction")
            String rawInstruction,
            @SerializedName("world_state")
            WorldStateSummary worldState,
            String model,
            @SerializedName("prompt_hash")
            String promptHash,
            @SerializedName("raw_output")
            String rawOutput,
            @SerializedName("parsed_output")
            Object parsedOutput,
            @SerializedName("valid_output")
            boolean validOutput,
            @SerializedName("error_code")
            @Nullable String errorCode,
            @SerializedName("latency_ms")
            long latencyMillis,
            @SerializedName("attempt_count")
            int attemptCount,
            @SerializedName("input_tokens")
            int inputTokens,
            @SerializedName("output_tokens")
            int outputTokens
    ) {
    }

    private record ValidationEvent(
            String timestamp,
            @SerializedName("episode_id")
            UUID episodeId,
            @SerializedName("decision_id")
            UUID decisionId,
            String event,
            @SerializedName("system_variant")
            String systemVariant,
            @SerializedName("batch_id")
            @Nullable String batchId,
            @SerializedName("scenario_id")
            @Nullable String scenarioId,
            @SerializedName("expected_outcome")
            @Nullable String expectedOutcome,
            @SerializedName("world_seed")
            long worldSeed,
            String dimension,
            @SerializedName("player_id")
            UUID playerId,
            @SerializedName("partner_id")
            UUID partnerId,
            @SerializedName("raw_instruction")
            String rawInstruction,
            @SerializedName("candidate_job")
            @Nullable JobSpec candidateJob,
            boolean accepted,
            String outcome,
            @SerializedName("world_state")
            WorldStateSummary worldState
    ) {
    }

    private record ScenarioEvent(
            String timestamp,
            @SerializedName("episode_id")
            UUID episodeId,
            String event,
            @SerializedName("batch_id")
            String batchId,
            @SerializedName("scenario_id")
            String scenarioId,
            @SerializedName("expected_outcome")
            String expectedOutcome,
            @SerializedName("world_seed")
            long worldSeed,
            String dimension,
            @SerializedName("player_id")
            UUID playerId,
            @SerializedName("anchor_x")
            int anchorX,
            @SerializedName("anchor_y")
            int anchorY,
            @SerializedName("anchor_z")
            int anchorZ,
            String outcome
    ) {
    }

    private record EpisodeResultEvent(
            @SerializedName("log_schema_version")
            String logSchemaVersion,
            String timestamp,
            @SerializedName("episode_id")
            UUID episodeId,
            String event,
            @SerializedName("batch_id")
            String batchId,
            @SerializedName("batch_kind")
            String batchKind,
            @SerializedName("plan_index")
            int planIndex,
            int repetition,
            @SerializedName("scenario_id")
            String scenarioId,
            @SerializedName("system_variant")
            String systemVariant,
            @SerializedName("protocol_fingerprint")
            String protocolFingerprint,
            @SerializedName("world_seed")
            long worldSeed,
            String dimension,
            @SerializedName("player_id")
            UUID playerId,
            @SerializedName("partner_id")
            UUID partnerId,
            @SerializedName("raw_instruction")
            String rawInstruction,
            @SerializedName("dialogue_act")
            @Nullable String dialogueAct,
            @SerializedName("candidate_job")
            @Nullable JobSpec candidateJob,
            boolean accepted,
            boolean scheduled,
            @SerializedName("submission_outcome")
            String submissionOutcome,
            @SerializedName("contract_id")
            @Nullable UUID contractId,
            @SerializedName("contract_status")
            String contractStatus,
            @SerializedName("failure_code")
            String failureCode,
            @SerializedName("expected_outcome")
            String expectedOutcome,
            @SerializedName("actual_outcome")
            String actualOutcome,
            boolean passed,
            @SerializedName("excluded_operational_error")
            boolean excludedOperationalError,
            @SerializedName("goal_satisfied")
            boolean goalSatisfied,
            @SerializedName("safety_satisfied")
            boolean safetySatisfied,
            @SerializedName("safety_violations")
            int safetyViolations,
            @SerializedName("ibc_consistent")
            boolean ibcConsistent,
            @SerializedName("runtime_recoveries")
            int runtimeRecoveries,
            @SerializedName("disturbance_applied")
            boolean disturbanceApplied,
            @SerializedName("navigation_done")
            boolean navigationDone,
            @SerializedName("terminal_execution_state")
            String terminalExecutionState,
            @SerializedName("duration_ticks")
            long durationTicks,
            @Nullable String model,
            @SerializedName("prompt_hash")
            @Nullable String promptHash,
            @SerializedName("latency_ms")
            long latencyMillis,
            @SerializedName("attempt_count")
            int attemptCount,
            @SerializedName("input_tokens")
            int inputTokens,
            @SerializedName("output_tokens")
            int outputTokens,
            @SerializedName("world_state")
            WorldStateSummary worldState
    ) {
    }

    private record BatchEvent(
            @SerializedName("log_schema_version")
            String logSchemaVersion,
            String timestamp,
            String event,
            @SerializedName("batch_id")
            String batchId,
            @SerializedName("batch_kind")
            String batchKind,
            String status,
            @SerializedName("next_index")
            int nextIndex,
            int total,
            @SerializedName("protocol_fingerprint")
            String protocolFingerprint,
            String details
    ) {
    }
}
