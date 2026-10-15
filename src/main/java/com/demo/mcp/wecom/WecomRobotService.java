package com.demo.mcp.wecom;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.demo.service.CmdService;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EchoWork 机器人 WebSocket 长连接客户端。
 * <p>
 * 协议文档对齐：订阅/心跳/消息回调/流式回复
 */
@Slf4j
@Service
public class WecomRobotService {

    static { System.out.println("\n[EchoWork] ======== 类已加载 ========"); }

    @Resource
    private WecomRobotConfigProperties config;

    @Resource
    private CmdService cmdService;

    private WebSocket webSocket;
    private OkHttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private volatile boolean subscribed = false;
    private final AtomicInteger reconnectDelay = new AtomicInteger(1);
    private final AtomicLong reqIdCounter = new AtomicLong(1);

    @jakarta.annotation.PostConstruct
    public void init() {
        System.out.println("\n========================================");
        System.out.println("[EchoWork] @PostConstruct init()");
        System.out.println("[EchoWork] config=" + (config != null ? "OK" : "NULL"));
        System.out.println("[EchoWork] cmdService=" + (cmdService != null ? "OK" : "NULL"));
        System.out.println("[EchoWork] enabled=" + config.isEnabled());

        if (!config.isConfigured()) {
            System.out.println("[EchoWork] ⚠️ 未配置，跳过");
            return;
        }
        try {
            httpClient = new OkHttpClient.Builder()
                    .pingInterval(0, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            System.out.println("[EchoWork] ❌ OkHttpClient 创建失败: " + e.getMessage());
            return;
        }
        running = true;
        connect();
    }

    @PreDestroy
    public void shutdown() {
        System.out.println("[EchoWork] shutdown");
        running = false;
        if (scheduler != null) scheduler.shutdownNow();
        if (webSocket != null) webSocket.close(1000, "shutdown");
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdownNow();
            httpClient.connectionPool().evictAll();
        }
    }

    // ======================== 连接 ========================

    private void connect() {
        String url = config.getWsUrl() + "?bot_id=" + config.getBotId()
                + "&bot_secret=" + config.getBotSecret();
        System.out.println("\n[EchoWork] 🔌 连接: " + maskUrl(url));
        httpClient.newWebSocket(new Request.Builder().url(url).build(), new WsListener());
        System.out.println("[EchoWork] newWebSocket 已调用");
    }

    private void subscribe() {
        JSONObject msg = new JSONObject();
        msg.put("cmd", "aibot_subscribe");
        JSONObject headers = new JSONObject();
        headers.put("req_id", nextReqId());
        msg.put("headers", headers);
        JSONObject body = new JSONObject();
        body.put("bot_id", config.getBotId());
        body.put("secret", config.getBotSecret());  // 注意字段名是 secret 不是 bot_secret
        msg.put("body", body);
        String json = msg.toJSONString();
        System.out.println("[EchoWork] 📡 订阅: " + json);
        webSocket.send(json);
    }

    private void sendHeartbeat() {
        JSONObject msg = new JSONObject();
        msg.put("cmd", "ping");
        JSONObject headers = new JSONObject();
        headers.put("req_id", nextReqId());
        msg.put("headers", headers);
        webSocket.send(msg.toJSONString());
        log.debug("[EchoWork] 💓 心跳");
    }

