package io.github.ozozorz.aipartner.conversation;

import io.github.ozozorz.aipartner.AiPartnerMod;
import io.github.ozozorz.aipartner.control.LocalMaidIntentParser;
import io.github.ozozorz.aipartner.control.MaidControlDecision;
import io.github.ozozorz.aipartner.control.MaidControlDialogueAct;
import io.github.ozozorz.aipartner.control.MaidControlIntent;
import io.github.ozozorz.aipartner.control.MaidControlInterpretation;
import io.github.ozozorz.aipartner.control.MaidControlService;
import io.github.ozozorz.aipartner.control.MaidDriveMode;
import io.github.ozozorz.aipartner.core.event.MaidDomainEvent;
import io.github.ozozorz.aipartner.core.event.MaidDomainEvents;
import io.github.ozozorz.aipartner.core.event.MaidWorkflowLifecycleEvent;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.llm.MaidControlLlmGateway;
import io.github.ozozorz.aipartner.llm.MaidControlLlmResult;
import io.github.ozozorz.aipartner.llm.MaidConversationMessage;
import io.github.ozozorz.aipartner.llm.MaidLlmRequestKind;
import io.github.ozozorz.aipartner.llm.MaidWorkflowFeedback;
import io.github.ozozorz.aipartner.service.PartnerService;
import io.github.ozozorz.aipartner.workflow.MaidWorkflowSpec;
import io.github.ozozorz.aipartner.workflow.MaidWorkflowStartResult;
import io.github.ozozorz.aipartner.world.MaidControlContextSnapshot;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Coordinates continuous dialogue, offline fallback, stale-request rejection, bounded workflows,
 * replanning, and server-result-constrained narration.
 */
public final class MaidConversationService {
    public static final int MAX_MESSAGE_LENGTH = 512;
    private static final long LLM_RATE_LIMIT_NANOS = Duration.ofMillis(750).toNanos();
    private static final long CLARIFICATION_TTL_NANOS = Duration.ofMinutes(2).toNanos();
    private static final Map<UUID, PendingRequest> PENDING_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<UUID, WorkflowRequest> WORKFLOW_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<ConversationKey, ClarificationContext> CLARIFICATIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_LLM_REQUEST_NANOS = new ConcurrentHashMap<>();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private MaidConversationService() {
    }

