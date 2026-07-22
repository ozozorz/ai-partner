package io.github.ozozorz.aipartner.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 验证 LLM 地址白名单和配置失效时的关闭策略，避免密钥经远程明文 HTTP 发送。
 */
class LlmEndpointPolicyTest {
    @Test
    void acceptsHttpsRemoteEndpoint() {
        LlmEndpointPolicy.validate("https://api.example.com/v1/chat/completions");
        assertFalse(LlmEndpointPolicy.isLocal("https://api.example.com/v1/chat/completions"));
    }

    @Test
    void acceptsHttpOnlyForExplicitLoopbackHosts() {
        assertTrue(LlmEndpointPolicy.isLocal("http://localhost:11434/v1/chat/completions"));
        assertTrue(LlmEndpointPolicy.isLocal("http://127.0.0.1:11434/v1/chat/completions"));
        assertTrue(LlmEndpointPolicy.isLocal("http://[::1]:11434/v1/chat/completions"));
    }

    @Test
    void rejectsPlaintextRemoteEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> LlmEndpointPolicy.validate("http://api.example.com/v1/chat/completions"));
    }

    @Test
    void rejectsMalformedOrAmbiguousEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> LlmEndpointPolicy.validate("https:///missing-host"));
        assertThrows(IllegalArgumentException.class, () -> LlmEndpointPolicy.validate("file:///tmp/model"));
        assertThrows(IllegalArgumentException.class,
                () -> LlmEndpointPolicy.validate("https://user:secret@api.example.com/v1#fragment"));
    }

    @Test
    void failsClosedAfterConfigLoadFailure() {
        AiPartnerConfig fallback = AiPartnerConfig.disabledAfterLoadFailure();

        assertFalse(fallback.llmEnabled());
        assertFalse(fallback.isLlmReady());
        assertTrue(LlmEndpointPolicy.isLocal(fallback.endpoint()));
    }
}
