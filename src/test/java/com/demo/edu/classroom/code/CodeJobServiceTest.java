package com.demo.edu.classroom.code;

import com.demo.edu.classroom.ClassroomCourseService;
import com.demo.edu.classroom.ClassroomException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真正经过 JDK HTTP 与异步队列，覆盖取消和迟到响应，不在业务 JVM 执行学生源码。 */
class CodeJobServiceTest {
    private static final String OWNER_A="A".repeat(43), OWNER_B="B".repeat(43), OWNER_C="C".repeat(43);
    private static final String TOKEN="runner-"+"fixture".repeat(4);
    private final ObjectMapper json=new ObjectMapper();
    private ClassroomCourseService courses;
    private HttpServer server;
    private ExecutorService handlers;
    private CodeJobService service;
    private String endpoint;
    private final AtomicInteger runs=new AtomicInteger(), active=new AtomicInteger(), maximumActive=new AtomicInteger();
    private final AtomicReference<String> observedAuthorization=new AtomicReference<>();
    private final AtomicReference<JsonNode> observedRequest=new AtomicReference<>();
    private Runner runner;
    private Canceller canceller;
    @FunctionalInterface interface Runner { void run(HttpExchange exchange,JsonNode request) throws Exception; }
    @FunctionalInterface interface Canceller { void cancel(HttpExchange exchange) throws Exception; }

