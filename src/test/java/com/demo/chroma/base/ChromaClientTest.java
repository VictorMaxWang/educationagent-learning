package com.demo.chroma.base;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Chroma 分批写入测试。
 *
 * <p>背景：一次性提交整库向量会撞 fastjson2 的 64MB 序列化上限
 * （3224 条 1024 维 ≈ 64MB），报
 * {@code Maximum array size exceeded. Requested size: 67108880, max size: 67108864}；
 * 即使勉强序列化出来，Chroma 也会因请求体过大掐断连接。
 * 两者都表现为"入库失败 → 置 FAILED → 每分钟重试"，永远好不了。
 */
class ChromaClientTest {

    private RestTemplate restTemplate;
    private ChromaClient client;

    @BeforeEach
    void setUp() {
        client = new ChromaClient();
        ReflectionTestUtils.setField(client, "chromaUrl", "http://localhost:8000");
        ReflectionTestUtils.setField(client, "addBatchSize", 500);
        restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(client, "restTemplate", restTemplate);
    }

    private static List<String> ids(int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add("id" + i);
        return out;
    }

    private static List<List<Double>> vectors(int n) {
        List<List<Double>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(List.of(0.1, 0.2, 0.3));
        return out;
    }

    private static List<String> docs(int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add("文档" + i);
        return out;
    }

    private void stubOk() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
    }

    /** 每批实际提交了多少条——直接解析被捕获的请求体，而不是只看调用次数。 */
    @SuppressWarnings("unchecked")
    private List<Integer> capturedBatchSizes(int expectedCalls) {
        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(expectedCalls)).postForEntity(anyString(), captor.capture(), eq(String.class));
        List<Integer> sizes = new ArrayList<>();
        for (HttpEntity<String> entity : captor.getAllValues()) {
            JSONObject body = JSON.parseObject(entity.getBody());
            sizes.add(body.getJSONArray("ids").size());
        }
        return sizes;
    }

    @Test
    void 小于批次上限时只发一次请求() {
        stubOk();
        assertTrue(client.addDocuments("col1", ids(100), vectors(100), docs(100), null));
        assertEquals(List.of(100), capturedBatchSizes(1));
    }

    @Test
    void 超过批次上限时拆成多批且每批不超限() {
        stubOk();
        // 1200 条 / 每批 500 → 500 + 500 + 200
        assertTrue(client.addDocuments("col1", ids(1200), vectors(1200), docs(1200), null));
        assertEquals(List.of(500, 500, 200), capturedBatchSizes(3));
    }

    @Test
    void 恰好等于批次上限时只发一次() {
        stubOk();
        assertTrue(client.addDocuments("col1", ids(500), vectors(500), docs(500), null));
        assertEquals(List.of(500), capturedBatchSizes(1));
    }

    @Test
    void 元数据也按同样的区间切片() {
        stubOk();
        List<Map<String, Object>> metas = new ArrayList<>();
        for (int i = 0; i < 1200; i++) metas.add(Map.of("i", i));

        assertTrue(client.addDocuments("col1", ids(1200), vectors(1200), docs(1200), metas));

        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(3)).postForEntity(anyString(), captor.capture(), eq(String.class));
        for (HttpEntity<String> entity : captor.getAllValues()) {
            JSONObject body = JSON.parseObject(entity.getBody());
            assertEquals(body.getJSONArray("ids").size(), body.getJSONArray("metadatas").size(),
                    "metadatas 必须和 ids 切在同一区间，否则 Chroma 会报参数长度不一致");
        }
    }

    /** 一批失败不能让整批流程崩掉，但整体必须报失败——否则索引器会「写完就报成功」。 */
    @Test
    void 某批失败时整体返回失败() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"))
                .thenThrow(new ResourceAccessException("boom"))
                .thenReturn(ResponseEntity.ok("{}"));

        assertFalse(client.addDocuments("col1", ids(1200), vectors(1200), docs(1200), null),
                "有批次失败时整体必须返回 false，索引器才会标记 FAILED 而不是 DONE");
    }

    @Test
    void 空批次直接失败不请求() {
        assertFalse(client.addDocuments("col1", List.of(), List.of(), List.of(), null));
        verify(restTemplate, times(0)).postForEntity(anyString(), any(), eq(String.class));
    }
}
