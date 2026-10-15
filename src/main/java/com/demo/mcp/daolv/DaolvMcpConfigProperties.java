package com.demo.mcp.daolv;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 道旅 MCP 配置绑定类。
 * <p>
 * 映射 application.properties 中 mcp.daolv.* 全部配置项。
 * 鉴权方式：Authorization: Bearer &lt;API Key&gt;（mcp.daolv.token）。
 * 未配置 token 时自动降级（不阻塞启动）。
 * <p>
 * 用户侧引导链接（AI 对话页 / 酒店搜索页，含微信小程序短链 + 二维码）：
 * 聊天中涉及酒店搜索/预订时，可把链接附给用户跳转到道旅网页/小程序继续操作。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.daolv")
public class DaolvMcpConfigProperties {

    /** 是否启用道旅 MCP（默认 false，需显式开启） */
    private boolean enabled = false;

    /** 道旅酒店 MCP Server 地址 */
    private String url = "https://mcp.rollinggo.cn/mcp";

    /** 道旅开放平台 API Key（Authorization: Bearer 传） */
    private String token;

    /** HTTP 请求超时（秒） */
    private int timeoutSeconds = 30;

    // ---- 用户侧引导链接 ----
    /** AI 对话页 · Web 链接 */
    private String aiPageUrl = "https://rollinggo.cn/pages/ai-home/index?utm_source=0A0GZA";
    /** AI 对话页 · 微信小程序短链 */
    private String aiPageMini = "https://wxmpurl.cn/WvJIZGD4QKe";
    /** 酒店搜索页 · Web 链接 */
    private String hotelPageUrl = "https://rollinggo.cn/pages/reservationLink/index?utm_source=0A0GZA";
    /** 酒店搜索页 · 微信小程序短链 */
    private String hotelPageMini = "https://wxmpurl.cn/zZFfao9IMia";

    /** 是否具备真实调用条件：启用且 token 非空 */
    public boolean isConfigured() {
        return enabled && token != null && !token.isBlank();
    }
}
