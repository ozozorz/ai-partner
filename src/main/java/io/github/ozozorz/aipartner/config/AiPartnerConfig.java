package io.github.ozozorz.aipartner.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.ozozorz.aipartner.AiPartnerMod;
import io.github.ozozorz.aipartner.control.MaidDriverSettings;
import java.io.IOException;
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
    private static final AiPartnerConfig DEFAULT = new AiPartnerConfig(
            true,
            "https://api.deepseek.com/chat/completions",
            "deepseek-v4-flash",
            "DEEPSEEK_API_KEY",
            30,
            1,
            512,
            0.0,
            true
    );
    private static final AiPartnerConfig DISABLED_AFTER_LOAD_FAILURE = new AiPartnerConfig(
            false,
            "http://127.0.0.1:1/disabled",
            "disabled",
            "",
            30,
            0,
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
            Path configPath = configPath();
            if (!Files.exists(configPath)) {
                writeConfig(DEFAULT, configPath);
                return DEFAULT;
            }
            AiPartnerConfig loaded = GSON.fromJson(Files.readString(configPath, StandardCharsets.UTF_8), AiPartnerConfig.class);
            AiPartnerConfig validated = validate(loaded);
            if (isLegacyPlaceholder(validated) && hasEnvironmentVariable(DEFAULT.apiKeyEnvironmentVariable())) {
                AiPartnerMod.LOGGER.info("Migrating unconfigured AI Partner LLM settings to DeepSeek");
                writeConfig(DEFAULT, configPath);
                return DEFAULT;
            }
            return validated;
        } catch (IOException | RuntimeException exception) {
            AiPartnerMod.LOGGER.error("Failed to load config/ai-partner.json; LLM integration is disabled", exception);
            return disabledAfterLoadFailure();
        }
    }

    /**
     * 配置读取或校验失败时返回关闭状态，避免错误配置意外启用远程网关。
     */
    static AiPartnerConfig disabledAfterLoadFailure() {
        return DISABLED_AFTER_LOAD_FAILURE;
    }

    private static AiPartnerConfig validate(AiPartnerConfig config) {
        if (config == null
                || config.endpoint() == null
                || config.model() == null
                || config.apiKeyEnvironmentVariable() == null) {
            throw new IllegalArgumentException("Missing required AI Partner configuration fields");
        }
        LlmEndpointPolicy.validate(config.endpoint());
        if (!MaidDriverSettings.isValidEnvironmentVariableName(config.apiKeyEnvironmentVariable())) {
            throw new IllegalArgumentException("apiKeyEnvironmentVariable must be a valid server variable name");
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
        if (!llmEnabled || model.isBlank() || "configure-me".equals(model)) {
            return false;
        }
        boolean localEndpoint = LlmEndpointPolicy.isLocal(endpoint);
        return localEndpoint
                || apiKeyEnvironmentVariable.isBlank()
                || hasEnvironmentVariable(apiKeyEnvironmentVariable);
    }

    /**
     * 判断指定环境变量是否存在；只检查存在性，不读取、记录或写入密钥内容。
     */
    public static boolean hasEnvironmentVariable(String variableName) {
        String value = System.getenv(variableName);
        return value != null && !value.isBlank();
    }

    private static boolean isLegacyPlaceholder(AiPartnerConfig config) {
        return !config.llmEnabled()
                && "http://127.0.0.1:11434/v1/chat/completions".equals(config.endpoint())
                && "configure-me".equals(config.model())
                && "AI_PARTNER_API_KEY".equals(config.apiKeyEnvironmentVariable());
    }

    /**
     * 延迟解析 Fabric 配置目录，使纯单元测试可验证不依赖游戏运行时的配置策略。
     */
    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("ai-partner.json");
    }

    private static void writeConfig(AiPartnerConfig config, Path configPath) throws IOException {
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, GSON.toJson(config) + System.lineSeparator(), StandardCharsets.UTF_8);
    }
}
