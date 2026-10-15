package com.demo.mcp.antvchart;

import com.alibaba.fastjson2.JSON;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * antv-chart 图表 MCP 客户端 —— 基于官方 MCP Java SDK（Streamable HTTP 传输）。
 * <p>
 * 复用 {@code XhsSpringAiMcpSource} 的 SDK 连接骨架，连接图表服务：
 * <ul>
 *   <li><b>单例 Session</b>：全局共享一个 {@link McpSyncClient}，多个 Agent 共用</li>
 *   <li><b>心跳 + 自动重连</b>：定时探测连接，断开后自动重建</li>
 *   <li><b>强制 base64</b>：所有图表工具调用附加 {@code outputFormat=base64}，禁止在线图片 URL 模式，避免业务数据外发</li>
 *   <li><b>图片回传</b>：结果中的 base64 图片解码写入 {@link ChartImageOutputHolder}，由发送层发图</li>
 *   <li><b>友好异常</b>：断开/超时/失败返回提示，不抛堆栈中断对话</li>
 * </ul>
 */
@Slf4j
@Component
public class AntvChartMcpSource implements McpSource {

    private static final String SOURCE_NAME = "antvchart";

    /** 强制输出格式：base64，禁止默认在线图片 URL 模式 */
    private static final String OUTPUT_FORMAT = "base64";

    /** 重连最小间隔（毫秒），避免连接失败时高频重试 */
    private static final long RECONNECT_MIN_INTERVAL_MS = 30_000;

    @Resource
    private AntvChartMcpConfigProperties config;

    @Resource
    private ChartImageOutputHolder chartImageHolder;

    /** 单例 MCP Session 连接 */
    private volatile McpSyncClient mcpClient;

    private final AtomicBoolean ready = new AtomicBoolean(false);
    private volatile List<McpToolDefinition> toolCache = Collections.emptyList();
    private volatile long lastConnectAttempt = 0;

    /** 懒加载门控：true=未启用（心跳禁止自动连接），由 init()/close() 切换 */
    private volatile boolean disposed = true;

    // ======================== McpSource 接口 ========================

    @Override
    public String getSourceName() {
        return SOURCE_NAME;
    }

    @Override
    public boolean isReady() {
        return ready.get();
    }

    @Override
    public void init() {
        log.info("📊 [{}] 开始初始化 antv-chart MCP 连接 (Streamable HTTP)...", SOURCE_NAME);
        if (!config.isConfigured()) {
            log.warn("⚠️ [{}] antv-chart MCP 未启用或 URL 为空，跳过初始化", SOURCE_NAME);
            return;
        }
        disposed = false;   // 懒加载激活后开放心跳自愈
        connect();
    }

    /**
     * 卸载：关闭连接、清空工具缓存、关闭心跳自愈（懒加载 deactivate 调用）。
     * 之后可再次 init() 重新建立连接。
     */
    @Override
    public void close() {
        disposed = true;
        ready.set(false);
        toolCache = Collections.emptyList();
        closeClient();
        log.info("📊 [{}] 已卸载，连接已关闭", SOURCE_NAME);
    }

    @Override
    public List<McpToolDefinition> listTools() {
        return toolCache;
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) {
        McpSyncClient client = mcpClient;
        if (client == null || !ready.get()) {
            return "❌ 图表服务未就绪，请稍后再试。";
        }
        Objects.requireNonNull(arguments, "arguments 参数不能为 null");

        try {
            // ★ 强制 base64：禁止在线图片 URL 模式，避免业务数据外发
            arguments.put("outputFormat", OUTPUT_FORMAT);

            long start = System.currentTimeMillis();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
            long elapsed = System.currentTimeMillis() - start;

            ParseResult parsed = parseResult(result);
            byte[] image = parsed.image();
            String imgSource = "base64";
            if (image == null) {
                // antv-chart v0.8.x 忽略 outputFormat，返回在线图片 URL → 下载成图片发送
                String url = extractUrl(parsed.text());
                if (url != null) {
                    byte[] downloaded = downloadImage(url);
                    if (isImageBytes(downloaded)) {
                        image = downloaded;
                        imgSource = "url下载";
                    }
                }
            }

            if (image != null && image.length > 0) {
                chartImageHolder.set(image);
                log.info("📊 [{}] 图表工具 {} 成功 | 来源={} | 入参摘要={} | 图片 {}KB | {}ms",
                        SOURCE_NAME, toolName, imgSource, summarize(arguments), image.length / 1024, elapsed);
                return "✅ 图表已生成，正在发送图片...";
            }
            log.info("📊 [{}] 图表工具 {} 完成 | 入参摘要={} | {}ms", SOURCE_NAME, toolName, summarize(arguments), elapsed);
            return (parsed.text() != null && !parsed.text().isBlank()) ? parsed.text() : "✅ 图表已生成。";
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("❌ [{}] 图表工具 {} 调用异常: {}", SOURCE_NAME, toolName, msg);
            // 连接/超时类异常 → 标记未就绪，触发心跳重连
            String lower = msg.toLowerCase();
            if (lower.contains("connect") || lower.contains("http") || lower.contains("timeout")) {
                ready.set(false);
            }
            return "❌ 图表生成失败：" + friendlyMessage(e);
        }
    }

