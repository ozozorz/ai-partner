package io.github.ozozorz.aipartner.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.job.JobType;
import java.util.Set;

/**
 * 对模型输出执行与 JobSpec JSON Schema 等价的严格本地校验。
 */
public final class JobSpecJsonCodec {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema_version",
            "dialogue_act",
            "candidate_job",
            "clarification_question"
    );
    private static final Set<String> JOB_FIELDS = Set.of("type", "target", "quantity", "radius");

    private JobSpecJsonCodec() {
    }

    /**
     * 只接受单个 JSON 对象；Markdown 代码块、额外字段和类型不符都会失败。
     */
    public static LlmInterpretation decode(String rawJson) {
        try {
            JsonElement rootElement = JsonParser.parseString(rawJson);
            if (!rootElement.isJsonObject()) {
                throw invalid("root_not_object");
            }
            JsonObject root = rootElement.getAsJsonObject();
            requireOnlyFields(root, ROOT_FIELDS);
            if (!root.keySet().containsAll(ROOT_FIELDS)) {
                throw invalid("missing_root_field");
            }
            if (!"1.0".equals(requiredString(root, "schema_version"))) {
                throw invalid("unsupported_schema_version");
            }
            DialogueAct act = parseEnum(DialogueAct.class, requiredString(root, "dialogue_act"), "invalid_dialogue_act");
            String clarification = nullableString(root, "clarification_question");
            JobSpec job = null;
            JsonElement candidateElement = root.get("candidate_job");
            if (candidateElement != null && !candidateElement.isJsonNull()) {
                if (!candidateElement.isJsonObject()) {
                    throw invalid("candidate_job_not_object");
                }
                job = decodeJob(candidateElement.getAsJsonObject());
            }

            if (act == DialogueAct.PROPOSE_JOB && job == null) {
                throw invalid("missing_candidate_job");
            }
            if (act != DialogueAct.PROPOSE_JOB && job != null) {
                throw invalid("unexpected_candidate_job");
            }
            if (act == DialogueAct.ASK_CLARIFICATION && (clarification == null || clarification.isBlank())) {
                throw invalid("missing_clarification_question");
            }
            if (clarification != null && clarification.length() > 200) {
                throw invalid("clarification_too_long");
            }
            return new LlmInterpretation(act, job, clarification);
        } catch (JsonParseException | IllegalStateException exception) {
            throw invalid("invalid_json");
        }
    }

    private static JobSpec decodeJob(JsonObject object) {
        requireOnlyFields(object, JOB_FIELDS);
        JobType type = parseEnum(JobType.class, requiredString(object, "type"), "invalid_job_type");
        if (type == JobType.FOLLOW || type == JobType.STAY || type == JobType.CANCEL) {
            return JobSpec.basic(type);
        }
        String target = requiredString(object, "target");
        int quantity = requiredInt(object, "quantity");
        int radius = requiredInt(object, "radius");
        if (target.isBlank() || target.length() > 100 || quantity < 1 || quantity > 64 || radius < 1 || radius > 24) {
            throw invalid("job_parameter_out_of_range");
        }
        return new JobSpec(type, target, quantity, radius);
    }

    private static void requireOnlyFields(JsonObject object, Set<String> allowed) {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw invalid("unexpected_field");
            }
        }
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw invalid("invalid_" + field);
        }
        return element.getAsString();
    }

    private static String nullableString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw invalid("invalid_" + field);
        }
        return element.getAsString();
    }

    private static int requiredInt(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid("invalid_" + field);
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)
                || value != Math.rint(value)
                || value < Integer.MIN_VALUE
                || value > Integer.MAX_VALUE) {
            throw invalid("invalid_" + field);
        }
        return element.getAsInt();
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String value, String error) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw invalid(error);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
