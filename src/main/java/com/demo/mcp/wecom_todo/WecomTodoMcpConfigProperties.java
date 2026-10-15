package com.demo.mcp.wecom_todo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 企业微信机器人待办 MCP 配置绑定类。
 * <p>
 * 映射 application.properties 中 mcp.wecom_todo.* 全部配置项。
 * url 为完整 Streamable HTTP 地址（含 apikey）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.wecom-todo")
public class WecomTodoMcpConfigProperties {

    private boolean enabled = false;

    /** 完整 MCP Server URL（含 apikey） */
    private String url = "https://qyapi.weixin.qq.com/mcp/robot-todo";

    private int timeoutSeconds = 30;

    public boolean isConfigured() {
        return enabled && url != null && !url.isBlank();
    }
}
