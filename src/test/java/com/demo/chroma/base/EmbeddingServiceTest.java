package com.demo.chroma.base;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 检查 OpenAI 兼容嵌入接口实际发送的 HTTP 协议，保护固定维度模型的旧请求格式。
 * HTTP 全部由内存 mock 接管；不启动 Spring 上下文探针，不读取配置文件或真实密钥。
 */
class EmbeddingServiceTest {

    private static final String ENDPOINT = "https://embedding.example.test/v1/embeddings";

    @Test
    void defaultFixedDimensionProviderOmitsDimensionsEntirely() {
        RestTemplate client = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-embedding-key"))
                .andExpect(jsonPath("$.model").value("BAAI/bge-m3"))
                .andExpect(jsonPath("$.input[0]").value("乡村课堂"))
                .andExpect(request -> assertFalse(JSON.parseObject(
                        ((MockClientHttpRequest) request).getBodyAsString()).containsKey("dimensions"),
                        "默认请求应完全省略 dimensions，保持旧提供方协议"))
                .andRespond(withSuccess(response(1024), MediaType.APPLICATION_JSON));

        // 不设置 requestDimensions：未启用新选项时沿用原来的固定维度请求。
        EmbeddingService service = service(client, "BAAI/bge-m3", 1024);
        List<Float> vector = service.generateEmbedding("乡村课堂");
        assertNotNull(vector);
        assertEquals(1024, vector.size());
        assertEquals(0.125f, vector.get(0));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {512, 1024})
    void optInSendsConfiguredDimensionToCompatibleProvider(int dimension) {
        RestTemplate client = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-embedding-key"))
                .andExpect(jsonPath("$.model").value("qwen3.7-text-embedding-flash"))
                .andExpect(jsonPath("$.input[0]").value("学习资料"))
                .andExpect(jsonPath("$.dimensions").value(dimension))
                .andRespond(withSuccess(response(dimension), MediaType.APPLICATION_JSON));

        EmbeddingService service = service(client, "qwen3.7-text-embedding-flash", dimension);
        ReflectionTestUtils.setField(service, "requestDimensions", true);
        List<Float> vector = service.generateEmbedding("学习资料");
        assertNotNull(vector);
        assertEquals(dimension, vector.size());
        assertEquals(0.125f, vector.get(dimension - 1));
        server.verify();
    }

    private static EmbeddingService service(RestTemplate client, String model, int dimension) {
        EmbeddingService service = new EmbeddingService();
        ReflectionTestUtils.setField(service, "provider", "siliconflow");
        ReflectionTestUtils.setField(service, "siliconflowUrl", ENDPOINT);
        ReflectionTestUtils.setField(service, "siliconflowKey", "test-embedding-key");
        ReflectionTestUtils.setField(service, "siliconflowModel", model);
        ReflectionTestUtils.setField(service, "expectedDimension", dimension);
        ReflectionTestUtils.setField(service, "restTemplate", client);
        return service;
    }

    private static String response(int dimension) {
        return JSON.toJSONString(Map.of("data", List.of(Map.of(
                "index", 0, "embedding", Collections.nCopies(dimension, 0.125f)))));
    }
}
