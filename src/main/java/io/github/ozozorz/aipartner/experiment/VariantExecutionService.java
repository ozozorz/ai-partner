package io.github.ozozorz.aipartner.experiment;

import io.github.ozozorz.aipartner.contract.ContractCompiler;
import io.github.ozozorz.aipartner.contract.ContractDecision;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.contract.SchemaOnlyContractCompiler;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.core.order.MaidOrderService;
import io.github.ozozorz.aipartner.core.task.TaskExecutionPolicy;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.llm.DialogueAct;
import io.github.ozozorz.aipartner.llm.LlmCallResult;
import io.github.ozozorz.aipartner.llm.LlmGateway;
import io.github.ozozorz.aipartner.logging.ExperimentLogger;
import io.github.ozozorz.aipartner.parser.RuleJobParser;
import io.github.ozozorz.aipartner.world.WorldStateSummary;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * 以同一提交入口运行 Rule-BT、LLM-Schema、完整 Maid-IBC 与 A2，确保仅切换声明的实验模块。
 */
public final class VariantExecutionService {
    private VariantExecutionService() {
    }

    /**
     * 异步解析指令，并保证契约编译和实体状态修改最终回到 Minecraft 服务器线程。
     */
    public static CompletableFuture<SubmissionResult> submit(
            SystemVariant variant,
            ServerPlayer player,
            AiPartnerEntity partner,
            String instruction
    ) {
        if (!variant.usesLlm()) {
            JobSpec candidate = RuleJobParser.parse(instruction).orElse(null);
            if (candidate == null) {
                ExperimentLogger.getInstance().logValidationDecision(
                        variant.name(),
                        partner,
                        player,
                        instruction,
                        null,
                        null,
                        "NEEDS_CLARIFICATION"
                );
                return CompletableFuture.completedFuture(SubmissionResult.notScheduled(
                        variant,
                        DialogueAct.ASK_CLARIFICATION,
                        null,
                        "NEEDS_CLARIFICATION",
                        null
                ));
            }
            return CompletableFuture.completedFuture(compileAndSchedule(
                    variant,
                    player,
                    partner,
                    instruction,
                    candidate,
                    null
            ));
        }

        WorldStateSummary worldState = WorldStateSummary.capture(partner, player);
        CompletableFuture<SubmissionResult> completion = new CompletableFuture<>();
        LlmGateway.getInstance().interpret(instruction, worldState).whenComplete((result, throwable) -> {
            MinecraftServer server = player.level().getServer();
            server.execute(() -> {
                if (throwable != null || result == null) {
                    completion.complete(SubmissionResult.notScheduled(
                            variant,
                            null,
                            null,
                            "MODEL_CALLBACK_ERROR",
                            null
                    ));
                    return;
                }
                ExperimentLogger.getInstance().logLlmInteraction(
                        variant.name(),
                        partner,
                        player,
                        instruction,
                        worldState,
                        result
                );
                completion.complete(processModelResult(
                        variant,
                        player,
                        partner,
                        instruction,
                        result
                ));
            });
        });
        return completion;
    }

    private static SubmissionResult processModelResult(
            SystemVariant variant,
            ServerPlayer player,
            AiPartnerEntity partner,
            String instruction,
            LlmCallResult result
    ) {
        if (!result.successful() || result.interpretation() == null) {
            return SubmissionResult.notScheduled(
                    variant,
                    null,
                    null,
                    "MODEL_ERROR:" + (result.errorCode() == null ? "UNKNOWN" : result.errorCode()),
                    result
            );
        }

        DialogueAct act = result.interpretation().dialogueAct();
        JobSpec candidate = result.interpretation().candidateJob();
        if (act != DialogueAct.PROPOSE_JOB) {
            ExperimentLogger.getInstance().logValidationDecision(
                    variant.name(),
                    partner,
                    player,
                    instruction,
                    null,
                    null,
                    act.name()
            );
            return SubmissionResult.notScheduled(variant, act, null, act.name(), result);
        }
        if (candidate == null) {
            return SubmissionResult.notScheduled(
                    variant,
                    act,
                    null,
                    "MODEL_ERROR:MISSING_CANDIDATE",
                    result
            );
        }
        return compileAndSchedule(variant, player, partner, instruction, candidate, result);
    }

    private static SubmissionResult compileAndSchedule(
            SystemVariant variant,
            ServerPlayer player,
            AiPartnerEntity partner,
            String instruction,
            JobSpec candidate,
            @Nullable LlmCallResult modelResult
    ) {
        ContractDecision decision = variant.semanticValidationEnabled()
                ? ContractCompiler.compile(partner, player, candidate)
                : SchemaOnlyContractCompiler.compile(candidate);
        MaidOrderService.submitValidated(
                partner,
                player,
                candidate,
                instruction,
                decision,
                new TaskExecutionPolicy(
                        variant.name(),
                        variant.runtimeMonitoringEnabled(),
                        variant.localRecoveryEnabled()
                )
        );
        if (!decision.accepted() || decision.contract() == null) {
            return SubmissionResult.notScheduled(
                    variant,
                    DialogueAct.PROPOSE_JOB,
                    candidate,
                    decision.failureCode().name(),
                    modelResult
            );
        }

        return new SubmissionResult(
                variant,
                DialogueAct.PROPOSE_JOB,
                candidate,
                true,
                true,
                "ACCEPTED",
                decision.contract(),
                modelResult
        );
    }

    /**
     * 一次变体提交的可判定结果；未调度的模型/验证终态也会完整保留。
     */
    public record SubmissionResult(
            SystemVariant variant,
            @Nullable DialogueAct dialogueAct,
            @Nullable JobSpec candidateJob,
            boolean accepted,
            boolean scheduled,
            String outcome,
            @Nullable TaskContract contract,
            @Nullable LlmCallResult modelResult
    ) {
        private static SubmissionResult notScheduled(
                SystemVariant variant,
                @Nullable DialogueAct dialogueAct,
                @Nullable JobSpec candidateJob,
                String outcome,
                @Nullable LlmCallResult modelResult
        ) {
            return new SubmissionResult(
                    variant,
                    dialogueAct,
                    candidateJob,
                    false,
                    false,
                    outcome,
                    null,
                    modelResult
            );
        }

        public boolean operationalError() {
            return outcome.startsWith("MODEL_ERROR:") || outcome.equals("MODEL_CALLBACK_ERROR");
        }
    }
}
