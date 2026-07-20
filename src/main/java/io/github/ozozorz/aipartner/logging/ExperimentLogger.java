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
        writer.execute(() -> appendLine(gson.toJson(entry)));
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
        UUID requestId = UUID.randomUUID();
        ExperimentSessionRegistry.Context context = ExperimentSessionRegistry.current(player).orElse(null);
        LlmEvent entry = new LlmEvent(
                Instant.now().toString(),
                context == null ? requestId : context.episodeId(),
                requestId,
                "llm_response",
                "MAID_IBC",
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
                result.inputTokens(),
                result.outputTokens()
        );
        writer.execute(() -> appendLine(gson.toJson(entry)));
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
        writer.execute(() -> appendLine(gson.toJson(entry)));
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
        writer.execute(() -> appendLine(gson.toJson(entry)));
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

    private void appendLine(String json) {
        try {
            Files.createDirectories(logPath.getParent());
            Files.writeString(
                    logPath,
                    json + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            AiPartnerMod.LOGGER.error("Failed to append AI Partner experiment log", exception);
        }
    }

    // TODO: 在长时间自动化实验阶段增加按批次文件轮转，避免单个 JSONL 无限增长。
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
}
