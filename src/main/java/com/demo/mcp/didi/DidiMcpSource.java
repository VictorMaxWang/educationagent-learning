package com.demo.mcp.didi;
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
 * 滴滴出行 MCP 专属实现 —— RestClient 直调（不使用 LangChain4j MCP SDK）。
 * <p>
 * 协议：Streamable HTTP（JSON-RPC over HTTP），每次调用独立请求-响应。
 * 鉴权：Bearer Token（mcp.didi.api-key）。
 * 滴滴出行提供 13 个 MCP 工具：
 *   place_search, taxi_estimate, taxi_create_order, taxi_cancel_order,
 *   taxi_order_status, taxi_nearby_drivers, ride_estimate, ride_create_order,
 *   delivery_estimate, delivery_create_order, travel_time_estimate,
 *   route_planning, price_compare
 * <p>
 * 沙箱模式：mcp.didi.sandbox=true 时不产生真实订单。
 */
@Slf4j
@Component
public class DidiMcpSource implements McpSource {
    private static final String SOURCE_NAME = "didi";
    @org.springframework.beans.factory.annotation.Value("${mcp.didi.base-url}")
    private String mcpUrl;
    @Resource
    private McpConfigProperties config;
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
        log.info("🚕 [{}] 开始初始化滴滴 MCP 连接... sandbox={}", SOURCE_NAME, config.isSandbox());
        if (!config.isConfigured()) {
            log.warn("⚠️ [{}] 滴滴 MCP 未配置（mcp.didi.api-key 为空），跳过初始化", SOURCE_NAME);
            System.out.println("[DidiMcp] ⚠️ 未配置，跳过");
            return;
        }
        JSONObject params = new JSONObject();
        params.put("protocolVersion", "2025-03-26");
        params.put("capabilities", new JSONObject());
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "wechat-bot-didi");
        clientInfo.put("version", "1.0.0");
        params.put("clientInfo", clientInfo);
        try {
            JSONObject result = sendRpc("initialize", params);
            this.ready = true;
            String serverName = result.containsKey("serverInfo")
                    ? result.getJSONObject("serverInfo").getString("name") : "unknown";
            log.info("✅ [{}] 滴滴 MCP 初始化成功: server={} sandbox={}",
                    SOURCE_NAME, serverName, config.isSandbox());
            System.out.println("[DidiMcp] ✅ 初始化成功 server=" + serverName + " sandbox=" + config.isSandbox());
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("⚠️ [{}] 滴滴 MCP 初始化失败: {}", SOURCE_NAME, msg);
            System.out.println("[DidiMcp] ❌ 初始化失败: " + msg);
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
            return "滴滴出行服务暂未就绪，请稍后再试。";
        }
        try {
            JSONObject params = new JSONObject();
            params.put("name", toolName);
            params.put("arguments", new JSONObject(arguments));
            JSONObject result = sendRpc("tools/call", params);
            return extractTextContent(result);
        } catch (Exception e) {
            log.error("[{}] 工具调用失败: {} | {}", SOURCE_NAME, toolName, e.getMessage(), e);
            return "🚫 滴滴出行「" + toolName + "」服务暂时不可用：" + e.getMessage();
        }
    }
    // ======================== JSON-RPC 通信 ========================
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
        if (sessionId != null) {
            headers.set("Mcp-Session-Id", sessionId);
        }
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String baseUrl = config.isSandbox() ? mcpUrl + "-sandbox" : mcpUrl;
        String fullUrl = baseUrl + "?key=" + config.getApiKey();
        HttpEntity<String> entity = new HttpEntity<>(request.toJSONString(), headers);
        log.debug("[{}] RPC → {}: {}", SOURCE_NAME, method, request.toJSONString());

        // 用 execute + ResponseExtractor 手动读流，彻底绕过 ByteArrayHttpMessageConverter 对大响应的截断问题
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
        //由于滴滴的工具清单很长，导致之前使用exchange的时候切段传输只取回了第一个字符，改用execute手动读取全部内容
        byte[] rawBytes = restTemplate.execute(fullUrl, HttpMethod.POST, requestCallback, responseExtractor);

        if (rawBytes == null || rawBytes.length == 0) {
            log.warn("[{}] 接口返回空字节流", SOURCE_NAME);
            return new JSONObject();
        }
        // 跳过UTF8 BOM 0xEF 0xBB 0xBF
        int start = 0;
        if (rawBytes.length >= 3
                && (rawBytes[0] & 0xFF) == 0xEF
                && (rawBytes[1] & 0xFF) == 0xBB
                && (rawBytes[2] & 0xFF) == 0xBF) {
            start = 3;
            log.info("[{}] 移除UTF-8 BOM头", SOURCE_NAME);
        }
        // 跳过前面所有非 JSON 字节
        while (start < rawBytes.length
                && rawBytes[start] != '{'
                && rawBytes[start] != '[') {
            start++;
        }
        if (start >= rawBytes.length) {
            log.error("[{}] 响应无合法JSON开头，字节长度:{}", SOURCE_NAME, rawBytes.length);
            return new JSONObject();
        }
        if (start > 0) {
            log.info("[{}] 跳过前置非JSON字节共 {} 位", SOURCE_NAME, start);
        }
        String body = new String(rawBytes, start, rawBytes.length - start, java.nio.charset.StandardCharsets.UTF_8);
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
    /** 剥离 SSE data: 前缀，并兼容响应体前的控制字符/乱码 */
    private String extractJsonFromSse(String raw) {
        if (raw == null || raw.isBlank()) return "";
        // 1. 先尝试 SSE data: 行
        String sse = java.util.Arrays.stream(raw.split("\n"))
                .map(String::trim)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim())
                .findFirst()
                .orElse(null);
        if (sse != null && (sse.startsWith("{") || sse.startsWith("["))) {
            return sse;
        }
        // 2. 回退：从原始响应中找到第一个 { 或 [，跳过前面的控制字符/乱码
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