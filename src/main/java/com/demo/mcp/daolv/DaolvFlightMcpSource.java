package com.demo.mcp.daolv;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONReader;
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
 * 道旅机票 MCP 专属实现 —— RestClient 直调（不使用 LangChain4j MCP SDK）。
 * <p>
 * 协议：Streamable HTTP（JSON-RPC over HTTP），每次调用独立请求-响应。
 * 鉴权：Authorization: Bearer &lt;API Key&gt;（与酒店 MCP 同一个 key）。
 * 官方工具：searchAirports（机场搜索）、searchFlights（机票搜索），
 * 由 MCP Server 的 tools/list 自动发现。
 * <p>
 * 与酒店 MCP（{@link DaolvMcpSource}）结构一致，实现统一的 {@link McpSource} 接口。
 */
@Slf4j
@Component
public class DaolvFlightMcpSource implements McpSource {

    private static final String SOURCE_NAME = "daolv-flight";

    @Resource
    private DaolvFlightMcpConfigProperties config;

    @Resource
    private RestTemplate restTemplate;

    private final AtomicInteger requestId = new AtomicInteger(1);
    private volatile String sessionId;
    private volatile boolean ready = false;
    private volatile String lastError; // 初始化失败原因（供前端展示）

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
        log.info("✈️ [{}] 开始初始化道旅机票 MCP 连接...", SOURCE_NAME);

        if (!config.isConfigured()) {
            log.warn("⚠️ [{}] 道旅机票 MCP 未配置（mcp.daolv-flight.token 为空或未启用），跳过初始化", SOURCE_NAME);
            System.out.println("[DaolvFlightMcp] ⚠️ 未配置，跳过");
            return;
        }

        JSONObject params = new JSONObject();
        params.put("protocolVersion", "2025-03-26");
        params.put("capabilities", new JSONObject());
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "wechat-bot-daolv-flight");
        clientInfo.put("version", "1.0.0");
        params.put("clientInfo", clientInfo);

        try {
            JSONObject result = sendRpc("initialize", params);
            this.ready = true;
            String serverName = result.containsKey("serverInfo")
                    ? result.getJSONObject("serverInfo").getString("name") : "unknown";
            log.info("✅ [{}] 道旅机票 MCP 初始化成功: server={}", SOURCE_NAME, serverName);
            System.out.println("[DaolvFlightMcp] ✅ 初始化成功 server=" + serverName);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            this.lastError = msg;
            log.warn("⚠️ [{}] 道旅机票 MCP 初始化失败: {}", SOURCE_NAME, msg);
            System.out.println("[DaolvFlightMcp] ❌ 初始化失败: " + msg);
        }
    }

    /** 初始化失败原因（未失败则为 null） */
    public String getLastError() {
        return lastError;
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
            return "道旅机票服务暂未就绪，请稍后再试。";
        }
        try {
            JSONObject params = new JSONObject();
            params.put("name", toolName);
            params.put("arguments", new JSONObject(arguments));

            JSONObject result = sendRpc("tools/call", params);
            return extractTextContent(result);
        } catch (Exception e) {
            log.error("[{}] 工具调用失败: {} | {}", SOURCE_NAME, toolName, e.getMessage(), e);
            return "🚫 道旅机票「" + toolName + "」服务暂时不可用：" + e.getMessage();
        }
    }

    // ======================== JSON-RPC 通信 ========================

    /**
     * 发送 JSON-RPC 请求并返回 result 对象。
     * 鉴权：Authorization: Bearer &lt;API Key&gt;。
     */
    private JSONObject sendRpc(String method, JSONObject params) {
        int id = requestId.getAndIncrement();

        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        request.put("params", params);
        request.put("id", id);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
        headers.set("Mcp-Protocol-Version", "2025-03-26");
        if (sessionId != null) {
            headers.set("Mcp-Session-Id", sessionId);
        }
        // 道旅机票鉴权：Authorization: Bearer <API Key>
        headers.setBearerAuth(config.getToken());

        String fullUrl = config.getUrl();
        HttpEntity<String> entity = new HttpEntity<>(request.toJSONString(), headers);

        log.debug("[{}] RPC → {}: {}", SOURCE_NAME, method, request.toJSONString());

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

        String rawStr = new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);
        String jsonStr = extractJsonFromSse(rawStr);

        int start = 0;
        byte[] jsonBytes = jsonStr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (jsonBytes.length >= 3
                && (jsonBytes[0] & 0xFF) == 0xEF
                && (jsonBytes[1] & 0xFF) == 0xBB
                && (jsonBytes[2] & 0xFF) == 0xBF) {
            start = 3;
            log.info("[{}] 移除UTF-8 BOM头", SOURCE_NAME);
        }
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

        if (start > 0) {
            log.info("[{}] 跳过前置非JSON字节共 {} 位", SOURCE_NAME, start);
        }

        String body = new String(jsonBytes, start, jsonBytes.length - start,
                java.nio.charset.StandardCharsets.UTF_8);

        log.info("[{}] 响应长度 {} 字节, JSON前200字符: {}", SOURCE_NAME, body.length(),
                body.length() > 200 ? body.substring(0, 200) : body);

        JSONObject json;
        try {
            json = JSON.parseObject(body, JSONReader.Feature.AllowUnQuotedFieldNames);
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

    /** 提取 MCP tools/call 返回的文本内容 */
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

    /** 从 Streamable HTTP 响应中提取 JSON 字符串（兼容 SSE data: 与纯 JSON） */
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

        if (start > 0) {
            log.debug("[{}] SSE处理跳过前置非JSON字符 {} 个", SOURCE_NAME, start);
        }
        return raw.substring(start);
    }
}
