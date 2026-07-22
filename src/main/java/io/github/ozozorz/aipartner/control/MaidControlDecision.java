package io.github.ozozorz.aipartner.control;

import io.github.ozozorz.aipartner.contract.FailureCode;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Server-authoritative action receipt consumed by commands, menus, and workflows. */
public record MaidControlDecision(
        MaidActionCompletion completion,
        Component message,
        String evidence,
        FailureCode failureCode,
        @Nullable UUID taskContractId
) {
    public boolean accepted() {
        return completion != MaidActionCompletion.REJECTED;
    }

    public boolean completed() {
        return completion == MaidActionCompletion.COMPLETED;
    }

    public static MaidControlDecision completed(Component message, String evidence) {
        return new MaidControlDecision(
                MaidActionCompletion.COMPLETED,
                message,
                evidence,
                FailureCode.NONE,
                null
        );
    }

    public static MaidControlDecision completed(Component message, String evidence, UUID taskContractId) {
        return new MaidControlDecision(
                MaidActionCompletion.COMPLETED,
                message,
                evidence,
                FailureCode.NONE,
                taskContractId
        );
    }

    public static MaidControlDecision running(Component message, String evidence, UUID taskContractId) {
        return new MaidControlDecision(
                MaidActionCompletion.RUNNING,
                message,
                evidence,
                FailureCode.NONE,
                taskContractId
        );
    }

    public static MaidControlDecision rejected(Component message, FailureCode failureCode, String evidence) {
        return new MaidControlDecision(
                MaidActionCompletion.REJECTED,
                message,
                evidence,
                failureCode,
                null
        );
    }

    public Optional<UUID> relatedTaskContractId() {
        return Optional.ofNullable(taskContractId);
    }
}
