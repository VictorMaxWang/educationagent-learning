package com.demo.agent;

import com.demo.service.tools.ReportScheduleService;
import com.demo.util.VoiceContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LangChain4j Agent 工具 —— 日报定时设置。
 * <p>
 * 用户可通过自然语言设置/修改/开启/关闭每日日报推送的时间、城市。
 * LLM 负责将自然语言时间（"早上8点"→8:0、"下午5点半"→17:30）转为结构化参数。
 * <p>
 * 日报内容：当日天气 + 热点新闻，由 ScheduledTaskService 生成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportScheduleTool {

    private final ReportScheduleService reportScheduleService;

    @Tool("设置日报推送时间。触发词：日报、每日推送、定时推送、每天推送。"
            + "LLM 需将用户口语化时间转为小时和分钟（分钟可以是0~59任意值）。"
            + "例子：'早上8点'→hour=8,minute=0, '下午5点半'→hour=17,minute=30, "
            + "'中午12点20分'→hour=12,minute=20, '11点21分'→hour=11,minute=21。"
            + "城市默认用用户所在城市，没说就用上次设置或杭州。"
            + "设置成功后回复用户设置结果。")
    public String setReportTime(
            @P(value = "小时（24小时制，如8点=8，下午5点=17）", name = "hour") String hour,
            @P(value = "分钟（如0、30）", name = "minute") String minute,
            @P(value = "天气城市名（如北京、上海、杭州），可选，不传则使用默认", name = "city", required = false) String city) {

        String userId = VoiceContext.getUserId();
        if (userId == null) {
            log.warn("📅 VoiceContext 未设置 userId");
            return "设置失败，请重试~";
        }

        if (hour == null || hour.isBlank() || minute == null || minute.isBlank()) {
            return "请告诉我你希望几点推送日报，例如「早上8点」或「下午5点半」~";
        }

        int hourInt, minuteInt;
        try {
            hourInt = Integer.parseInt(hour.trim());
            minuteInt = Integer.parseInt(minute.trim());
        } catch (NumberFormatException e) {
            return "时间格式不正确，请输入数字，例如早上8点→hour=8,minute=0~";
        }

        if (hourInt < 0 || hourInt > 23 || minuteInt < 0 || minuteInt > 59) {
            return "时间格式不正确，请输入有效的小时(0-23)和分钟(0-59)~";
        }

        String time = String.format("%02d:%02d", hourInt, minuteInt);
        String safeCity = (city != null && !city.isBlank()) ? city : null;

        try {
            reportScheduleService.setSchedule(userId, time, safeCity);
            String resultCity = safeCity != null ? safeCity : "默认城市";
            log.info("📅 [{}] LLM 设置日报: time={} city={}", userId, time, safeCity);
            return "✅ 日报已设置为每天 " + time + " 推送" + resultCity + "天气和热点新闻~\n"
                    + "你可以随时说「修改日报时间」来调整。";
        } catch (IllegalArgumentException e) {
            return "❌ " + e.getMessage();
        }
    }

    @Tool("开启日报推送。触发词：开启日报、打开日报、启用日报、恢复日报推送。"
            + "使用用户已有的时间/城市设置，没有则用默认值。")
    public String enableReport() {
        String userId = VoiceContext.getUserId();
        if (userId == null) {
            log.warn("📅 VoiceContext 未设置 userId");
            return "操作失败，请重试~";
        }

        reportScheduleService.enable(userId);
        String status = reportScheduleService.getStatus(userId);
        log.info("📅 [{}] LLM 开启日报", userId);
        return "✅ 日报已开启~\n" + status;
    }

    @Tool("关闭日报推送。触发词：关闭日报、取消日报、停用日报、不要日报推送。")
    public String disableReport() {
        String userId = VoiceContext.getUserId();
        if (userId == null) {
            log.warn("📅 VoiceContext 未设置 userId");
            return "操作失败，请重试~";
        }

        reportScheduleService.disable(userId);
        log.info("📅 [{}] LLM 关闭日报", userId);
        return "✅ 日报已关闭，不再推送~";
    }

    @Tool("查询日报推送设置状态。触发词：日报状态、日报设置、查看日报、推送状态。")
    public String checkReportStatus() {
        String userId = VoiceContext.getUserId();
        if (userId == null) {
            log.warn("📅 VoiceContext 未设置 userId");
            return "查询失败，请重试~";
        }

        String status = reportScheduleService.getStatus(userId);
        log.info("📅 [{}] LLM 查询日报状态", userId);
        return status;
    }
}
