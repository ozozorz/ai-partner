package io.github.ozozorz.aipartner.control;

import java.util.regex.Pattern;

/**
 * 校验可持久化的 LLM 驱动配置；只保存环境变量名称，永远不接收或保存密钥值。
 */
public final class MaidDriverSettings {
    public static final int MAX_ENVIRONMENT_VARIABLE_LENGTH = 64;
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private MaidDriverSettings() {
    }

    public static boolean isValidEnvironmentVariableName(String value) {
        return value != null && ENVIRONMENT_VARIABLE.matcher(value.strip()).matches();
    }

    public static String requireEnvironmentVariableName(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!isValidEnvironmentVariableName(normalized)) {
            throw new IllegalArgumentException("Invalid LLM API key environment variable name");
        }
        return normalized;
    }
}
