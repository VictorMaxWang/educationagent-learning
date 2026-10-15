package com.demo.mcp.xhs;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 小红书 MCP 配置。
 * <p>
 * 映射 application.properties 中 mcp.xhs.* 全部配置项。
 * 鉴权方式：Cookie 做 Base64 编码存入配置，MCP客户端自动解码。
 * 未配置时自动跳过，不阻塞应用启动。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.xhs")
public class XhsMcpConfigProperties {

    /** 是否启用小红书 MCP 源 */
    private boolean enabled = false;

    /** MCP服务端地址（v2.2.6 外部独立 MCP Server，协议：Streamable HTTP） */
    private String url = "http://127.0.0.1:18060";

    /**
     * 小红书创作者中心完整Cookie，需Base64编码后填入配置
     * 复制浏览器creator.xiaohongshu.com Cookie字符串，使用Base64编码存储
     */
    private String cookie;

    /**
     * MCP客户端HTTP全局超时（秒）
     * 建议大于服务端自动化最大等待时间125s，此处设180s
     */
    private int timeoutSeconds = 180;

    /**
     * 浏览器自动化最大等待发布结果时长（秒）
     * 同步XhsService PUBLISH_MAX_WAIT，统一维护
     */
    private int publishWaitSeconds = 120;

    /** 是否已完整启用配置：开关开启 + Cookie不为空 */
    public boolean isConfigured() {
        return enabled && cookie != null && !cookie.isBlank();
    }
}