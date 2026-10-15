package com.demo.bot;

import com.demo.listener.WechatMsgListener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 统一创建、启动、停止、查询所有机器人实例，程序启动自动批量创建机器人，关闭自动销毁全部的机器人
 * Bot 实例管理器 — Spring Component，统一管理所有 BotInstance 的生命周期
 */
@Slf4j
@Component
public class BotManager {

    private final Map<String, BotInstance> instances = new ConcurrentHashMap<>();
    private final WechatMsgListener messageHandler;
    private final AtomicInteger botCounter = new AtomicInteger(0);

    @Value("${bot.auto-start-count:0}")
    private int autoStartCount;//读取配置文件，程序启动自动创建多少个机器人，默认0

    @Value("${bot.start-delay-ms:3000}")//启动间隔3秒
    private long startDelayMs;

    public BotManager(WechatMsgListener messageHandler) {
        this.messageHandler = messageHandler;
    }

    /** 启动时按配置自动启动 Bot */
    @PostConstruct//项目启动自动执行
    public void autoStart() {
        if (autoStartCount > 0) {//配置文件里写
            log.info("🚀 自动启动 {} 个 Bot 实例...", autoStartCount);
            startBots(autoStartCount);
        } else {//在日志里设置
            log.info("💤 bot.auto-start-count=0，不自动启动 Bot（使用控制台 bot start N 命令）");
        }
    }

    /** 关闭时停止所有 Bot */
    @PreDestroy//项目关闭前自动执行
    public void shutdown() {
        log.info("🛑 关闭所有 Bot 实例...");
        for (BotInstance bot : instances.values()) {//逐个机器人停止
            bot.stop();
        }
        instances.clear();//清除存储，释放资源
    }

    /** 启动 N 个 Bot（名字自动生成：bot1, bot2, ...） */
    public List<BotInstance> startBots(int count) {
        List<BotInstance> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int seq = botCounter.incrementAndGet();
            String name = "bot" + seq;
            BotInstance bot = startBot(name);
            if (bot != null) list.add(bot);

            if (i < count - 1) {
                try { Thread.sleep(startDelayMs); } catch (InterruptedException ignored) {}
            }
        }
        log.info("✅ 已创建 {} 个 Bot 实例", list.size());
        return list;
    }

    /** 启动单个 Bot */
    public BotInstance startBot(String name) {
        if (instances.containsKey(name)) {
            System.out.println("Bot [" + name + "] 已存在");
            return instances.get(name);
        }
        BotInstance bot = new BotInstance(name, messageHandler);
        instances.put(name, bot);
        bot.start();
        return bot;
    }

    /** 停止单个 Bot */
    public boolean stopBot(String name) {
        BotInstance bot = instances.remove(name);
        if (bot != null) {
            bot.stop();
            return true;
        }
        return false;
    }

    /** 按名称获取 Bot */
    public BotInstance getBot(String name) {
        return instances.get(name);
    }

    /** 获取第一个在线 Bot（用于定时任务等场景） */
    public BotInstance getDefaultBot() {
        return instances.values().stream()
                .filter(b -> b.getState() == BotInstance.State.ONLINE)
                .findFirst()
                .orElse(null);
    }

    /** 列出所有 Bot 状态 */
    public String listBotsStatus() {
        if (instances.isEmpty()) {
            return "当前没有 Bot 实例运行。使用 bot start N 启动。";
        }
        StringBuilder sb = new StringBuilder("\n当前 Bot 实例：\n");
        sb.append("  ").append(String.format("%-12s | %-10s | %s", "名称", "状态", "Bot ID")).append("\n");
        sb.append("  ").append("-".repeat(50)).append("\n");
        for (BotInstance bot : instances.values()) {
            sb.append(bot.getStatusSummary()).append("\n");
        }
        sb.append("  共 ").append(instances.size()).append(" 个实例\n");
        return sb.toString();
    }

    /** 获取实例总数 */
    public int getInstanceCount() {
        return instances.size();
    }
}
