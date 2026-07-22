package io.github.ozozorz.aipartner.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Proves that every sealed control intent has a complete IBC action declaration. */
class MaidActionRegistryTest {
    @Test
    void registryCoversEveryPermittedIntentSubtype() {
        Set<Class<?>> permitted = Arrays.stream(MaidControlIntent.class.getPermittedSubclasses())
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(permitted, MaidActionRegistry.contracts().keySet());
    }

    @Test
    void everyActionDeclaresPreconditionsGoalsAndInvariants() {
        for (MaidActionContract contract : MaidActionRegistry.contracts().values()) {
            assertFalse(contract.preconditions().isEmpty(), contract.actionKind());
            assertFalse(contract.goals().isEmpty(), contract.actionKind());
            assertFalse(contract.invariants().isEmpty(), contract.actionKind());
            assertTrue(contract.preconditions().contains(MaidContractPredicate.ACTOR_IS_OWNER));
            assertTrue(contract.invariants().contains(MaidContractPredicate.OWNER_AND_DIMENSION_PRESERVED));
        }
    }
}
