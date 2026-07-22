package io.github.ozozorz.aipartner.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.ozozorz.aipartner.combat.CombatPolicy;
import io.github.ozozorz.aipartner.contract.JobSpec;
import io.github.ozozorz.aipartner.core.schedule.ScheduleType;
import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.life.ActivityLocationType;
import io.github.ozozorz.aipartner.work.MaidWorkMode;
import java.util.Arrays;
import java.util.Set;

/** Decodes the strict v2 LLM protocol into the same typed intents used by local control. */
public final class MaidControlJsonCodec {
    public static final String SCHEMA_VERSION = "2.0";
    private static final int MAX_RESPONSE_LENGTH = 240;
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema_version",
            "dialogue_act",
            "intent",
            "response_text"
    );

    private MaidControlJsonCodec() {
    }

    /** Rejects markdown, missing fields, extra fields, invalid enums, and out-of-range values. */
    public static MaidControlInterpretation decode(String rawJson) {
        try {
            JsonElement rootElement = JsonParser.parseString(rawJson);
            if (!rootElement.isJsonObject()) {
                throw invalid("root_not_object");
            }
            JsonObject root = rootElement.getAsJsonObject();
            requireExactFields(root, ROOT_FIELDS);
            if (!SCHEMA_VERSION.equals(requiredString(root, "schema_version"))) {
                throw invalid("unsupported_schema_version");
            }
            MaidControlDialogueAct dialogueAct = parseEnum(
                    MaidControlDialogueAct.class,
                    requiredString(root, "dialogue_act"),
                    "invalid_dialogue_act"
            );
            String responseText = nullableString(root, "response_text");
            if (responseText != null && exceedsCodePointLimit(responseText, MAX_RESPONSE_LENGTH)) {
                throw invalid("response_too_long");
            }

            MaidControlIntent intent = null;
            JsonElement intentElement = root.get("intent");
            if (intentElement != null && !intentElement.isJsonNull()) {
                if (!intentElement.isJsonObject()) {
                    throw invalid("intent_not_object");
                }
                intent = decodeIntent(intentElement.getAsJsonObject());
            }
            return validateDialogueAct(dialogueAct, intent, responseText);
        } catch (JsonParseException | IllegalStateException exception) {
            throw invalid("invalid_json");
        }
    }

    private static MaidControlInterpretation validateDialogueAct(
            MaidControlDialogueAct dialogueAct,
            MaidControlIntent intent,
            String responseText
    ) {
        if (dialogueAct == MaidControlDialogueAct.PROPOSE_INTENT) {
            if (intent == null || responseText != null) {
                throw invalid("invalid_proposal_payload");
            }
            return MaidControlInterpretation.propose(intent);
        }
        if (intent != null) {
            throw invalid("unexpected_intent");
        }
        return switch (dialogueAct) {
            case ASK_CLARIFICATION -> {
                if (responseText == null || responseText.isBlank()) {
                    throw invalid("missing_clarification");
                }
                yield MaidControlInterpretation.clarify(responseText);
            }
            case SOCIAL_REPLY -> {
                if (responseText == null || responseText.isBlank()) {
                    throw invalid("missing_social_reply");
                }
                yield MaidControlInterpretation.social(responseText);
            }
            case REJECT_UNSUPPORTED -> {
                if (responseText != null) {
                    throw invalid("unexpected_response_text");
                }
                yield MaidControlInterpretation.reject();
            }
            case PROPOSE_INTENT -> throw invalid("missing_intent");
        };
    }

    private static MaidControlIntent decodeIntent(JsonObject object) {
        String kind = requiredString(object, "kind");
        return switch (kind) {
            case "RUN_TASK" -> decodeRunTask(object);
            case "SET_WORK_MODE" -> {
                requireExactFields(object, Set.of("kind", "mode"));
                MaidWorkMode mode = Arrays.stream(MaidWorkMode.values())
                        .filter(candidate -> candidate.serializedName().equals(requiredString(object, "mode")))
                        .findFirst()
                        .orElseThrow(() -> invalid("invalid_work_mode"));
                yield new MaidControlIntent.SetWorkMode(mode);
            }
            case "SET_SCHEDULE" -> {
                requireExactFields(object, Set.of("kind", "schedule"));
                yield new MaidControlIntent.SetSchedule(parseEnum(
                        ScheduleType.class,
                        requiredString(object, "schedule"),
                        "invalid_schedule"
                ));
            }
            case "SET_COMBAT_POLICY" -> {
                requireExactFields(object, Set.of("kind", "policy"));
                CombatPolicy policy = Arrays.stream(CombatPolicy.values())
                        .filter(candidate -> candidate.serializedName().equals(requiredString(object, "policy")))
                        .findFirst()
                        .orElseThrow(() -> invalid("invalid_combat_policy"));
                yield new MaidControlIntent.SetCombatPolicy(policy);
            }
            case "RETURN_HOME" -> {
                requireExactFields(object, Set.of("kind"));
                yield new MaidControlIntent.ReturnHome();
            }
            case "CONFIGURE_LOCATION" -> {
                requireExactFields(object, Set.of("kind", "location", "clear"));
                yield new MaidControlIntent.ConfigureLocation(
                        parseEnum(
                                ActivityLocationType.class,
                                requiredString(object, "location"),
                                "invalid_location"
                        ),
                        requiredBoolean(object, "clear")
                );
            }
            case "SET_HOME_BOUND" -> {
                requireExactFields(object, Set.of("kind", "enabled"));
                yield new MaidControlIntent.SetHomeBound(requiredBoolean(object, "enabled"));
            }
            case "SET_RADIUS" -> {
                requireExactFields(object, Set.of("kind", "radius"));
                int radius = requiredInt(object, "radius");
                if (radius < 1 || radius > 64) {
                    throw invalid("radius_out_of_range");
                }
                yield new MaidControlIntent.SetRadius(radius);
            }
            case "RENAME" -> {
                requireExactFields(object, Set.of("kind", "name"));
                String name = requiredString(object, "name").strip();
                if (name.isEmpty() || exceedsCodePointLimit(name, 32)
                        || name.chars().anyMatch(Character::isISOControl)) {
                    throw invalid("invalid_name");
                }
                yield new MaidControlIntent.Rename(name);
            }
            case "QUERY_STATUS" -> noFieldIntent(object, new MaidControlIntent.QueryStatus());
            case "QUERY_INVENTORY" -> noFieldIntent(object, new MaidControlIntent.QueryInventory());
            case "RETRIEVE_INVENTORY" -> noFieldIntent(object, new MaidControlIntent.RetrieveInventory());
            default -> throw invalid("invalid_intent_kind");
        };
    }

    private static MaidControlIntent decodeRunTask(JsonObject object) {
        requireExactFields(object, Set.of("kind", "job_type", "target", "quantity", "radius"));
        JobType type = parseEnum(JobType.class, requiredString(object, "job_type"), "invalid_job_type");
        String target = requiredString(object, "target");
        int quantity = requiredInt(object, "quantity");
        int radius = requiredInt(object, "radius");
        if (type == JobType.FOLLOW || type == JobType.STAY || type == JobType.CANCEL) {
            if (!target.isEmpty() || quantity != 0 || radius != 0) {
                throw invalid("basic_job_has_parameters");
            }
            return JobSpecIntent.of(JobSpec.basic(type));
        }
        if (target.isBlank() || exceedsCodePointLimit(target, 100)
                || quantity < 1 || quantity > 64
                || radius < 1 || radius > 24) {
            throw invalid("job_parameter_out_of_range");
        }
        return JobSpecIntent.of(new JobSpec(type, target, quantity, radius));
    }

    private static MaidControlIntent noFieldIntent(JsonObject object, MaidControlIntent intent) {
        requireExactFields(object, Set.of("kind"));
        return intent;
    }

    private static void requireExactFields(JsonObject object, Set<String> fields) {
        if (!object.keySet().equals(fields)) {
            throw invalid("invalid_fields");
        }
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()
                || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
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

    private static boolean requiredBoolean(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw invalid("invalid_" + field);
        }
        return element.getAsBoolean();
    }

    private static int requiredInt(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid("invalid_" + field);
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
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

    /**
     * JSON Schema 的 maxLength 按 Unicode 码点计数；本地 codec 使用相同口径避免代理对造成分歧。
     */
    private static boolean exceedsCodePointLimit(String value, int maximum) {
        return value.codePointCount(0, value.length()) > maximum;
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    /** Keeps task construction visually consistent with the other intent branches. */
    private static final class JobSpecIntent {
        private JobSpecIntent() {
        }

        private static MaidControlIntent of(JobSpec job) {
            return new MaidControlIntent.RunTask(job);
        }
    }
}
