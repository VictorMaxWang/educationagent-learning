package com.demo.mcp.antvchart;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * antv-chart 图表 MCP 配置绑定类。
 * <p>
 * 映射 application.properties 中 mcp.antv-chart.* 配置项。
 * 传输协议固定为 Streamable HTTP（本项目仅用网络连接，不启 stdio 进程）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.antv-chart")
public class AntvChartMcpConfigProperties {

    /** 是否启用 */
    private boolean enabled = false;

    /** Streamable HTTP 端点地址（如 http://127.0.0.1:1122/mcp） */
    private String url;

    /** 请求超时（秒），图表渲染可能较慢，默认 60s */
    private int timeoutSeconds = 60;

    /** 空闲心跳间隔（秒），默认 30s */
    private int heartbeatSeconds = 30;

    /** 是否满足启动条件 */
    public boolean isConfigured() {
        return enabled && url != null && !url.isBlank();
    }
}
