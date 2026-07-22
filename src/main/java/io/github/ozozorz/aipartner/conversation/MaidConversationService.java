package io.github.ozozorz.aipartner.conversation;

import io.github.ozozorz.aipartner.control.LocalMaidIntentParser;
import io.github.ozozorz.aipartner.control.MaidControlDecision;
import io.github.ozozorz.aipartner.control.MaidControlDialogueAct;
import io.github.ozozorz.aipartner.control.MaidControlIntent;
import io.github.ozozorz.aipartner.control.MaidControlInterpretation;
import io.github.ozozorz.aipartner.control.MaidControlService;
import io.github.ozozorz.aipartner.control.MaidDriveMode;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.llm.MaidControlLlmGateway;
import io.github.ozozorz.aipartner.llm.MaidControlLlmResult;
import io.github.ozozorz.aipartner.service.PartnerService;
import io.github.ozozorz.aipartner.world.MaidControlContextSnapshot;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/** Coordinates targeting, local/LLM interpretation, stale-request rejection, and server execution. */
public final class MaidConversationService {
    public static final int MAX_MESSAGE_LENGTH = 512;
    private static final long LLM_RATE_LIMIT_NANOS = Duration.ofMillis(750).toNanos();
    private static final long CLARIFICATION_TTL_NANOS = Duration.ofMinutes(2).toNanos();
    private static final Map<UUID, PendingRequest> PENDING_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<ConversationKey, ClarificationContext> CLARIFICATIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_LLM_REQUEST_NANOS = new ConcurrentHashMap<>();

    private MaidConversationService() {
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
            handleInterpretation(player, maid, instruction, emergency, "LOCAL_EMERGENCY");
            return true;
        }
        if (maid.getDriveMode() == MaidDriveMode.LOCAL) {
            cancelPending(player.getUUID());
            ClarificationContext clarification = activeClarification(player.getUUID(), maid.getUUID());
            String parseInput = clarification == null
                    ? instruction
                    : clarification.previousInstruction() + " " + instruction;
            handleInterpretation(
                    player,
                    maid,
                    instruction,
                    LocalMaidIntentParser.parse(parseInput),
                    "LOCAL_NL"
            );
            return true;
        }
        return submitLlm(player, maid, instruction);
    }

    private static boolean submitLlm(ServerPlayer player, AiPartnerEntity maid, String instruction) {
        MaidControlLlmGateway gateway = MaidControlLlmGateway.getInstance();
        String environmentVariable = maid.getLlmApiKeyEnvironmentVariable();
        String readinessError = gateway.readinessError(environmentVariable);
        if (readinessError != null) {
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
        UUID playerId = player.getUUID();
        UUID maidId = maid.getUUID();
        MinecraftServer server = player.level().getServer();
        CompletableFuture<MaidControlLlmResult> future = gateway.interpret(
                instruction,
                context,
                environmentVariable
        );
        PendingRequest pending = new PendingRequest(UUID.randomUUID(), maidId, instruction, future);
        PendingRequest previous = PENDING_REQUESTS.put(playerId, pending);
        if (previous != null) {
            previous.future().cancel(true);
        }
        Component thinking = Component.translatable("message.ai-partner.thinking");
        player.sendSystemMessage(thinking);
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
        AiPartnerEntity maid = PartnerService.findOwnedPartners(player).stream()
                .filter(candidate -> candidate.getUUID().equals(pending.maidId()))
                .findFirst()
                .orElse(null);
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
            }
            case ASK_CLARIFICATION -> {
                String question = sanitizeModelText(interpretation.responseText());
                CLARIFICATIONS.put(
                        new ConversationKey(player.getUUID(), maid.getUUID()),
                        new ClarificationContext(instruction, question, System.nanoTime())
                );
                Component message = Component.translatable("message.ai-partner.model_clarification", question);
                player.sendSystemMessage(message);
                maid.showSpeechBubble(Component.literal(question));
            }
            case REJECT_UNSUPPORTED -> {
                clearClarification(player.getUUID(), maid.getUUID());
                Component message = Component.translatable("message.ai-partner.model_rejected");
                player.sendSystemMessage(message);
                maid.showSpeechBubble(message);
            }
            case SOCIAL_REPLY -> {
                clearClarification(player.getUUID(), maid.getUUID());
                String reply = sanitizeModelText(interpretation.responseText());
                player.sendSystemMessage(Component.translatable("message.ai-partner.social_reply", reply));
                maid.showSpeechBubble(Component.literal(reply));
            }
        }
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
        return pending != null && pending.maidId().equals(maidId);
    }

    /** Cancels the player's outstanding model request, if any. */
    public static void cancelPending(UUID playerId) {
        PendingRequest pending = PENDING_REQUESTS.remove(playerId);
        if (pending != null) {
            pending.future().cancel(true);
        }
    }

    /** Cancels all outstanding requests during server shutdown. */
    public static void cancelAll() {
        for (PendingRequest pending : PENDING_REQUESTS.values()) {
            pending.future().cancel(true);
        }
        PENDING_REQUESTS.clear();
        CLARIFICATIONS.clear();
        LAST_LLM_REQUEST_NANOS.clear();
    }

    private static String sanitizeModelText(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return "...";
        }
        String sanitized = text.replaceAll("[\\p{Cntrl}]+", " ").strip();
        return sanitized.length() <= 200 ? sanitized : sanitized.substring(0, 200);
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

    private record ConversationKey(UUID playerId, UUID maidId) {
    }

    private record ClarificationContext(String previousInstruction, String question, long startedNanos) {
    }
}
