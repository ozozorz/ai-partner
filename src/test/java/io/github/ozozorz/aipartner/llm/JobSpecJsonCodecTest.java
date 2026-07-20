package io.github.ozozorz.aipartner.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ozozorz.aipartner.job.JobType;
import org.junit.jupiter.api.Test;

/**
 * 验证模型输出只有通过严格结构边界后才能形成 JobSpec。
 */
class JobSpecJsonCodecTest {
    @Test
    void decodesValidCollectProposal() {
        String json = """
                {
                  "schema_version": "1.0",
                  "dialogue_act": "PROPOSE_JOB",
                  "candidate_job": {
                    "type": "COLLECT_BLOCK",
                    "target": "minecraft:oak_log",
                    "quantity": 8,
                    "radius": 16
                  },
                  "clarification_question": null
                }
                """;

        LlmInterpretation result = JobSpecJsonCodec.decode(json);

        assertEquals(DialogueAct.PROPOSE_JOB, result.dialogueAct());
        assertEquals(JobType.COLLECT_BLOCK, result.candidateJob().type());
        assertEquals(8, result.candidateJob().quantity());
        assertNull(result.clarificationQuestion());
    }

    @Test
    void acceptsClarificationWithoutCandidate() {
        String json = """
                {
                  "schema_version": "1.0",
                  "dialogue_act": "ASK_CLARIFICATION",
                  "candidate_job": null,
                  "clarification_question": "你想收集哪一种原木、多少块？"
                }
                """;

        LlmInterpretation result = JobSpecJsonCodec.decode(json);

        assertEquals(DialogueAct.ASK_CLARIFICATION, result.dialogueAct());
        assertNull(result.candidateJob());
    }

    @Test
    void rejectsMarkdownCodeFence() {
        assertThrows(IllegalArgumentException.class, () -> JobSpecJsonCodec.decode("""
                ```json
                {"schema_version":"1.0"}
                ```
                """));
    }

    @Test
    void rejectsUnexpectedField() {
        String json = """
                {
                  "schema_version": "1.0",
                  "dialogue_act": "PROPOSE_JOB",
                  "candidate_job": {"type": "FOLLOW", "shell_command": "op @s"},
                  "clarification_question": null
                }
                """;

        assertThrows(IllegalArgumentException.class, () -> JobSpecJsonCodec.decode(json));
    }

    @Test
    void rejectsMissingRequiredRootField() {
        String json = """
                {
                  "schema_version": "1.0",
                  "dialogue_act": "SOCIAL_REPLY",
                  "candidate_job": null
                }
                """;

        assertThrows(IllegalArgumentException.class, () -> JobSpecJsonCodec.decode(json));
    }
}

