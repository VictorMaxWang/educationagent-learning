package com.demo.study.english.speaking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 讯飞 ISE 口语评测配置。
 * <p>
 * 映射 application.properties 中 speech.xfyun.* 配置项。
 * 凭证在讯飞开放平台控制台获取（语音评测 → 应用）。
 * <p>
 * 注：不依赖 Lombok @Data，显式提供 getter/setter，
 * 避免 IDE 未启用注解处理器时 setter 无法解析。
 */
@Component
@ConfigurationProperties(prefix = "speech.xfyun")
public class XfyunIseConfig {

    /** 讯飞 APPID（占位 xxx，用户自行填入） */
    private String appId = "xxx";
    /** 讯飞 APIKey */
    private String apiKey = "xxx";
    /** 讯飞 APISecret */
    private String apiSecret = "xxx";

    /** 讯飞 ISE 接口地址 */
    private String url = "https://ise-api.xfyun.cn/v2/open-ise";

    /** 是否已配置真实凭证 */
    public boolean isConfigured() {
        return appId != null && apiKey != null && apiSecret != null
                && !"xxx".equals(appId) && !"xxx".equals(apiKey) && !"xxx".equals(apiSecret);
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
