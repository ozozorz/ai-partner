package io.github.ozozorz.aipartner.control;

import java.util.List;
import java.util.Objects;

/**
 * Executable IBC declaration for one whitelisted semantic action.
 * Predicate identifiers are evaluated by {@link MaidActionRegistry} before and after execution.
 */
public record MaidActionContract(
        String actionKind,
        MaidActionCompletion acceptedCompletion,
        List<MaidContractPredicate> preconditions,
        List<MaidContractPredicate> goals,
        List<MaidContractPredicate> invariants
) {
    public MaidActionContract {
        actionKind = Objects.requireNonNull(actionKind, "actionKind");
        acceptedCompletion = Objects.requireNonNull(acceptedCompletion, "acceptedCompletion");
        preconditions = List.copyOf(preconditions);
        goals = List.copyOf(goals);
        invariants = List.copyOf(invariants);
        if (actionKind.isBlank() || preconditions.isEmpty() || goals.isEmpty() || invariants.isEmpty()) {
            throw new IllegalArgumentException("Action contracts require a kind and complete predicate sections");
        }
    }
}
