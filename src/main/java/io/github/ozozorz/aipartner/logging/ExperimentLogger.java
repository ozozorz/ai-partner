package io.github.ozozorz.aipartner.logging;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.ozozorz.aipartner.AiPartnerMod;
import io.github.ozozorz.aipartner.contract.ContractDecision;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
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
        LogEvent entry = new LogEvent(
                Instant.now().toString(),
                contract.contractId(),
                event,
                systemVariant,
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
        LlmEvent entry = new LlmEvent(
                Instant.now().toString(),
                UUID.randomUUID(),
                "llm_response",
                "MAID_IBC",
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
        ValidationEvent entry = new ValidationEvent(
                Instant.now().toString(),
                decision != null && decision.contract() != null ? decision.contract().contractId() : UUID.randomUUID(),
                "contract_validation",
                systemVariant,
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

    // TODO: 在自动化实验阶段增加 scenario_id 注入和按实验批次文件轮转。
    private record LogEvent(
            String timestamp,
            UUID contractId,
            String event,
            String systemVariant,
            @Nullable UUID playerId,
            UUID partnerId,
            String rawInstruction,
            String jobType,
            String target,
            int quantity,
            int radius,
            String contractStatus,
            String failureCode,
            @Nullable WorldStateSummary worldState
    ) {
    }

    private record LlmEvent(
            String timestamp,
            UUID requestId,
            String event,
            String systemVariant,
            UUID playerId,
            UUID partnerId,
            String rawInstruction,
            WorldStateSummary worldState,
            String model,
            String promptHash,
            String rawOutput,
            Object parsedOutput,
            boolean validOutput,
            @Nullable String errorCode,
            long latencyMillis,
            int inputTokens,
            int outputTokens
    ) {
    }

    private record ValidationEvent(
            String timestamp,
            UUID episodeId,
            String event,
            String systemVariant,
            UUID playerId,
            UUID partnerId,
            String rawInstruction,
            @Nullable JobSpec candidateJob,
            boolean accepted,
            String outcome,
            WorldStateSummary worldState
    ) {
    }
}
