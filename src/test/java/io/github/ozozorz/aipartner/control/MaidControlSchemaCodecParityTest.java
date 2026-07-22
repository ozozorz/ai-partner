package io.github.ozozorz.aipartner.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 以同一组边界样本同时执行 Draft 2020-12 Schema 和 Java codec，防止两条验证边界漂移。
 */
class MaidControlSchemaCodecParityTest {
    private static final String SCHEMA_RESOURCE = "/assets/ai-partner/schema/maid_control.schema.json";
    private static final Schema SCHEMA = loadSchema();

    @ParameterizedTest(name = "{0}")
    @MethodSource("protocolCases")
    void schemaAndCodecAgreeOnProtocolCorpus(String label, String json, boolean expected) {
        List<com.networknt.schema.Error> schemaErrors = SCHEMA.validate(json, InputFormat.JSON);
        boolean codecAccepted = codecAccepts(json);

        assertEquals(expected, schemaErrors.isEmpty(), () -> label + " schema errors: " + schemaErrors);
        assertEquals(expected, codecAccepted, () -> label + " codec acceptance differed");
    }

    /**
     * 覆盖每一种 v2 intent，并针对已发现的条件参数、文本和标准化缺口加入反例。
     */
    private static Stream<Arguments> protocolCases() {
        String thirtyTwoEmoji = "😀".repeat(32);
        String thirtyThreeEmoji = "😀".repeat(33);
        return Stream.of(
                valid("run bounded task", intent("RUN_TASK", "\"job_type\":\"COLLECT_BLOCK\",\"target\":\"minecraft:oak_log\",\"quantity\":8,\"radius\":16")),
                valid("run basic task", intent("RUN_TASK", "\"job_type\":\"FOLLOW\",\"target\":\"\",\"quantity\":0,\"radius\":0")),
                valid("set work mode", intent("SET_WORK_MODE", "\"mode\":\"lumberjack\"")),
                valid("set schedule", intent("SET_SCHEDULE", "\"schedule\":\"DAY_SHIFT\"")),
                valid("set combat policy", intent("SET_COMBAT_POLICY", "\"policy\":\"defend-owner\"")),
                valid("return home", intent("RETURN_HOME", "")),
                valid("configure location", intent("CONFIGURE_LOCATION", "\"location\":\"WORK\",\"clear\":false")),
                valid("set home bound", intent("SET_HOME_BOUND", "\"enabled\":true")),
                valid("set radius", intent("SET_RADIUS", "\"radius\":24")),
                valid("rename with unicode code points", intent("RENAME", "\"name\":\"" + thirtyTwoEmoji + "\"")),
                valid("query status", intent("QUERY_STATUS", "")),
                valid("query inventory", intent("QUERY_INVENTORY", "")),
                valid("retrieve inventory", intent("RETRIEVE_INVENTORY", "")),
                validRoot("clarification", "ASK_CLARIFICATION", "null", "\"Which item?\""),
                validRoot("social reply", "SOCIAL_REPLY", "null", "\"Hello\""),
                validRoot("unsupported request", "REJECT_UNSUPPORTED", "null", "null"),
                invalid("basic task carries parameters", intent("RUN_TASK", "\"job_type\":\"FOLLOW\",\"target\":\"minecraft:diamond\",\"quantity\":1,\"radius\":16")),
                invalid("bounded task has zero parameters", intent("RUN_TASK", "\"job_type\":\"COLLECT_BLOCK\",\"target\":\"minecraft:oak_log\",\"quantity\":0,\"radius\":0")),
                invalid("bounded task has blank target", intent("RUN_TASK", "\"job_type\":\"COLLECT_BLOCK\",\"target\":\"　\",\"quantity\":1,\"radius\":1")),
                invalid("work mode alias is not protocol value", intent("SET_WORK_MODE", "\"mode\":\"SUGAR_CANE\"")),
                invalid("combat alias is not protocol value", intent("SET_COMBAT_POLICY", "\"policy\":\"DEFEND_OWNER\"")),
                invalid("rename is blank", intent("RENAME", "\"name\":\"　\"")),
                invalid("rename contains control", intent("RENAME", "\"name\":\"A\\u0000B\"")),
                invalid("rename exceeds unicode limit", intent("RENAME", "\"name\":\"" + thirtyThreeEmoji + "\"")),
                invalidRoot("blank clarification", "ASK_CLARIFICATION", "null", "\"　\""),
                invalidRoot("proposal carries response", "PROPOSE_INTENT", intentObject("QUERY_STATUS", ""), "\"done\""),
                invalidRoot("social reply carries intent", "SOCIAL_REPLY", intentObject("QUERY_STATUS", ""), "\"hello\""),
                invalid("intent has extra field", "{\"schema_version\":\"2.0\",\"dialogue_act\":\"PROPOSE_INTENT\",\"intent\":{\"kind\":\"QUERY_STATUS\",\"command\":\"/op @s\"},\"response_text\":null}")
        );
    }

    private static Arguments valid(String label, String json) {
        return Arguments.of(label, json, true);
    }

    private static Arguments invalid(String label, String json) {
        return Arguments.of(label, json, false);
    }

    private static Arguments validRoot(String label, String dialogueAct, String intent, String responseText) {
        return Arguments.of(label, root(dialogueAct, intent, responseText), true);
    }

    private static Arguments invalidRoot(String label, String dialogueAct, String intent, String responseText) {
        return Arguments.of(label, root(dialogueAct, intent, responseText), false);
    }

    private static String intent(String kind, String fields) {
        return root("PROPOSE_INTENT", intentObject(kind, fields), "null");
    }

    private static String intentObject(String kind, String fields) {
        String suffix = fields.isEmpty() ? "" : "," + fields;
        return "{\"kind\":\"" + kind + "\"" + suffix + "}";
    }

    private static String root(String dialogueAct, String intent, String responseText) {
        return "{\"schema_version\":\"2.0\",\"dialogue_act\":\"" + dialogueAct
                + "\",\"intent\":" + intent + ",\"response_text\":" + responseText + "}";
    }

    private static boolean codecAccepts(String json) {
        try {
            MaidControlJsonCodec.decode(json);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 从与生产网关相同的类路径资源加载 Draft 2020-12 Schema。
     */
    private static Schema loadSchema() {
        try (InputStream stream = Objects.requireNonNull(
                MaidControlSchemaCodecParityTest.class.getResourceAsStream(SCHEMA_RESOURCE),
                "Missing schema resource " + SCHEMA_RESOURCE
        )) {
            byte[] schemaBytes = stream.readAllBytes();
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(new String(schemaBytes, java.nio.charset.StandardCharsets.UTF_8), InputFormat.JSON);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load maid control schema", exception);
        }
    }
}
