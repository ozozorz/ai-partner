package io.github.ozozorz.aipartner.conversation;

import io.github.ozozorz.aipartner.control.LocalMaidIntentParser;
import io.github.ozozorz.aipartner.control.MaidControlDecision;
import io.github.ozozorz.aipartner.control.MaidControlInterpretation;
import io.github.ozozorz.aipartner.control.MaidControlService;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * 在服务端同步解析本地自然语言，并把单个类型化意图交给统一契约层执行。
 */
public final class MaidConversationService {
    public static final int MAX_MESSAGE_LENGTH = 512;
    private static final long CLARIFICATION_TTL_NANOS = Duration.ofMinutes(2).toNanos();
    private static final Map<ConversationKey, ClarificationContext> CLARIFICATIONS = new ConcurrentHashMap<>();

    private MaidConversationService() {
    }

    /**
     * 接收一条自然语言消息，解析目标女仆并执行一个受验证的语义动作。
     */
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
        ConversationKey key = new ConversationKey(player.getUUID(), maid.getUUID());
        ClarificationContext clarification = activeClarification(key);
        String parseInput = clarification == null
                ? instruction
                : clarification.previousInstruction() + " " + instruction;
        handleInterpretation(player, maid, instruction, key, LocalMaidIntentParser.parse(parseInput));
        return true;
    }

    private static void handleInterpretation(
            ServerPlayer player,
            AiPartnerEntity maid,
            String instruction,
            ConversationKey key,
            MaidControlInterpretation interpretation
    ) {
        switch (interpretation.dialogueAct()) {
            case PROPOSE_INTENT -> {
                CLARIFICATIONS.remove(key);
                MaidControlDecision decision = MaidControlService.apply(
                        maid,
                        player,
                        interpretation.intent(),
                        instruction,
                        "LOCAL_NATURAL_LANGUAGE"
                );
                display(player, maid, decision.message());
            }
            case ASK_CLARIFICATION -> {
                String question = sanitizeText(interpretation.responseText());
                CLARIFICATIONS.put(key, new ClarificationContext(instruction, System.nanoTime()));
                display(player, maid, Component.literal(question));
            }
            case REJECT_UNSUPPORTED -> {
                CLARIFICATIONS.remove(key);
                Component response = interpretation.responseText() == null
                        ? Component.translatable("message.ai-partner.conversation.unsupported")
                        : Component.literal(sanitizeText(interpretation.responseText()));
                display(player, maid, response);
            }
            case SOCIAL_REPLY -> {
                CLARIFICATIONS.remove(key);
                display(player, maid, Component.literal(sanitizeText(interpretation.responseText())));
            }
        }
    }

    private static void display(ServerPlayer player, AiPartnerEntity maid, Component message) {
        player.sendSystemMessage(message);
        maid.showSpeechBubble(message);
    }

    private static @Nullable ClarificationContext activeClarification(ConversationKey key) {
        ClarificationContext context = CLARIFICATIONS.get(key);
        if (context != null && System.nanoTime() - context.startedNanos() <= CLARIFICATION_TTL_NANOS) {
            return context;
        }
        CLARIFICATIONS.remove(key);
        return null;
    }

    private static String sanitizeText(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return "...";
        }
        String sanitized = text.replaceAll("[\\p{Cntrl}]+", " ").strip();
        return sanitized.length() <= 240 ? sanitized : sanitized.substring(0, 240);
    }

    private record ConversationKey(UUID playerId, UUID maidId) {
    }

    private record ClarificationContext(String previousInstruction, long startedNanos) {
    }
}
