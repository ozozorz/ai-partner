package io.github.ozozorz.aipartner.config;

import java.net.URI;

/**
 * 集中校验 LLM 网关地址，防止远程密钥和请求正文通过明文 HTTP 传输。
 */
public final class LlmEndpointPolicy {
    private LlmEndpointPolicy() {
    }

    /**
     * 校验地址结构和传输策略，并返回可供网关复用的 URI。
     * 本机回环服务允许使用 HTTP；其他主机必须使用 HTTPS。
     */
    public static URI validate(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("LLM endpoint must not be blank");
        }

        URI uri = URI.create(endpoint);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("LLM endpoint must use HTTP or HTTPS");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("LLM endpoint must include a valid host");
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("LLM endpoint must not include credentials or a fragment");
        }
        if ("http".equalsIgnoreCase(scheme) && !isLoopbackHost(host)) {
            throw new IllegalArgumentException("Remote LLM endpoints must use HTTPS");
        }
        return uri;
    }

    /**
     * 判断地址是否指向明确列入白名单的本机回环主机。
     */
    public static boolean isLocal(String endpoint) {
        return isLoopbackHost(validate(endpoint).getHost());
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }
}
