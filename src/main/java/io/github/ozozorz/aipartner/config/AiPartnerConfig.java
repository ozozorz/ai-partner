package io.github.ozozorz.aipartner.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.ozozorz.aipartner.AiPartnerMod;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 加载不会包含明文密钥的模型网关配置；API 密钥只通过环境变量读取。
 */
public record AiPartnerConfig(
        boolean llmEnabled,
        String endpoint,
        String model,
        String apiKeyEnvironmentVariable,
        int timeoutSeconds,
        int maxRetries,
        int maxOutputTokens,
        double temperature,
        boolean requestJsonResponseFormat
) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("ai-partner.json");
    private static final AiPartnerConfig DEFAULT = new AiPartnerConfig(
            false,
            "http://127.0.0.1:11434/v1/chat/completions",
            "configure-me",
            "AI_PARTNER_API_KEY",
            20,
            1,
            512,
            0.0,
            true
    );
    private static volatile AiPartnerConfig instance;

    /**
     * 首次调用时读取或生成配置，后续返回同一冻结实例。
     */
    public static AiPartnerConfig get() {
        AiPartnerConfig current = instance;
        if (current != null) {
            return current;
        }
        synchronized (AiPartnerConfig.class) {
            if (instance == null) {
                instance = load();
            }
            return instance;
        }
    }

    private static AiPartnerConfig load() {
        try {
            if (!Files.exists(CONFIG_PATH)) {
                Files.createDirectories(CONFIG_PATH.getParent());
                Files.writeString(CONFIG_PATH, GSON.toJson(DEFAULT) + System.lineSeparator(), StandardCharsets.UTF_8);
                return DEFAULT;
            }
            AiPartnerConfig loaded = GSON.fromJson(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8), AiPartnerConfig.class);
            return validate(loaded);
        } catch (IOException | RuntimeException exception) {
            AiPartnerMod.LOGGER.error("Failed to load config/ai-partner.json; LLM integration is disabled", exception);
            return DEFAULT;
        }
    }

    private static AiPartnerConfig validate(AiPartnerConfig config) {
        if (config == null
                || config.endpoint() == null
                || config.model() == null
                || config.apiKeyEnvironmentVariable() == null) {
            throw new IllegalArgumentException("Missing required AI Partner configuration fields");
        }
        URI endpointUri = URI.create(config.endpoint());
        if (!("http".equalsIgnoreCase(endpointUri.getScheme()) || "https".equalsIgnoreCase(endpointUri.getScheme()))) {
            throw new IllegalArgumentException("LLM endpoint must use HTTP or HTTPS");
        }
        if (config.timeoutSeconds() < 1 || config.timeoutSeconds() > 120) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 120");
        }
        if (config.maxRetries() < 0 || config.maxRetries() > 1) {
            throw new IllegalArgumentException("maxRetries must be 0 or 1");
        }
        if (config.maxOutputTokens() < 64 || config.maxOutputTokens() > 4096) {
            throw new IllegalArgumentException("maxOutputTokens must be between 64 and 4096");
        }
        if (config.temperature() < 0.0 || config.temperature() > 1.0) {
            throw new IllegalArgumentException("temperature must be between 0 and 1");
        }
        return config;
    }

    /**
     * 判断配置是否明确启用且已填写模型名称。
     */
    public boolean isLlmReady() {
        return llmEnabled && !model.isBlank() && !"configure-me".equals(model);
    }
}