    // ======================== 连接 / 心跳 / 重连 ========================

    /**
     * 建立（或重建）单例 MCP 连接：Streamable HTTP 握手 + 工具发现。
     * 加 synchronized 保证并发下只建一个 Session。
     */
    private synchronized void connect() {
        long now = System.currentTimeMillis();
        if (now - lastConnectAttempt < RECONNECT_MIN_INTERVAL_MS) {
            log.debug("[{}] 距上次连接尝试过短，跳过", SOURCE_NAME);
            return;
        }
        lastConnectAttempt = now;

        try {
            closeClient();
            // 1. Streamable HTTP 传输
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
            var transport = HttpClientStreamableHttpTransport.builder(config.getUrl())
                    .requestBuilder(requestBuilder)
                    .build();
            // 2. 同步 MCP 客户端（单例 Session）
            mcpClient = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .build();
            // 3. 握手
            McpSchema.InitializeResult init = mcpClient.initialize();
            // 4. 工具发现
            discoverTools();
            ready.set(true);
            log.info("✅ [{}] antv-chart MCP 连接成功 | Server: {} | {} 个工具",
                    SOURCE_NAME,
                    init.serverInfo() != null ? init.serverInfo().name() : "unknown",
                    toolCache.size());
        } catch (Exception e) {
            ready.set(false);
            log.warn("⚠️ [{}] antv-chart MCP 连接失败: {}", SOURCE_NAME, e.getMessage());
        }
    }

    /**
     * 空闲心跳：周期探测连接，断开自动重连（退避：最短间隔 30s）。
     */
    @Scheduled(fixedDelayString = "${mcp.antv-chart.heartbeat-seconds:30}000")
    public void heartbeat() {
        if (disposed) return;   // 懒加载未启用：不自动连接
        if (!config.isConfigured()) return;
        McpSyncClient client = mcpClient;
        if (client == null) {
            connect();
            return;
        }
        try {
            client.listTools(); // 轻量探测
            if (!ready.get()) ready.set(true);
            log.debug("[{}] 心跳正常", SOURCE_NAME);
        } catch (Exception e) {
            log.warn("⚠️ [{}] 心跳异常（{}），触发重连", SOURCE_NAME, e.getMessage());
            ready.set(false);
            connect();
        }
    }

    /** 清理连接 */
    @PreDestroy
    public void destroy() {
        closeClient();
    }

