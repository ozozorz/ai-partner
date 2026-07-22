package io.github.ozozorz.aipartner.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import org.junit.jupiter.api.Test;

/** Verifies the strict v3 boundary that combines natural dialogue with a bounded action plan. */
class MaidControlJsonCodecTest {
    @Test
    void decodesDialogueAndPersistentWorkPlan() {
        MaidControlInterpretation result = MaidControlJsonCodec.decode("""
                {
                  "schema_version": "3.0",
                  "dialogue_act": "PROPOSE_PLAN",
                  "plan": [{"kind": "SET_WORK_MODE", "mode": "lumberjack"}],
                  "response_text": "I will start tending the nearby trees."
                }
                """);

        MaidControlIntent.SetWorkMode intent = assertInstanceOf(
                MaidControlIntent.SetWorkMode.class,
                result.intent()
        );
        assertEquals(MaidWorkMode.LUMBERJACK, intent.mode());
        assertEquals("I will start tending the nearby trees.", result.responseText());
    }

    @Test
    void decodesOrderedMultiStepPlan() {
        MaidControlInterpretation result = MaidControlJsonCodec.decode("""
                {
                  "schema_version": "3.0",
                  "dialogue_act": "PROPOSE_PLAN",
                  "plan": [
                    {"kind":"SET_WORK_MODE","mode":"none"},
                    {"kind":"RUN_TASK","job_type":"COLLECT_BLOCK","target":"minecraft:oak_log","quantity":8,"radius":16},
                    {"kind":"RUN_TASK","job_type":"DEPOSIT_ITEM","target":"minecraft:oak_log","quantity":8,"radius":16}
                  ],
                  "response_text": "I will stop my routine, collect eight logs, then store them."
                }
                """);

        assertEquals(3, result.plan().size());
        MaidControlIntent.RunTask collect = assertInstanceOf(
                MaidControlIntent.RunTask.class,
                result.plan().get(1)
        );
        assertEquals(JobType.COLLECT_BLOCK, collect.job().type());
        assertEquals(8, collect.job().quantity());
    }

    @Test
    void decodesClarificationWithoutPlan() {
        MaidControlInterpretation result = MaidControlJsonCodec.decode("""
                {
                  "schema_version": "3.0",
                  "dialogue_act": "ASK_CLARIFICATION",
                  "plan": [],
                  "response_text": "Which item should I move?"
                }
                """);

        assertEquals(MaidControlDialogueAct.ASK_CLARIFICATION, result.dialogueAct());
        assertNull(result.intent());
        assertEquals("Which item should I move?", result.responseText());
    }

    @Test
    void persistedIntentRoundTripsThroughSameWhitelist() {
        MaidControlIntent original = new MaidControlIntent.RunTask(
                new io.github.ozozorz.aipartner.contract.JobSpec(
                        JobType.COLLECT_AND_DEPOSIT,
                        "minecraft:birch_log",
                        5,
                        12
                )
        );

        MaidControlIntent restored = MaidControlJsonCodec.decodePersistedIntent(
                MaidControlJsonCodec.encodeIntent(original)
        );

        assertEquals(original, restored);
    }

    @Test
    void rejectsArbitraryFieldsInvalidParametersAndUnboundedPlans() {
        assertThrows(IllegalArgumentException.class, () -> MaidControlJsonCodec.decode("""
                {"schema_version":"3.0","dialogue_act":"PROPOSE_PLAN","plan":[{"kind":"QUERY_STATUS","command":"/op @s"}],"response_text":"Checking."}
                """));
        assertThrows(IllegalArgumentException.class, () -> MaidControlJsonCodec.decode("""
                {"schema_version":"3.0","dialogue_act":"PROPOSE_PLAN","plan":[{"kind":"RUN_TASK","job_type":"FOLLOW","target":"minecraft:diamond","quantity":1,"radius":16}],"response_text":"Following."}
                """));
        assertThrows(IllegalArgumentException.class, () -> MaidControlJsonCodec.decode("""
                {"schema_version":"3.0","dialogue_act":"PROPOSE_PLAN","plan":[{"kind":"QUERY_STATUS"},{"kind":"QUERY_STATUS"},{"kind":"QUERY_STATUS"},{"kind":"QUERY_STATUS"},{"kind":"QUERY_STATUS"},{"kind":"QUERY_STATUS"},{"kind":"QUERY_STATUS"}],"response_text":"Checking."}
                """));
    }

    @Test
    void rejectsMarkdownAndDialoguePayloadMismatch() {
        assertThrows(IllegalArgumentException.class, () -> MaidControlJsonCodec.decode("""
                ```json
                {"schema_version":"3.0"}
                ```
                """));
        assertThrows(IllegalArgumentException.class, () -> MaidControlJsonCodec.decode("""
                {"schema_version":"3.0","dialogue_act":"SOCIAL_REPLY","plan":[{"kind":"QUERY_STATUS"}],"response_text":"Hello"}
                """));
    }
}
