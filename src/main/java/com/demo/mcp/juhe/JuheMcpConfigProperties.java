package com.demo.mcp.juhe;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 聚合数据 MCP 配置绑定类。
 * <p>
 * 映射 application.properties 中 juhe.mcp.* 全部配置项。
 * 与滴滴 MCP 的 {@code mcp.didi.*} 配置模式对齐，采用统一的 @ConfigurationProperties 风格。
 * 未配置 token 时自动降级（不阻塞启动）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "juhe.mcp")
public class JuheMcpConfigProperties {

    /** 是否启用聚合数据 MCP（默认 false，需显式开启） */
    private boolean enabled = false;

    /** 聚合数据 MCP Server 地址 */
    private String url = "https://mcp.juhe.cn/mcp";

    /** 聚合数据 MCP Token（URL 参数鉴权） */
    private String token;

    /** HTTP 请求超时（秒） */
    private int timeoutSeconds = 30;

    /**
     * 是否具备真实调用条件。
     * 未配置 token 时 McpClientManager 跳过该源，不阻塞启动。
     */
    public boolean isConfigured() {
        return enabled && token != null && !token.isBlank();
    }
}
