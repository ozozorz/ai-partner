package io.github.ozozorz.aipartner.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.ozozorz.aipartner.config.AiPartnerConfig;
import io.github.ozozorz.aipartner.config.LlmEndpointPolicy;
import io.github.ozozorz.aipartner.control.MaidControlInterpretation;
import io.github.ozozorz.aipartner.control.MaidControlJsonCodec;
import io.github.ozozorz.aipartner.control.MaidDriverSettings;
import io.github.ozozorz.aipartner.world.MaidControlContextSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;

/**
 * Asynchronous OpenAI-compatible gateway for gameplay control. API key values
 * are read at request time from the selected environment variable and never logged.
 */
public final class MaidControlLlmGateway {
    private static final MaidControlLlmGateway INSTANCE = new MaidControlLlmGateway(AiPartnerConfig.get());
    private static final String PROMPT_RESOURCE = "/assets/ai-partner/prompts/maid_control_system.txt";
    private static final String SCHEMA_RESOURCE = "/assets/ai-partner/schema/maid_control.schema.json";
    private static final int MAX_CAPTURED_OUTPUT_LENGTH = 16_384;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final AiPartnerConfig config;
    private final HttpClient httpClient;
    private final String systemPrompt;

    private MaidControlLlmGateway(AiPartnerConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
        this.systemPrompt = loadResource(PROMPT_RESOURCE)
                + "\n\nAuthoritative output JSON Schema:\n"
                + loadResource(SCHEMA_RESOURCE);
    }

    public static MaidControlLlmGateway getInstance() {
        return INSTANCE;
    }

    public String model() {
        return config.model();
    }

    /** Returns null only when endpoint, model, and environment-variable indirection are ready. */
    public @Nullable String readinessError(String apiKeyEnvironmentVariable) {
        if (!config.llmEnabled()) {
            return "LLM_DISABLED";
        }
        if (config.model().isBlank() || "configure-me".equals(config.model())) {
            return "MODEL_NOT_CONFIGURED";
        }
        if (!MaidDriverSettings.isValidEnvironmentVariableName(apiKeyEnvironmentVariable)) {
            return "INVALID_API_KEY_ENV";
        }
        if (isLocalEndpoint()) {
            return null;
        }
        return AiPartnerConfig.hasEnvironmentVariable(apiKeyEnvironmentVariable)
                ? null
                : "MISSING_API_KEY_ENV";
    }

    public boolean isReady(String apiKeyEnvironmentVariable) {
        return readinessError(apiKeyEnvironmentVariable) == null;
    }

    /** Sends one bounded interpretation request off the server tick thread. */
    public CompletableFuture<MaidControlLlmResult> interpret(
            String playerMessage,
            MaidControlContextSnapshot context,
            String apiKeyEnvironmentVariable
    ) {
        long startedNanos = System.nanoTime();
        String readinessError = readinessError(apiKeyEnvironmentVariable);
        if (readinessError != null) {
            return CompletableFuture.completedFuture(MaidControlLlmResult.failed(
                    config.model(),
                    0L,
                    0,
                    "",
                    readinessError
            ));
        }

        String requestBody = createRequestBody(playerMessage, context);
        return sendWithRetry(requestBody, apiKeyEnvironmentVariable, 0)
                .handle((attemptedResponse, throwable) -> {
                    long latencyMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
                    if (throwable != null) {
                        Throwable root = unwrap(throwable);
                        if (root instanceof java.util.concurrent.CancellationException cancellation) {
                            throw cancellation;
                        }
                        return MaidControlLlmResult.failed(
                                config.model(),
                                latencyMillis,
                                attemptsOf(throwable),
                                "",
                                classify(throwable)
                        );
                    }
                    return decodeResponse(
                            attemptedResponse.response(),
                            latencyMillis,
                            attemptedResponse.attempts()
                    );
                });
    }