    private void closeClient() {
        McpSyncClient c = mcpClient;
        mcpClient = null;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ======================== 工具发现 ========================

    private void discoverTools() {
        McpSchema.ListToolsResult toolsResult = mcpClient.listTools();
        List<McpSchema.Tool> tools = toolsResult.tools();
        if (tools == null || tools.isEmpty()) {
            log.warn("[{}] 图表服务返回空工具列表", SOURCE_NAME);
            toolCache = Collections.emptyList();
            return;
        }
        List<McpToolDefinition> definitions = new ArrayList<>(tools.size());
        for (McpSchema.Tool tool : tools) {
            McpToolDefinition def = new McpToolDefinition(
                    tool.name(), tool.description(), SOURCE_NAME, convertSchema(tool.inputSchema()));
            definitions.add(def);
            log.info("   📋 [{}] 加载图表工具: {}", SOURCE_NAME, def.qualifiedName());
        }
        toolCache = definitions;
        log.info("✅ [{}] 图表工具列表加载完成，共 {} 个", SOURCE_NAME, definitions.size());
    }

    /** Jackson JsonSchema → fastjson2 JSONObject */
    private JSONObject convertSchema(McpSchema.JsonSchema jsonSchema) {
        if (jsonSchema == null) return new JSONObject();
        try {
            String jsonStr = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(jsonSchema);
            return JSONObject.parseObject(jsonStr);
        } catch (Exception e) {
            log.warn("[{}] inputSchema 转换失败: {}", SOURCE_NAME, e.getMessage());
            return new JSONObject();
        }
    }

    // ======================== 结果解析 ========================

    /** 解析结果：文本（多段拼接）+ 图片（base64，兼容多种返回形态） */
    private ParseResult parseResult(McpSchema.CallToolResult result) {
        List<McpSchema.Content> content = result.content();
        if (content == null || content.isEmpty()) return new ParseResult("", null);
        StringBuilder text = new StringBuilder();
        byte[] image = null;
        for (McpSchema.Content item : content) {
            if (item instanceof McpSchema.ImageContent imageContent) {
                if (image == null) image = decodeBase64Image(imageContent.data());
            } else if (item instanceof McpSchema.TextContent textContent) {
                String t = textContent.text();
                if (t == null || t.isBlank()) continue;
                // 文本可能是 base64 图片（data URL / 纯 base64 / JSON 包裹）
                byte[] img = imageFromText(t);
                if (img != null) {
                    if (image == null) image = img;
                } else {
                    if (text.length() > 0) text.append("\n");
                    text.append(t);
                }
            }
        }
        return new ParseResult(text.toString(), image);
    }

    /** 从文本中识别并解码 base64 图片：data URL → 纯 base64 → JSON 包裹 */
    private byte[] imageFromText(String text) {
        // 1) data URL：data:image/xxx;base64,xxxx
        int b64Idx = text.indexOf("base64,");
        if (b64Idx >= 0) {
            byte[] img = decodeBase64Image(text.substring(b64Idx + "base64,".length()));
            if (img != null) return img;
        }
        // 2) 纯 base64（足够长且无空格才尝试，避免把普通文本当图）
        String trimmed = text.trim();
        if (trimmed.length() > 200 && !trimmed.contains(" ")) {
            byte[] img = decodeBase64Image(trimmed);
            if (img != null) return img;
        }
        // 3) JSON 包裹：{"data":"data:image/...;base64,xxx"} 等
        try {
            JSONObject obj = JSON.parseObject(text);
            if (obj != null) {
                for (String key : new String[]{"data", "image", "img", "base64", "content"}) {
                    String v = obj.getString(key);
                    if (v != null && !v.isBlank()) {
                        byte[] img = imageFromText(v);
                        if (img != null) return img;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 解码 base64 图片（兼容 data:xxx;base64, 前缀），带魔数校验防误判；失败返回 null */
    private byte[] decodeBase64Image(String data) {
        if (data == null || data.isBlank()) return null;
        String b64 = data.contains(",") ? data.substring(data.indexOf(',') + 1) : data.trim();
        b64 = b64.replaceAll("\\s+", "");
        if (b64.length() < 50) return null; // 太短不像图片
        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            if (bytes.length < 16) return null;
            // 魔数校验：PNG / JPEG / GIF / BMP
            if (bytes.length > 8) {
                if ((bytes[0] & 0xFF) == 0x89 && (bytes[1] & 0xFF) == 'P') return bytes; // PNG
                if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return bytes; // JPEG
                if ((bytes[0] & 0xFF) == 'G' && (bytes[1] & 0xFF) == 'I') return bytes;   // GIF
                if ((bytes[0] & 0xFF) == 'B' && (bytes[1] & 0xFF) == 'M') return bytes;   // BMP
            }
            // 非栅格图（SVG 等）但足够长，也接受
            if (bytes.length > 500) return bytes;
        } catch (IllegalArgumentException e) {
            log.warn("[{}] 图片 base64 解码失败: {}", SOURCE_NAME, e.getMessage());
        }
        return null;
    }

    /** MCP 调用结果解析产物：文本 + 图片字节 */
    private record ParseResult(String text, byte[] image) {}

    /** 入参摘要：不打印大字段值，只打印 key 与值的长度 */
    private String summarize(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        args.forEach((k, v) -> {
            if (sb.length() > 1) sb.append(",");
            String s = v != null ? v.toString() : "null";
            sb.append(k).append("=").append(s.length() > 20 ? s.substring(0, 20) + "…(" + s.length() + ")" : s);
        });
        sb.append("}");
        return sb.toString();
    }

    /** 把底层异常转成友好提示，不暴露堆栈 */
    private String friendlyMessage(Exception e) {
        String m = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String lower = m.toLowerCase();
        if (lower.contains("timeout")) return "图表服务响应超时，请稍后再试。";
        if (lower.contains("connect") || lower.contains("http") || lower.contains("refused")) {
            return "图表服务连接断开，请确认图表服务已启动后稍后再试。";
        }
        return m.length() > 80 ? m.substring(0, 80) + "…" : m;
    }

    // ======================== 在线 URL 图片兜底（服务器忽略 outputFormat 时） ========================

    /** 从文本中提取第一个 http(s) URL */
    private String extractUrl(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://[^\\s\"'<>]+").matcher(text);
        return m.find() ? m.group(0) : null;
    }

    /** 下载 URL 图片字节（限时，失败返回 null） */
    private byte[] downloadImage(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setInstanceFollowRedirects(true);
            try {
                int code = conn.getResponseCode();
                if (code != 200) {
                    log.warn("[{}] 下载图表图片失败 HTTP {}", SOURCE_NAME, code);
                    return null;
                }
                try (InputStream in = conn.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    return out.toByteArray();
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            log.warn("[{}] 下载图表图片失败: {}", SOURCE_NAME, e.getMessage());
            return null;
        }
    }

    /** 校验字节是否为可识别的图片（PNG/JPEG/GIF/BMP 魔数，或足够长的非空内容） */
    private boolean isImageBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 16) return false;
        int b0 = bytes[0] & 0xFF, b1 = bytes[1] & 0xFF;
        if (b0 == 0x89 && b1 == 'P') return true;   // PNG
        if (b0 == 0xFF && b1 == 0xD8) return true;  // JPEG
        if (b0 == 'G' && b1 == 'I') return true;    // GIF
        if (b0 == 'B' && b1 == 'M') return true;    // BMP
        return bytes.length > 500;                  // SVG 等无魔数但足够长
    }
}
