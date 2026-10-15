package com.demo.mcp.juhe;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.demo.mcp.McpSource;
import com.demo.mcp.McpToolDefinition;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 聚合数据 MCP 专属实现 —— RestClient 直调（不使用 LangChain4j MCP SDK）。
 * <p>
 * 协议：Streamable HTTP（JSON-RPC over HTTP），每次调用独立请求-响应。
 * 鉴权：URL 参数 token（?token=xxx）。
 * 与滴滴 MCP 的 {@code DidiMcpSource} 结构对齐，实现统一的 {@link McpSource} 接口。
 * <p>
 * 聚合数据提供 3 个 MCP 工具：
 *   query_train_tickets   查询火车票
 *   book_train_ticket     预订火车票（含两段式确认）
 *   pay_train_ticket      支付火车票订单
 * <p>
 * 注意：query_train_tickets 暂未使用（查询走 TrainTicketService REST API），
 * book_train_ticket 和 pay_train_ticket 由 JuheTrainTicketMcpTool @Tool 包装后使用。
 */
@Slf4j
@Component
public class JuheMcpSource implements McpSource {

    private static final String SOURCE_NAME = "juhe-train";

    @Resource
    private JuheMcpConfigProperties config;

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
        log.info(" [{}] 开始初始化聚合数据 MCP 连接...", SOURCE_NAME);

        if (!config.isConfigured()) {
            log.warn("⚠️ [{}] 聚合数据 MCP 未配置（juhe.mcp.token 或 juhe.mcp.enabled=false），跳过初始化", SOURCE_NAME);
            System.out.println("[JuheMcp] ⚠️ 未配置，跳过");
            return;
        }

        JSONObject params = new JSONObject();
        params.put("protocolVersion", "2025-03-26");
        params.put("capabilities", new JSONObject());
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "wechat-bot-juhe");
        clientInfo.put("version", "1.0.0");
        params.put("clientInfo", clientInfo);

        try {
            JSONObject result = sendRpc("initialize", params);
            this.ready = true;
            String serverName = result.containsKey("serverInfo")
                    ? result.getJSONObject("serverInfo").getString("name") : "unknown";
            log.info("✅ [{}] 聚合数据 MCP 初始化成功: server={}", SOURCE_NAME, serverName);
            System.out.println("[JuheMcp] ✅ 初始化成功 server=" + serverName);

            // 启动时列出所有可用工具及其参数 schema
            discoverTools();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("⚠️ [{}] 聚合数据 MCP 初始化失败: {}", SOURCE_NAME, msg);
            System.out.println("[JuheMcp] ❌ 初始化失败: " + msg);
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
            log.error("[{}] 工具列表获取失败: {}", SOURCE_NAME, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) {
        if (!ready) {
            return "聚合数据服务暂未就绪，请稍后再试。";
        }
        try {
            JSONObject params = new JSONObject();
            params.put("name", toolName);
            params.put("arguments", new JSONObject(arguments));

            JSONObject result = sendRpc("tools/call", params);
            return extractTextContent(result);
        } catch (Exception e) {
            log.error("[{}] 工具调用失败: {} | {}", SOURCE_NAME, toolName, e.getMessage());
            return "🚫 聚合数据「" + toolName + "」服务暂时不可用：" + e.getMessage();
        }
    }

    // ======================== JSON-RPC 通信 ========================

    /**
     * 发送 JSON-RPC 请求并返回 result 对象。
     * 每次调用是独立的 HTTP POST 请求-响应，无状态、无长连接。
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
        headers.set("Mcp-Protocol-Version", "2025-03-26");
        if (sessionId != null) {
            headers.set("Mcp-Session-Id", sessionId);
        }
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));

        // 聚合数据鉴权：token 走 URL 参数
        String fullUrl = config.getUrl() + "?token=" + config.getToken();

        log.debug("[{}] RPC → {}: {}", SOURCE_NAME, method, request.toJSONString());

        HttpEntity<String> entity = new HttpEntity<>(request.toJSONString(), headers);
        ResponseEntity<String> response = restTemplate.exchange(
                fullUrl, HttpMethod.POST, entity, String.class);

        // 提取 session ID
        List<String> sessionHeaders = response.getHeaders().get("Mcp-Session-Id");
        if (sessionHeaders != null && !sessionHeaders.isEmpty()) {
            this.sessionId = sessionHeaders.get(0);
        }

        String body = response.getBody();
        log.debug("[{}] ← HTTP body({}): {}", SOURCE_NAME,
                body != null ? body.length() : 0,
                body != null ? body.substring(0, Math.min(500, body.length())) : "null");

        if (body == null || body.isBlank()) {
            log.debug("[{}] 响应体为空（可能走 SSE 事件流）", SOURCE_NAME);
            return new JSONObject();
        }
        body = extractJsonFromSse(body);
        if (body == null || body.isBlank()) {
            log.debug("[{}] SSE 提取后响应体为空", SOURCE_NAME);
            return new JSONObject();
        }
        JSONObject json = JSONObject.parseObject(body);

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

    /** 剥离 SSE data: 前缀，提取纯净 JSON 字符串 */
    private String extractJsonFromSse(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return java.util.Arrays.stream(raw.split("\n"))
                .map(String::trim)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim())
                .findFirst()
                .orElse(raw);
    }

    /** 启动时发现 MCP 工具列表及其精确参数 schema（调试用） */
    private void discoverTools() {
        try {
            System.out.println("[JuheMcp] 发现 MCP 工具列表...");
            List<McpToolDefinition> tools = listTools();
            if (tools.isEmpty()) {
                System.out.println("[JuheMcp] 📋 listTools 返回空（MCP 可能不支持 tools/list）");
                return;
            }
            System.out.println("[JuheMcp] ==================== MCP 工具列表 (" + tools.size() + "个) ====================");
            for (int i = 0; i < tools.size(); i++) {
                McpToolDefinition tool = tools.get(i);
                System.out.println("[JuheMcp] [" + i + "] " + tool.qualifiedName() + " → " + tool.getDescription());
                log.info("📋 MCP 工具 [{}]: {} — {}", i, tool.qualifiedName(), tool.getDescription());
                if (tool.getInputSchema() != null) {
                    JSONArray required = tool.getInputSchema().getJSONArray("required");
                    JSONObject props = tool.getInputSchema().getJSONObject("properties");
                    String reqStr = required != null ? required.toJSONString() : "[]";
                    System.out.println("[JuheMcp]    Required: " + reqStr);
                    if (props != null) {
                        for (String key : props.keySet()) {
                            JSONObject prop = props.getJSONObject(key);
                            System.out.println("[JuheMcp]    - " + key + ": type=" + prop.getString("type")
                                    + " desc=" + prop.getString("description"));
                        }
                    }
                }
            }
            System.out.println("[JuheMcp] ==================== 工具列表结束 ====================");
        } catch (Exception e) {
            log.warn("⚠️ [{}] 工具发现失败: {}", SOURCE_NAME, e.getMessage());
            System.out.println("[JuheMcp] ❌ 工具发现失败: " + e.getMessage());
        }
    }
}
