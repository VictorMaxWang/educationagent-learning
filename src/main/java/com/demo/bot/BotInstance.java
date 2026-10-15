package com.demo.bot;

import com.demo.listener.WechatMsgListener;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 单个 Bot 实例（instance） — 封装完整生命周期，每个机器人独立扫码、独立收消息、独立网络连接
 * <p>
 * 每个 BotInstance 拥有独立的 ILinkClient、独立线程、独立二维码。
 * 不是 Spring Bean，由 BotManager 手动 new 创建。
 */
@Slf4j
public class BotInstance {

    public enum State { CREATED, LOGGING_IN, ONLINE, OFFLINE, ERROR }

    private final String name;
    private ILinkClient client;
    private volatile State state = State.CREATED;
    private Thread pollThread;
    private String botId;
    private String qrCode;

    /**
     * @param name           Bot 名称（如 "bot1"），用于日志区分
     * @param messageHandler 消息处理器（Spring 单例，所有 Bot 共享）
     */
    public BotInstance(String name, WechatMsgListener messageHandler) {
        this.name = (name != null && !name.isBlank()) ? name : "bot";

        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(35000)
                .readTimeoutMs(35000)
                .httpMaxRetries(3)
                .heartbeatEnabled(true)
                .autoReconnectEnabled(true)
                .reconnectMaxAttempts(Integer.MAX_VALUE)
                .build();

        this.client = new ILinkClientBuilder()
                .config(config)
                //登录回调
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        botId = context.getBotId();
                        state = State.ONLINE;
                        System.out.println("[" + name + "] 微信登录成功，botId = " + botId);
                        log.info("✅ [{}] 登录成功 botId={}", name, botId);
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        state = State.ERROR;
                        System.err.println("[" + name + "] 微信登录失败：" + throwable.getMessage());
                        log.error("❌ [{}] 登录失败", name, throwable);
                    }
                })
                //消息回调
                .onMessage(messages -> {
                    BotContext.set(BotInstance.this.client, name);
                    try {
                        messageHandler.onMessages(messages);
                    } finally {
                        BotContext.clear();
                    }
                })
                .build();

        log.info("🤖 [{}] BotInstance 已创建", name);
    }

    /** 启动机器人：扫码登录 + 开启消息轮询 */
    public void start() {
        if (state != State.CREATED) {
            System.out.println("[" + name + "] 已经启动过了");
            return;
        }
        state = State.LOGGING_IN;

        System.out.println("\n========== Bot [" + name + "] 二维码 ==========");
        try {
            client.executeLogin();
            qrCode = client.getQrcode();
            if (qrCode != null && !qrCode.isEmpty()) {
                String url = "https://liteapp.weixin.qq.com/q/7GiQu1?qrcode=" + qrCode + "&bot_type=3";
                System.out.println("  请用微信扫描下方二维码登录");
                System.out.println("  " + url);
                System.out.println("============================================\n");
            } else {
                System.out.println("  获取二维码失败，请检查网络");
            }
        } catch (Exception e) {
            state = State.ERROR;
            System.err.println("[" + name + "] 启动失败：" + e.getMessage());
            return;
        }

        // 后台轮询线程：每秒拉取消息
        pollThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    client.getUpdates();
                } catch (Exception ignored) {
                    // 未登录时抛异常是正常的
                }
                try {
                    Thread.sleep(1000);//休眠1秒，每秒拉取微信新消息
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "poll-" + name);
        pollThread.setDaemon(true);
        pollThread.start();
    }

    /** 停止：中断轮询 + 关闭连接 */
    public void stop() {
        if (state == State.OFFLINE) return;
        state = State.OFFLINE;

        if (pollThread != null) {
            pollThread.interrupt();//等3秒线程结束
            try { pollThread.join(3000); } catch (InterruptedException ignored) {}
        }
        try {
            client.close();
        } catch (Exception e) {
            log.warn("[{}] 关闭 ILinkClient 异常: {}", name, e.getMessage());
        }
        log.info("🛑 [{}] 已停止", name);
        System.out.println("[" + name + "] 已停止");
    }

    // ======================== Getters ========================

    public String getName() { return name; }
    public State getState() { return state; }
    public String getBotId() { return botId; }
    public ILinkClient getClient() { return client; }
    public String getQrCode() { return qrCode; }

    public String getStatusSummary() {
        return String.format("  %-12s | %-10s | %s",
                name, state.name(),
                botId != null ? "botId=" + botId : "--");
    }
}
