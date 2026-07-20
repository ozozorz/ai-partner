package io.github.ozozorz.aipartner.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.ozozorz.aipartner.AiPartnerMod;
import io.github.ozozorz.aipartner.config.AiPartnerConfig;
import io.github.ozozorz.aipartner.world.WorldStateSummary;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 使用 Java HttpClient 异步调用 OpenAI-compatible Chat Completions 端点。
 */
public final class LlmGateway {
    private static final LlmGateway INSTANCE = new LlmGateway(AiPartnerConfig.get());
    private static final String PROMPT_RESOURCE = "/assets/ai-partner/prompts/job_parser_system.txt";
    private static final String SCHEMA_RESOURCE = "/assets/ai-partner/schema/job_spec.schema.json";

    private final AiPartnerConfig config;
    private final HttpClient httpClient;
    private final String systemPrompt;
    private final String promptHash;

    private LlmGateway(AiPartnerConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
        this.systemPrompt = loadResource(PROMPT_RESOURCE) + "\n\nJSON Schema:\n" + loadResource(SCHEMA_RESOURCE);
        this.promptHash = sha256(systemPrompt);
    }

    public static LlmGateway getInstance() {
        return INSTANCE;
    }

    public boolean isEnabled() {
        return config.isLlmReady();
    }

    /**
     * 在非 tick 线程发送请求，并把所有预期失败归一化为 LlmCallResult。
     */
    public CompletableFuture<LlmCallResult> interpret(
            String playerMessage,
            WorldStateSummary worldState
    ) {
        long startedNanos = System.nanoTime();
        if (!isEnabled()) {
            return CompletableFuture.completedFuture(LlmCallResult.failed(
                    config.model(),
                    promptHash,
                    0L,
                    "",
                    "LLM_DISABLED"
            ));
        }

        String requestBody = createRequestBody(playerMessage, worldState);
        return sendWithRetry(requestBody, 0)
                .handle((response, throwable) -> {
                    long latencyMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
                    if (throwable != null) {
                        if (unwrap(throwable) instanceof java.util.concurrent.CancellationException cancellation) {
                            throw cancellation;
                        }
                        return LlmCallResult.failed(config.model(), promptHash, latencyMillis, "", classify(throwable));
                    }
                    return decodeResponse(response, latencyMillis);
                });
    }

    private CompletableFuture<HttpResponse<String>> sendWithRetry(String requestBody, int attempt) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.endpoint()))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        String apiKey = System.getenv(config.apiKeyEnvironmentVariable());
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, throwable) -> {
                    Throwable root = throwable == null ? null : unwrap(throwable);
                    if (root instanceof java.util.concurrent.CancellationException) {
                        return CompletableFuture.<HttpResponse<String>>failedFuture(root);
                    }
                    boolean retryable = throwable != null || response.statusCode() >= 500 || response.statusCode() == 429;
                    if (retryable && attempt < config.maxRetries()) {
                        return sendWithRetry(requestBody, attempt + 1);
                    }
                    if (throwable != null) {
                        return CompletableFuture.<HttpResponse<String>>failedFuture(root);
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return CompletableFuture.<HttpResponse<String>>failedFuture(
                                new GatewayException("HTTP_" + response.statusCode())
                        );
                    }
                    return CompletableFuture.completedFuture(response);
                })
                .thenCompose(future -> future);
    }

    private String createRequestBody(String playerMessage, WorldStateSummary worldState) {
        JsonObject untrustedInput = new JsonObject();
        untrustedInput.addProperty("player_message_untrusted", playerMessage);
        untrustedInput.add("world_state", AiPartnerJson.GSON.toJsonTree(worldState));

        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", AiPartnerJson.GSON.toJson(untrustedInput)));

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
        return AiPartnerJson.GSON.toJson(request);
    }

    private LlmCallResult decodeResponse(HttpResponse<String> response, long latencyMillis) {
        String rawModelOutput = "";
        try {
            JsonObject envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            rawModelOutput = envelope.getAsJsonArray("choices")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content")
                    .getAsString();
            LlmInterpretation interpretation = JobSpecJsonCodec.decode(rawModelOutput);
            JsonObject usage = envelope.has("usage") && envelope.get("usage").isJsonObject()
                    ? envelope.getAsJsonObject("usage")
                    : new JsonObject();
            int inputTokens = optionalInt(usage, "prompt_tokens");
            int outputTokens = optionalInt(usage, "completion_tokens");
            String responseModel = optionalString(envelope, "model", config.model());
            return new LlmCallResult(
                    true,
                    interpretation,
                    rawModelOutput,
                    responseModel,
                    promptHash,
                    latencyMillis,
                    inputTokens,
                    outputTokens,
                    null
            );
        } catch (RuntimeException exception) {
            return LlmCallResult.failed(config.model(), promptHash, latencyMillis, rawModelOutput, "INVALID_MODEL_OUTPUT");
        }
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
        try (InputStream input = LlmGateway.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled resource " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read bundled resource " + path, exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String classify(Throwable throwable) {
        Throwable root = unwrap(throwable);
        if (root instanceof java.net.http.HttpTimeoutException || root instanceof java.util.concurrent.TimeoutException) {
            return "TIMEOUT";
        }
        if (root instanceof GatewayException gatewayException) {
            return gatewayException.code;
        }
        return "NETWORK_ERROR";
    }

    /**
     * 模组内部共享的 Gson 实例，避免配置不同导致实验请求不一致。
     */
    private static final class AiPartnerJson {
        private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();

        private AiPartnerJson() {
        }
    }

    private static final class GatewayException extends RuntimeException {
        private final String code;

        private GatewayException(String code) {
            super(code);
            this.code = code;
        }
    }
}
