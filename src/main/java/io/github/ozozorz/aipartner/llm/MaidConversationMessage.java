package io.github.ozozorz.aipartner.llm;

import java.util.Objects;

/** One bounded user or assistant turn supplied to the model for dialogue continuity. */
public record MaidConversationMessage(String role, String content) {
    public static final int MAX_CONTENT_LENGTH = 512;

    public MaidConversationMessage {
        role = Objects.requireNonNull(role, "role");
        content = Objects.requireNonNull(content, "content").strip();
        if (!(role.equals("user") || role.equals("assistant")) || content.isBlank()) {
            throw new IllegalArgumentException("Conversation history role or content is invalid");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
        }
    }
}