    private void startHeartbeat() {
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "echowork-hb");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 30, 30, TimeUnit.SECONDS);
        System.out.println("[EchoWork] 💓 心跳已启动(30s)");
    }

    // ======================== 回复消息 ========================

    /** 回复文本（流式，单次完成） */
    private void respondText(String reqId, String streamId, String content) {
        JSONObject msg = new JSONObject();
        msg.put("cmd", "aibot_respond_msg");
        JSONObject headers = new JSONObject();
        headers.put("req_id", reqId);
        msg.put("headers", headers);
        JSONObject body = new JSONObject();
        body.put("msgtype", "stream");
        JSONObject stream = new JSONObject();
        stream.put("id", streamId);
        stream.put("finish", true);
        stream.put("content", content);
        body.put("stream", stream);
        msg.put("body", body);
        webSocket.send(msg.toJSONString());
        System.out.println("[EchoWork] ✅ 回复已发送, len=" + content.length());
    }

    /** 发送"处理中"中间状态 */
    private void respondAck(String reqId, String streamId) {
        JSONObject msg = new JSONObject();
        msg.put("cmd", "aibot_respond_msg");
        JSONObject headers = new JSONObject();
        headers.put("req_id", reqId);
        msg.put("headers", headers);
        JSONObject body = new JSONObject();
        body.put("msgtype", "stream");
        JSONObject stream = new JSONObject();
        stream.put("id", streamId);
        stream.put("finish", false);
        stream.put("content", "🤖 收到，正在处理...");
        body.put("stream", stream);
        msg.put("body", body);
        webSocket.send(msg.toJSONString());
    }

    // ======================== 调度 ========================

    private void scheduleReconnect() {
        if (!running) return;
        subscribed = false;
        int delay = Math.min(reconnectDelay.getAndUpdate(d -> d * 2), 60);
        System.out.println("[EchoWork] 🔄 " + delay + "s 重连");
        if (scheduler == null || scheduler.isShutdown())
            scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    // ======================== 监听器 ========================

    private class WsListener extends okhttp3.WebSocketListener {
        @Override
        public void onOpen(okhttp3.WebSocket ws, Response resp) {
            webSocket = ws;
            System.out.println("[EchoWork] ✅✅✅ onOpen status=" + (resp != null ? resp.code() : -1));
            reconnectDelay.set(1);
            subscribe();
        }

        @Override
        public void onMessage(okhttp3.WebSocket ws, String text) {
            System.out.println("\n[EchoWork] 📩 onMessage: " + text);
            handleMessage(text);
        }

        @Override
        public void onClosing(okhttp3.WebSocket ws, int code, String reason) {
            System.out.println("[EchoWork] 🔒 onClosing code=" + code + " reason=" + reason);
            ws.close(code, reason);
        }

        @Override
        public void onClosed(okhttp3.WebSocket ws, int code, String reason) {
            System.out.println("[EchoWork] 🔒 onClosed code=" + code + " reason=" + reason);
            subscribed = false;
            scheduleReconnect();
        }

        @Override
        public void onFailure(okhttp3.WebSocket ws, Throwable t, Response resp) {
            System.out.println("[EchoWork] ❌ onFailure: " + t.getClass().getName() + ": " + t.getMessage());
            if (resp != null) System.out.println("[EchoWork] status=" + resp.code());
            t.printStackTrace(System.out);
            subscribed = false;
            scheduleReconnect();
        }
    }

    // ======================== 消息分发 ========================

    private void handleMessage(String raw) {
        try {
            JSONObject msg = JSON.parseObject(raw);
            String cmd = msg.getString("cmd");
            JSONObject headers = msg.getJSONObject("headers");
            String reqId = headers != null ? headers.getString("req_id") : null;
            JSONObject body = msg.getJSONObject("body");
            int errcode = msg.getIntValue("errcode", 0);

            System.out.println("[EchoWork] cmd=" + cmd + " errcode=" + errcode + " reqId=" + reqId);

            // ---- 订阅响应 ----
            if ("aibot_subscribe".equals(cmd)) {
                if (errcode == 0) {
                    subscribed = true;
                    System.out.println("[EchoWork] ✅✅✅ 订阅成功！");
                    startHeartbeat();
                } else {
                    System.out.println("[EchoWork] ❌ 订阅失败 errcode=" + errcode + " errmsg=" + msg.getString("errmsg"));
                }
                return;
            }

            // ---- 心跳响应 ----
            if ("ping".equals(cmd)) {
                System.out.println("[EchoWork] 💓 心跳响应 errcode=" + errcode);
                return;
            }

            // ---- 消息回调 ----
            if ("aibot_msg_callback".equals(cmd)) {
                handleMsgCallback(reqId, body);
                return;
            }

            // ---- 事件回调 ----
            if ("aibot_event_callback".equals(cmd)) {
                handleEventCallback(reqId, body);
                return;
            }

            // ---- 其他响应（respond_msg 等） ----
            System.out.println("[EchoWork] 其他消息 cmd=" + cmd + " errcode=" + errcode + " errmsg=" + msg.getString("errmsg"));

        } catch (Exception e) {
            System.out.println("[EchoWork] ❌ handleMessage 异常: " + e.getMessage());
            e.printStackTrace(System.out);
        }
    }

    // ======================== 消息回调处理 ========================

    private void handleMsgCallback(String reqId, JSONObject body) {
        if (body == null) {
            System.out.println("[EchoWork] body 为空");
            return;
        }

        String msgtype = body.getString("msgtype");
        JSONObject from = body.getJSONObject("from");
        String userId = from != null ? from.getString("userid") : null;
        String msgid = body.getString("msgid");

        System.out.println("[EchoWork] msgtype=" + msgtype + " userId=" + userId + " msgid=" + msgid);

        // 只处理文本消息
        if (!"text".equals(msgtype)) {
            System.out.println("[EchoWork] 跳过非文本 msgtype=" + msgtype);
            return;
        }

        JSONObject text = body.getJSONObject("text");
        String content = text != null ? text.getString("content") : null;
        if (content == null || content.isBlank()) {
            System.out.println("[EchoWork] 空消息跳过");
            return;
        }

        if (reqId == null) {
            System.out.println("[EchoWork] reqId 为空，无法回复");
            return;
        }

        System.out.println("[EchoWork] 💬 " + userId + ": " + content);

        // 生成流式 ID，先发"处理中"
        String streamId = "s" + System.currentTimeMillis();
        respondAck(reqId, streamId);

        // 异步调用 Agent
        String finalReqId = reqId;
        String finalUserId = userId;
        String finalStreamId = streamId;
        String finalContent = content;
        CompletableFuture.runAsync(() -> {
            System.out.println("[EchoWork] 🔄 调用 Agent...");
            String reply;
            try {
                reply = cmdService.dealMsg(finalContent, finalUserId);
            } catch (Exception e) {
                System.out.println("[EchoWork] ❌ Agent 异常: " + e.getMessage());
                reply = "抱歉，处理消息时出了点问题，请稍后再试。";
            }
            respondText(finalReqId, finalStreamId, reply != null ? reply : "（无回复）");
        });
    }

    // ======================== 事件回调处理 ========================

    private void handleEventCallback(String reqId, JSONObject body) {
        if (body == null) return;
        JSONObject event = body.getJSONObject("event");
        String eventType = event != null ? event.getString("eventtype") : null;
        System.out.println("[EchoWork] 📅 事件 eventtype=" + eventType);

        // 进入会话 → 回复欢迎语
        if ("enter_chat".equals(eventType) && reqId != null) {
            JSONObject msg = new JSONObject();
            msg.put("cmd", "aibot_respond_welcome_msg");
            JSONObject headers = new JSONObject();
            headers.put("req_id", reqId);
            msg.put("headers", headers);
            JSONObject bodyOut = new JSONObject();
            bodyOut.put("msgtype", "text");
            JSONObject text = new JSONObject();
            text.put("content", "您好！我是智能助手，有什么可以帮您的吗？");
            bodyOut.put("text", text);
            msg.put("body", bodyOut);
            webSocket.send(msg.toJSONString());
            System.out.println("[EchoWork] 👋 已发送欢迎语");
        }
    }

    // ======================== 工具 ========================

    private String nextReqId() {
        return "r" + reqIdCounter.getAndIncrement() + "-" + System.currentTimeMillis();
    }

    private static String mask(String raw) {
        if (raw == null || raw.length() < 6) return raw;
        return raw.substring(0, 4) + "****" + raw.substring(raw.length() - 4);
    }

    private static String maskUrl(String url) {
        return url.replaceAll("bot_secret=[^&]+", "bot_secret=****");
    }
}