    /** Registers the workflow result bridge once during mod initialization. */
    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            MaidDomainEvents.register(MaidConversationService::handleDomainEvent);
        }
    }

    /** Accepts one natural-language message on the server thread. */
    public static boolean submit(
            ServerPlayer player,
            @Nullable UUID fallbackMaidId,
            String rawMessage
    ) {
        String boundedMessage = rawMessage == null ? "" : rawMessage.strip();
        if (boundedMessage.isEmpty() || boundedMessage.length() > MAX_MESSAGE_LENGTH) {
            player.sendSystemMessage(Component.translatable("message.ai-partner.conversation.invalid_length"));
            return false;
        }
        MaidConversationTargetResolver.Resolution resolution = MaidConversationTargetResolver.resolve(
                player,
                fallbackMaidId,
                boundedMessage
        );
        if (!resolution.successful()) {
            Component failure = resolution.errorDetail().isEmpty()
                    ? Component.translatable(resolution.errorKey())
                    : Component.translatable(resolution.errorKey(), resolution.errorDetail());
            player.sendSystemMessage(failure);
            return false;
        }

        AiPartnerEntity maid = resolution.maid().orElseThrow();
        String instruction = resolution.instruction();
        MaidControlInterpretation emergency = LocalMaidIntentParser.parse(instruction);
        if (isEmergencyCancel(emergency)) {
            cancelPending(player.getUUID());
            clearClarification(player.getUUID(), maid.getUUID());
            maid.conversationMemory().appendUser(instruction);
            handleInterpretation(player, maid, instruction, emergency, "LOCAL_EMERGENCY");
            return true;
        }
        if (maid.getDriveMode() == MaidDriveMode.LOCAL) {
            return submitLocal(player, maid, instruction, "LOCAL_NL");
        }
        return submitLlm(player, maid, instruction);
    }

    private static boolean submitLocal(
            ServerPlayer player,
            AiPartnerEntity maid,
            String instruction,
            String sourceId
    ) {
        cancelPending(player.getUUID());
        ClarificationContext clarification = activeClarification(player.getUUID(), maid.getUUID());
        String parseInput = clarification == null
                ? instruction
                : clarification.previousInstruction() + " " + instruction;
        maid.conversationMemory().appendUser(instruction);
        handleInterpretation(player, maid, instruction, LocalMaidIntentParser.parse(parseInput), sourceId);
        return true;
    }

    private static boolean submitLlm(ServerPlayer player, AiPartnerEntity maid, String instruction) {
        MaidControlLlmGateway gateway = MaidControlLlmGateway.getInstance();
        String environmentVariable = maid.getLlmApiKeyEnvironmentVariable();
        String readinessError = gateway.readinessError(environmentVariable);
        if (readinessError != null) {
            MaidControlInterpretation fallback = LocalMaidIntentParser.parse(instruction);
            if (fallback.dialogueAct() == MaidControlDialogueAct.PROPOSE_INTENT) {
                return submitLocal(player, maid, instruction, "LOCAL_OFFLINE_FALLBACK");
            }
            Component failure = "MISSING_API_KEY_ENV".equals(readinessError)
                    ? Component.translatable("message.ai-partner.llm_env_missing", environmentVariable)
                    : Component.translatable("message.ai-partner.llm_unavailable", readinessError);
            player.sendSystemMessage(failure);
            maid.showSpeechBubble(failure);
            return false;
        }

        long now = System.nanoTime();
        Long previousStarted = LAST_LLM_REQUEST_NANOS.put(player.getUUID(), now);
        if (previousStarted != null && now - previousStarted < LLM_RATE_LIMIT_NANOS) {
            player.sendSystemMessage(Component.translatable("message.ai-partner.conversation.rate_limited"));
            return false;
        }

        ClarificationContext clarification = activeClarification(player.getUUID(), maid.getUUID());
        MaidControlContextSnapshot context = MaidControlContextSnapshot.capture(
                maid,
                player,
                clarification == null ? "" : clarification.previousInstruction(),
                clarification == null ? "" : clarification.question()
        );
        List<MaidConversationMessage> history = maid.conversationMemory().snapshot();
        maid.conversationMemory().appendUser(instruction);
        UUID playerId = player.getUUID();
        UUID maidId = maid.getUUID();
        MinecraftServer server = player.level().getServer();
        CompletableFuture<MaidControlLlmResult> future = gateway.request(
                MaidLlmRequestKind.PLAYER_MESSAGE,
                instruction,
                context,
                history,
                null,
                environmentVariable
        );
        PendingRequest pending = new PendingRequest(UUID.randomUUID(), maidId, instruction, future);
        PendingRequest previous = PENDING_REQUESTS.put(playerId, pending);
        if (previous != null) {
            previous.future().cancel(true);
        }
        player.sendSystemMessage(Component.translatable("message.ai-partner.thinking"));
        maid.showSpeechBubble(Component.literal("..."));

        future.whenComplete((result, throwable) -> server.execute(() -> completeLlmRequest(
                server,
                playerId,
                pending,
                result,
                throwable
        )));
        return true;
    }

    private static void completeLlmRequest(
            MinecraftServer server,
            UUID playerId,
            PendingRequest pending,
            @Nullable MaidControlLlmResult result,
            @Nullable Throwable throwable
    ) {
        if (!PENDING_REQUESTS.remove(playerId, pending)) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return;
        }
        AiPartnerEntity maid = findOwnedMaid(player, pending.maidId());
        if (maid == null) {
            player.sendSystemMessage(Component.translatable("message.ai-partner.conversation.target_unavailable"));
            return;
        }
        if (throwable != null || result == null) {
            if (!(unwrap(throwable) instanceof java.util.concurrent.CancellationException)) {
                player.sendSystemMessage(Component.translatable("message.ai-partner.llm_failed", "NETWORK_ERROR"));
            }
            return;
        }
        if (!result.successful() || result.interpretation() == null) {
            Component failure = Component.translatable(
                    "message.ai-partner.llm_failed",
                    result.errorCode() == null ? "UNKNOWN" : result.errorCode()
            );
            player.sendSystemMessage(failure);
            maid.showSpeechBubble(failure);
            return;
        }
        handleInterpretation(player, maid, pending.instruction(), result.interpretation(), "LLM_NL");
    }

    private static void handleInterpretation(
            ServerPlayer player,
            AiPartnerEntity maid,
            String instruction,
            MaidControlInterpretation interpretation,
            String sourceId
    ) {
        switch (interpretation.dialogueAct()) {
            case PROPOSE_INTENT -> {
                clearClarification(player.getUUID(), maid.getUUID());
                MaidControlDecision decision = MaidControlService.apply(
                        maid,
                        player,
                        interpretation.intent(),
                        instruction,
                        sourceId
                );
                player.sendSystemMessage(decision.message());
                maid.showSpeechBubble(decision.message());
                maid.conversationMemory().appendAssistant(decision.message().getString());
            }
            case PROPOSE_PLAN -> {
                clearClarification(player.getUUID(), maid.getUUID());
                String reply = sanitizeModelText(interpretation.responseText());
                MaidWorkflowSpec spec;
                try {
                    spec = MaidWorkflowSpec.llm(player.getUUID(), instruction, interpretation.plan());
                } catch (IllegalArgumentException exception) {
                    Component failure = Component.translatable("message.ai-partner.llm_failed", "INVALID_PLAN");
                    player.sendSystemMessage(failure);
                    maid.showSpeechBubble(failure);
                    return;
                }
                maid.conversationMemory().appendAssistant(reply);
                displayNaturalReply(player, maid, reply);
                MaidWorkflowStartResult start = maid.startWorkflow(spec, player);
                if (!start.accepted()) {
                    Component failure = Component.translatable(
                            "message.ai-partner.failed",
                            start.failureCode().name()
                    );
                    player.sendSystemMessage(failure);
                    maid.showSpeechBubble(failure);
                }
            }
            case ASK_CLARIFICATION -> {
                String question = sanitizeModelText(interpretation.responseText());
                CLARIFICATIONS.put(
                        new ConversationKey(player.getUUID(), maid.getUUID()),
                        new ClarificationContext(instruction, question, System.nanoTime())
                );
                maid.conversationMemory().appendAssistant(question);
                Component message = Component.translatable("message.ai-partner.model_clarification", question);
                player.sendSystemMessage(message);
                maid.showSpeechBubble(Component.literal(question));
            }
            case REJECT_UNSUPPORTED -> {
                clearClarification(player.getUUID(), maid.getUUID());
                String reply = interpretation.responseText() == null
                        ? Component.translatable("message.ai-partner.model_rejected").getString()
                        : sanitizeModelText(interpretation.responseText());
                maid.conversationMemory().appendAssistant(reply);
                displayNaturalReply(player, maid, reply);
            }
            case SOCIAL_REPLY -> {
                clearClarification(player.getUUID(), maid.getUUID());
                String reply = sanitizeModelText(interpretation.responseText());
                maid.conversationMemory().appendAssistant(reply);
                displayNaturalReply(player, maid, reply);
            }
        }
    }

    private static void handleDomainEvent(MaidDomainEvent domainEvent) {
        if (!(domainEvent instanceof MaidWorkflowLifecycleEvent event)
                || !event.sourceId().startsWith("LLM")) {
            return;
        }
        switch (event.event()) {
            case "workflow_replan_requested" -> submitWorkflowRequest(event, MaidLlmRequestKind.WORKFLOW_REPLAN);
            case "workflow_completed", "workflow_failed" ->
                    submitWorkflowRequest(event, MaidLlmRequestKind.WORKFLOW_OUTCOME);
            case "workflow_cancelled" -> cancelWorkflowRequest(event.workflowId());
            default -> {
                // Intermediate evidence remains inside the authoritative workflow runtime.
            }
        }
    }

    /** Stops a model call for a workflow that has already reached a cancelled terminal state. */
    private static void cancelWorkflowRequest(UUID workflowId) {
        WorkflowRequest request = WORKFLOW_REQUESTS.remove(workflowId);
        if (request != null) {
            request.future().cancel(true);
        }
    }

    private static void submitWorkflowRequest(
            MaidWorkflowLifecycleEvent event,
            MaidLlmRequestKind kind
    ) {
        if (!(event.partner().getOwner() instanceof ServerPlayer player)) {
            if (kind == MaidLlmRequestKind.WORKFLOW_REPLAN) {
                event.partner().rejectWorkflowReplan(event.workflowId(), "owner_offline_during_replan");
            }
            return;
        }
        AiPartnerEntity maid = event.partner();
        MaidControlLlmGateway gateway = MaidControlLlmGateway.getInstance();
        String environmentVariable = maid.getLlmApiKeyEnvironmentVariable();
        String readinessError = gateway.readinessError(environmentVariable);
        if (readinessError != null) {
            if (kind == MaidLlmRequestKind.WORKFLOW_REPLAN) {
                maid.rejectWorkflowReplan(event.workflowId(), readinessError);
            } else {
                displayGroundedFallback(player, maid, event);
            }
            return;
        }

        MaidControlContextSnapshot context = MaidControlContextSnapshot.capture(maid, player, "", "");
        CompletableFuture<MaidControlLlmResult> future = gateway.request(
                kind,
                "",
                context,
                maid.conversationMemory().snapshot(),
                MaidWorkflowFeedback.from(event),
                environmentVariable
        );
        WorkflowRequest pending = new WorkflowRequest(
                player.getUUID(),
                maid.getUUID(),
                kind,
                event,
                future
        );
        WorkflowRequest previous = WORKFLOW_REQUESTS.put(event.workflowId(), pending);
        if (previous != null) {
            previous.future().cancel(true);
        }
        MinecraftServer server = player.level().getServer();
        future.whenComplete((result, throwable) -> server.execute(() -> completeWorkflowRequest(
                server,
                event.workflowId(),
                pending,
                result,
                throwable
        )));
    }

    private static void completeWorkflowRequest(
            MinecraftServer server,
            UUID workflowId,
            WorkflowRequest pending,
            @Nullable MaidControlLlmResult result,
            @Nullable Throwable throwable
    ) {
        if (!WORKFLOW_REQUESTS.remove(workflowId, pending)) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId());
        if (player == null) {
            return;
        }
        AiPartnerEntity maid = findOwnedMaid(player, pending.maidId());
        if (maid == null) {
            return;
        }
        if (throwable != null || result == null || !result.successful() || result.interpretation() == null) {
            AiPartnerMod.LOGGER.warn(
                    "Workflow LLM request failed: kind={}, workflow={}, code={}, latencyMs={}, attempts={}",
                    pending.kind(),
                    workflowId,
                    workflowRequestErrorCode(result, throwable),
                    result == null ? 0L : result.latencyMillis(),
                    result == null ? 0 : result.attempts()
            );
            if (pending.kind() == MaidLlmRequestKind.WORKFLOW_REPLAN) {
                maid.rejectWorkflowReplan(workflowId, "workflow_model_request_failed");
            } else if (!(unwrap(throwable) instanceof java.util.concurrent.CancellationException)) {
                displayGroundedFallback(player, maid, pending.event());
            }
            return;
        }

        MaidControlInterpretation interpretation = result.interpretation();
        if (pending.kind() == MaidLlmRequestKind.WORKFLOW_REPLAN) {
            if (interpretation.dialogueAct() != MaidControlDialogueAct.PROPOSE_PLAN
                    || interpretation.plan().isEmpty()) {
                maid.rejectWorkflowReplan(workflowId, "invalid_replan_model_output");
                return;
            }
            String reply = sanitizeModelText(interpretation.responseText());
            if (maid.applyWorkflowReplan(workflowId, interpretation.plan(), player)) {
                maid.conversationMemory().appendAssistant(reply);
                displayNaturalReply(player, maid, reply);
            }
            return;
        }

        if (interpretation.dialogueAct() != MaidControlDialogueAct.SOCIAL_REPLY
                || !interpretation.plan().isEmpty()) {
            AiPartnerMod.LOGGER.warn(
                    "Workflow LLM response violated request kind: kind={}, workflow={}, dialogueAct={}, planSteps={}",
                    pending.kind(),
                    workflowId,
                    interpretation.dialogueAct(),
                    interpretation.plan().size()
            );
            displayGroundedFallback(player, maid, pending.event());
            return;
        }
        String reply = sanitizeModelText(interpretation.responseText());
        maid.conversationMemory().appendAssistant(reply);
        displayGroundedOutcome(player, maid, pending.event(), reply);
    }

    private static void displayNaturalReply(ServerPlayer player, AiPartnerEntity maid, String reply) {
        player.sendSystemMessage(Component.translatable("message.ai-partner.social_reply", reply));
        maid.showSpeechBubble(Component.literal(reply));
    }

    private static void displayGroundedOutcome(
            ServerPlayer player,
            AiPartnerEntity maid,
            MaidWorkflowLifecycleEvent event,
            String reply
    ) {
        Component message = event.event().equals("workflow_completed")
                ? Component.translatable("message.ai-partner.workflow_completed_dialogue", reply)
                : Component.translatable(
                        "message.ai-partner.workflow_failed_dialogue",
                        event.failureCode().name(),
                        reply
                );
        player.sendSystemMessage(message);
        maid.showSpeechBubble(Component.literal(reply));
    }

    private static void displayGroundedFallback(
            ServerPlayer player,
            AiPartnerEntity maid,
            MaidWorkflowLifecycleEvent event
    ) {
        String reply = groundedFallbackReply(event);
        maid.conversationMemory().appendAssistant(reply);
        displayGroundedOutcome(player, maid, event, reply);
    }

    /** Produces a truthful in-character fallback without asking a failed model to invent details. */
    private static String groundedFallbackReply(MaidWorkflowLifecycleEvent event) {
        boolean chinese = containsCjk(event.originalRequest());
        if (event.event().equals("workflow_completed")) {
            return chinese
                    ? "主人，服务器已经确认这项任务完成了。"
                    : "The server has confirmed that the task is complete.";
        }
        return chinese
                ? "主人，这项任务没有完成，服务器给出的原因是 " + event.failureCode().name() + "。"
                : "The task did not complete; the server reported " + event.failureCode().name() + ".";
    }

    private static boolean containsCjk(String value) {
        return value.codePoints().anyMatch(codePoint -> codePoint >= 0x3400 && codePoint <= 0x9FFF);
    }

    private static String workflowRequestErrorCode(
            @Nullable MaidControlLlmResult result,
            @Nullable Throwable throwable
    ) {
        if (result != null && result.errorCode() != null) {
            return result.errorCode();
        }
        Throwable root = unwrap(throwable);
        return root == null ? "UNKNOWN" : root.getClass().getSimpleName();
    }

    private static AiPartnerEntity findOwnedMaid(ServerPlayer player, UUID maidId) {
        return PartnerService.findOwnedPartners(player).stream()
                .filter(candidate -> candidate.getUUID().equals(maidId))
                .findFirst()
                .orElse(null);
    }

    private static boolean isEmergencyCancel(MaidControlInterpretation interpretation) {
        return interpretation.dialogueAct() == MaidControlDialogueAct.PROPOSE_INTENT
                && interpretation.intent() instanceof MaidControlIntent.RunTask runTask
                && runTask.job().type() == JobType.CANCEL;
    }

    private static @Nullable ClarificationContext activeClarification(UUID playerId, UUID maidId) {
        ConversationKey key = new ConversationKey(playerId, maidId);
        ClarificationContext context = CLARIFICATIONS.get(key);
        if (context != null && System.nanoTime() - context.startedNanos() > CLARIFICATION_TTL_NANOS) {
            CLARIFICATIONS.remove(key, context);
            return null;
        }
        return context;
    }

    private static void clearClarification(UUID playerId, UUID maidId) {
        CLARIFICATIONS.remove(new ConversationKey(playerId, maidId));
    }

    public static boolean hasPending(UUID playerId, UUID maidId) {
        PendingRequest pending = PENDING_REQUESTS.get(playerId);
        if (pending != null && pending.maidId().equals(maidId)) {
            return true;
        }
        return WORKFLOW_REQUESTS.values().stream()
                .anyMatch(request -> request.playerId().equals(playerId) && request.maidId().equals(maidId));
    }

    /** Cancels the player's outstanding initial or workflow model requests, if any. */
    public static void cancelPending(UUID playerId) {
        PendingRequest pending = PENDING_REQUESTS.remove(playerId);
        if (pending != null) {
            pending.future().cancel(true);
        }
        for (Map.Entry<UUID, WorkflowRequest> entry : WORKFLOW_REQUESTS.entrySet()) {
            WorkflowRequest request = entry.getValue();
            if (request.playerId().equals(playerId) && WORKFLOW_REQUESTS.remove(entry.getKey(), request)) {
                request.future().cancel(true);
                if (request.kind() == MaidLlmRequestKind.WORKFLOW_REPLAN) {
                    request.event().partner().interruptWorkflow(
                            request.event().workflowId(),
                            request.event().actor(),
                            "workflow_replan_request_cancelled"
                    );
                }
            }
        }
    }

    /** Cancels all outstanding model requests during server shutdown. */
    public static void cancelAll() {
        for (PendingRequest pending : PENDING_REQUESTS.values()) {
            pending.future().cancel(true);
        }
        for (WorkflowRequest pending : WORKFLOW_REQUESTS.values()) {
            pending.future().cancel(true);
        }
        PENDING_REQUESTS.clear();
        WORKFLOW_REQUESTS.clear();
        CLARIFICATIONS.clear();
        LAST_LLM_REQUEST_NANOS.clear();
    }

    private static String sanitizeModelText(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return "...";
        }
        String sanitized = text.replaceAll("[\\p{Cntrl}]+", " ").strip();
        return sanitized.length() <= 240 ? sanitized : sanitized.substring(0, 240);
    }

    private static @Nullable Throwable unwrap(@Nullable Throwable throwable) {
        Throwable current = throwable;
        while (current != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record PendingRequest(
            UUID requestId,
            UUID maidId,
            String instruction,
            CompletableFuture<MaidControlLlmResult> future
    ) {
    }

    private record WorkflowRequest(
            UUID playerId,
            UUID maidId,
            MaidLlmRequestKind kind,
            MaidWorkflowLifecycleEvent event,
            CompletableFuture<MaidControlLlmResult> future
    ) {
    }

    private record ConversationKey(UUID playerId, UUID maidId) {
    }

    private record ClarificationContext(String previousInstruction, String question, long startedNanos) {
    }
}
