package io.github.ozozorz.aipartner.control;

import java.util.regex.Pattern;

/**
 * 校验服务端 LLM 配置中的环境变量标识；任何协议都不接收密钥值。
 */
public final class MaidDriverSettings {
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private MaidDriverSettings() {
    }

    public static boolean isValidEnvironmentVariableName(String value) {
        return value != null && ENVIRONMENT_VARIABLE.matcher(value.strip()).matches();
    }
}
