package io.github.ozozorz.aipartner.conversation;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.service.PartnerService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import net.minecraft.server.level.ServerPlayer;

/** Resolves an optional {@code @maid name} prefix without changing global selection. */
public final class MaidConversationTargetResolver {
    private MaidConversationTargetResolver() {
    }

    /** Resolves a named target first, then the packet-bound maid, then the selected maid. */
    public static Resolution resolve(
            ServerPlayer player,
            @Nullable UUID fallbackMaidId,
            String rawMessage
    ) {
        String message = rawMessage == null ? "" : rawMessage.strip();
        List<AiPartnerEntity> owned = PartnerService.findOwnedPartners(player);
        if (owned.isEmpty()) {
            return Resolution.failure("message.ai-partner.not_found", "");
        }
        if (message.startsWith("@")) {
            return resolvePrefixed(owned, message.substring(1).stripLeading());
        }
        if (message.isEmpty()) {
            return Resolution.failure("message.ai-partner.conversation.empty", "");
        }
        if (fallbackMaidId != null) {
            Optional<AiPartnerEntity> fallback = owned.stream()
                    .filter(maid -> maid.getUUID().equals(fallbackMaidId))
                    .findFirst();
            if (fallback.isPresent()) {
                return Resolution.success(fallback.get(), message);
            }
            return Resolution.failure("message.ai-partner.conversation.target_unavailable", "");
        }
        return PartnerService.findOwnedPartner(player)
                .map(maid -> Resolution.success(maid, message))
                .orElseGet(() -> Resolution.failure("message.ai-partner.not_found", ""));
    }

    private static Resolution resolvePrefixed(List<AiPartnerEntity> owned, String remainder) {
        if (remainder.isBlank()) {
            return Resolution.failure("message.ai-partner.conversation.empty", "");
        }
        List<NameMatch> matches = new ArrayList<>();
        String normalizedRemainder = remainder.toLowerCase(Locale.ROOT);
        for (AiPartnerEntity maid : owned) {
            String name = maid.getName().getString().strip();
            if (hasSelectorPrefix(normalizedRemainder, name.toLowerCase(Locale.ROOT))) {
                matches.add(new NameMatch(maid, name.length()));
            }
        }
        if (!matches.isEmpty()) {
            int longest = matches.stream().mapToInt(NameMatch::selectorLength).max().orElse(0);
            List<NameMatch> longestMatches = matches.stream()
                    .filter(match -> match.selectorLength() == longest)
                    .toList();
            if (longestMatches.size() != 1) {
                return Resolution.failure("message.ai-partner.conversation.target_ambiguous", "");
            }
            NameMatch match = longestMatches.getFirst();
            return remainingInstruction(match.maid(), remainder.substring(match.selectorLength()));
        }

        int separator = firstWhitespace(remainder);
        String uuidPrefix = separator < 0 ? remainder : remainder.substring(0, separator);
        if (uuidPrefix.length() < 4) {
            return Resolution.failure("message.ai-partner.conversation.target_not_found", uuidPrefix);
        }
        List<AiPartnerEntity> uuidMatches = owned.stream()
                .filter(maid -> maid.getStringUUID().toLowerCase(Locale.ROOT)
                        .startsWith(uuidPrefix.toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(AiPartnerEntity::getStringUUID))
                .toList();
        if (uuidMatches.size() != 1) {
            return Resolution.failure(
                    uuidMatches.isEmpty()
                            ? "message.ai-partner.conversation.target_not_found"
                            : "message.ai-partner.conversation.target_ambiguous",
                    uuidPrefix
            );
        }
        return remainingInstruction(uuidMatches.getFirst(), remainder.substring(uuidPrefix.length()));
    }

    private static Resolution remainingInstruction(AiPartnerEntity maid, String remainder) {
        String instruction = remainder.strip();
        return instruction.isEmpty()
                ? Resolution.failure("message.ai-partner.conversation.empty", "")
                : Resolution.success(maid, instruction);
    }

    static boolean hasSelectorPrefix(String message, String selector) {
        return !selector.isEmpty()
                && message.startsWith(selector)
                && (message.length() == selector.length()
                || Character.isWhitespace(message.charAt(selector.length())));
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private record NameMatch(AiPartnerEntity maid, int selectorLength) {
    }

    public record Resolution(
            Optional<AiPartnerEntity> maid,
            String instruction,
            String errorKey,
            String errorDetail
    ) {
        private static Resolution success(AiPartnerEntity maid, String instruction) {
            return new Resolution(Optional.of(maid), instruction, "", "");
        }

        private static Resolution failure(String errorKey, String errorDetail) {
            return new Resolution(Optional.empty(), "", errorKey, errorDetail);
        }

        public boolean successful() {
            return maid.isPresent();
        }
    }
}
