package com.demo.bot;

import com.github.wechat.ilink.sdk.ILinkClient;

/** 解决多机器人的消息区分问题
 * Bot 上下文 — ThreadLocal 持有当前线程的 ILinkClient + Bot 名称
 * <p>
 * 每个 Bot 的消息处理线程在入口处 set，整个调用链（预处理 → Agent → 回复）
 * 通过 getClient() 拿到正确的 client 发消息。
 * <p>
 * 用法：
 * <pre>
 *   BotContext.set(client, "bot1");
 *   try { ... process ... }
 *   finally { BotContext.clear(); }
 * </pre>
 */
public class BotContext {

    //存当前消息线程对应的机器人微信连接client
    private static final ThreadLocal<ILinkClient> CURRENT_CLIENT = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_BOT_NAME = new ThreadLocal<>();

    public static void set(ILinkClient client, String botName) {
        CURRENT_CLIENT.set(client);
        CURRENT_BOT_NAME.set(botName);
    }

    /** 获取当前线程绑定的 ILinkClient，null 表示控制台模式 */
    public static ILinkClient getClient() {
        return CURRENT_CLIENT.get();
    }

    /** 获取当前线程绑定的 Bot 名称 */
    public static String getBotName() {
        return CURRENT_BOT_NAME.get();
    }

    /** 清除当前线程上下文 */
    public static void clear() {
        CURRENT_CLIENT.remove();
        CURRENT_BOT_NAME.remove();
    }
}
