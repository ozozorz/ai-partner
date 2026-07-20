package io.github.ozozorz.aipartner.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.ozozorz.aipartner.contract.ContractCompiler;
import io.github.ozozorz.aipartner.contract.ContractDecision;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.job.AllowedTargets;
import io.github.ozozorz.aipartner.job.ContainerTargets;
import io.github.ozozorz.aipartner.llm.DialogueAct;
import io.github.ozozorz.aipartner.llm.LlmCallResult;
import io.github.ozozorz.aipartner.llm.LlmGateway;
import io.github.ozozorz.aipartner.logging.ExperimentLogger;
import io.github.ozozorz.aipartner.parser.RuleJobParser;
import io.github.ozozorz.aipartner.service.PartnerService;
import io.github.ozozorz.aipartner.world.WorldStateSummary;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.SharedSuggestionProvider;

/**
 * 注册 `/maid` 交互入口；所有动作先经过契约编译器，再交给实体执行。
 */
public final class MaidCommand {
    private static final ConcurrentHashMap<UUID, CompletableFuture<LlmCallResult>> PENDING_LLM_REQUESTS = new ConcurrentHashMap<>();

    private MaidCommand() {
    }

    /**
     * 注册生成、状态、基础任务和自然语言消息子命令。
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("maid")
                        .executes(MaidCommand::showHelp)
                        .then(Commands.literal("spawn").executes(MaidCommand::spawn))
                        .then(Commands.literal("status").executes(MaidCommand::status))
                        .then(Commands.literal("inventory").executes(MaidCommand::inventory))
                        .then(Commands.literal("retrieve").executes(MaidCommand::retrieveInventory))
                        .then(Commands.literal("follow").executes(context -> runBasicJob(context, JobType.FOLLOW)))
                        .then(Commands.literal("stay").executes(context -> runBasicJob(context, JobType.STAY)))
                        .then(Commands.literal("cancel").executes(context -> runBasicJob(context, JobType.CANCEL)))
                        .then(Commands.literal("collect")
                                .then(Commands.argument("block", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                AllowedTargets.suggestedBlockIds(),
                                                builder
                                        ))
                                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> runCollectJob(
                                                        context,
                                                        AllowedTargets.DEFAULT_COLLECT_RADIUS
                                                ))
                                                .then(Commands.argument(
                                                        "radius",
                                                        IntegerArgumentType.integer(1, AllowedTargets.MAX_COLLECT_RADIUS)
                                                ).executes(context -> runCollectJob(
                                                        context,
                                                        IntegerArgumentType.getInteger(context, "radius")
                                                )))
                                        )))
                        .then(Commands.literal("deposit")
                                .then(Commands.argument("item", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                AllowedTargets.suggestedBlockIds(),
                                                builder
                                        ))
                                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> runDepositJob(
                                                        context,
                                                        ContainerTargets.DEFAULT_DEPOSIT_RADIUS
                                                ))
                                                .then(Commands.argument(
                                                        "radius",
                                                        IntegerArgumentType.integer(1, ContainerTargets.MAX_DEPOSIT_RADIUS)
                                                ).executes(context -> runDepositJob(
                                                        context,
                                                        IntegerArgumentType.getInteger(context, "radius")
                                                )))
                                        )))
                        .then(Commands.argument("message", StringArgumentType.greedyString()).executes(MaidCommand::handleMessage))
        ));
    }

    private static int showHelp(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.translatable("message.ai-partner.help"), false);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        if (PartnerService.findOwnedPartner(player).isPresent()) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.already_exists"));
            return 0;
        }
        if (PartnerService.spawnPartner(player).isEmpty()) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.spawn_failed"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("message.ai-partner.spawned"), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = PartnerService.findOwnedPartner(player).orElse(null);
        if (partner == null) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.not_found"));
            return 0;
        }
        String contractStatus = partner.getCurrentContract()
                .map(contract -> contract.status().name())
                .orElse("NONE");
        String jobType = partner.getCurrentContract()
                .map(contract -> contract.job().type().name())
                .orElse("NONE");
        context.getSource().sendSuccess(
                () -> Component.translatable(
                        "message.ai-partner.status",
                        partner.getMode().name(),
                        jobType,
                        contractStatus
                ),
                false
        );
        return 1;
    }

    private static int inventory(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = PartnerService.findOwnedPartner(player).orElse(null);
        if (partner == null) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.not_found"));
            return 0;
        }
        context.getSource().sendSuccess(
                () -> Component.translatable("message.ai-partner.inventory", partner.inventorySummary()),
                false
        );
        return 1;
    }

    private static int retrieveInventory(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = PartnerService.findOwnedPartner(player).orElse(null);
        if (partner == null) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.not_found"));
            return 0;
        }
        int returned = partner.returnInventoryTo(player);
        if (returned < 0) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.inventory_busy"));
            return 0;
        }
        context.getSource().sendSuccess(
                () -> Component.translatable("message.ai-partner.inventory_returned", returned),
                false
        );
        return 1;
    }

    private static int runBasicJob(CommandContext<CommandSourceStack> context, JobType type) throws CommandSyntaxException {
        cancelPendingRequest(context.getSource().getPlayerOrException().getUUID());
        return compileAndRun(context, JobSpec.basic(type), type.name().toLowerCase());
    }

    private static int runCollectJob(
            CommandContext<CommandSourceStack> context,
            int radius
    ) throws CommandSyntaxException {
        cancelPendingRequest(context.getSource().getPlayerOrException().getUUID());
        String block = StringArgumentType.getString(context, "block");
        int quantity = IntegerArgumentType.getInteger(context, "quantity");
        return compileAndRun(
                context,
                new JobSpec(JobType.COLLECT_BLOCK, block, quantity, radius),
                "collect " + block + " " + quantity + " " + radius
        );
    }

    private static int runDepositJob(
            CommandContext<CommandSourceStack> context,
            int radius
    ) throws CommandSyntaxException {
        cancelPendingRequest(context.getSource().getPlayerOrException().getUUID());
        String item = StringArgumentType.getString(context, "item");
        int quantity = IntegerArgumentType.getInteger(context, "quantity");
        return compileAndRun(
                context,
                new JobSpec(JobType.DEPOSIT_ITEM, item, quantity, radius),
                "deposit " + item + " " + quantity + " " + radius
        );
    }

    private static int handleMessage(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String message = StringArgumentType.getString(context, "message");
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = PartnerService.findOwnedPartner(player).orElse(null);
        if (partner == null) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.not_found"));
            return 0;
        }

        JobSpec localCandidate = RuleJobParser.parse(message).orElse(null);
        if (localCandidate != null && localCandidate.type() == JobType.CANCEL) {
            cancelPendingRequest(player.getUUID());
            return compileAndRun(context, localCandidate, message);
        }
        if (!LlmGateway.getInstance().isEnabled()) {
            if (localCandidate == null) {
                ExperimentLogger.getInstance().logValidationDecision(
                        "RULE_BT",
                        partner,
                        player,
                        message,
                        null,
                        null,
                        "NEEDS_CLARIFICATION"
                );
                context.getSource().sendFailure(Component.translatable("message.ai-partner.clarify"));
                return 0;
            }
            return compileAndRun(context, localCandidate, message);
        }

        WorldStateSummary worldState = WorldStateSummary.capture(partner, player);
        context.getSource().sendSuccess(() -> Component.translatable("message.ai-partner.thinking"), false);
        submitLlmRequest(player, partner, message, worldState);
        return 1;
    }

    private static void submitLlmRequest(
            ServerPlayer player,
            AiPartnerEntity partner,
            String rawInstruction,
            WorldStateSummary worldState
    ) {
        UUID playerId = player.getUUID();
        MinecraftServer server = player.level().getServer();
        CompletableFuture<LlmCallResult> request = LlmGateway.getInstance().interpret(rawInstruction, worldState);
        CompletableFuture<LlmCallResult> previous = PENDING_LLM_REQUESTS.put(playerId, request);
        if (previous != null) {
            previous.cancel(true);
        }

        request.whenComplete((result, throwable) -> {
            if (!PENDING_LLM_REQUESTS.remove(playerId, request)) {
                return;
            }
            if (throwable != null || result == null) {
                return;
            }
            ExperimentLogger.getInstance().logLlmInteraction(partner, player, rawInstruction, worldState, result);
            server.execute(() -> handleLlmResult(server, playerId, rawInstruction, result));
        });
    }

    private static void handleLlmResult(
            MinecraftServer server,
            UUID playerId,
            String rawInstruction,
            LlmCallResult result
    ) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return;
        }
        if (!result.successful() || result.interpretation() == null) {
            player.sendSystemMessage(Component.translatable(
                    "message.ai-partner.llm_failed",
                    result.errorCode() == null ? "UNKNOWN" : result.errorCode()
            ));
            return;
        }

        DialogueAct act = result.interpretation().dialogueAct();
        switch (act) {
            case PROPOSE_JOB -> applyCandidate(
                    player,
                    result.interpretation().candidateJob(),
                    rawInstruction,
                    "MAID_IBC"
            );
            case ASK_CLARIFICATION -> player.sendSystemMessage(Component.translatable(
                    "message.ai-partner.model_clarification",
                    sanitizeModelText(result.interpretation().clarificationQuestion())
            ));
            case REJECT_UNSUPPORTED -> player.sendSystemMessage(Component.translatable("message.ai-partner.model_rejected"));
            case SOCIAL_REPLY -> player.sendSystemMessage(Component.translatable(
                    "message.ai-partner.social_reply",
                    sanitizeModelText(result.interpretation().clarificationQuestion())
            ));
        }
    }

    private static int compileAndRun(
            CommandContext<CommandSourceStack> context,
            JobSpec candidate,
            String rawInstruction
    ) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AiPartnerEntity partner = PartnerService.findOwnedPartner(player).orElse(null);
        if (partner == null) {
            context.getSource().sendFailure(Component.translatable("message.ai-partner.not_found"));
            return 0;
        }

        ContractDecision decision = ContractCompiler.compile(partner, player, candidate);
        ExperimentLogger.getInstance().logValidationDecision(
                "RULE_BT",
                partner,
                player,
                rawInstruction,
                candidate,
                decision,
                decision.failureCode().name()
        );
        if (!decision.accepted()) {
            context.getSource().sendFailure(Component.translatable(decision.messageKey()));
            return 0;
        }

        partner.applyContract(decision.contract(), player, rawInstruction);
        String responseKey = responseKey(candidate, decision);
        context.getSource().sendSuccess(() -> Component.translatable(responseKey), false);
        return 1;
    }

    private static boolean applyCandidate(
            ServerPlayer player,
            JobSpec candidate,
            String rawInstruction,
            String systemVariant
    ) {
        if (candidate == null) {
            player.sendSystemMessage(Component.translatable("message.ai-partner.llm_failed", "MISSING_CANDIDATE"));
            return false;
        }
        AiPartnerEntity partner = PartnerService.findOwnedPartner(player).orElse(null);
        if (partner == null) {
            player.sendSystemMessage(Component.translatable("message.ai-partner.not_found"));
            return false;
        }
        ContractDecision decision = ContractCompiler.compile(partner, player, candidate);
        ExperimentLogger.getInstance().logValidationDecision(
                systemVariant,
                partner,
                player,
                rawInstruction,
                candidate,
                decision,
                decision.failureCode().name()
        );
        if (!decision.accepted()) {
            player.sendSystemMessage(Component.translatable(decision.messageKey()));
            return false;
        }
        partner.applyContract(decision.contract(), player, rawInstruction, systemVariant);
        player.sendSystemMessage(Component.translatable(responseKey(candidate, decision)));
        return true;
    }

    private static String responseKey(JobSpec candidate, ContractDecision decision) {
        return switch (candidate.type()) {
            case FOLLOW -> "message.ai-partner.following";
            case STAY -> "message.ai-partner.staying";
            case CANCEL -> "message.ai-partner.cancelled";
            case COLLECT_BLOCK -> "message.ai-partner.collecting";
            case DEPOSIT_ITEM -> "message.ai-partner.depositing";
            default -> decision.messageKey();
        };
    }

    private static String sanitizeModelText(String text) {
        if (text == null || text.isBlank()) {
            return "...";
        }
        String sanitized = text.replaceAll("[\\r\\n\\t]+", " ").strip();
        return sanitized.length() <= 200 ? sanitized : sanitized.substring(0, 200);
    }

    private static void cancelPendingRequest(UUID playerId) {
        CompletableFuture<LlmCallResult> request = PENDING_LLM_REQUESTS.remove(playerId);
        if (request != null) {
            request.cancel(true);
        }
    }
}
