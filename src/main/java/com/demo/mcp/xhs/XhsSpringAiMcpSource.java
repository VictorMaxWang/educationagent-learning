package com.demo.mcp.xhs;

import com.alibaba.fastjson2.JSONObject;
import com.demo.mcp.McpSource;
import com.demo.mcp.McpToolDefinition;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 小红书 MCP 客户端——基于 Spring AI MCP SDK (McpSyncClient) 实现。
 * <p>
 * 使用 MCP Java SDK 的 Streamable HTTP Transport，自动处理协议握手、
 * 会话管理、工具发现与调用。
 * <p>
 * 鉴权方式：Cookie（Base64 编码后配置，运行时解码注入 Header）。
 */
@Slf4j
@Component
@SuppressWarnings("SpellCheckingInspection")
public class XhsSpringAiMcpSource implements McpSource {

    private static final String SOURCE_NAME = "xhs";

    @Resource
    private XhsMcpConfigProperties config;

    private McpSyncClient mcpClient;
    
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private volatile List<McpToolDefinition> toolCache = Collections.emptyList();

    // ======================== McpSource 接口实现 ========================

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    @Override
    public boolean isReady() {
        return ready.get();
    }

    /**
     * 初始化 MCP 连接（PostConstruct 自动触发）。
     * 完成 Streamable HTTP 握手 + 工具发现。
     */
    @Override
    public void init() {
        log.info("📕 [{}] 开始初始化小红书 MCP 连接 (Spring AI SDK)...", SOURCE_NAME);

        if (!config.isConfigured()) {
            log.warn("⚠️ [{}] 小红书 MCP 未启用或 Cookie 为空，跳过初始化", SOURCE_NAME);
            return;
        }

        try {
            // 1. 解码 Cookie
            String cookie = decodeCookie(config.getCookie());

            // 2. 预配置 HttpRequest.Builder，注入 Cookie 鉴权 Header
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .header("Cookie", cookie);

            // 3. 构建 Streamable HTTP Transport
            var transport = HttpClientStreamableHttpTransport.builder(config.getUrl())
                    .requestBuilder(requestBuilder)
                    .build();

            // 4. 创建同步 MCP 客户端
            mcpClient = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .build();

            // 5. 初始化握手（协议版本协商 + 能力声明）
            McpSchema.InitializeResult initResult = mcpClient.initialize();
            log.info("✅ [{}] 小红书 MCP 握手成功 | Server: {} v{}",
                    SOURCE_NAME,
                    initResult.serverInfo() != null ? initResult.serverInfo().name() : "unknown",
                    initResult.serverInfo() != null ? initResult.serverInfo().version() : "?");

            // 6. 发现工具列表
            discoverTools();

            ready.set(true);
            log.info("✅ [{}] 小红书 MCP 初始化完成，共 {} 个工具", SOURCE_NAME, toolCache.size());
        } catch (Exception e) {
            log.error("⚠️ [{}] MCP 初始化失败: {}", SOURCE_NAME, e.getMessage(), e);
            ready.set(false);
        }
    }

    /**
     * 卸载：关闭连接并清空工具缓存（懒加载 deactivate 调用）。
     * 之后可再次 init() 重新建立连接。
     */
    @Override
    public void close() {
        ready.set(false);
        toolCache = Collections.emptyList();
        if (mcpClient != null) {
            try {
                mcpClient.close();
            } catch (Exception e) {
                log.warn("[{}] 关闭 MCP 客户端时异常: {}", SOURCE_NAME, e.getMessage());
            }
            mcpClient = null;
        }
        log.info("📕 [{}] 已卸载，连接已关闭", SOURCE_NAME);
    }

    /**
     * 清理 MCP 客户端连接。
     */
    @PreDestroy
    public void destroy() {
        if (mcpClient != null) {
            try {
                mcpClient.close();
                log.info("[{}] MCP 客户端已关闭", SOURCE_NAME);
            } catch (Exception e) {
                log.warn("[{}] 关闭 MCP 客户端时异常: {}", SOURCE_NAME, e.getMessage());
            }
        }
    }

