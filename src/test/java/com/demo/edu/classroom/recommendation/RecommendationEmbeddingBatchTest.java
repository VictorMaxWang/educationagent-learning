package com.demo.edu.classroom.recommendation;

import com.alibaba.fastjson2.JSONObject;
import com.demo.chroma.base.EmbeddingService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实本机 HTTP 链路验证 POST 不重发；不是靠 mock RestTemplate 证明其底层传输策略。 */
class RecommendationEmbeddingBatchTest {
    HttpServer server;ExecutorService serverThreads;EmbeddingService service;
    final AtomicInteger requests=new AtomicInteger();volatile int status=200;volatile boolean slowBody;
    volatile String response="{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}";
    volatile String lastPath,headerRequestId;final RestTemplate oldClient=mock(RestTemplate.class);
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);serverThreads=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(serverThreads);
        server.createContext("/",exchange->{requests.incrementAndGet();lastPath=exchange.getRequestURI().getPath();exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.getResponseHeaders().add("Retry-After","0");
            if(headerRequestId!=null)exchange.getResponseHeaders().add("x-request-id",headerRequestId);
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status,bytes.length);
                if(slowBody){exchange.getResponseBody().write(bytes,0,1);exchange.getResponseBody().flush();try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
                exchange.getResponseBody().write(bytes,slowBody?1:0,bytes.length-(slowBody?1:0));
            }catch(java.io.IOException cancelled){/* 客户端在总预算结束后应取消正在接收的正文。 */}finally{exchange.close();}
        });server.start();service=new EmbeddingService();
        ReflectionTestUtils.setField(service,"provider","siliconflow");ReflectionTestUtils.setField(service,"expectedDimension",2);
        ReflectionTestUtils.setField(service,"siliconflowUrl",base()+"/v1/embeddings");ReflectionTestUtils.setField(service,"siliconflowModel","test-model");
        ReflectionTestUtils.setField(service,"restTemplate",oldClient);ReflectionTestUtils.setField(service,"ollamaRestTemplate",oldClient);
    }
    String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    @AfterEach void cleanup(){service.closeOnceTransport();server.stop(0);serverThreads.shutdownNow();verifyNoInteractions(oldClient);}
    @Test void reorderedIndicesBindToOriginalText(){response="{\"data\":[{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}";
        assertEquals(List.of(List.of(1f,0f),List.of(0f,1f)),service.generateEmbeddingsOnce(List.of("甲","乙")));assertEquals(1,requests.get());}
    @Test void duplicateIndexReturnsUnknownWithoutRetry(){response="{\"data\":[{\"index\":0,\"embedding\":[1,0]},{\"index\":0,\"embedding\":[0,1]}]}";
        assertNull(service.generateEmbeddingsOnce(List.of("甲","乙")));assertEquals(1,requests.get());}
    @Test void missingIndexReturnsUnknownWithoutRetry(){response="{\"data\":[{\"embedding\":[1,0]}]}";assertNull(service.generateEmbeddingsOnce(List.of("甲")));assertEquals(1,requests.get());}
    @Test void nullVectorValueIsNotConvertedToZero(){response="{\"data\":[{\"index\":0,\"embedding\":[1,null]}]}";assertNull(service.generateEmbeddingsOnce(List.of("甲")));assertEquals(1,requests.get());}
    @Test void stringVectorValueIsNotConvertedToNumber(){response="{\"data\":[{\"index\":0,\"embedding\":[1,\"0\"]}]}";assertNull(service.generateEmbeddingsOnce(List.of("甲")));assertEquals(1,requests.get());}
    @Test void dashscopeTextIndicesDoNotAssumeResponseOrder(){var parsed=JSONObject.parseObject("{\"output\":{\"embeddings\":[{\"text_index\":1,\"embedding\":[0,1]},{\"text_index\":0,\"embedding\":[1,0]}]}}");
        assertEquals(List.of(List.of(1f,0f),List.of(0f,1f)),ReflectionTestUtils.invokeMethod(service,"parseOnceEmbeddings",parsed,2,false,false));assertEquals(0,requests.get());}
    @Test void rateLimitDoesNotRetryEvenWithRetryAfter(){status=429;long start=System.nanoTime();assertNull(service.generateEmbeddingsOnce(List.of("甲"),Duration.ofMillis(700)));
        assertEquals(1,requests.get());assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1500);}
    @Test void serviceUnavailableDoesNotRetryEvenWithRetryAfter(){status=503;assertNull(service.generateEmbeddingsOnce(List.of("甲"),Duration.ofMillis(700)));assertEquals(1,requests.get());}
    @Test void slowResponseBodyIsCancelledAtWholeCallBudget(){slowBody=true;long start=System.nanoTime();assertNull(service.generateEmbeddingsOnce(List.of("甲"),Duration.ofMillis(350)));
        long elapsed=Duration.ofNanos(System.nanoTime()-start).toMillis();assertEquals(1,requests.get());assertTrue(elapsed<1200,"whole-call budget elapsed="+elapsed);}
    @Test void bodyLimitIsEnforcedWhileReceiving(){response="{\"padding\":\""+"a".repeat(2*1024*1024)+"\"}";
        assertNull(service.generateEmbeddingsOnce(List.of("甲"),Duration.ofSeconds(2)));assertEquals(1,requests.get());}
    @Test void ollamaFailureDoesNotCallLegacyEndpoint(){ReflectionTestUtils.setField(service,"provider","ollama");ReflectionTestUtils.setField(service,"ollamaUrl",base());status=503;
        assertNull(service.generateEmbeddingsOnce(List.of("甲")));assertEquals(1,requests.get());assertEquals("/api/embed",lastPath);}
    @Test void actualResponseEvidenceKeepsReturnedModelSeparateFromConfiguration(){headerRequestId="actual-header-id";
        response="{\"data\":[{\"index\":0,\"embedding\":[1,0]}],\"model\":\"actually-served-model\",\"request_id\":\"actual-body-id\",\"usage\":{\"prompt_tokens\":7,\"total_tokens\":7}}";
        ReflectionTestUtils.setField(service,"siliconflowKey","local-test-secret");
        var result=service.generateEmbeddingsOnceWithEvidence(List.of("不应进入证据的输入文本"),Duration.ofSeconds(2));var evidence=result.evidence();
        assertEquals(List.of(List.of(1f,0f)),result.vectors());assertEquals(200,evidence.path("httpStatus").asInt());assertEquals(1,evidence.path("attemptCount").asInt());
        assertEquals("test-model",evidence.path("requestedModel").asText());assertEquals("actually-served-model",evidence.path("model").asText());
        assertEquals("siliconflow",evidence.path("adapterMode").asText());assertTrue(evidence.path("provider").isNull());
        assertEquals("actual-header-id",evidence.path("requestId").asText());assertEquals(7,evidence.path("tokenUsage").path("total_tokens").asInt());
        assertTrue(evidence.path("elapsedMs").asLong()>=0);assertFalse(evidence.toString().contains(base()));assertFalse(evidence.toString().contains("local-test-secret"));
        assertFalse(evidence.toString().contains("不应进入证据的输入文本"));assertEquals(1,requests.get());}
    @Test void missingModelUsageAndRequestIdRemainNullRatherThanUsingConfiguredValues(){
        var result=service.generateEmbeddingsOnceWithEvidence(List.of("甲"));assertNotNull(result.vectors());
        assertTrue(result.evidence().path("model").isNull());assertTrue(result.evidence().path("tokenUsage").isNull());assertTrue(result.evidence().path("requestId").isNull());
        assertEquals("test-model",result.evidence().path("requestedModel").asText());assertEquals(1,requests.get());}
    @Test void bodyRequestIdIsRecordedWhenNoHeaderIsAvailable(){response="{\"data\":[{\"index\":0,\"embedding\":[1,0]}],\"request_id\":\"actual-body-id\"}";
        assertEquals("actual-body-id",service.generateEmbeddingsOnceWithEvidence(List.of("甲")).evidence().path("requestId").asText());assertEquals(1,requests.get());}
    @Test void failedHttpStatusHasRealEvidenceAndNoInventedModel(){status=503;headerRequestId="failed-request-id";
        var result=service.generateEmbeddingsOnceWithEvidence(List.of("甲"));assertNull(result.vectors());assertEquals(503,result.evidence().path("httpStatus").asInt());
        assertEquals("failed-request-id",result.evidence().path("requestId").asText());assertTrue(result.evidence().path("model").isNull());assertEquals(1,requests.get());}
    @Test void endpointFingerprintDistinguishesGatewaysAndNormalizesHostPortAndDotSegments(){var first=service.describeIdentity();
        ReflectionTestUtils.setField(service,"siliconflowUrl",base()+"/different/embeddings");var second=service.describeIdentity();
        assertEquals(first.provider(),second.provider());assertEquals(first.model(),second.model());assertEquals(first.dimension(),second.dimension());
        assertNotEquals(first.endpointFingerprint(),second.endpointFingerprint());assertTrue(first.endpointFingerprint().matches("[a-f0-9]{64}"));
        ReflectionTestUtils.setField(service,"siliconflowUrl","HTTPS://EXAMPLE.invalid:443/a/../v1/embeddings#ignored");String normalized=service.describeIdentity().endpointFingerprint();
        ReflectionTestUtils.setField(service,"siliconflowUrl","https://example.invalid/v1/embeddings");assertEquals(normalized,service.describeIdentity().endpointFingerprint());assertEquals(0,requests.get());}
}
