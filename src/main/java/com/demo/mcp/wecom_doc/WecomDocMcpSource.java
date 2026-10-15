package com.demo.mcp.wecom_doc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.demo.mcp.McpSource;
import com.demo.mcp.McpToolDefinition;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
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
 * 企业微信机器人文档 MCP 专属实现 —— RestClient 直调。
 * <p>
 * 协议：Streamable HTTP（JSON-RPC over HTTP），每次调用独立请求-响应。
 * <p>
 * 企业微信机器人提供 20+ 个 MCP 工具，覆盖文档/表格/智能文档的完整读写：
 * <ul>
 *   <li>文档：create_doc, edit_doc_content, get_doc_content</li>
 *   <li>智能文档：smartpage_create, smartpage_get_export_result</li>
 *   <li>智能表格：smartsheet_CRUD (sheet/fields/records)</li>
 *   <li>表格：sheet_get_info, sheet_add_sub, sheet_delete_sub, sheet_update_range_data, sheet_append_data</li>
 * </ul>
 * <p>
 * 与滴滴 MCP ({@link com.demo.mcp.didi.DidiMcpSource}) 结构对齐，
 * 实现统一的 {@link McpSource} 接口，由 {@link com.demo.mcp.McpClientManager} 统一管理。
 */
@Slf4j
@Component
public class WecomDocMcpSource implements McpSource {

    private static final String SOURCE_NAME = "wecom";

    @Resource
    private WecomDocMcpConfigProperties config;

    @Resource
    private RestTemplate restTemplate;

    private final AtomicInteger requestId = new AtomicInteger(1);
    private volatile String sessionId;
    private volatile boolean ready = false;

    // ======================== McpSource 接口实现 ========================

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public void init() {
        log.info("📄 [{}] 开始初始化企业微信机器人文档 MCP 连接...", SOURCE_NAME);

        if (!config.isConfigured()) {
            log.warn("⚠️ [{}] 企业微信文档 MCP 未配置（mcp.wecom.api-key 为空），跳过初始化", SOURCE_NAME);
            System.out.println("[WecomMcp] ⚠️ 未配置，跳过");
            return;
        }

        // JSON-RPC initialize
        JSONObject params = new JSONObject();
        params.put("protocolVersion", "2025-03-26");
        params.put("capabilities", new JSONObject());
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "wechat-bot-wecom");
        clientInfo.put("version", "1.0.0");
        params.put("clientInfo", clientInfo);

