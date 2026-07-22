package io.github.ozozorz.aipartner.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import org.junit.jupiter.api.Test;

/** Verifies that the gameplay LLM boundary only accepts the strict v2 intent protocol. */
class MaidControlJsonCodecTest {
    @Test
    void decodesPersistentWorkIntent() {
        MaidControlInterpretation result = MaidControlJsonCodec.decode("""
                {
                  "schema_version": "2.0",
                  "dialogue_act": "PROPOSE_INTENT",
                  "intent": {"kind": "SET_WORK_MODE", "mode": "lumberjack"},
                  "response_text": null
                }
                """);

        MaidControlIntent.SetWorkMode intent = assertInstanceOf(
                MaidControlIntent.SetWorkMode.class,
                result.intent()
        );
        assertEquals(MaidWorkMode.LUMBERJACK, intent.mode());
    }

    @Test
    void decodesBoundedTaskAndQuery() {
        MaidControlInterpretation taskResult = MaidControlJsonCodec.decode("""
                {
                  "schema_version": "2.0",
                  "dialogue_act": "PROPOSE_INTENT",
                  "intent": {
                    "kind": "RUN_TASK",
                    "job_type": "COLLECT_BLOCK",
                    "target": "minecraft:oak_log",
                    "quantity": 8,
                    "radius": 16
                  },
                  "response_text": null
                }
                """);
        MaidControlIntent.RunTask task = assertInstanceOf(MaidControlIntent.RunTask.class, taskResult.intent());
        assertEquals(JobType.COLLECT_BLOCK, task.job().type());
        assertEquals(8, task.job().quantity());

        MaidControlInterpretation queryResult = MaidControlJsonCodec.decode("""
                {
                  "schema_version": "2.0",
                  "dialogue_act": "PROPOSE_INTENT",
                  "intent": {"kind": "QUERY_STATUS"},
                  "response_text": null
                }
                """);
        assertInstanceOf(MaidControlIntent.QueryStatus.class, queryResult.intent());
    }

    @Test
    void decodesClarificationWithoutIntent() {
        MaidControlInterpretation result = MaidControlJsonCodec.decode("""
                {
                  "schema_version": "2.0",
                  "dialogue_act": "ASK_CLARIFICATION",
                  "intent": null,
                  "response_text": "Which item should I move?"
                }
                """);

        assertEquals(MaidControlDialogueAct.ASK_CLARIFICATION, result.dialogueAct());
        assertNull(result.intent());
        assertEquals("Which item should I move?", result.responseText());
    }

    @Test
    void rejectsArbitraryFieldsAndInvalidBasicParameters() {
        assertThrows(IllegalArgumentException.class, () -> MaidControlJsonCodec.decode("""
                {
                  "schema_version": "2.0",
                  "dialogue_act": "PROPOSE_INTENT",
                  "intent": {"kind": "QUERY_STATUS", "command": "/op @s"},
                  "response_text": null
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> MaidControlJsonCodec.decode("""
                {
                  "schema_version": "2.0",
                  "dialogue_act": "PROPOSE_INTENT",
                  "intent": {
                    "kind": "RUN_TASK",
                    "job_type": "FOLLOW",
                    "target": "minecraft:diamond",
                    "quantity": 1,
                    "radius": 16
                  },
                  "response_text": null
                }
                """));
    }

    @Test
    void rejectsMarkdownAndDialoguePayloadMismatch() {
        assertThrows(IllegalArgumentException.class, () -> MaidControlJsonCodec.decode("""
                ```json
                {"schema_version":"2.0"}
                ```
                """));
        assertThrows(IllegalArgumentException.class, () -> MaidControlJsonCodec.decode("""
                {
                  "schema_version": "2.0",
                  "dialogue_act": "SOCIAL_REPLY",
                  "intent": {"kind": "QUERY_STATUS"},
                  "response_text": "Hello"
                }
                """));
    }
}
