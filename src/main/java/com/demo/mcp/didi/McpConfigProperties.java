package com.demo.mcp.didi;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 滴滴出行 MCP 配置绑定类。
 * <p>
 * 映射 application.properties 中 mcp.didi.* 全部配置项。
 * 支持沙箱/生产双环境切换，未配置 api-key 时自动降级（不阻塞启动）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.didi")
public class McpConfigProperties {

    /** 是否启用滴滴 MCP（默认 false，需显式开启） */
    private boolean enabled = false;

    /** 滴滴 MCP Server 地址 */
    private String baseUrl = "https://mcp.didichuxing.com/mcp-servers";

    /** 滴滴开放平台 API Key（URL 参数鉴权） */
    private String apiKey;

    /** 沙箱模式：true=沙箱环境(不产生真实订单)，false=正式环境 */
    private boolean sandbox = true;

    /** HTTP 请求超时（秒），同时应用于连接超时和读取超时 */
    private int timeoutSeconds = 30;

    /**
     * 是否具备真实调用条件。
     * 未配置 apiKey 时 McpToolBridge 跳过该源，不阻塞启动。
     */
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
