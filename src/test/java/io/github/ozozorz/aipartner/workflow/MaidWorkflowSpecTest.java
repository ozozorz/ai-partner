package io.github.ozozorz.aipartner.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ozozorz.aipartner.control.MaidControlIntent;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.job.JobType;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the plan-size, recovery, timeout, and persistent-directive workflow bounds. */
class MaidWorkflowSpecTest {
    @Test
    void acceptsSixWhitelistedSequentialSteps() {
        List<MaidControlIntent> steps = Collections.nCopies(6, new MaidControlIntent.QueryStatus());

        MaidWorkflowSpec spec = MaidWorkflowSpec.llm(UUID.randomUUID(), "check repeatedly", steps);

        assertEquals(6, spec.steps().size());
        assertEquals(1, spec.maxReplans());
    }

    @Test
    void rejectsMoreThanSixSteps() {
        assertThrows(IllegalArgumentException.class, () -> MaidWorkflowSpec.llm(
                UUID.randomUUID(),
                "too many",
                Collections.nCopies(7, new MaidControlIntent.QueryStatus())
        ));
    }

    @Test
    void rejectsPersistentDirectiveBeforeFinalStep() {
        assertThrows(IllegalArgumentException.class, () -> MaidWorkflowSpec.llm(
                UUID.randomUUID(),
                "follow then query",
                List.of(
                        new MaidControlIntent.RunTask(JobSpec.basic(JobType.FOLLOW)),
                        new MaidControlIntent.QueryStatus()
                )
        ));
        assertThrows(IllegalArgumentException.class, () -> MaidWorkflowSpec.llm(
                UUID.randomUUID(),
                "return home then query",
                List.of(
                        new MaidControlIntent.ReturnHome(),
                        new MaidControlIntent.QueryStatus()
                )
        ));
    }

    @Test
    void replanMayPrepareButCannotDropOrMutatePendingGoals() {
        MaidControlIntent collectThree = new MaidControlIntent.RunTask(new JobSpec(
                JobType.COLLECT_BLOCK,
                "minecraft:oak_log",
                3,
                16
        ));
        MaidControlIntent depositThree = new MaidControlIntent.RunTask(new JobSpec(
                JobType.DEPOSIT_ITEM,
                "minecraft:oak_log",
                3,
                16
        ));
        List<MaidControlIntent> pending = List.of(collectThree, depositThree);

        assertTrue(MaidWorkflowSpec.preservesOrderedGoals(
                List.of(new MaidControlIntent.QueryInventory(), collectThree, depositThree),
                pending
        ));
        assertFalse(MaidWorkflowSpec.preservesOrderedGoals(List.of(depositThree), pending));
        assertFalse(MaidWorkflowSpec.preservesOrderedGoals(
                List.of(
                        new MaidControlIntent.RunTask(new JobSpec(
                                JobType.COLLECT_BLOCK,
                                "minecraft:oak_log",
                                1,
                                16
                        )),
                        depositThree
                ),
                pending
        ));
    }
}
