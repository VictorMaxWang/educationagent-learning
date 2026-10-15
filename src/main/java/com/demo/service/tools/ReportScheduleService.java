package com.demo.service.tools;

import com.demo.entity.UserSettingsEntity;
import com.demo.mapper.UserSettingsMapper;
import com.demo.service.ScheduledTaskService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 用户日报定时调度服务（多用户独立时间）。
 * <p>
 * 替代原有的固定 @Scheduled 注解，为每个启用日报的用户创建独立的定时任务。
 * 用户可随时通过 LLM @Tool 修改自己的推送时间和城市。
 * <p>
 * 启动时自动从 user_settings 表加载所有 report_enabled=1 的用户并创建定时任务。
 * 每次修改后自动取消旧任务并用新时间重新调度。
 * <p>
 * 时间精度：分钟级（HH:mm），秒固定为0。
 */
@Slf4j
@Service
@DependsOn("databaseMigration")
public class ReportScheduleService {

    @Resource
    private UserSettingsMapper userSettingsMapper;

    @Lazy
    @Resource
    private ScheduledTaskService scheduledTaskService;

    /** 每个用户独立的 ScheduledFuture */
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /** 通用周期任务（jobId → ScheduledFuture），供热门视频等复用同一线程池 */
    private final Map<String, ScheduledFuture<?>> recurringTasks = new ConcurrentHashMap<>();

    /** 线程池调度器 */
    private final ThreadPoolTaskScheduler taskScheduler;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public ReportScheduleService() {
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setPoolSize(5);
        this.taskScheduler.setThreadNamePrefix("report-schedule-");
        this.taskScheduler.setWaitForTasksToCompleteOnShutdown(true);
        this.taskScheduler.setAwaitTerminationSeconds(10);
        this.taskScheduler.initialize();
        log.info("📅 日报调度线程池初始化完成 (poolSize=5)");
    }

    /**
     * 启动时从数据库加载所有已启用日报的用户，创建定时任务
     */
    @PostConstruct
    public void init() {
        log.info("📅 加载用户日报设置...");
        List<UserSettingsEntity> users = userSettingsMapper.findAllWithReportEnabled();
        if (users.isEmpty()) {
            log.info("📅 暂无可调度的日报用户");
            return;
        }
        for (UserSettingsEntity user : users) {
            String userId = user.getUserId();
            String time = user.getReportTime();
            String city = user.getReportCity();
            if (time == null || time.isBlank()) {
                time = "08:00";
            }
            if (city == null || city.isBlank()) {
                city = "杭州"; // 默认城市
            }
            log.info("📅 [{}] 启动日报: time={} city={}", userId, time, city);
            scheduleUserTask(userId, time, city);
        }
        log.info("📅 日报调度初始化完成，共 {} 个用户", users.size());
    }

    /**
     * 关闭时取消所有调度任务
     */
    @PreDestroy
    public void destroy() {
        cancelAll();
        taskScheduler.destroy();
    }

    // ======================== 对外 API ========================

    /**
     * 为用户设置日报时间+城市（自动启用并调度）。
     * 如果已存在旧任务，自动取消后用新时间重新调度。
     *
     * @param userId 用户ID
     * @param time   HH:mm 格式时间，如 "08:00"
     * @param city   天气城市名
     */
    public void setSchedule(String userId, String time, String city) {
        if (time == null || time.isBlank()) {
            time = "08:00";
        }
        if (city == null || city.isBlank()) {
            city = "杭州";
        }

        // 校验时间格式
        if (!isValidTime(time)) {
            log.warn("📅 [{}] 时间格式无效: {}", userId, time);
            throw new IllegalArgumentException("时间格式无效，请使用 HH:mm 格式（如 08:00）");
        }

        // 持久化到数据库
        UserSettingsEntity settings = getOrCreate(userId);
        settings.setReportEnabled(true);
        settings.setReportTime(time);
        settings.setReportCity(city);
        userSettingsMapper.updateById(settings);

        // 重新调度
        cancelUserTask(userId);
        scheduleUserTask(userId, time, city);

        log.info("📅 [{}] 日报已设置: time={} city={}", userId, time, city);
    }

    /**
     * 启用用户的日报推送。
     * 使用已有的 time/city 设置，如果没有则使用默认值。
     */
    public void enable(String userId) {
        UserSettingsEntity settings = getOrCreate(userId);
        settings.setReportEnabled(true);
        if (settings.getReportTime() == null || settings.getReportTime().isBlank()) {
            settings.setReportTime("08:00");
        }
        if (settings.getReportCity() == null || settings.getReportCity().isBlank()) {
            settings.setReportCity("杭州");
        }
        userSettingsMapper.updateById(settings);

        cancelUserTask(userId);
        scheduleUserTask(userId, settings.getReportTime(), settings.getReportCity());
        log.info("📅 [{}] 日报已启用: time={} city={}", userId, settings.getReportTime(), settings.getReportCity());
    }

    /**
     * 禁用用户的日报推送，取消已调度的任务。
     */
    public void disable(String userId) {
        UserSettingsEntity settings = getOrCreate(userId);
        settings.setReportEnabled(false);
        userSettingsMapper.updateById(settings);

        cancelUserTask(userId);
        log.info("📅 [{}] 日报已禁用", userId);
    }

