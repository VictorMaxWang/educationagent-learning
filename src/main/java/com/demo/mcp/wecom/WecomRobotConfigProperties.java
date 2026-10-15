package com.demo.mcp.wecom;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * EchoWork 机器人（企微工作台聊天通道）配置绑定类。
 * <p>
 * 映射 application.properties 中 wecom.robot.* 全部配置项。
 * 负责员工 ↔ Agent 的聊天消息双向传递。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wecom.robot")
public class WecomRobotConfigProperties {

    private boolean enabled = false;

    /** EchoWork 机器人 BotId */
    private String botId;

    /** EchoWork 机器人 BotSecret（用于获取 access_token） */
    private String botSecret;

    /** WebSocket 连接地址（wss） */
    private String wsUrl = "wss://openws.work.weixin.qq.com";

    /** 心跳间隔（秒） */
    private int heartbeatSeconds = 30;

    private int timeoutSeconds = 30;

    public boolean isConfigured() {
        return enabled && botId != null && !botId.isBlank()
                && botSecret != null && !botSecret.isBlank();
    }
}
