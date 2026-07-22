package io.github.ozozorz.aipartner.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ozozorz.aipartner.control.MaidControlIntent;
import io.github.ozozorz.aipartner.control.MaidControlInterpretation;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the server-owned response contract for each model request phase. */
class MaidLlmRequestKindTest {
    @Test
    void outcomeAcceptsOnlyEmptySocialReply() {
        assertTrue(MaidLlmRequestKind.WORKFLOW_OUTCOME.accepts(
                MaidControlInterpretation.social("The verified task is complete.")
        ));
        assertFalse(MaidLlmRequestKind.WORKFLOW_OUTCOME.accepts(
                MaidControlInterpretation.plan(
                        List.of(new MaidControlIntent.QueryStatus()),
                        "I will check again."
                )
        ));
    }

    @Test
    void replanRequiresAtLeastOneBoundedAction() {
        assertTrue(MaidLlmRequestKind.WORKFLOW_REPLAN.accepts(
                MaidControlInterpretation.plan(
                        List.of(new MaidControlIntent.QueryInventory()),
                        "I will use the remaining safe step."
                )
        ));
        assertFalse(MaidLlmRequestKind.WORKFLOW_REPLAN.accepts(
                MaidControlInterpretation.social("There is no replacement plan.")
        ));
    }
}
