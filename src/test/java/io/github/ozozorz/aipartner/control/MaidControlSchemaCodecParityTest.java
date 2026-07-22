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

/** Runs one v3 corpus through both Draft 2020-12 Schema and the production Java codec. */
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

    private static Stream<Arguments> protocolCases() {
        String thirtyTwoEmoji = "😀".repeat(32);
        String thirtyThreeEmoji = "😀".repeat(33);
        return Stream.of(
                valid("multi-step plan", root("PROPOSE_PLAN", "[" +
                        intentObject("SET_WORK_MODE", "\"mode\":\"none\"") + "," +
                        intentObject("RUN_TASK", "\"job_type\":\"COLLECT_BLOCK\",\"target\":\"minecraft:oak_log\",\"quantity\":8,\"radius\":16") + "]", "\"Starting now.\"")),
                valid("run basic task", plan(intentObject("RUN_TASK", "\"job_type\":\"FOLLOW\",\"target\":\"\",\"quantity\":0,\"radius\":0"))),
                valid("set work mode", plan(intentObject("SET_WORK_MODE", "\"mode\":\"lumberjack\""))),
                valid("set schedule", plan(intentObject("SET_SCHEDULE", "\"schedule\":\"DAY_SHIFT\""))),
                valid("set combat policy", plan(intentObject("SET_COMBAT_POLICY", "\"policy\":\"defend-owner\""))),
                valid("return home", plan(intentObject("RETURN_HOME", ""))),
                valid("configure location", plan(intentObject("CONFIGURE_LOCATION", "\"location\":\"WORK\",\"clear\":false"))),
                valid("set home bound", plan(intentObject("SET_HOME_BOUND", "\"enabled\":true"))),
                valid("set radius", plan(intentObject("SET_RADIUS", "\"radius\":24"))),
                valid("rename unicode", plan(intentObject("RENAME", "\"name\":\"" + thirtyTwoEmoji + "\""))),
                valid("query status", plan(intentObject("QUERY_STATUS", ""))),
                valid("query inventory", plan(intentObject("QUERY_INVENTORY", ""))),
                valid("retrieve inventory", plan(intentObject("RETRIEVE_INVENTORY", ""))),
                valid("clarification", root("ASK_CLARIFICATION", "[]", "\"Which item?\"")),
                valid("social reply", root("SOCIAL_REPLY", "[]", "\"Hello\"")),
                valid("unsupported request", root("REJECT_UNSUPPORTED", "[]", "\"I cannot build that.\"")),
                invalid("v2 protocol rejected", "{\"schema_version\":\"2.0\",\"dialogue_act\":\"SOCIAL_REPLY\",\"plan\":[],\"response_text\":\"hello\"}"),
                invalid("basic task carries parameters", plan(intentObject("RUN_TASK", "\"job_type\":\"FOLLOW\",\"target\":\"minecraft:diamond\",\"quantity\":1,\"radius\":16"))),
                invalid("bounded task has zero parameters", plan(intentObject("RUN_TASK", "\"job_type\":\"COLLECT_BLOCK\",\"target\":\"minecraft:oak_log\",\"quantity\":0,\"radius\":0"))),
                invalid("work mode alias", plan(intentObject("SET_WORK_MODE", "\"mode\":\"SUGAR_CANE\""))),
                invalid("rename exceeds unicode limit", plan(intentObject("RENAME", "\"name\":\"" + thirtyThreeEmoji + "\""))),
                invalid("blank clarification", root("ASK_CLARIFICATION", "[]", "\"　\"")),
                invalid("proposal has empty plan", root("PROPOSE_PLAN", "[]", "\"Okay\"")),
                invalid("proposal has null response", root("PROPOSE_PLAN", "[" + intentObject("QUERY_STATUS", "") + "]", "null")),
                invalid("social reply carries plan", root("SOCIAL_REPLY", "[" + intentObject("QUERY_STATUS", "") + "]", "\"hello\"")),
                invalid("intent has extra field", root("PROPOSE_PLAN", "[{\"kind\":\"QUERY_STATUS\",\"command\":\"/op @s\"}]", "\"Checking\"")),
                invalid("plan exceeds six", root("PROPOSE_PLAN", "[" + String.join(",", java.util.Collections.nCopies(7, intentObject("QUERY_STATUS", ""))) + "]", "\"Checking\""))
        );
    }

    private static Arguments valid(String label, String json) {
        return Arguments.of(label, json, true);
    }

    private static Arguments invalid(String label, String json) {
        return Arguments.of(label, json, false);
    }

    private static String plan(String intent) {
        return root("PROPOSE_PLAN", "[" + intent + "]", "\"I will do that.\"");
    }

    private static String intentObject(String kind, String fields) {
        String suffix = fields.isEmpty() ? "" : "," + fields;
        return "{\"kind\":\"" + kind + "\"" + suffix + "}";
    }

    private static String root(String dialogueAct, String plan, String responseText) {
        return "{\"schema_version\":\"3.0\",\"dialogue_act\":\"" + dialogueAct
                + "\",\"plan\":" + plan + ",\"response_text\":" + responseText + "}";
    }

    private static boolean codecAccepts(String json) {
        try {
            MaidControlJsonCodec.decode(json);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

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
