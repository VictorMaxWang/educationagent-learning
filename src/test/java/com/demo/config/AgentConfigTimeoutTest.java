package com.demo.config;

import com.demo.service.interrupt.InterruptManager;
import com.demo.service.interrupt.InterruptibleHttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/** 在模型 SDK 与 HTTP 客户端的公开配置边界验证实际生效的超时，不发起网络请求。 */
class AgentConfigTimeoutTest {

    @Test
    void synchronousModelKeepsEnoughReadTimeForInteractiveHtmlGeneration() {
        AgentConfig config = new AgentConfig();
        AiChatProperties aiChat = new AiChatProperties(new MockEnvironment()
                .withProperty("AI_CHAT_BASE_URL", "https://model.example.test/v1")
                .withProperty("AI_CHAT_API_KEY", "test-timeout-key")
                .withProperty("AI_CHAT_MODEL", "qwen3.8-flash"));
        ReflectionTestUtils.setField(config, "aiChat", aiChat);

        RecordingHttpClientBuilder client = new RecordingHttpClientBuilder();
        // 即便包装器预设较长超时，SDK 构造模型时仍会再次写入自己的默认值。
        client.setReadTimeout(Duration.ofSeconds(150));
        assertNotNull(config.deepseekChatModel(client));
        assertEquals(Duration.ofSeconds(150), client.lastReadTimeout,
                "交互 HTML 生成可能超过一分钟；模型 SDK 最后交给 HTTP 客户端的超时应为 150 秒");
    }

    private static final class RecordingHttpClientBuilder extends InterruptibleHttpClientBuilder {
        private Duration lastReadTimeout;

        private RecordingHttpClientBuilder() {
            super(mock(InterruptManager.class));
        }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            lastReadTimeout = timeout;
            return super.readTimeout(timeout);
        }
    }
}