    @BeforeEach void setup() throws Exception {
        courses=mock(ClassroomCourseService.class);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        handlers=Executors.newCachedThreadPool(); server.setExecutor(handlers);
        runner=(exchange,request)->send(exchange,200,result("completed","1\n"));
        canceller=exchange->send(exchange,200,json.createObjectNode().put("status","cancelled"));
        server.createContext("/",exchange->{
            observedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                if(exchange.getRequestMethod().equals("POST") && exchange.getRequestURI().getPath().equals("/run")) {
                    JsonNode request=json.readTree(exchange.getRequestBody()); observedRequest.set(request);
                    runs.incrementAndGet(); maximumActive.accumulateAndGet(active.incrementAndGet(),Math::max);
                    try { runner.run(exchange,request); } finally { active.decrementAndGet(); }
                } else if(exchange.getRequestMethod().equals("DELETE")) canceller.cancel(exchange);
                else send(exchange,404,json.createObjectNode());
            } catch(Exception ignored) { /* 测试取消连接允许服务端见到客户端主动断开。 */ }
            finally { exchange.close(); }
        });
        server.start(); endpoint="http://127.0.0.1:"+server.getAddress().getPort();
        service=new CodeJobService(json,courses,endpoint,TOKEN,true);
    }
    @AfterEach void cleanup() {
        if(service!=null) service.close();
        if(server!=null) server.stop(0);
        if(handlers!=null) handlers.shutdownNow();
    }
    private ObjectNode submission() { return json.createObjectNode().put("language","python").put("code","print(1)").put("stdin",""); }
    private ObjectNode result(String status,String stdout) {
        var result=json.createObjectNode().put("status",status).put("stdout",stdout).put("stderr","").put("truncated",false);
        result.putArray("diagnostics");
        var step=result.putArray("trace").addObject().put("index",0).put("line",1).put("event","line").put("stdout",stdout);
        step.putArray("frames").addObject().put("name","main").putObject("locals").put("total",1);
        step.putObject("heap");
        return result;
    }
    private void send(HttpExchange exchange,int status,JsonNode result) throws IOException { sendRaw(exchange,status,json.writeValueAsBytes(result)); }
    private void sendRaw(HttpExchange exchange,int status,byte[] raw) throws IOException {
        exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,raw.length); exchange.getResponseBody().write(raw);
    }
    private static void until(BooleanSupplier condition) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(),"异步任务未在预期时间内完成");
    }
    private ObjectNode terminal(String owner,String id) throws InterruptedException {
        until(()->Set.of("completed","cancelled","timeout","error").contains(service.get(owner,id).path("status").asText()));
        return service.get(owner,id);
    }

    @Test void validatesEndpointAndStrictInputBeforeQueueing() {
        for(String url:List.of("https://127.0.0.1:8899","http://remote.invalid:8899","http://127.0.0.1:8899/path","http://user@localhost:8899","http://localhost:8899?x=1"))
            assertThrows(IllegalArgumentException.class,()->new CodeJobService(json,courses,url,TOKEN,true));
        for(JsonNode body:List.of(json.createArrayNode(),submission().put("language","bash"),submission().put("code"," "),
                submission().put("code","x".repeat(20001)),submission().put("stdin","x".repeat(4001)),submission().put("stdin",1)))
            assertEquals(400,assertThrows(ClassroomException.class,()->service.create(OWNER_A,body)).status());
        assertEquals(400,assertThrows(ClassroomException.class,()->service.create("invalid",submission())).status());
        try(var disabled=new CloseableService(new CodeJobService(json,courses,endpoint,TOKEN,false))) {
            assertEquals(503,assertThrows(ClassroomException.class,()->disabled.value.create(OWNER_A,submission())).status());
        }
        assertEquals(0,runs.get());
    }

    @Test void returnsOnlyOwnedBoundedOutputAndSendsMinimalAuthenticatedRequest() throws Exception {
        runner=(exchange,request)->send(exchange,200,result("completed","1\n").put("privateConfig","must-not-leak").put("owner","must-not-leak"));
        String id=service.create(OWNER_A,submission().put("apiKey","must-not-forward")).path("id").asText();
        ObjectNode view=terminal(OWNER_A,id);
        assertEquals("completed",view.path("status").asText()); assertEquals(1,view.path("trace").size());
        assertFalse(view.has("privateConfig")); assertFalse(view.has("owner")); assertFalse(view.has("code"));
        assertEquals("Bearer "+TOKEN,observedAuthorization.get());
        assertEquals(Set.of("id","language","code","stdin"),fieldNames(observedRequest.get()));
        assertEquals(404,assertThrows(ClassroomException.class,()->service.get(OWNER_B,id)).status());
        assertEquals(404,assertThrows(ClassroomException.class,()->service.cancel(OWNER_B,id)).status());
        view.put("status","tampered"); assertEquals("completed",service.get(OWNER_A,id).path("status").asText());
    }

    @Test void generationRunsFirstAndOnlyOneCodeRequestRunsAtATime() throws Exception {
        when(courses.hasActiveGeneration()).thenReturn(true);
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        runner=(exchange,request)->{ entered.countDown(); release.await(4,TimeUnit.SECONDS); send(exchange,200,result("completed","done")); };
        String first=service.create(OWNER_A,submission()).path("id").asText();
        String second=service.create(OWNER_A,submission()).path("id").asText();
        assertEquals(429,assertThrows(ClassroomException.class,()->service.create(OWNER_A,submission())).status());
        Thread.sleep(180); assertEquals(0,runs.get()); assertEquals("queued",service.get(OWNER_A,first).path("status").asText());
        when(courses.hasActiveGeneration()).thenReturn(false);
        assertTrue(entered.await(3,TimeUnit.SECONDS)); assertEquals(1,runs.get());
        assertEquals("queued",service.get(OWNER_A,second).path("status").asText());
        release.countDown(); terminal(OWNER_A,first); terminal(OWNER_A,second);
        assertEquals(2,runs.get()); assertEquals(1,maximumActive.get());
    }

    @Test void finiteQueueRejectsOverflowAndCancelledQueuedJobReleasesItsSlot() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        runner=(exchange,request)->{ entered.countDown(); release.await(4,TimeUnit.SECONDS); send(exchange,200,result("completed","done")); };
        String running=service.create(OWNER_A,submission()).path("id").asText(); assertTrue(entered.await(3,TimeUnit.SECONDS));
        String queued=service.create(OWNER_A,submission()).path("id").asText();
        service.create(OWNER_B,submission()); service.create(OWNER_B,submission()); service.create(OWNER_C,submission());
        assertEquals(429,assertThrows(ClassroomException.class,()->service.create(OWNER_C,submission())).status());
        assertEquals("cancelled",service.cancel(OWNER_A,queued).path("status").asText());
        String replacement=service.create(OWNER_C,submission()).path("id").asText();
        release.countDown(); terminal(OWNER_A,running); terminal(OWNER_C,replacement);
        assertEquals(5,runs.get()); assertEquals(1,maximumActive.get());
    }

    @Test void cancelKeepsPartialTraceAndLateCompletedStatusCannotReviveJobOrChangeNextJob() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), stopped=new CountDownLatch(1);
        runner=(exchange,request)->{
            if(runs.get()==1) { entered.countDown(); stopped.await(4,TimeUnit.SECONDS); send(exchange,200,result("completed","partial\n")); }
            else send(exchange,200,result("completed","next\n"));
        };
        canceller=exchange->{ stopped.countDown(); send(exchange,200,json.createObjectNode().put("status","cancelled")); };
        String first=service.create(OWNER_A,submission()).path("id").asText(); assertTrue(entered.await(3,TimeUnit.SECONDS));
        String next=service.create(OWNER_A,submission()).path("id").asText();
        ObjectNode cancelled=service.cancel(OWNER_A,first);
        assertEquals("cancelled",cancelled.path("status").asText()); assertEquals("partial\n",cancelled.path("stdout").asText());
        assertEquals(1,cancelled.path("trace").size()); assertTrue(cancelled.path("truncated").asBoolean());
        assertEquals("next\n",terminal(OWNER_A,next).path("stdout").asText());
        assertEquals("cancelled",service.get(OWNER_A,first).path("status").asText());
    }

    @Test void cancellationRetriesBrieflyWhenDeleteArrivesBeforeRunnerRegistration() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), stopped=new CountDownLatch(1);
        AtomicInteger deletes=new AtomicInteger();
        runner=(exchange,request)->{ entered.countDown(); stopped.await(4,TimeUnit.SECONDS); send(exchange,200,result("cancelled","kept")); };
        canceller=exchange->{ if(deletes.incrementAndGet()==1) send(exchange,404,json.createObjectNode()); else { stopped.countDown(); send(exchange,200,json.createObjectNode()); } };
        String id=service.create(OWNER_A,submission()).path("id").asText(); assertTrue(entered.await(3,TimeUnit.SECONDS));
        ObjectNode cancelled=service.cancel(OWNER_A,id);
        assertEquals(2,deletes.get()); assertEquals("kept",cancelled.path("stdout").asText());
        assertEquals("cancelled",cancelled.path("status").asText());
    }

    @Test void cancelledJobAdvertisesLateTraceUntilItsExchangeSettles() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        runner=(exchange,request)->{ entered.countDown(); release.await(6,TimeUnit.SECONDS); send(exchange,200,result("completed","late partial\n")); };
        String id=service.create(OWNER_A,submission()).path("id").asText();
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        try {
            ObjectNode cancelled=service.cancel(OWNER_A,id);
            assertEquals("cancelled",cancelled.path("status").asText());
            assertTrue(cancelled.path("tracePending").asBoolean());
            assertTrue(cancelled.path("trace").isEmpty());
        } finally { release.countDown(); }
        until(()->!service.get(OWNER_A,id).path("tracePending").asBoolean());
        ObjectNode finalView=service.get(OWNER_A,id);
        assertEquals("cancelled",finalView.path("status").asText());
        assertEquals("late partial\n",finalView.path("stdout").asText());
        assertEquals(1,finalView.path("trace").size());
    }

    @Test void invalidHugeAndMalformedRunnerResultsBecomeRecoverableErrors() throws Exception {
        runner=(exchange,request)->{
            if(request.path("stdin").asText().equals("huge")) sendRaw(exchange,200," ".repeat(4_500_001).getBytes(StandardCharsets.UTF_8));
            else if(request.path("stdin").asText().equals("output")) send(exchange,200,result("completed","x".repeat(65537)));
            else { ObjectNode malformed=result("completed","ok"); ((ObjectNode)malformed.path("trace").get(0)).put("frames","invalid"); send(exchange,200,malformed); }
        };
        for(String variant:List.of("huge","output","malformed")) {
            String id=service.create(OWNER_A,submission().put("stdin",variant)).path("id").asText();
            ObjectNode view=terminal(OWNER_A,id); assertEquals("error",view.path("status").asText());
            assertTrue(view.path("trace").isEmpty()); assertFalse(view.path("reason").asText().contains(TOKEN));
        }
    }

    @Test void shutdownDoesNotHoldStateLockDuringRemoteCleanupAndRejectsNewJobs() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), stopped=new CountDownLatch(1);
        runner=(exchange,request)->{ entered.countDown(); stopped.await(4,TimeUnit.SECONDS); send(exchange,200,result("cancelled","last")); };
        String id=service.create(OWNER_A,submission()).path("id").asText(); assertTrue(entered.await(3,TimeUnit.SECONDS));
        canceller=exchange->{ assertEquals("cancelled",service.get(OWNER_A,id).path("status").asText()); stopped.countDown(); send(exchange,200,json.createObjectNode()); };
        assertTimeoutPreemptively(Duration.ofSeconds(3),()->service.close());
        assertEquals("cancelled",service.get(OWNER_A,id).path("status").asText());
        assertEquals(503,assertThrows(ClassroomException.class,()->service.create(OWNER_A,submission())).status());
        assertTimeoutPreemptively(Duration.ofMillis(100),()->service.close());
    }
    private static Set<String> fieldNames(JsonNode node) { var names=new java.util.HashSet<String>(); node.fieldNames().forEachRemaining(names::add); return names; }
    private record CloseableService(CodeJobService value) implements AutoCloseable { @Override public void close() { value.close(); } }
}