    /**
     * 查询用户的日报设置状态
     */
    public String getStatus(String userId) {
        UserSettingsEntity settings = getOrCreate(userId);
        boolean enabled = settings.getReportEnabled() != null && settings.getReportEnabled();
        String time = settings.getReportTime() != null ? settings.getReportTime() : "08:00";
        String city = settings.getReportCity() != null ? settings.getReportCity() : "杭州";

        if (enabled) {
            return "✅ 日报已开启，每天 " + time + " 推送" + city + "天气和热点新闻。";
        } else {
            return "❌ 日报已关闭。如需开启，请说「开启日报」。";
        }
    }

    // ======================== 内部调度 ========================

    /**
     * 为用户创建定时任务。
     * 将 "HH:mm" 转为当天（或明天）对应时间点的初始延迟 + 24小时周期。
     */
    private void scheduleUserTask(String userId, String time, String city) {
        String[] parts = time.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);

        // 每天的固定时间触发
        Runnable task = () -> {
            try {
                log.info("📅 [{}] 开始推送日报 (city={})", userId, city);
                scheduledTaskService.sendDailyReport(userId, city);
                log.info("📅 [{}] 日报推送完成", userId);
            } catch (Exception e) {
                log.error("📅 [{}] 日报推送异常: {}", userId, e.getMessage(), e);
            }
        };

        // 计算首次执行的延迟（到今天的 HH:mm 或明天的 HH:mm）
        LocalTime now = LocalTime.now(ZONE);
        LocalTime targetTime = LocalTime.of(hour, minute);
        long initialDelaySeconds;
        if (now.isBefore(targetTime)) {
            initialDelaySeconds = java.time.Duration.between(now, targetTime).getSeconds();
        } else {
            // 已经过了今天的时间点，明天再执行
            initialDelaySeconds = java.time.Duration.between(now, targetTime).getSeconds()
                    + 24 * 3600;
        }

        // 每天的固定间隔（24小时）
        long periodSeconds = 24 * 3600;

        ScheduledFuture<?> future = taskScheduler.scheduleAtFixedRate(
                task,
                java.time.Instant.now().plusSeconds(initialDelaySeconds),
                java.time.Duration.ofSeconds(periodSeconds)
        );

        scheduledTasks.put(userId, future);
        log.info("📅 [{}] 定时任务已调度: {} (每天 {}:{:02d}, 首推在 {} 秒后)",
                userId, city, hour, minute, initialDelaySeconds);
    }

    private void cancelUserTask(String userId) {
        ScheduledFuture<?> future = scheduledTasks.remove(userId);
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
            log.debug("📅 [{}] 旧定时任务已取消", userId);
        }
    }

    // ======================== 通用周期任务（复用本线程池） ========================

    /**
     * 注册一个按 cron 周期触发的任务（自重排：跑完自动排下一次）。
     * 供小红书热门视频等非日报功能复用同一线程池。
     *
     * @param jobId    任务唯一标识
     * @param task     要执行的任务
     * @param cronExpr Spring 6 段 cron，如 "0 0 14 * * 3"
     */
    public void scheduleRecurring(String jobId, Runnable task, String cronExpr) {
        cancelRecurring(jobId);
        try {
            CronExpression cron = CronExpression.parse(cronExpr);
            // 用 ZonedDateTime 而非 Instant：带星期字段的 cron 对 Instant 会抛 Unsupported field: DayOfWeek
            ZonedDateTime next = cron.next(ZonedDateTime.now(ZONE));
            if (next == null) {
                log.warn("📅 周期任务 [{}] cron 无下一次触发: {}", jobId, cronExpr);
                return;
            }
            ScheduledFuture<?> future = taskScheduler.schedule(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.error("📅 周期任务 [{}] 执行异常: {}", jobId, e.getMessage(), e);
                } finally {
                    // 自重排下一次
                    scheduleRecurring(jobId, task, cronExpr);
                }
            }, next.toInstant());
            recurringTasks.put(jobId, future);
            log.info("📅 周期任务 [{}] 已调度，cron={}，下次触发={}", jobId, cronExpr, next);
        } catch (Exception e) {
            log.error("📅 周期任务 [{}] 调度失败: {}", jobId, e.getMessage(), e);
        }
    }

    /** 取消一个通用周期任务 */
    public void cancelRecurring(String jobId) {
        ScheduledFuture<?> future = recurringTasks.remove(jobId);
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
            log.info("📅 周期任务 [{}] 已取消", jobId);
        }
    }

    private void cancelAll() {
        for (Map.Entry<String, ScheduledFuture<?>> entry : scheduledTasks.entrySet()) {
            ScheduledFuture<?> future = entry.getValue();
            if (future != null && !future.isCancelled()) {
                future.cancel(false);
            }
        }
        scheduledTasks.clear();
        // 通用周期任务一并取消
        for (Map.Entry<String, ScheduledFuture<?>> entry : recurringTasks.entrySet()) {
            ScheduledFuture<?> future = entry.getValue();
            if (future != null && !future.isCancelled()) {
                future.cancel(false);
            }
        }
        recurringTasks.clear();
        log.info("📅 所有日报定时任务已取消");
    }

    // ======================== 工具方法 ========================

    private UserSettingsEntity getOrCreate(String userId) {
        UserSettingsEntity settings = userSettingsMapper.findByUserId(userId);
        if (settings == null) {
            settings = new UserSettingsEntity(userId);
            userSettingsMapper.insert(settings);
        }
        return settings;
    }

    private boolean isValidTime(String time) {
        try {
            LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm"));
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
