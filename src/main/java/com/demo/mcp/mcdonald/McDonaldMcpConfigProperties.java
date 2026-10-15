package com.demo.mcp.mcdonald;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 麦当劳 MCP 配置绑定类。
 * <p>
 * 映射 application.properties 中 mcp.mcdonald.* 全部配置项。
 * 鉴权方式：Authorization: Bearer &lt;token&gt;（Bearer Token 请求头）。
 * 未配置 token 时自动降级（不阻塞启动）。
 * <p>
 * Token 获取：登录 https://open.mcd.cn/mcp → 控制台 → 申请激活。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.mcdonald")
public class McDonaldMcpConfigProperties {

    /** 是否启用麦当劳 MCP（默认 false，需显式开启） */
    private boolean enabled = false;

    /** 麦当劳 MCP Server 地址 */
    private String url = "https://mcp.mcd.cn";

    /** 麦当劳开放平台 Bearer Token（Authorization 请求头鉴权） */
    private String token;

    /** HTTP 请求超时（秒），同时应用于连接超时和读取超时 */
    private int timeoutSeconds = 30;

    /**
     * 是否具备真实调用条件。
     * 未配置 token 时 McpClientManager 跳过该源，不阻塞启动。
     */
    public boolean isConfigured() {
        return enabled && token != null && !token.isBlank();
    }
}
