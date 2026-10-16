package com.demo.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * 用户原 MaaS 已验证的 Qwen 实时通道。每个课堂片段使用独立 commit 会话，
 * 只有 response.done.status=completed 且 session.finished 后才交付完整 PCM/WAV。
 */
@Slf4j
@Service
public class MaasRealtimeTtsService {
    public static final String DEFAULT_ENDPOINT = "wss://maas.qianwenaiapi.com/api-ws/v1/realtime";
    public static final String DEFAULT_MODEL = "qwen3-tts-flash-realtime";
    private static final int SOURCE_SAMPLE_RATE = 24000;
    private final HttpClient http;
    private final String apiKey;
    private final String endpoint;
    private final String model;

    @Autowired
    public MaasRealtimeTtsService(@Value("${website.tts.maas.api-key:${AI_CHAT_API_KEY:}}") String apiKey,
            @Value("${website.tts.realtime.endpoint:" + DEFAULT_ENDPOINT + "}") String endpoint,
            @Value("${website.tts.realtime.model:" + DEFAULT_MODEL + "}") String model) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build(),
                apiKey, endpoint, model);
    }

    MaasRealtimeTtsService(HttpClient http, String apiKey, String endpoint, String model) {
        this.http = http;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.endpoint = endpoint;
        this.model = model;
    }

    /** 不握手、不计费；这里只验证凭证存在且发送目标是用户授权的原 MaaS。 */
    public boolean isAvailable() {
        if (apiKey.isBlank() || apiKey.startsWith("${") || model == null || model.isBlank()) return false;
        try {
            URI uri = URI.create(endpoint);
            return "wss".equals(uri.getScheme()) && "maas.qianwenaiapi.com".equals(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null
                    && "/api-ws/v1/realtime".equals(uri.getPath()) && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (RuntimeException e) { return false; }
    }

    public String model() { return model; }
    public String cacheIdentity() { return "maas-qwen-realtime\n" + endpoint + "\n" + model + "\npcm24000"; }

    /** 线程取消、超时、失败状态、损坏 Base64 或未结束连接均不会返回半段声音。 */
    public byte[] synthesizeWav(String text, String voice) {
        if (!isAvailable() || text == null || text.isBlank() || text.length() > 600
                || voice == null || voice.isBlank() || Thread.currentThread().isInterrupted()) return null;
        RealtimeSession session = new RealtimeSession(text, voice);
        CompletableFuture<WebSocket> connecting = null;
        WebSocket socket = null;
        try {
            URI uri = URI.create(endpoint + "?model=" + URLEncoder.encode(model, StandardCharsets.UTF_8));
            connecting = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey).buildAsync(uri, session);
            socket = connecting.get(20, TimeUnit.SECONDS);
            byte[] pcm = session.result.get(90, TimeUnit.SECONDS);
            log.info("MaaS 实时 TTS 完成：{} 字，voice={}，PCM {}KB", text.length(), voice, pcm.length / 1024);
            return pcmToWav(pcm);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.cancel();
            return null;
        } catch (Exception e) {
            // 不输出握手异常正文，避免把 Authorization 或服务返回的敏感信息写入日志。
            log.warn("MaaS 实时 TTS 失败：{}", e.getClass().getSimpleName());
            session.cancel();
            return null;
        } finally {
            if (connecting != null && socket == null) {
                // 握手超时后晚到的连接也必须关闭，不能遗留云端计费会话。
                connecting.thenAccept(WebSocket::abort);
                connecting.cancel(true);
            }
            if (socket != null) socket.abort();
        }
    }

    static byte[] pcmToWav(byte[] pcm) {
        if (pcm == null || pcm.length == 0 || pcm.length % 2 != 0 || pcm.length > AliyunTtsService.MAX_AUDIO_BYTES) {
            throw new IllegalArgumentException("完整 PCM 音频无效");
        }
        ByteBuffer output = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        output.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(SOURCE_SAMPLE_RATE).putInt(SOURCE_SAMPLE_RATE * 2)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return output.array();
    }

    /** 无网络状态机，便于用实际脱敏事件对成功/失败/关闭边界进行回归测试。 */
    static final class RealtimeSession implements WebSocket.Listener {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private enum State { CREATED, UPDATING, SPEAKING, FINISHING, FINISHED, FAILED }
        private final String text;
        private final String voice;
        private final StringBuilder frame = new StringBuilder();
        private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        private volatile WebSocket socket;
        private State state = State.CREATED;
        private String responseId;

        RealtimeSession(String text, String voice) { this.text = text; this.voice = voice; }

        @Override public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            if (result.isDone()) webSocket.abort(); else webSocket.request(1);
        }

        @Override public synchronized CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (result.isDone()) return CompletableFuture.completedFuture(null);
            try {
                frame.append(data);
                if (frame.length() > 2 * 1024 * 1024) throw new IllegalStateException("语音事件过大");
                if (last) {
                    JSONObject event = JSON.parseObject(frame.toString());
                    frame.setLength(0);
                    accept(webSocket, event);
                }
            } catch (Exception e) { fail(); }
            if (!result.isDone()) webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        private void accept(WebSocket webSocket, JSONObject event) {
            if (event == null) throw new IllegalStateException("语音事件为空");
            String type = event.getString("type");
            if (type == null) throw new IllegalStateException("语音事件缺少类型");
            switch (type) {
                case "session.created" -> {
                    require(State.CREATED);
                    state = State.UPDATING;
                    JSONObject update = event("session.update");
                    JSONObject settings = new JSONObject();
                    settings.put("voice", voice); settings.put("mode", "commit");
                    settings.put("language_type", "Chinese"); settings.put("response_format", "pcm");
                    settings.put("sample_rate", SOURCE_SAMPLE_RATE); update.put("session", settings);
                    send(webSocket, update);
                }
                case "session.updated" -> {
                    require(State.UPDATING);
                    JSONObject settings = event.getJSONObject("session");
                    if (settings == null || !voice.equals(settings.getString("voice"))
                            || !"commit".equals(settings.getString("mode"))
                            || !"pcm".equals(settings.getString("response_format"))
                            || settings.getIntValue("sample_rate") != SOURCE_SAMPLE_RATE
                            || !"Chinese".equalsIgnoreCase(settings.getString("language_type"))) {
                        throw new IllegalStateException("云端未确认所选音色或 PCM 格式");
                    }
                    state = State.SPEAKING;
                    JSONObject append = event("input_text_buffer.append"); append.put("text", text);
                    // JDK WebSocket 同一时刻只允许一个文本发送，串行完成后再 commit。
                    send(webSocket, append).thenCompose(ignored -> send(webSocket, event("input_text_buffer.commit")))
                            .exceptionally(error -> { fail(); return null; });
                }
                case "response.audio.delta" -> {
                    require(State.SPEAKING);
                    String currentId = event.getString("response_id");
                    if (responseId == null) responseId = currentId;
                    else if (currentId != null && !responseId.equals(currentId)) throw new IllegalStateException("音频响应混入其他请求");
                    String delta = event.getString("delta");
                    if (delta == null) throw new IllegalStateException("音频片段缺失");
                    byte[] bytes = Base64.getDecoder().decode(delta);
                    if (pcm.size() + (long) bytes.length > AliyunTtsService.MAX_AUDIO_BYTES) throw new IllegalStateException("音频超过大小限制");
                    pcm.writeBytes(bytes);
                }
                case "response.done" -> {
                    require(State.SPEAKING);
                    JSONObject response = event.getJSONObject("response");
                    if (response == null || !"completed".equals(response.getString("status"))
                            || pcm.size() == 0 || pcm.size() % 2 != 0) {
                        throw new IllegalStateException("语音生成未完整成功");
                    }
                    state = State.FINISHING;
                    send(webSocket, event("session.finish"));
                }
                case "session.finished" -> {
                    require(State.FINISHING);
                    state = State.FINISHED;
                    byte[] complete = pcm.toByteArray();
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "finished");
                    result.complete(complete);
                }
                case "error" -> fail();
                default -> { /* committed/response.created/audio.done 等通知不能替代完整完成事件。 */ }
            }
        }

        private CompletionStage<WebSocket> send(WebSocket webSocket, JSONObject data) {
            return webSocket.sendText(data.toJSONString(), true).whenComplete((ignored, error) -> { if (error != null) fail(); });
        }

        private static JSONObject event(String type) {
            JSONObject event = new JSONObject(); event.put("event_id", "event_" + UUID.randomUUID()); event.put("type", type); return event;
        }
        private void require(State expected) { if (state != expected) throw new IllegalStateException("语音事件顺序不匹配"); }
        private synchronized void fail() {
            if (result.isDone()) return;
            state = State.FAILED;
            frame.setLength(0); pcm.reset();
            result.completeExceptionally(new IllegalStateException("实时语音未完整完成"));
            if (socket != null) socket.abort();
        }
        void cancel() { fail(); }
        @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            fail(); return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket webSocket, Throwable error) { fail(); }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!result.isDone()) fail();
            return CompletableFuture.completedFuture(null);
        }
    }
}