        try {
            JSONObject result = sendRpc("initialize", params);
            this.ready = true;
            String serverName = result.containsKey("serverInfo")
                    ? result.getJSONObject("serverInfo").getString("name") : "unknown";
            log.info("✅ [{}] 企业微信文档 MCP 初始化成功: server={}", SOURCE_NAME, serverName);
            System.out.println("[WecomMcp] ✅ 初始化成功 server=" + serverName);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("⚠️ [{}] 企业微信文档 MCP 初始化失败: {}", SOURCE_NAME, msg);
            System.out.println("[WecomMcp] ❌ 初始化失败: " + msg);
        }
    }

    @Override
    public List<McpToolDefinition> listTools() {
        if (!ready) {
            return Collections.emptyList();
        }
        try {
            JSONObject result = sendRpc("tools/list", new JSONObject());
            JSONArray tools = result.getJSONArray("tools");
            if (tools == null || tools.isEmpty()) {
                log.warn("[{}] tools/list 返回空列表", SOURCE_NAME);
                return Collections.emptyList();
            }

            List<McpToolDefinition> definitions = new ArrayList<>();
            for (int i = 0; i < tools.size(); i++) {
                JSONObject tool = tools.getJSONObject(i);
                McpToolDefinition def = new McpToolDefinition(
                        tool.getString("name"),
                        tool.getString("description"),
                        SOURCE_NAME,
                        tool.getJSONObject("inputSchema")
                );
                definitions.add(def);
                log.info("   📋 [{}] 发现工具: {}", SOURCE_NAME, def.qualifiedName());
            }
            log.info("✅ [{}] 工具发现完成，共 {} 个工具", SOURCE_NAME, definitions.size());
            return definitions;
        } catch (Exception e) {
            log.error("[{}] 工具列表获取失败: {}", SOURCE_NAME, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) {
        if (!ready) {
            return "企业微信文档服务暂未就绪，请稍后再试。";
        }
        try {
            JSONObject params = new JSONObject();
            params.put("name", toolName);
            params.put("arguments", new JSONObject(arguments));

            JSONObject result = sendRpc("tools/call", params);
            return extractTextContent(result);
        } catch (Exception e) {
            log.error("[{}] 工具调用失败: {} | {}", SOURCE_NAME, toolName, e.getMessage(), e);
            return "🚫 企业微信文档「" + toolName + "」服务暂时不可用：" + e.getMessage();
        }
    }

    // ======================== JSON-RPC 通信 ========================

    /**
     * 发送 JSON-RPC 请求并返回 result 对象。
     * <p>
     * 鉴权：apikey 已包含在 URL 中（由 ConfigProperties 拼接）。
     * 使用 execute + ResponseExtractor 手动读流，绕过 ByteArrayHttpMessageConverter
     * 对大响应的截断问题（参考 DidiMcpSource 的修复方案）。
     */
    private JSONObject sendRpc(String method, JSONObject params) {
        int id = requestId.getAndIncrement();

        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        request.put("params", params);
        request.put("id", id);

        String fullUrl = config.getUrl();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Mcp-Protocol-Version", "2025-03-26");
        if (sessionId != null) {
            headers.set("Mcp-Session-Id", sessionId);
        }
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));

        log.debug("[{}] RPC → {}: {}", SOURCE_NAME, method, request.toJSONString());

        // 用 execute + ResponseExtractor 手动读流（解决大响应截断问题）
        byte[] reqBytes = request.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RequestCallback requestCallback = clientReq -> {
            clientReq.getHeaders().putAll(headers);
            clientReq.getBody().write(reqBytes);
        };
        ResponseExtractor<byte[]> responseExtractor = (ClientHttpResponse response) -> {
            List<String> sh = response.getHeaders().get("Mcp-Session-Id");
            if (sh != null && !sh.isEmpty()) {
                this.sessionId = sh.get(0);
            }
            try (InputStream is = response.getBody()) {
                return StreamUtils.copyToByteArray(is);
            }
        };

        byte[] rawBytes = restTemplate.execute(fullUrl, HttpMethod.POST, requestCallback, responseExtractor);

        if (rawBytes == null || rawBytes.length == 0) {
            log.warn("[{}] 接口返回空字节流", SOURCE_NAME);
            return new JSONObject();
        }

        // 尝试 SSE 解析
        String rawStr = new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);
        String jsonStr = extractJsonFromSse(rawStr);

        // 跳过 UTF-8 BOM
        int start = 0;
        byte[] jsonBytes = jsonStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (jsonBytes.length >= 3
                && (jsonBytes[0] & 0xFF) == 0xEF
                && (jsonBytes[1] & 0xFF) == 0xBB
                && (jsonBytes[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        // 跳过前面所有非 JSON 字节
        while (start < jsonBytes.length
                && jsonBytes[start] != '{'
                && jsonBytes[start] != '[') {
            start++;
        }

        if (start >= jsonBytes.length) {
            log.error("[{}] 响应无合法JSON开头，原始响应前200字符: {}", SOURCE_NAME,
                    rawStr.length() > 200 ? rawStr.substring(0, 200) : rawStr);
            return new JSONObject();
        }

        String body = new String(jsonBytes, start, jsonBytes.length - start,
                java.nio.charset.StandardCharsets.UTF_8);

        log.debug("[{}] 响应长度 {} 字节", SOURCE_NAME, body.length());

        JSONObject json;
        try {
            json = JSON.parseObject(body);
        } catch (Exception e) {
            log.error("[{}] JSON解析失败，前200字符: {}", SOURCE_NAME,
                    body.length() > 200 ? body.substring(0, 200) : body, e);
            throw e;
        }

        if (json.containsKey("error")) {
            JSONObject error = json.getJSONObject("error");
            throw new RuntimeException("JSON-RPC error " + error.getIntValue("code")
                    + ": " + error.getString("message"));
        }

        return json.getJSONObject("result");
    }

    // ======================== 工具方法 ========================

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

    private String extractJsonFromSse(String raw) {
        if (raw == null || raw.isBlank()) return "";

        String sse = java.util.Arrays.stream(raw.split("\n"))
                .map(String::trim)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim())
                .findFirst()
                .orElse(null);

        if (sse != null && (sse.startsWith("{") || sse.startsWith("["))) {
            return sse;
        }

        int brace = raw.indexOf('{');
        int bracket = raw.indexOf('[');
        int start;
        if (brace >= 0 && bracket >= 0) {
            start = Math.min(brace, bracket);
        } else if (brace >= 0) {
            start = brace;
        } else if (bracket >= 0) {
            start = bracket;
        } else {
            return raw;
        }
        return raw.substring(start);
    }
}
