package com.demo.mcp.liepin;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 猎聘 MCP 配置绑定类。
 * <p>
 * 映射 application.properties 中 mcp.liepin.* 全部配置项。
 * 鉴权方式：x-user-token 自定义 Header（JWT 格式凭证）。
 * 未配置 credential 时自动降级（不阻塞启动）。
 * <p>
 * 凭证获取：登录 liepin.com → 打开 liepin.com/mcp/server → 点击「生成凭证」。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.liepin")
public class LiepinMcpConfigProperties {

    /** 是否启用猎聘 MCP（默认 false，需显式开启） */
    private boolean enabled = false;

    /** 猎聘 MCP Server 地址 */
    private String url = "https://open-agent.liepin.com/mcp/user";

    /** 猎聘 MCP 凭证（JWT 格式，x-user-token Header 鉴权） */
    private String credential;

    /** HTTP 请求超时（秒），同时应用于连接超时和读取超时 */
    private int timeoutSeconds = 30;

    /**
     * 是否具备真实调用条件。
     * 未配置 credential 时跳过该源，不阻塞启动。
     */
    public boolean isConfigured() {
        return enabled && credential != null && !credential.isBlank();
    }
}
