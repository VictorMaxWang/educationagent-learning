package com.demo.service;

import com.demo.bot.BotInstance;
import com.demo.bot.BotManager;
import com.demo.service.tools.WeatherService;
import com.demo.service.tools.WebSearchService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 定时日报服务
 * <p>
 * 由 {@link com.demo.service.tools.ReportScheduleService} 按用户独立调度。
 * 每个用户可设置自己的推送时间和城市，不再使用全局固定 cron。
 * <p>
 * 日报内容：当天天气 + 新闻热点
 * 不走 LLM，直接调用后端 Service + SDK sendText
 */
@Slf4j
@Service
public class ScheduledTaskService {

    @Value("${scheduled.task.city:杭州}")
    private String defaultCity;

    @Resource
    private WeatherService weatherService;

    @Resource
    private WebSearchService webSearchService;

    @Lazy
    @Resource
    private BotManager botManager;

    /**
     * 为指定用户推送日报（由 ReportScheduleService 定时调用）
     *
     * @param userId 目标用户微信ID
     * @param city   天气城市名，null则用默认
     */
    public void sendDailyReport(String userId, String city) {
        if (userId == null || userId.isBlank()) {
            log.warn("⏰ userId 为空，跳过");
            return;
        }

        String safeCity = (city != null && !city.isBlank()) ? city : defaultCity;
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));

        String weatherStr;
        try {
            weatherStr = weatherService.getWeatherInfo(safeCity);
        } catch (Exception e) {
            log.error("🌤 [{}] 天气查询失败", userId, e);
            weatherStr = null;
        }

        String newsStr;
        try {
            newsStr = webSearchService.search("今日热点新闻", 10);
        } catch (Exception e) {
            log.error("📰 [{}] 新闻搜索失败", userId, e);
            newsStr = null;
        }

        String report = buildReport(today, safeCity, weatherStr, newsStr);

        try {
            BotInstance defaultBot = botManager.getDefaultBot();
            if (defaultBot != null) {
                defaultBot.getClient().sendText(userId, report);
                log.info("⏰ 日报已通过 [{}] 推送到 [{}] (city={})", defaultBot.getName(), userId, safeCity);
            } else {
                log.error("⏰ 无可用 Bot，推送失败 [{}]", userId);
            }
        } catch (Exception e) {
            log.error("⏰ 推送到 [{}] 失败：{}", userId, e.getMessage(), e);
        }
    }

    /**
     * 构建日报文本
     */
    private String buildReport(String today, String city, String weatherStr, String newsRaw) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌅 早安 · ").append(today).append("\n");
        sb.append("━━━━━━━━━━━━━━\n\n");
        sb.append("☀️ ").append(city).append("天气\n");
        sb.append(formatWeather(weatherStr)).append("\n\n");
        sb.append("📰 今日热点\n");
        sb.append(formatNews(newsRaw)).append("\n");
        return sb.toString();
    }

    private String formatWeather(String raw) {
        if (raw == null || raw.contains("不可用") || raw.contains("失败")
                || raw.contains("无法识别") || raw.contains("暂未")) {
            return "  暂无数据";
        }
        try {
            String[] lines = raw.split("\n");
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                if (line.contains("天气情况：")) {
                    sb.append(line.replace("天气情况：", "")).append("  ");
                } else if (line.contains("实时温度：")) {
                    sb.append(line.replace("实时温度：", "")).append("  ");
                } else if (line.contains("体感温度：")) {
                    sb.append("体感 ").append(line.replace("体感温度：", ""));
                }
            }
            String result = sb.toString().trim();
            return result.isEmpty() ? "  暂无数据" : result;
        } catch (Exception e) {
            return "  暂无数据";
        }
    }

    private String formatNews(String raw) {
        if (raw == null || raw.isBlank()) {
            return "  暂无新闻数据\n";
        }
        StringBuilder sb = new StringBuilder();
        String[] lines = raw.split("\n");
        int count = 0;
        for (String line : lines) {
            if (line.matches("^\\d+\\..*")) {
                count++;
                if (count > 10) break;
                sb.append("  ").append(line.trim()).append("\n");
            }
        }
        return sb.isEmpty() ? "  暂无新闻数据\n" : sb.toString();
    }
}
