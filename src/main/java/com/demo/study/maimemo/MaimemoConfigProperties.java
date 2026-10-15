package com.demo.study.maimemo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 英语学习（墨墨背单词）配置。
 * <p>
 * 映射 application.properties 中 maimemo.* 配置项：
 * - 墨墨同步 API（未配置则仅本地词库）
 * - autoSync 自动同步开关
 */
@Data
@Component
@ConfigurationProperties(prefix = "maimemo")
public class MaimemoConfigProperties {

    /** 是否启用英语学习模块（未启用时接口返回提示） */
    private boolean enabled = false;

    /** 墨墨云词本 API 地址（仅支持「加入云词本」；未配置则仅本地词库） */
    private String maimemoApiUrl;
    /** 墨墨 API Key */
    private String maimemoApiKey;

    /** 听力音频保存目录 */
    private String audioDir = "data/maimemo_audio";
}
