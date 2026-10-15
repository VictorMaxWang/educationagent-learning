package com.demo.pet;

import com.demo.config.AiChatProperties;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 宠物定时推送调度服务
 * 参考 ReportScheduleService 模式，使用独立 ThreadPoolTaskScheduler
 * 支持 cron 表达式 + 启动恢复 + BotManager 推送到微信
 */
@Slf4j
@Service
public class PetScheduleService {

    @Resource
    private AiChatProperties aiChat;

    @Resource
    private PetScheduleMapper scheduleMapper;

    @Resource
    private PetService petService;

    /** 推送通道 SPI（可选）：无实现时定时任务照常调度，仅跳过实际推送 */
    @Resource
    private ObjectProvider<PetNotifier> notifierProvider;

    /** 每个 schedule.id -> ScheduledFuture */
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /** 独立调度线程池 */
    private final ThreadPoolTaskScheduler taskScheduler;

    private OpenAiChatModel cronChatModel;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public PetScheduleService() {
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setPoolSize(3);
        this.taskScheduler.setThreadNamePrefix("pet-schedule-");
        this.taskScheduler.setWaitForTasksToCompleteOnShutdown(true);
        this.taskScheduler.setAwaitTerminationSeconds(10);
        this.taskScheduler.initialize();
        log.info("🐾 宠物定时调度线程池初始化完成 (poolSize=3)");
    }

    @PostConstruct
    public void init() {
        this.cronChatModel = aiChat.chatModelBuilder()
                .temperature(0.1)
                .maxRetries(1)
                .build();

        restoreSchedules();
    }

    @PreDestroy
    public void destroy() {
        cancelAll();
        taskScheduler.destroy();
        log.info("🐾 宠物定时调度器已关闭");
    }

    // ==================== 对外 API ====================

    /** 创建定时任务 */
    public PetScheduleEntity createSchedule(String userId, String petName,
                                             String timeExpr, String action) {
        // 自动建档（如果宠物不存在）
        petService.getOrCreate(userId, petName);

        // 时间表达式 → cron（LLM 转换）
        String cronExpr = parseTimeExpression(timeExpr);
        validateCron(cronExpr);

        PetScheduleEntity schedule = new PetScheduleEntity(userId, petName, timeExpr, cronExpr, action);
        scheduleMapper.insert(schedule);

        // 注册调度
        scheduleTask(schedule);

        log.info("🐾 [{}] 创建定时: {} {} {} (cron={})", userId, petName, timeExpr, action, cronExpr);
        return schedule;
    }

    /** 列出用户的定时任务 */
    public List<PetScheduleEntity> listSchedules(String userId, String petName) {
        if (petName != null && !petName.isBlank()) {
            return scheduleMapper.findByUserIdAndPetName(userId, petName);
        }
        return scheduleMapper.findByUserId(userId);
    }

    /** 取消定时任务 */
    public void cancelSchedule(Long scheduleId) {
        PetScheduleEntity schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        cancelTask(scheduleId);
        scheduleMapper.deleteById(scheduleId);
        log.info("🐾 取消定时: id={}, pet={}, action={}", scheduleId,
                schedule.getPetName(), schedule.getAction());
    }

    /** 启用/停用定时任务 */
    public PetScheduleEntity toggleSchedule(Long scheduleId, boolean enabled) {
        PetScheduleEntity schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        schedule.setEnabled(enabled ? 1 : 0);
        scheduleMapper.updateById(schedule);

        if (enabled) {
            scheduleTask(schedule);
        } else {
            cancelTask(scheduleId);
        }

        log.info("🐾 定时任务 {} 已{}", scheduleId, enabled ? "启用" : "停用");
        return schedule;
    }

