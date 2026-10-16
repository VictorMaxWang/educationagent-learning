package com.demo.edu.classroom.ai;

import com.demo.config.AiChatProperties;
import com.demo.edu.classroom.ClassroomCancellation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ClassroomAiGatewayTest {
    private static final String KEY = "test-secret-do-not-print";
    final ObjectMapper json = new ObjectMapper();
    final AtomicInteger calls = new AtomicInteger();
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final ExecutorService handlers = Executors.newCachedThreadPool();
    HttpServer server;
    ClassroomAiGateway gateway;
    volatile Handler handler;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        handler = (exchange, index) -> send(exchange, 200, envelope("{\"ok\":true}"));
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                requests.add(json.readTree(exchange.getRequestBody()));
                handler.run(exchange, calls.incrementAndGet());
            } catch (Exception ignored) { exchange.close(); }
        });
        server.start();
        gateway = new ClassroomAiGateway(properties(KEY));
    }

    @AfterEach void stop() {
        gateway.close(); server.stop(0); handlers.shutdownNow();
    }

    @Test void recordsActualProviderModelIdUsageAndDisabledThinking() {
        var result = run();
        assertTrue(result.value().path("ok").asBoolean());
        assertEquals("provider-actual-model", result.evidence().path("model").asText());
        assertEquals("requested-model", result.evidence().path("requestedModel").asText());
        assertEquals("req-123", result.evidence().path("requestId").asText());
        assertEquals(9, result.evidence().path("tokenUsage").path("total_tokens").asInt());
        assertEquals(1, result.evidence().path("attemptCount").asInt());
        assertEquals(1, result.evidence().path("attempts").get(0).path("attempt").asInt());
        assertFalse(requests.getFirst().path("enable_thinking").asBoolean());
        assertEquals("json_object", requests.getFirst().path("response_format").path("type").asText());
        assertFalse(result.evidence().toString().contains(KEY));
        assertFalse(result.evidence().toString().contains("private-input"));
        assertFalse(gateway.capabilityStatus().path("liveVerified").asBoolean());
    }

    @Test void missingActualMetadataStaysNullRatherThanInvented() {
        handler = (e, n) -> {
            ObjectNode response = envelope("{\"ok\":true}"); response.remove(List.of("model", "id", "usage"));
            send(e, 200, response);
        };
        var evidence = run().evidence();
        assertTrue(evidence.path("model").isNull());
        assertTrue(evidence.path("requestId").isNull());
        assertTrue(evidence.path("tokenUsage").isNull());
    }

    @Test void completeContractFailureMayRepairOnceAndRetainsBothCalls() {
        handler = (e, n) -> send(e, 200, envelope(n == 1 ? "{\"ok\":false}" : "{\"ok\":true}"));
        var result = run();
        assertEquals(2, calls.get());
        assertEquals("contract-invalid", result.evidence().path("attempts").get(0).path("validation").asText());
        assertEquals(1,result.evidence().path("attempts").get(0).path("attempt").asInt());
        assertEquals(2,result.evidence().path("attempts").get(1).path("attempt").asInt());
        assertEquals(18, result.evidence().path("tokenUsage").path("total_tokens").asInt());
        assertEquals(4, requests.get(1).path("messages").size());
    }

    @Test void validatorExceptionDetailsAreNotSentToRepairOrEvidence() {
        AtomicInteger validations = new AtomicInteger();
        var result = gateway.structured("test", "system", "input", Duration.ofSeconds(4), node -> {
            if (validations.incrementAndGet() == 1) throw new IllegalArgumentException("private material " + KEY);
        });
        assertEquals(2, calls.get());
        assertFalse(requests.get(1).toString().contains(KEY));
        assertFalse(result.evidence().toString().contains(KEY));
    }

    @Test void rejectsTrailingDuplicateFencedArrayAndDeepJsonWithBoundedRepair() {
        for (String invalid : List.of("{\"ok\":true} {\"another\":1}", "{\"ok\":true,\"ok\":false}",
                "```json\n{\"ok\":true}\n```", "[{\"ok\":true}]", "{\"ok\":" + "[".repeat(70) + "1" + "]".repeat(70) + "}")) {
            calls.set(0);
            handler = (e, n) -> send(e, 200, envelope(invalid));
            var failure = assertThrows(ClassroomAiGateway.Failure.class, this::run);
            assertEquals("contract-invalid", failure.failureKind());
            assertEquals(2, calls.get());
        }
    }

    @Test void onceDoesNotAddAHiddenFormatRepair() {
        handler = (e, n) -> send(e, 200, envelope("not-json"));
        assertThrows(ClassroomAiGateway.Failure.class, () -> gateway.structuredOnce("test", "system", "input",
                Duration.ofSeconds(4), valid(), null));
        assertEquals(1, calls.get());
    }

    @Test void authenticationRateLimitAndServerErrorsNeverRetry() {
        for (int status : List.of(401, 403, 429, 500)) {
            calls.set(0); handler = (e, n) -> send(e, status, json.createObjectNode().put("error", KEY));
            var failure = assertThrows(ClassroomAiGateway.Failure.class, this::run);
            assertEquals(status == 429 ? "rate-limited" : status < 500 ? "authentication" : "provider-response", failure.failureKind());
            assertEquals(1, calls.get());
            assertFalse(failure.getMessage().contains(KEY));
            assertFalse(failure.evidence().toString().contains(KEY));
        }
    }

    @Test void malformedEnvelopeEmptyBodyAndTruncatedGenerationNeverRetry() {
        for (String scenario : List.of("envelope", "empty", "length")) {
            calls.set(0);
            handler = (e, n) -> {
                if (scenario.equals("envelope")) sendRaw(e, 200, "bad upstream " + KEY);
                else if (scenario.equals("empty")) send(e, 200, envelope(""));
                else {
                    ObjectNode response = envelope("{\"ok\":true}");
                    ((ObjectNode) response.path("choices").get(0)).put("finish_reason", "length"); send(e, 200, response);
                }
            };
            var failure = assertThrows(ClassroomAiGateway.Failure.class, this::run);
            assertEquals(1, calls.get());
            assertFalse(failure.getMessage().contains(KEY));
        }
    }

    @Test void responseLimitIsEnforcedDuringReceptionWithoutRepair() {
        handler = (e, n) -> sendRaw(e, 200, "x".repeat(ClassroomAiGateway.MAX_RESPONSE_BYTES + 100));
        var failure = assertThrows(ClassroomAiGateway.Failure.class, this::run);
        assertEquals("response-too-large", failure.failureKind());
        assertEquals(1, calls.get());
    }

    @Test void connectionClosedWithoutResultNeverTriggersApplicationRetry() {
        handler = (e, n) -> e.close();
        var failure = assertThrows(ClassroomAiGateway.Failure.class, this::run);
        assertEquals("provider-response", failure.failureKind());
        assertEquals(1, calls.get());
        assertEquals(1, failure.evidence().path("attemptCount").asInt());
    }

    @Test void cancellationInsideValidatorPreventsThePossibleRepairCall() {
        ClassroomCancellation cancellation = new ClassroomCancellation();
        assertThrows(CancellationException.class, () -> gateway.structured("test", "sys", "input", Duration.ofSeconds(4), node -> {
            cancellation.cancel(); throw new IllegalArgumentException("contract invalid after cancellation");
        }, cancellation));
        assertEquals(1, calls.get());
    }

    @Test void slowBodySharesDeadlineWithHeadersAndIsNotRetried() {
        handler = (e, n) -> {
            byte[] bytes = envelope("{\"ok\":true}").toString().getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes, 0, 1); e.getResponseBody().flush();
            Thread.sleep(1500); e.getResponseBody().write(bytes, 1, bytes.length - 1); e.close();
        };
        long start = System.nanoTime();
        var failure = assertThrows(ClassroomAiGateway.Failure.class, () -> gateway.structured("test", "sys", "input", Duration.ofMillis(350), valid()));
        assertEquals("request-timeout", failure.failureKind());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1100);
        assertEquals(1, calls.get());
    }

    @Test void parsingValidatorAndRepairShareOriginalBudget() {
        handler = (e, n) -> { Thread.sleep(180); send(e, 200, envelope("{\"ok\":false}")); };
        long start = System.nanoTime();
        var failure = assertThrows(ClassroomAiGateway.Failure.class, () -> gateway.structured("test", "sys", "input", Duration.ofMillis(300), valid()));
        assertEquals("request-timeout", failure.failureKind());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 900);
        assertTrue(calls.get() <= 2);
    }

    @Test void slowValidatorCannotOutliveCallerBudgetOrCauseAnotherPaidCall() {
        long start = System.nanoTime();
        var failure = assertThrows(ClassroomAiGateway.Failure.class, () -> gateway.structured("test", "sys", "input", Duration.ofMillis(350), node -> {
            try { Thread.sleep(1800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        assertEquals("request-timeout", failure.failureKind());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1100);
        assertEquals(1, calls.get());
    }

    @Test void explicitCancellationStopsWaitingAndDoesNotRepair() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        handler = (e, n) -> { started.countDown(); Thread.sleep(1200); send(e, 200, envelope("not-json")); };
        ClassroomCancellation cancellation = new ClassroomCancellation();
        try (var execution = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> future = execution.submit(() -> gateway.structured("test", "sys", "input", Duration.ofSeconds(4), valid(), cancellation));
            assertTrue(started.await(2, TimeUnit.SECONDS)); cancellation.cancel();
            var failure = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertEquals(1, calls.get());
        }
    }

    @Test void preCancelledAndMissingConfigMakeNoRequest() {
        ClassroomCancellation cancellation = new ClassroomCancellation(); cancellation.cancel();
        assertThrows(CancellationException.class, () -> gateway.structured("test", "sys", "input", Duration.ofSeconds(2), valid(), cancellation));
        try (var absent = new ClassroomAiGateway(properties(""))) {
            var failure = assertThrows(ClassroomAiGateway.Failure.class, () -> absent.structured("test", "sys", "input", Duration.ofSeconds(2), valid()));
            assertEquals("missing-env", failure.failureKind());
            assertEquals(0, failure.evidence().path("attemptCount").asInt());
        }
        assertEquals(0, calls.get());
    }

    @Test void providerMetadataCannotEchoCredentialIntoEvidence() {
        handler = (e, n) -> send(e, 200, envelope("{\"ok\":true}").put("model", KEY).put("id", KEY));
        var result = run();
        assertTrue(result.evidence().path("model").isNull());
        assertTrue(result.evidence().path("requestId").isNull());
        assertFalse(result.evidence().toString().contains(KEY));
    }

    private ClassroomAiGateway.Result run() { return gateway.structured("test", "system", "private-input", Duration.ofSeconds(4), valid()); }
    private Consumer<JsonNode> valid() { return node -> { if (!node.path("ok").asBoolean()) throw new IllegalArgumentException("invalid"); }; }
    private AiChatProperties properties(String key) {
        return new AiChatProperties(new MockEnvironment().withProperty("AI_CHAT_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .withProperty("AI_CHAT_API_KEY", key).withProperty("AI_CHAT_MODEL", "requested-model"));
    }
    private ObjectNode envelope(String content) {
        ObjectNode result = json.createObjectNode().put("id", "req-123").put("model", "provider-actual-model");
        result.putObject("usage").put("prompt_tokens", 5).put("completion_tokens", 4).put("total_tokens", 9);
        result.putArray("choices").addObject().put("finish_reason", "stop").putObject("message").put("content", content);
        return result;
    }
    private void send(HttpExchange exchange, int status, JsonNode body) throws IOException { sendRaw(exchange, status, body.toString()); }
    private void sendRaw(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }
    @FunctionalInterface interface Handler { void run(HttpExchange exchange, int index) throws Exception; }
}