    @Override
    public List<McpToolDefinition> listTools() {
        return toolCache;
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) {
        if (!ready.get()) {
            return "❌ 小红书 MCP 服务未就绪，请等待初始化完成";
        }
        Objects.requireNonNull(arguments, "arguments 参数不能为 null");
        Objects.requireNonNull(toolName, "toolName 工具名不能为空");

        try {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, arguments);
            McpSchema.CallToolResult result = mcpClient.callTool(request);
            return extractTextContent(result);
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("[{}] 工具调用异常 {}: {}", SOURCE_NAME, toolName, errMsg, e);
            return "🚫 小红书工具 [" + toolName + "] 执行失败：" + errMsg;
        }
    }

    // ======================== 内部方法 ========================

    /**
     * 发现 MCP 服务端工具列表，转为 {@link McpToolDefinition} 缓存。
     */
    private void discoverTools() {
        McpSchema.ListToolsResult toolsResult = mcpClient.listTools();
        List<McpSchema.Tool> tools = toolsResult.tools();

        if (tools == null || tools.isEmpty()) {
            log.warn("[{}] MCP 服务端返回空工具列表", SOURCE_NAME);
            toolCache = Collections.emptyList();
            return;
        }

        List<McpToolDefinition> definitions = new ArrayList<>(tools.size());
        for (McpSchema.Tool tool : tools) {
            String name = tool.name();
            String description = tool.description();

            // 将 Jackson JsonNode inputSchema 转为 fastjson2 JSONObject
            JSONObject inputSchema = convertSchema(tool.inputSchema());

            McpToolDefinition def = new McpToolDefinition(name, description, SOURCE_NAME, inputSchema);
            definitions.add(def);
            log.info("   📋 [{}] 加载工具: {}", SOURCE_NAME, def.qualifiedName());
        }

        toolCache = definitions;
        log.info("✅ [{}] 工具列表加载完成，共 {} 个", SOURCE_NAME, definitions.size());
    }

    /**
     * 将 MCP Schema JsonSchema 转为 fastjson2 JSONObject。
     */
    private JSONObject convertSchema(McpSchema.JsonSchema jsonSchema) {
        if (jsonSchema == null) {
            return new JSONObject();
        }
        try {
            // Jackson 序列化 → fastjson2 解析
            String jsonStr = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(jsonSchema);
            return JSONObject.parseObject(jsonStr);
        } catch (Exception e) {
            log.warn("[{}] inputSchema 转换失败: {}", SOURCE_NAME, e.getMessage());
            return new JSONObject();
        }
    }

    /**
     * 从 MCP CallToolResult 中提取文本内容。
     */
    private String extractTextContent(McpSchema.CallToolResult result) {
        List<McpSchema.Content> content = result.content();
        if (content == null || content.isEmpty()) {
            return "";
        }

        StringBuilder textBuilder = new StringBuilder();
        for (McpSchema.Content item : content) {
            if (item instanceof McpSchema.TextContent textContent) {
                String text = textContent.text();
                if (text != null && !text.isBlank()) {
                    if (textBuilder.length() > 0) {
                        textBuilder.append("\n");
                    }
                    textBuilder.append(text);
                }
            } else if (item instanceof McpSchema.ImageContent imageContent) {
                // 图片内容（如登录二维码）不能丢弃，以 [IMAGE:mime]base64 形式透传
                String mime = imageContent.mimeType() != null && !imageContent.mimeType().isBlank()
                        ? imageContent.mimeType() : "image/png";
                String data = imageContent.data();
                if (data != null && !data.isBlank()) {
                    if (textBuilder.length() > 0) {
                        textBuilder.append("\n");
                    }
                    textBuilder.append("[IMAGE:").append(mime).append("]").append(data);
                }
            }
        }
        return textBuilder.toString();
    }

    /**
     * Base64 解码 Cookie 字符串。
     */
    private String decodeCookie(String encodedCookie) {
        if (encodedCookie == null || encodedCookie.isBlank()) {
            return "";
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedCookie);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.error("[{}] Cookie Base64 解码失败，请检查配置值是否合法 Base64", SOURCE_NAME, e);
            return encodedCookie;
        }
    }
}
