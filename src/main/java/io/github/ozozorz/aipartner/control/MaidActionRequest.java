package io.github.ozozorz.aipartner.control;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Identifies the trusted ingress and optional workflow that invoked an action. */
public record MaidActionRequest(
        String rawInstruction,
        String sourceId,
        @Nullable UUID workflowId
) {
    public MaidActionRequest {
        rawInstruction = bounded(rawInstruction, 512);
        sourceId = bounded(Objects.requireNonNullElse(sourceId, "UNKNOWN"), 64);
        if (sourceId.isBlank()) {
            sourceId = "UNKNOWN";
        }
    }

    /** Creates an invocation made directly by a command, menu, or local parser. */
    public static MaidActionRequest direct(String rawInstruction, String sourceId) {
        return new MaidActionRequest(rawInstruction, sourceId, null);
    }

    /** Creates an invocation owned by a bounded workflow runtime. */
    public static MaidActionRequest workflow(String rawInstruction, String sourceId, UUID workflowId) {
        return new MaidActionRequest(rawInstruction, sourceId, Objects.requireNonNull(workflowId, "workflowId"));
    }

    public Optional<UUID> workflow() {
        return Optional.ofNullable(workflowId);
    }

    private static String bounded(String value, int maximumLength) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        return normalized.length() <= maximumLength ? normalized : normalized.substring(0, maximumLength);
    }
}
