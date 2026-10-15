package com.demo.mcp.wecom_todo;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.demo.mcp.McpSource;
import com.demo.mcp.McpToolDefinition;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 企业微信机器人待办 MCP 专属实现。
 * <p>
 * 协议：Streamable HTTP（JSON-RPC over HTTP）。
 * 鉴权：URL 参数 ?apikey=xxx。
 * <p>
 * 提供 7 个工具：
 *   create_todo, update_todo, get_todo_list, get_todo_detail,
 *   delete_todo, search_todo_userid, change_todo_user_status
 */
@Slf4j
@Component
public class WecomTodoMcpSource implements McpSource {

    private static final String SOURCE_NAME = "todo";

    @Resource
    private WecomTodoMcpConfigProperties config;

    @Resource
    private RestTemplate restTemplate;

    private final AtomicInteger requestId = new AtomicInteger(1);
    private volatile String sessionId;
    private volatile boolean ready = false;

    @Override
    public String getSourceName() { return SOURCE_NAME; }

    @Override
    public boolean isReady() { return ready; }

    @Override
    public void init() {
        log.info("✅ [{}] 初始化企业微信待办 MCP 连接...", SOURCE_NAME);
        if (!config.isConfigured()) {
            log.warn("⚠️ [{}] 未配置（mcp.todo.api-key 为空），跳过", SOURCE_NAME);
            System.out.println("[TodoMcp] ⚠️ 未配置，跳过");
            return;
        }
        JSONObject params = new JSONObject();
        params.put("protocolVersion", "2025-03-26");
        params.put("capabilities", new JSONObject());
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "wechat-bot-todo");
        clientInfo.put("version", "1.0.0");
        params.put("clientInfo", clientInfo);
        try {
            sendRpc("initialize", params);
            this.ready = true;
            System.out.println("[TodoMcp] ✅ 初始化成功");
            log.info("✅ [{}] 初始化成功", SOURCE_NAME);
        } catch (Exception e) {
            System.out.println("[TodoMcp] ❌ 初始化失败: " + e.getMessage());
            log.warn("⚠️ [{}] 初始化失败: {}", SOURCE_NAME, e.getMessage());
        }
    }

    @Override
    public List<McpToolDefinition> listTools() {
        if (!ready) return Collections.emptyList();
        try {
            JSONObject result = sendRpc("tools/list", new JSONObject());
            JSONArray tools = result.getJSONArray("tools");
            if (tools == null || tools.isEmpty()) return Collections.emptyList();
            List<McpToolDefinition> defs = new ArrayList<>();
            for (int i = 0; i < tools.size(); i++) {
                JSONObject t = tools.getJSONObject(i);
                McpToolDefinition def = new McpToolDefinition(
                        t.getString("name"), t.getString("description"),
                        SOURCE_NAME, t.getJSONObject("inputSchema"));
                defs.add(def);
                log.info("   📋 [{}] 发现工具: {}", SOURCE_NAME, def.qualifiedName());
            }
            log.info("✅ [{}] 共 {} 个工具", SOURCE_NAME, defs.size());
            return defs;
        } catch (Exception e) {
            log.error("[{}] 工具发现失败: {}", SOURCE_NAME, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) {
        if (!ready) return "待办服务暂未就绪";
        try {
            JSONObject params = new JSONObject();
            params.put("name", toolName);
            params.put("arguments", new JSONObject(arguments));
            JSONObject result = sendRpc("tools/call", params);
            return extractTextContent(result);
        } catch (Exception e) {
            log.error("[{}] 调用失败 {}: {}", SOURCE_NAME, toolName, e.getMessage());
            return "🚫 待办「" + toolName + "」不可用：" + e.getMessage();
        }
    }

    // ======================== JSON-RPC ========================

    private JSONObject sendRpc(String method, JSONObject params) {
        int id = requestId.getAndIncrement();
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        request.put("params", params);
        request.put("id", id);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Mcp-Protocol-Version", "2025-03-26");
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        if (sessionId != null) headers.set("Mcp-Session-Id", sessionId);

        byte[] reqBytes = request.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RequestCallback callback = req -> {
            req.getHeaders().putAll(headers);
            req.getBody().write(reqBytes);
        };
        ResponseExtractor<byte[]> extractor = resp -> {
            List<String> sh = resp.getHeaders().get("Mcp-Session-Id");
            if (sh != null && !sh.isEmpty()) sessionId = sh.get(0);
            try (InputStream is = resp.getBody()) { return StreamUtils.copyToByteArray(is); }
        };

        byte[] raw = restTemplate.execute(config.getUrl(), HttpMethod.POST, callback, extractor);
        if (raw == null || raw.length == 0) return new JSONObject();

        String body = extractJson(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
        JSONObject json = JSON.parseObject(body);
        if (json.containsKey("error")) {
            JSONObject e = json.getJSONObject("error");
            throw new RuntimeException("JSON-RPC error " + e.getIntValue("code") + ": " + e.getString("message"));
        }
        return json.getJSONObject("result");
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        String sse = java.util.Arrays.stream(raw.split("\n"))
                .map(String::trim).filter(l -> l.startsWith("data:"))
                .map(l -> l.substring(5).trim()).findFirst().orElse(null);
        if (sse != null && (sse.startsWith("{") || sse.startsWith("["))) return sse;
        int start = 0;
        while (start < raw.length() && raw.charAt(start) != '{' && raw.charAt(start) != '[') start++;
        return start < raw.length() ? raw.substring(start) : raw;
    }

    private String extractTextContent(JSONObject result) {
        JSONArray content = result.getJSONArray("content");
        if (content != null && !content.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.size(); i++) {
                JSONObject item = content.getJSONObject(i);
                if ("text".equals(item.getString("type"))) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(item.getString("text"));
                }
            }
            return sb.toString();
        }
        return result.toJSONString();
    }
}
