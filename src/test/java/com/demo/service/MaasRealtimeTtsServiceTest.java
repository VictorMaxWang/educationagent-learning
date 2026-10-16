package com.demo.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 回放生产短句探测的脱敏事件顺序；所有测试均不连接网络。 */
class MaasRealtimeTtsServiceTest {
    private static final String UPDATED = "{\"type\":\"session.updated\",\"session\":{\"voice\":\"Cherry\",\"mode\":\"commit\",\"language_type\":\"chinese\",\"response_format\":\"pcm\",\"sample_rate\":24000}}";
    private static final String COMPLETED = "{\"type\":\"response.done\",\"response\":{\"status\":\"completed\"}}";

    @Test
    void verifiedEventFlowPreservesEveryPcmChunkAndWaitsForFullCompletion() {
        FakeSocket socket = new FakeSocket();
        var session = ready(socket);
        assertEquals(List.of("session.update", "input_text_buffer.append", "input_text_buffer.commit"), socket.types());
        JSONObject settings = socket.sent.getFirst().getJSONObject("session");
        assertEquals("Cherry", settings.getString("voice"));
        assertEquals("commit", settings.getString("mode"));
        assertEquals("Chinese", settings.getString("language_type"));
        assertEquals("pcm", settings.getString("response_format"));
        assertEquals(24000, settings.getIntValue("sample_rate"));
        assertEquals("你好，我是小禾。", socket.sent.get(1).getString("text"));
        assertEquals(3, socket.sent.stream().map(e -> e.getString("event_id")).distinct().count());
        accept(session, socket, delta(new byte[]{1, 2, 3}));
        accept(session, socket, delta(new byte[]{4, 5, 6}));
        accept(session, socket, "{\"type\":\"response.audio.done\"}");
        assertFalse(session.result.isDone(), "audio.done 不能代替 response.done");
        accept(session, socket, COMPLETED);
        assertEquals("session.finish", socket.sent.getLast().getString("type"));
        assertFalse(session.result.isDone(), "生成完成后还需确认 session.finished");
        accept(session, socket, "{\"type\":\"session.finished\"}");
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, session.result.join());
        assertTrue(socket.outputClosed);
        byte[] wav = MaasRealtimeTtsService.pcmToWav(session.result.join());
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(24000, header.getInt(24));
        assertEquals(1, header.getShort(22));
        assertEquals(16, header.getShort(34));
        assertEquals(6, header.getInt(40));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, java.util.Arrays.copyOfRange(wav, 44, wav.length));
    }

    @Test
    void fragmentedJsonIsReassembledBeforeProcessing() {
        FakeSocket socket = new FakeSocket();
        var session = new MaasRealtimeTtsService.RealtimeSession("你好", "Cherry");
        session.onOpen(socket);
        session.onText(socket, "{\"type\":\"session.", false);
        assertTrue(socket.sent.isEmpty());
        session.onText(socket, "created\"}", true);
        assertEquals(List.of("session.update"), socket.types());
        session.cancel();
    }

    @Test
    void wrongVoiceOrFormatDoesNotSubmitTextToBeRead() {
        for (String updated : List.of(UPDATED.replace("Cherry", "WrongVoice"), UPDATED.replace("24000", "16000"), UPDATED.replace("pcm", "mp3"))) {
            FakeSocket socket = new FakeSocket();
            var session = new MaasRealtimeTtsService.RealtimeSession("你好", "Cherry");
            session.onOpen(socket); accept(session, socket, "{\"type\":\"session.created\"}");
            accept(session, socket, updated);
            assertTrue(session.result.isCompletedExceptionally());
            assertEquals(List.of("session.update"), socket.types());
            assertTrue(socket.aborted);
        }
    }

    @Test
    void failedDoneEmptyAudioOddPcmAndPrematureCloseAreNeverSuccessful() {
        for (String status : List.of("failed", "cancelled", "incomplete")) {
            FakeSocket socket = new FakeSocket(); var session = ready(socket);
            accept(session, socket, delta(new byte[]{1, 2}));
            accept(session, socket, COMPLETED.replace("completed", status));
            assertTrue(session.result.isCompletedExceptionally());
        }
        FakeSocket emptySocket = new FakeSocket(); var empty = ready(emptySocket);
        accept(empty, emptySocket, COMPLETED);
        assertTrue(empty.result.isCompletedExceptionally());
        FakeSocket oddSocket = new FakeSocket(); var odd = ready(oddSocket);
        accept(odd, oddSocket, delta(new byte[]{1})); accept(odd, oddSocket, COMPLETED);
        assertTrue(odd.result.isCompletedExceptionally());
        FakeSocket closedSocket = new FakeSocket(); var closed = ready(closedSocket);
        accept(closed, closedSocket, delta(new byte[]{1, 2}));
        closed.onClose(closedSocket, 1000, "closed early");
        assertTrue(closed.result.isCompletedExceptionally());
    }

    @Test
    void providerErrorMalformedBase64AndCancellationDiscardPartialAudio() {
        for (String error : List.of("{\"type\":\"error\",\"error\":{\"message\":\"redacted\"}}",
                "{\"type\":\"response.audio.delta\",\"delta\":\"%%%%\"}")) {
            FakeSocket socket = new FakeSocket(); var session = ready(socket);
            accept(session, socket, delta(new byte[]{1, 2})); accept(session, socket, error);
            assertTrue(session.result.isCompletedExceptionally()); assertTrue(socket.aborted);
        }
        FakeSocket socket = new FakeSocket(); var session = ready(socket);
        accept(session, socket, delta(new byte[]{1, 2})); session.cancel();
        accept(session, socket, COMPLETED);
        assertTrue(session.result.isCompletedExceptionally()); assertTrue(socket.aborted);
        assertFalse(socket.types().contains("session.finish"));
    }

    @Test
    void responseBoundaryAndSizeLimitsRejectMixedOrExcessAudio() {
        FakeSocket socket = new FakeSocket(); var session = ready(socket);
        accept(session, socket, delta(new byte[]{1, 2}));
        accept(session, socket, delta(new byte[]{3, 4}).replace("response_one", "response_other"));
        assertTrue(session.result.isCompletedExceptionally());
        FakeSocket largeSocket = new FakeSocket(); var large = ready(largeSocket);
        String chunk = delta(new byte[1024 * 1024]);
        for (int i = 0; i < 13; i++) accept(large, largeSocket, chunk);
        assertTrue(large.result.isCompletedExceptionally());
        assertTrue(largeSocket.aborted);
    }

    @Test
    void onlyOriginalMaasHostMayReceiveKeyAndNoHttpFallbackOccurs() {
        HttpClient http = mock(HttpClient.class);
        MaasRealtimeTtsService wrongHost = new MaasRealtimeTtsService(http, "sk-ws-unit", "wss://dashscope.aliyuncs.com/api-ws/v1/realtime", MaasRealtimeTtsService.DEFAULT_MODEL);
        assertFalse(wrongHost.isAvailable()); assertNull(wrongHost.synthesizeWav("你好", "Cherry"));
        verifyNoInteractions(http);
        WebSocket.Builder builder = mock(WebSocket.Builder.class);
        when(http.newWebSocketBuilder()).thenReturn(builder);
        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.header("Authorization", "Bearer sk-ws-unit")).thenReturn(builder);
        FakeSocket socket = new FakeSocket();
        when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class))).thenAnswer(invocation -> {
            assertEquals("wss://maas.qianwenaiapi.com/api-ws/v1/realtime?model=qwen3-tts-flash-realtime", invocation.getArgument(0).toString());
            var listener = (MaasRealtimeTtsService.RealtimeSession) invocation.getArgument(1);
            listener.onOpen(socket);
            accept(listener, socket, "{\"type\":\"session.created\"}"); accept(listener, socket, UPDATED);
            accept(listener, socket, delta(new byte[]{1, 2, 3, 4})); accept(listener, socket, COMPLETED);
            accept(listener, socket, "{\"type\":\"session.finished\"}");
            return CompletableFuture.completedFuture(socket);
        });
        var service = new MaasRealtimeTtsService(http, "sk-ws-unit", MaasRealtimeTtsService.DEFAULT_ENDPOINT, MaasRealtimeTtsService.DEFAULT_MODEL);
        byte[] wav = service.synthesizeWav("你好，我是小禾。", "Cherry");
        assertNotNull(wav); assertTrue(AliyunTtsService.isWav(wav));
        verify(builder).header("Authorization", "Bearer sk-ws-unit");
        assertTrue(socket.aborted, "调用结束后关闭本次独占连接");
    }

    private static MaasRealtimeTtsService.RealtimeSession ready(FakeSocket socket) {
        var session = new MaasRealtimeTtsService.RealtimeSession("你好，我是小禾。", "Cherry");
        session.onOpen(socket); accept(session, socket, "{\"type\":\"session.created\"}"); accept(session, socket, UPDATED); return session;
    }
    private static void accept(MaasRealtimeTtsService.RealtimeSession session, FakeSocket socket, String json) { session.onText(socket, json, true); }
    private static String delta(byte[] pcm) {
        return "{\"type\":\"response.audio.delta\",\"response_id\":\"response_one\",\"delta\":\"" + Base64.getEncoder().encodeToString(pcm) + "\"}";
    }
    static final class FakeSocket implements WebSocket {
        final List<JSONObject> sent = new ArrayList<>();
        boolean outputClosed, aborted;
        List<String> types() { return sent.stream().map(e -> e.getString("type")).toList(); }
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) { sent.add(JSON.parseObject(data.toString())); return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) { return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) { return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) { outputClosed = true; return CompletableFuture.completedFuture(this); }
        public void request(long n) { }
        public String getSubprotocol() { return ""; }
        public boolean isOutputClosed() { return outputClosed; }
        public boolean isInputClosed() { return aborted; }
        public void abort() { aborted = true; }
    }
}