    private CompletableFuture<AttemptedResponse> sendWithRetry(
            String requestBody,
            String apiKeyEnvironmentVariable,
            int attempt
    ) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.endpoint()))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        String apiKey = System.getenv(apiKeyEnvironmentVariable);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, throwable) -> {
                    Throwable root = throwable == null ? null : unwrap(throwable);
                    if (root instanceof java.util.concurrent.CancellationException) {
                        return CompletableFuture.<AttemptedResponse>failedFuture(root);
                    }
                    boolean retryable = throwable != null
                            || response.statusCode() == 429
                            || response.statusCode() >= 500;
                    if (retryable && attempt < config.maxRetries()) {
                        return sendWithRetry(requestBody, apiKeyEnvironmentVariable, attempt + 1);
                    }
                    if (throwable != null) {
                        return CompletableFuture.<AttemptedResponse>failedFuture(
                                new GatewayException(classify(root), attempt + 1, root)
                        );
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return CompletableFuture.<AttemptedResponse>failedFuture(
                                new GatewayException("HTTP_" + response.statusCode(), attempt + 1, null)
                        );
                    }
                    return CompletableFuture.completedFuture(new AttemptedResponse(response, attempt + 1));
                })
                .thenCompose(future -> future);
    }

    private String createRequestBody(String playerMessage, MaidControlContextSnapshot context) {
        JsonObject untrustedInput = new JsonObject();
        untrustedInput.addProperty("player_message_untrusted", playerMessage);
        untrustedInput.add("server_authoritative_context", GSON.toJsonTree(context));

        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", GSON.toJson(untrustedInput)));

        JsonObject request = new JsonObject();
        request.addProperty("model", config.model());
        request.addProperty("temperature", config.temperature());
        request.addProperty("max_tokens", config.maxOutputTokens());
        request.add("messages", messages);
        if (config.requestJsonResponseFormat()) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            request.add("response_format", responseFormat);
        }
        if (isDeepSeekEndpoint()) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            request.add("thinking", thinking);
        }
        return GSON.toJson(request);
    }

    private MaidControlLlmResult decodeResponse(
            HttpResponse<String> response,
            long latencyMillis,
            int attempts
    ) {
        String rawOutput = "";
        try {
            JsonObject envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            rawOutput = envelope.getAsJsonArray("choices")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content")
                    .getAsString();
            if (rawOutput.length() > MAX_CAPTURED_OUTPUT_LENGTH) {
                return MaidControlLlmResult.failed(
                        config.model(), latencyMillis, attempts, "", "MODEL_OUTPUT_TOO_LARGE"
                );
            }
            MaidControlInterpretation interpretation = MaidControlJsonCodec.decode(rawOutput);
            JsonObject usage = envelope.has("usage") && envelope.get("usage").isJsonObject()
                    ? envelope.getAsJsonObject("usage")
                    : new JsonObject();
            return new MaidControlLlmResult(
                    true,
                    interpretation,
                    rawOutput,
                    optionalString(envelope, "model", config.model()),
                    latencyMillis,
                    attempts,
                    optionalInt(usage, "prompt_tokens"),
                    optionalInt(usage, "completion_tokens"),
                    null
            );
        } catch (RuntimeException exception) {
            return MaidControlLlmResult.failed(
                    config.model(), latencyMillis, attempts, rawOutput, "INVALID_MODEL_OUTPUT"
            );
        }
    }

    private boolean isLocalEndpoint() {
        return LlmEndpointPolicy.isLocal(config.endpoint());
    }

    private boolean isDeepSeekEndpoint() {
        String host = URI.create(config.endpoint()).getHost();
        return host != null
                && (host.equalsIgnoreCase("api.deepseek.com") || host.endsWith(".deepseek.com"));
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static int optionalInt(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsInt()
                : 0;
    }

    private static String optionalString(JsonObject object, String field, String fallback) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString()
                : fallback;
    }

    private static String loadResource(String path) {
        try (InputStream input = MaidControlLlmGateway.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled resource " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read bundled resource " + path, exception);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String classify(Throwable throwable) {
        Throwable root = unwrap(throwable);
        if (root instanceof java.net.http.HttpTimeoutException
                || root instanceof java.util.concurrent.TimeoutException) {
            return "TIMEOUT";
        }
        if (root instanceof GatewayException gatewayException) {
            return gatewayException.code;
        }
        return "NETWORK_ERROR";
    }

    private static int attemptsOf(Throwable throwable) {
        Throwable root = unwrap(throwable);
        return root instanceof GatewayException gatewayException ? gatewayException.attempts : 1;
    }

    private record AttemptedResponse(HttpResponse<String> response, int attempts) {
    }

    private static final class GatewayException extends RuntimeException {
        private final String code;
        private final int attempts;

        private GatewayException(String code, int attempts, Throwable cause) {
            super(code, cause);
            this.code = code;
            this.attempts = attempts;
        }
    }
}
