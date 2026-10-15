package com.demo.controller;

import com.demo.bot.BotContext;
import com.demo.bot.BotInstance;
import com.demo.bot.BotManager;
import com.demo.session.stream.SessionChatStreamer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 桌面端（Tauri）接口
 * <p>
 * 与微信链路共用同一个 Agent（{@link com.demo.agent.Assistant}）和同一套图片能力。
 * 流式对话逻辑已下沉到 {@link SessionChatStreamer}（NDJSON 真流式 + 会话模式落库），
 * 本控制器保留入口与文件上传接口：
 * <ul>
 *   <li>{@code POST /api/desktop/chat/stream} 向后兼容入口；携带 sessionId 时启用会话记忆与落库</li>
 *   <li>{@code POST /api/desktop/file/upload} 上传文件/图片，返回 fileId</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/desktop")
public class DesktopController {

    @Resource
    private SessionChatStreamer streamer;

    @Resource
    private DesktopFileStore fileStore;

    @Resource
    private BotManager botManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** B站搜索客户端（餐饮食谱教程用） */
    private final java.net.http.HttpClient bilibiliClient = java.net.http.HttpClient.newHttpClient();

    /**
     * 代理 B站视频搜索（供三餐食谱匹配做法教程）
     * GET /api/desktop/bilibili/search?keyword=红烧肉
     */
    @GetMapping("/bilibili/search")
    public Map<String, Object> bilibiliSearch(@RequestParam String keyword) {
        Map<String, Object> result = new HashMap<>();
        try {
            String url = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword="
                    + java.net.URLEncoder.encode(keyword + " 做法 教程", "UTF-8") + "&page=1";
            var req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Referer", "https://www.bilibili.com")
                    .GET().build();
            var resp = bilibiliClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            var json = objectMapper.readTree(resp.body());
            if (json.get("code").asInt() != 0) { result.put("code", 500); result.put("msg", "B站API异常"); return result; }
            var results = json.get("data").get("result");
            if (results == null || results.isEmpty()) { result.put("code", 0); result.put("data", java.util.Collections.emptyList()); return result; }
            // 取播放量最高的一条
            var sorted = new java.util.ArrayList<java.util.Map<String, Object>>();
            for (var v : results) {
                if (v.has("play") && v.get("play").asLong() > 0) {
                    var m = new HashMap<String, Object>();
                    m.put("title", v.get("title").asText());
                    m.put("url", "https://www.bilibili.com/video/" + v.get("bvid").asText());
                    m.put("play", v.get("play").asLong());
                    sorted.add(m);
                }
            }
            sorted.sort((a, b) -> Long.compare((Long) b.get("play"), (Long) a.get("play")));
            result.put("code", 0);
            result.put("data", sorted.isEmpty() ? java.util.Collections.emptyList() : sorted.get(0));
        } catch (Exception e) {
            result.put("code", 500); result.put("msg", e.getMessage());
        }
        return result;
    }

    /**
     * 向指定微信用户发送私聊文本消息（供桌面端定时用药提醒调用）
     * POST /api/desktop/sendPrivateMsg
     * Body: { "targetWxid": "wxid_xxx", "message": "提醒文案" }
     */
    @PostMapping("/sendPrivateMsg")
    public Map<String, Object> sendPrivateMsg(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();
        String targetWxid = request.get("targetWxid");
        String message = request.get("message");

        if (targetWxid == null || targetWxid.isBlank()) {
            result.put("code", 400);
            result.put("msg", "targetWxid 不能为空");
            return result;
        }
        if (message == null || message.isBlank()) {
            result.put("code", 400);
            result.put("msg", "message 不能为空");
            return result;
        }

        BotInstance bot = botManager.getDefaultBot();
        if (bot == null || bot.getClient() == null) {
            result.put("code", 503);
            result.put("msg", "Bot 未登录（请先扫码登录微信机器人）");
            return result;
        }

        ILinkClient client = bot.getClient();
        BotContext.set(client, bot.getName());
        try {
            client.sendText(targetWxid, message);
            result.put("code", 0);
            result.put("msg", "ok");
            log.info("📤 [Desktop] 私聊消息已发送 → wxid={} len={}", targetWxid, message.length());
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "发送失败: " + e.getMessage());
            log.error("❌ [Desktop] 私聊消息发送失败 → wxid={}", targetWxid, e);
        } finally {
            BotContext.clear();
        }
        return result;
    }

    /** 上传文件（图片/Word/Excel 等任意文件）：接收 base64，返回 fileId，供聊天请求携带 */
    @PostMapping("/file/upload")
    public ResponseEntity<Map<String, String>> uploadFile(@RequestBody Map<String, String> payload) {
        String data = payload.get("data");
        if (data == null || data.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件 data 不能为空"));
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "base64 解码失败"));
        }
        if (bytes.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件内容为空"));
        }
        if (bytes.length > 30 * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件过大（>30MB）"));
        }
        String fileId = fileStore.put(bytes, payload.get("fileName"), payload.get("mime"));
        return ResponseEntity.ok(Map.of("fileId", fileId));
    }

    /**
     * NDJSON 流式对话（向后兼容入口）。
     * 请求体：content(用户文本) / userId / fileId(可选) / sessionId(可选)。
     * sessionId 存在时启用会话模式（每会话独立上下文 + 落库 + 标题生成）。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseEntity<StreamingResponseBody> chatStream(@RequestBody Map<String, String> payload) {
        StreamingResponseBody streamBody = outputStream -> streamer.stream(payload, outputStream);
        return ResponseEntity.ok(streamBody);
    }
}
