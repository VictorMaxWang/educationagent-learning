package com.demo.agent;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
public class TimeTool {

    private static final DateTimeFormatter STANDARD_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FULL_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 获取今日标准日期 + 星期（原有功能不变）
     */
    @Tool("获取今日日期与星期，格式：yyyy-MM-dd，查询车票优先获取基准日期")
    public String getTodayInfo() {
        LocalDate now = LocalDate.now();
        String week = switch (now.getDayOfWeek()) {
            case MONDAY -> "星期一";
            case TUESDAY -> "星期二";
            case WEDNESDAY -> "星期三";
            case THURSDAY -> "星期四";
            case FRIDAY -> "星期五";
            case SATURDAY -> "星期六";
            case SUNDAY -> "星期日";
        };
        return now.format(STANDARD_DATE) + " " + week;
    }

    /**
     * 日期偏移计算（原有对外调用功能保留）
     */
    @Tool("日期推算工具，传入天数偏移值计算目标日期。明天传1，后天传2，昨天传-1，默认0为今天")
    public String calcOffsetDate(@P("偏移天数，整数") int offset) {
        LocalDate target = LocalDate.now().plusDays(offset);
        return target.format(STANDARD_DATE);
    }

    /**
     * 获取当前精确时分秒时间（原有功能完全保留）
     */
    @Tool("获取当前精确时间 年月日时分秒")
    public String getNowFullTime() {
        return LocalDateTime.now().format(FULL_DATETIME);
    }

    // ========== 内部专用方法（供给火车票工具调用，不暴露为AI工具） ==========
    /**
     * 获取当前本地日期对象
     */
    public LocalDate getNowLocalDate() {
        return LocalDate.now();
    }

    /**
     * 根据天数偏移快速获取标准日期字符串
     */
    public String getDateByOffset(int day) {
        return LocalDate.now().plusDays(day).format(STANDARD_DATE);
    }
}