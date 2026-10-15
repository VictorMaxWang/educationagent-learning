package com.demo.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.demo.router.CircuitBreaker;
import com.demo.router.IntentLabel;
import com.demo.router.LlmRouter;
import com.demo.router.MessageContext;
import com.demo.router.RouterProperties;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 只使用本机回环服务与内存 HTTP mock，验证模型库真实序列化出的参数，不连接外部 API。 */
class AiChatRequestCompatibilityTest {

    @Test
    void synchronousAndStreamingBuildersSendCanonicalModelAndDisableThinking() throws Exception {
        List<JSONObject> requests = new CopyOnWriteArrayList<>();
        List<String> authorizations = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            JSONObject request = JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(request);
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            boolean stream = request.getBooleanValue("stream");
            String response = stream ? """
                    data: {"id":"test","object":"chat.completion.chunk","model":"qwen3.8-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"OK"},"finish_reason":null}]}

                    data: {"id":"test","object":"chat.completion.chunk","model":"qwen3.8-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """ : """
                    {"id":"test","object":"chat.completion","model":"qwen3.8-flash","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            AiChatProperties config = configured("http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1");
            String response = config.chatModelBuilder().maxRetries(0).timeout(Duration.ofSeconds(5)).build().chat("test");
            assertEquals("OK", response);
            CompletableFuture<ChatResponse> streamed = new CompletableFuture<>();
            config.streamingModelBuilder().timeout(Duration.ofSeconds(5)).build()
                    .chat("test", new StreamingChatResponseHandler() {
                        @Override public void onCompleteResponse(ChatResponse response) { streamed.complete(response); }
                        @Override public void onError(Throwable error) { streamed.completeExceptionally(error); }
                    });
            assertEquals("OK", streamed.get(10, TimeUnit.SECONDS).aiMessage().text());
            assertEquals(2, requests.size());
            for (JSONObject request : requests) {
                assertEquals("qwen3.8-flash", request.getString("model"));
                assertTrue(request.containsKey("enable_thinking"));
                assertFalse(request.getBooleanValue("enable_thinking"));
            }
            assertEquals(List.of("Bearer test-only-key", "Bearer test-only-key"), authorizations);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shortRouterRequestIgnoresLegacyAuxiliaryModelAndNeverCallsOldEndpoint() {
        RestTemplate client = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
        server.expect(requestTo("https://example.test/compatible-mode/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-only-key"))
                .andExpect(jsonPath("$.model").value("qwen3.8-flash"))
                .andExpect(jsonPath("$.enable_thinking").value(false))
                .andExpect(jsonPath("$.max_tokens").value(10))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"WEATHER\"}}]}", MediaType.APPLICATION_JSON));
        RouterProperties properties = new RouterProperties();
        properties.getLlm().setEnabled(true);
        properties.getLlm().setModel("legacy-auxiliary-model");
        LlmRouter router = new LlmRouter(properties, client, new CircuitBreaker("test", false, 3, 1));
        ReflectionTestUtils.setField(router, "aiChat", configured("https://example.test/compatible-mode/v1"));
        assertTrue(router.isEnabled());
        assertEquals(IntentLabel.WEATHER, router.route("test", "今天天气如何", MessageContext.desktop()).getIntent());
        server.verify();
    }

    private AiChatProperties configured(String baseUrl) {
        return new AiChatProperties(new MockEnvironment()
                .withProperty("AI_CHAT_BASE_URL", baseUrl)
                .withProperty("AI_CHAT_API_KEY", "test-only-key")
                .withProperty("AI_CHAT_MODEL", "qwen3.8-flash")
                .withProperty("ai.deepseek.url", "https://old-provider.invalid/v1/chat/completions")
                .withProperty("ai.deepseek.key", "old-provider-test-key")
                .withProperty("ai.deepseek.model", "old-provider-model"));
    }
}
