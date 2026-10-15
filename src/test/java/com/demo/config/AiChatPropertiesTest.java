package com.demo.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 配置迁移回归：确保环境变量整体覆盖旧供应商，不会把新密钥发向旧地址。 */
class AiChatPropertiesTest {

    @Test
    void environmentOverridesCanonicalAndLegacyValuesTogether() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AI_CHAT_BASE_URL", "https://example.test/compatible-mode/v1/")
                .withProperty("AI_CHAT_API_KEY", "test-environment-key")
                .withProperty("AI_CHAT_MODEL", "qwen3.8-flash")
                .withProperty("ai.chat.base-url", "https://canonical.test/v1")
                .withProperty("ai.chat.api-key", "test-canonical-key")
                .withProperty("ai.chat.model", "canonical-model")
                .withProperty("ai.deepseek.url", "https://legacy.test/v1/chat/completions")
                .withProperty("ai.deepseek.key", "test-legacy-key")
                .withProperty("ai.deepseek.model", "legacy-model");
        AiChatProperties properties = new AiChatProperties(environment);
        assertEquals("https://example.test/compatible-mode/v1", properties.getBaseUrl());
        assertEquals("https://example.test/compatible-mode/v1/chat/completions", properties.getCompletionsUrl());
        assertEquals("test-environment-key", properties.getApiKey());
        assertEquals("qwen3.8-flash", properties.getModel());
        assertFalse(properties.isThinkingEnabled());
    }

    @Test
    void canonicalConfigWorksWithoutEnvironmentVariables() {
        AiChatProperties properties = new AiChatProperties(new MockEnvironment()
                .withProperty("ai.chat.base-url", "http://localhost:9100/v1")
                .withProperty("ai.chat.api-key", "test-canonical-key")
                .withProperty("ai.chat.model", "qwen3.8-flash"));
        assertEquals("http://localhost:9100/v1/chat/completions", properties.getCompletionsUrl());
        assertEquals("test-canonical-key", properties.getApiKey());
        assertEquals("qwen3.8-flash", properties.getModel());
    }

    @Test
    void legacyOnlyConfigRetainsItsProviderAndNormalizesFullUrl() {
        AiChatProperties properties = new AiChatProperties(new MockEnvironment()
                .withProperty("ai.deepseek.url", "https://legacy.test/v1/chat/completions/")
                .withProperty("ai.deepseek.key", "test-legacy-key")
                .withProperty("ai.deepseek.model", "legacy-model"));
        assertEquals("https://legacy.test/v1", properties.getBaseUrl());
        assertEquals("https://legacy.test/v1/chat/completions", properties.getCompletionsUrl());
        assertEquals("legacy-model", properties.getModel());
        assertTrue(properties.hasApiKey());
    }

    @Test
    void nativeRequestUsesSameModelAndKeepsShortOutputBudget() {
        AiChatProperties properties = new AiChatProperties(new MockEnvironment()
                .withProperty("AI_CHAT_MODEL", "qwen3.8-flash"));
        Map<String, Object> request = new LinkedHashMap<>(Map.of(
                "model", "old-model", "max_tokens", 10, "temperature", 0.0));
        properties.configureRequest(request);
        assertEquals("qwen3.8-flash", request.get("model"));
        assertEquals(false, request.get("enable_thinking"));
        assertEquals(10, request.get("max_tokens"));
        assertEquals(0.0, request.get("temperature"));
    }

    @Test
    void explicitThinkingSwitchWinsAndInvalidInputFailsEarly() {
        AiChatProperties properties = new AiChatProperties(new MockEnvironment()
                .withProperty("AI_CHAT_ENABLE_THINKING", "true")
                .withProperty("ai.chat.enable-thinking", "false"));
        assertEquals(true, properties.customParameters().get("enable_thinking"));
        assertThrows(IllegalArgumentException.class, () -> new AiChatProperties(new MockEnvironment()
                .withProperty("AI_CHAT_ENABLE_THINKING", "perhaps")));
    }

    @Test
    void missingKeyStaysUnavailableAndInvalidUrlFailsEarly() {
        assertFalse(new AiChatProperties(new MockEnvironment()).hasApiKey());
        assertThrows(IllegalArgumentException.class, () -> new AiChatProperties(new MockEnvironment()
                .withProperty("AI_CHAT_BASE_URL", "file:///example")));
    }
}
