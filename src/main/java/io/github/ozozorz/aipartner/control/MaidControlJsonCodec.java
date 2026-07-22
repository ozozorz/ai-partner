package io.github.ozozorz.aipartner.control;

import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Strict codec for the v3 dialogue-plus-bounded-workflow LLM protocol. */
public final class MaidControlJsonCodec {
    public static final String SCHEMA_VERSION = "3.0";
    private static final int MAX_RESPONSE_LENGTH = 240;
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema_version",
            "dialogue_act",
            "plan",
            "response_text"
    );

    private MaidControlJsonCodec() {
    }

    /** Rejects markdown, missing or extra fields, invalid enums, and unbounded plans. */
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
            MaidControlDialogueAct dialogueAct = parseLlmDialogueAct(requiredString(root, "dialogue_act"));
            String responseText = nullableString(root, "response_text");
            if (responseText != null && exceedsCodePointLimit(responseText, MAX_RESPONSE_LENGTH)) {
                throw invalid("response_too_long");
            }

            JsonElement planElement = root.get("plan");
            if (planElement == null || !planElement.isJsonArray()) {
                throw invalid("plan_not_array");
            }
            JsonArray planArray = planElement.getAsJsonArray();
            if (planArray.size() > MaidControlInterpretation.MAX_PLAN_STEPS) {
                throw invalid("plan_too_large");
            }
            List<MaidControlIntent> plan = new ArrayList<>(planArray.size());
            for (JsonElement element : planArray) {
                if (!element.isJsonObject()) {
                    throw invalid("plan_step_not_object");
                }
                plan.add(decodeIntentObject(element.getAsJsonObject()));
            }
            return validateDialogueAct(dialogueAct, plan, responseText);
        } catch (JsonParseException | IllegalStateException exception) {
            throw invalid("invalid_json");
        }
    }

    /** Encodes one typed action for bounded workflow persistence. */
    public static String encodeIntent(MaidControlIntent intent) {
        return encodeIntentObject(intent).toString();
    }

    /** Decodes one persisted typed action using the same whitelist as the model boundary. */
    public static MaidControlIntent decodePersistedIntent(String rawJson) {
        try {
            JsonElement element = JsonParser.parseString(rawJson);
            if (!element.isJsonObject()) {
                throw invalid("persisted_intent_not_object");
            }
            return decodeIntentObject(element.getAsJsonObject());
        } catch (JsonParseException | IllegalStateException exception) {
            throw invalid("invalid_persisted_intent");
        }
    }

    private static MaidControlInterpretation validateDialogueAct(
            MaidControlDialogueAct dialogueAct,
            List<MaidControlIntent> plan,
            String responseText
    ) {
        return switch (dialogueAct) {
            case PROPOSE_PLAN -> {
                if (plan.isEmpty() || responseText == null || responseText.isBlank()) {
                    throw invalid("invalid_plan_payload");
                }
                yield MaidControlInterpretation.plan(plan, responseText);
            }
            case ASK_CLARIFICATION -> {
                requireEmptyPlan(plan);
                if (responseText == null || responseText.isBlank()) {
                    throw invalid("missing_clarification");
                }
                yield MaidControlInterpretation.clarify(responseText);
            }
            case SOCIAL_REPLY -> {
                requireEmptyPlan(plan);
                if (responseText == null || responseText.isBlank()) {
                    throw invalid("missing_social_reply");
                }
                yield MaidControlInterpretation.social(responseText);
            }
            case REJECT_UNSUPPORTED -> {
                requireEmptyPlan(plan);
                if (responseText == null || responseText.isBlank()) {
                    throw invalid("missing_rejection_reply");
                }
                yield MaidControlInterpretation.reject(responseText);
            }
            case PROPOSE_INTENT -> throw invalid("offline_dialogue_act_not_allowed");
        };
    }

    private static void requireEmptyPlan(List<MaidControlIntent> plan) {
        if (!plan.isEmpty()) {
            throw invalid("unexpected_plan");
        }
    }

    private static MaidControlDialogueAct parseLlmDialogueAct(String value) {
        return switch (value) {
            case "PROPOSE_PLAN" -> MaidControlDialogueAct.PROPOSE_PLAN;
            case "ASK_CLARIFICATION" -> MaidControlDialogueAct.ASK_CLARIFICATION;
            case "REJECT_UNSUPPORTED" -> MaidControlDialogueAct.REJECT_UNSUPPORTED;
            case "SOCIAL_REPLY" -> MaidControlDialogueAct.SOCIAL_REPLY;
            default -> throw invalid("invalid_dialogue_act");
        };
    }

    private static MaidControlIntent decodeIntentObject(JsonObject object) {
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
            case "RETURN_HOME" -> noFieldIntent(object, new MaidControlIntent.ReturnHome());
            case "CONFIGURE_LOCATION" -> {
                requireExactFields(object, Set.of("kind", "location", "clear"));
                yield new MaidControlIntent.ConfigureLocation(
                        parseEnum(ActivityLocationType.class, requiredString(object, "location"), "invalid_location"),
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
            return new MaidControlIntent.RunTask(JobSpec.basic(type));
        }
        if (target.isBlank() || exceedsCodePointLimit(target, 100)
                || quantity < 1 || quantity > 64
                || radius < 1 || radius > 24) {
            throw invalid("job_parameter_out_of_range");
        }
        return new MaidControlIntent.RunTask(new JobSpec(type, target, quantity, radius));
    }

    private static JsonObject encodeIntentObject(MaidControlIntent intent) {
        JsonObject object = new JsonObject();
        switch (intent) {
            case MaidControlIntent.RunTask runTask -> {
                object.addProperty("kind", "RUN_TASK");
                object.addProperty("job_type", runTask.job().type().name());
                object.addProperty("target", runTask.job().target());
                object.addProperty("quantity", runTask.job().quantity());
                object.addProperty("radius", runTask.job().radius());
            }
            case MaidControlIntent.SetWorkMode setWorkMode -> {
                object.addProperty("kind", "SET_WORK_MODE");
                object.addProperty("mode", setWorkMode.mode().serializedName());
            }
            case MaidControlIntent.SetSchedule setSchedule -> {
                object.addProperty("kind", "SET_SCHEDULE");
                object.addProperty("schedule", setSchedule.schedule().name());
            }
            case MaidControlIntent.SetCombatPolicy setCombatPolicy -> {
                object.addProperty("kind", "SET_COMBAT_POLICY");
                object.addProperty("policy", setCombatPolicy.policy().serializedName());
            }
            case MaidControlIntent.ReturnHome ignored -> object.addProperty("kind", "RETURN_HOME");
            case MaidControlIntent.ConfigureLocation configureLocation -> {
                object.addProperty("kind", "CONFIGURE_LOCATION");
                object.addProperty("location", configureLocation.location().name());
                object.addProperty("clear", configureLocation.clear());
            }
            case MaidControlIntent.SetHomeBound setHomeBound -> {
                object.addProperty("kind", "SET_HOME_BOUND");
                object.addProperty("enabled", setHomeBound.enabled());
            }
            case MaidControlIntent.SetRadius setRadius -> {
                object.addProperty("kind", "SET_RADIUS");
                object.addProperty("radius", setRadius.radius());
            }
            case MaidControlIntent.Rename rename -> {
                object.addProperty("kind", "RENAME");
                object.addProperty("name", rename.name());
            }
            case MaidControlIntent.QueryStatus ignored -> object.addProperty("kind", "QUERY_STATUS");
            case MaidControlIntent.QueryInventory ignored -> object.addProperty("kind", "QUERY_INVENTORY");
            case MaidControlIntent.RetrieveInventory ignored -> object.addProperty("kind", "RETRIEVE_INVENTORY");
        }
        return object;
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

    private static boolean exceedsCodePointLimit(String value, int maximum) {
        return value.codePointCount(0, value.length()) > maximum;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid maid control JSON: " + reason);
    }
}
