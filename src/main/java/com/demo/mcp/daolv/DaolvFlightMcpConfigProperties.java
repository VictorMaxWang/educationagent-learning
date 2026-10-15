package com.demo.mcp.daolv;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 道旅机票 MCP 配置绑定类。
 * <p>
 * 映射 application.properties 中 mcp.daolv-flight.* 全部配置项。
 * 鉴权方式：Authorization: Bearer &lt;API Key&gt;（与酒店 MCP 同一个 key）。
 * 未配置 token 时自动降级（不阻塞启动）。
 * <p>
 * 官方文档：机票 MCP = https://mcp.rollinggo.cn/mcp/flight，工具 searchAirports / searchFlights。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.daolv-flight")
public class DaolvFlightMcpConfigProperties {

    /** 是否启用道旅机票 MCP（默认 false） */
    private boolean enabled = false;

    /** 道旅机票 MCP Server 地址 */
    private String url = "https://mcp.rollinggo.cn/mcp/flight";

    /** 道旅开放平台 API Key（Authorization: Bearer 传，与酒店同一个） */
    private String token;

    /** HTTP 请求超时（秒） */
    private int timeoutSeconds = 30;

    /** 是否具备真实调用条件：启用且 token 非空 */
    public boolean isConfigured() {
        return enabled && token != null && !token.isBlank();
    }
}
