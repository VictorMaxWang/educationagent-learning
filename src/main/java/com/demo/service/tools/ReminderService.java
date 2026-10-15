package com.demo.service.tools;

import com.demo.bot.BotInstance;
import com.demo.bot.BotManager;
import com.demo.entity.ReminderEntity;
import com.demo.mapper.ReminderMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 定时提醒从BotManager拿默认的Bot
 * 提醒服务（MySQL 持久化 + 内存调度，MyBatis-Plus）
 * 重启后从 MySQL 恢复未触发的提醒
 */
@Slf4j
@Service
public class ReminderService {

    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    private final ConcurrentHashMap<String, ScheduledFuture<?>> activeReminders = new ConcurrentHashMap<>();

    @Resource
    private ReminderMapper reminderMapper;

    @Lazy
    @Resource//注入配置，多账号管理入口
    private BotManager botManager;

    @PostConstruct
    public void init() {
        scheduler.setRemoveOnCancelPolicy(true);
        restorePendingReminders();
        log.info("⏰ 提醒服务初始化完成（MySQL/MyBatis-Plus 持久化）");
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
        log.info("⏰ 提醒服务已关闭");
    }

    /**
     * 设置定时提醒
     */
    public String setReminder(String userId, String message, String delayMinutes) {
        if (message == null || message.isBlank()) {
            return "提醒内容不能为空，请重试~";
        }

        int minutes;
        try {
            minutes = Integer.parseInt(delayMinutes);
        } catch (NumberFormatException e) {
            return "提醒时间格式有误，请重试~";
        }

        if (minutes < 1 || minutes > 1440) {
            return "提醒时间应在 1 分钟到 24 小时之间，请重试~";
        }

        long triggerAt = System.currentTimeMillis() + minutes * 60_000L;

        // 1. 先写入 MySQL
        ReminderEntity entity = new ReminderEntity(userId, message, triggerAt);
        reminderMapper.insert(entity);
        final long dbId = entity.getId();
        String reminderId = String.valueOf(dbId);

        // 2. 再调度内存定时器
        long delayMs = triggerAt - System.currentTimeMillis();
        ScheduledFuture<?> future = scheduler.schedule(
                () -> executeReminder(dbId, userId, message),
                Math.max(delayMs, 0), TimeUnit.MILLISECONDS);
        activeReminders.put(reminderId, future);

        log.info("⏰ 提醒已设置 [{}]：{} 分钟后 → \"{}\"（DB id={}）", userId, minutes, message, dbId);

        return "✅ 已设置提醒，「" + message + "」将在 " + minutes + " 分钟后提醒你~";
    }

    /**
     * 执行提醒：发微信 + 标记数据库
     */
    private void executeReminder(Long dbId, String userId, String message) {
        try {
            String text = "⏰ 提醒：「" + message + "」时间到了！";
            BotInstance defaultBot = botManager.getDefaultBot();
            if (defaultBot != null) {
                defaultBot.getClient().sendText(userId, text);
            } else {
                log.warn("⏰ 无可用 Bot，提醒发送失败 [{}]：{}", userId, message);
                return;
            }
            log.info("⏰ 提醒已发送 [{}]：{}", userId, message);

            // 标记数据库已执行
            ReminderEntity entity = reminderMapper.selectById(dbId);
            if (entity != null) {
                entity.setExecuted(1);
                reminderMapper.updateById(entity);
            }
        } catch (Exception e) {
            log.error("⏰ 提醒发送失败 [{}]：{}", userId, e.getMessage());
        } finally {
            activeReminders.remove(String.valueOf(dbId));
        }
    }

    /**
     * 启动时从 MySQL 恢复未触发的提醒
     */
    private void restorePendingReminders() {
        try {
            long now = System.currentTimeMillis();

            // 已过期的提醒：立即触发
            List<ReminderEntity> overdue = reminderMapper.findOverdue(0, now);
            for (ReminderEntity r : overdue) {
                log.info("⏰ 恢复过期提醒 [{}]：\"{}\"（已过期，立即发送）", r.getUserId(), r.getMessage());
                executeReminder(r.getId(), r.getUserId(), r.getMessage());
            }

            // 未到期的提醒：重新调度
            List<ReminderEntity> pending = reminderMapper.findPending(0, now);
            for (ReminderEntity r : pending) {
                long delayMs = r.getTriggerAt() - now;
                ScheduledFuture<?> future = scheduler.schedule(
                        () -> executeReminder(r.getId(), r.getUserId(), r.getMessage()),
                        Math.max(delayMs, 0), TimeUnit.MILLISECONDS);
                activeReminders.put(String.valueOf(r.getId()), future);
                log.info("⏰ 恢复提醒 [{}]：\"{}\"（{} 秒后触发）",
                        r.getUserId(), r.getMessage(), delayMs / 1000);
            }

            log.info("⏰ 从 MySQL 恢复提醒：过期 {} 条 + 待触发 {} 条", overdue.size(), pending.size());
        } catch (Exception e) {
            log.warn("⏰ 恢复提醒失败（可能数据库未连接）: {}", e.getMessage());
        }
    }
}
