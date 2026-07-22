package io.github.ozozorz.aipartner.conversation;

import io.github.ozozorz.aipartner.llm.MaidConversationMessage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Persisted, privacy-bounded dialogue memory owned by one maid entity. */
public final class MaidConversationMemory {
    public static final int MAX_MESSAGES = 12;
    private static final int FORMAT_VERSION = 1;
    private final List<MaidConversationMessage> messages = new ArrayList<>();

    public void appendUser(String content) {
        append(new MaidConversationMessage("user", content));
    }

    public void appendAssistant(String content) {
        append(new MaidConversationMessage("assistant", content));
    }

    public List<MaidConversationMessage> snapshot() {
        return List.copyOf(messages);
    }

    /** Writes only the last twelve bounded natural-language turns, never API keys or raw model envelopes. */
    public void save(ValueOutput output) {
        output.putInt("MaidConversationMemoryFormat", FORMAT_VERSION);
        output.putInt("MaidConversationMemoryCount", messages.size());
        for (int index = 0; index < messages.size(); index++) {
            MaidConversationMessage message = messages.get(index);
            output.putString("MaidConversationMemoryRole" + index, message.role());
            output.putString("MaidConversationMemoryContent" + index, message.content());
        }
    }

    /** Restores valid bounded turns and discards malformed or future formats fail-closed. */
    public void load(ValueInput input) {
        messages.clear();
        int count = input.getIntOr("MaidConversationMemoryCount", 0);
        if (count == 0) {
            return;
        }
        if (input.getIntOr("MaidConversationMemoryFormat", -1) != FORMAT_VERSION
                || count < 0 || count > MAX_MESSAGES) {
            return;
        }
        for (int index = 0; index < count; index++) {
            try {
                append(new MaidConversationMessage(
                        input.getStringOr("MaidConversationMemoryRole" + index, ""),
                        input.getStringOr("MaidConversationMemoryContent" + index, "")
                ));
            } catch (IllegalArgumentException exception) {
                messages.clear();
                return;
            }
        }
    }

    public void clear() {
        messages.clear();
    }

    private void append(MaidConversationMessage message) {
        if (messages.size() == MAX_MESSAGES) {
            messages.removeFirst();
        }
        messages.add(message);
    }
}