    /** 格式化定时任务列表为可读文本 */
    public String formatSchedules(List<PetScheduleEntity> schedules) {
        if (schedules.isEmpty()) return "📅 暂无宠物定时任务";

        StringBuilder sb = new StringBuilder("📅 宠物定时任务：\n\n");
        for (int i = 0; i < schedules.size(); i++) {
            PetScheduleEntity s = schedules.get(i);
            sb.append("【").append(i + 1).append("】ID:").append(s.getId())
                    .append(" ").append(s.getPetName())
                    .append(" — ").append(s.getTimeExpr())
                    .append(" — ").append(s.getAction());
            if (s.getEnabled() == 0) sb.append(" [已暂停]");
            sb.append("\n");
        }
        return sb.toString();
    }

    // ==================== 调度核心 ====================

    private void scheduleTask(PetScheduleEntity schedule) {
        if (schedule.getEnabled() == 0) return;

        Runnable task = () -> executePush(schedule);

        try {
            CronExpression cron = CronExpression.parse(schedule.getCronExpr());
            Instant next = cron.next(Instant.now());
            if (next == null) {
                log.warn("🐾 cron 无下一次触发: {}", schedule.getCronExpr());
                return;
            }

            ScheduledFuture<?> future = taskScheduler.schedule(task, next);
            scheduledTasks.put(schedule.getId(), future);

            log.debug("🐾 定时任务 #{} 已调度，下次触发: {}", schedule.getId(), next);
        } catch (Exception e) {
            log.error("🐾 调度失败 #{}: {}", schedule.getId(), e.getMessage());
        }
    }

    private void cancelTask(Long scheduleId) {
        ScheduledFuture<?> future = scheduledTasks.remove(scheduleId);
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
        }
    }

    private void cancelAll() {
        for (Map.Entry<Long, ScheduledFuture<?>> entry : scheduledTasks.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isCancelled()) {
                entry.getValue().cancel(false);
            }
        }
        scheduledTasks.clear();
    }

    /** 执行推送 + 重新调度下一次 */
    private void executePush(PetScheduleEntity schedule) {
        try {
            // 重新从 DB 读取最新状态（可能已被停用）
            PetScheduleEntity latest = scheduleMapper.selectById(schedule.getId());
            if (latest == null || latest.getEnabled() == 0) {
                cancelTask(schedule.getId());
                return;
            }

            // 推送到用户（可选 SPI：微信 Bot / 桌面端通知，未配置时仅记录）
            String text = "🐾 叮咚！" + latest.getTimeExpr() + "啦\n「"
                    + latest.getPetName() + "」" + latest.getAction();
            PetNotifier notifier = notifierProvider.getIfAvailable();
            if (notifier != null) {
                try {
                    boolean ok = notifier.push(latest.getUserId(), text);
                    log.info("🐾 [{}] 定时推送: pet={}, action={}, ok={}",
                            latest.getUserId(), latest.getPetName(), latest.getAction(), ok);
                } catch (Exception e) {
                    log.warn("🐾 定时推送失败 #{}: {}", schedule.getId(), e.getMessage());
                }
            } else {
                log.warn("🐾 未配置推送通道，任务 #{} 已记录但未推送 (pet={}, action={})",
                        schedule.getId(), latest.getPetName(), latest.getAction());
            }

            // 重新调度下一次
            scheduleTask(latest);

        } catch (Exception e) {
            log.error("🐾 定时推送执行失败 #{}: {}", schedule.getId(), e.getMessage(), e);
        }
    }

    /** 启动恢复：加载所有启用的任务并重新调度 */
    private void restoreSchedules() {
        List<PetScheduleEntity> all = scheduleMapper.findAllEnabled();
        if (all.isEmpty()) {
            log.info("🐾 无待恢复的定时任务");
            return;
        }
        for (PetScheduleEntity schedule : all) {
            scheduleTask(schedule);
        }
        log.info("🐾 启动恢复完成，共 {} 个定时任务", all.size());
    }

    // ==================== 时间解析 ====================

    /**
     * 将自然语言时间表达式转为 cron 表达式
     * 先尝试本地匹配，失败则用 LLM 转换
     */
    private String parseTimeExpression(String timeExpr) {
        // 本地匹配常见模式
        String localResult = localParse(timeExpr);
        if (localResult != null) return localResult;

        // 未配置 LLM 时无法解析复杂时间描述
        if (cronChatModel == null) {
            throw new IllegalArgumentException("无法解析时间表达式: [" + timeExpr + "]，且未配置 AI_CHAT_API_KEY。"
                    + "请使用更明确的描述，如'每天早8点'");
        }

        // LLM 转换
        try {
            String prompt = "将以下自然语言时间描述转换为标准的5位cron表达式。" +
                    "只返回cron表达式本身，不要任何解释。\n\n" +
                    "时间描述: " + timeExpr + "\n\n" +
                    "cron格式: 分 时 日 月 周\n" +
                    "示例：每天早上8点 → 0 8 * * *\n" +
                    "示例：每天晚上8点半 → 30 20 * * *\n" +
                    "示例：每周一早上9点 → 0 9 * * 1\n" +
                    "cron:";

            var response = cronChatModel.chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from(prompt)))
                    .build());
            return response.aiMessage().text().trim();
        } catch (Exception e) {
            log.error("🐾 LLM cron 解析失败: {}", timeExpr, e);
            throw new IllegalArgumentException("无法解析时间表达式: [" + timeExpr + "], 请使用更明确的描述，如'每天早8点'");
        }
    }

    private String localParse(String expr) {
        String e = expr.replaceAll("\\s+", "").toLowerCase();
        // 每天固定时间
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "每天(早上|早晨|上午|中午|下午|晚上|傍晚)?(\\d{1,2})点(半|(\\d{1,2})分)?");
        java.util.regex.Matcher m = p.matcher(e);
        if (m.find()) {
            int hour = Integer.parseInt(m.group(2));
            int minute = 0;
            if ("半".equals(m.group(3))) minute = 30;
            else if (m.group(4) != null) minute = Integer.parseInt(m.group(4));

            String period = m.group(1);
            if (period != null) {
                if (period.contains("下午") || period.contains("晚上") || period.contains("傍晚")) {
                    if (hour < 12) hour += 12;
                }
            } else if (e.contains("下午") || e.contains("晚上") || e.contains("傍晚")) {
                if (hour < 12) hour += 12;
            }

            return String.format("0 %d %d * * *", minute, hour);
        }

        // 每周X
        p = java.util.regex.Pattern.compile("每(周|星期)(\\w)(早上|上午|下午|晚上)?(\\d{1,2})点");
        m = p.matcher(e);
        if (m.find()) {
            int hour = Integer.parseInt(m.group(4));
            int dow = dayOfWeek(m.group(2));
            String period = m.group(3);
            if (period != null && (period.contains("下午") || period.contains("晚上"))) {
                if (hour < 12) hour += 12;
            }
            return String.format("0 0 %d * * %d", hour, dow);
        }

        return null;
    }

    private int dayOfWeek(String cn) {
        return switch (cn) {
            case "一", "1" -> 1;
            case "二", "2" -> 2;
            case "三", "3" -> 3;
            case "四", "4" -> 4;
            case "五", "5" -> 5;
            case "六", "6" -> 6;
            case "日", "天", "7", "0" -> 0;
            default -> 1;
        };
    }

    private void validateCron(String cronExpr) {
        try {
            CronExpression.parse(cronExpr);
        } catch (Exception e) {
            throw new IllegalArgumentException("无效的cron表达式: " + cronExpr);
        }
    }

    // ==================== SPI 接口（可选，解耦推送通道） ====================

    /** 推送通道 SPI：实现类接入微信 Bot / 桌面端通知。未配置时定时任务照常调度，仅跳过实际推送。 */
    @FunctionalInterface
    public interface PetNotifier {
        boolean push(String userId, String text);
    }
}
