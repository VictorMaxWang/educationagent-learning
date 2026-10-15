package com.demo.agent;

import com.demo.service.tools.TrainTicketService;
import com.demo.skill.annotation.RouterMatch;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrainTicketTool {

    private final TrainTicketService trainTicketService;
    // 时间工具
    private final TimeTool timeTool;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    // 车票最大可查询15天内
    private static final int MAX_QUERY_DAY = 15;

    @Tool("火车票查询工具，输入出发站、到达站、出行日期即可查询。日期支持今天、明天、后天、指定年月日格式，自动换算标准日期")
    @RouterMatch(
        patterns = {
            "查(?:询)?(?:高铁|火车|动车|车票|列车)(?<dateDesc>.*?)从(?<fromStation>.+?)到(?<toStation>.+)",
            "(?<dateDesc>.*?)从(?<fromStation>.+?)到(?<toStation>.+?)(?:的)?(?:高铁|火车|动车|车票)",
            "查(?:询)?(?<dateDesc>.*?)(?<fromStation>.+?)到(?<toStation>.+?)(?:的)?(?:高铁|火车|动车)"
        },
        confidence = 0.80,
        priority = 10
    )
    public String queryTrainTicket(
            @P("出发站点名称") String fromStation,
            @P("到达站点名称") String toStation,
            @P("出行日期：可填今天/明天/后天，也可填写yyyy-MM-dd标准日期") String dateDesc) {

        LocalDate today = timeTool.getNowLocalDate();
        String finalDateStr;

        // 智能解析口语化日期
        if (dateDesc == null || dateDesc.isBlank() || dateDesc.contains("今天")) {
            finalDateStr = timeTool.getDateByOffset(0);
        } else if (dateDesc.contains("明天")) {
            finalDateStr = timeTool.getDateByOffset(1);
        } else if (dateDesc.contains("后天")) {
            finalDateStr = timeTool.getDateByOffset(2);
        } else if (dateDesc.contains("大后天")) {
            finalDateStr = timeTool.getDateByOffset(3);
        } else {
            // 尝试解析标准日期
            try {
                LocalDate parseDate = LocalDate.parse(dateDesc, DATE_FORMAT);
                // 限制只能查今天~15天内
                if (parseDate.isBefore(today) || parseDate.isAfter(today.plusDays(MAX_QUERY_DAY))) {
                    finalDateStr = timeTool.getDateByOffset(1);
                } else {
                    finalDateStr = dateDesc;
                }
            } catch (DateTimeParseException e) {
                // 格式错误默认取明天
                finalDateStr = timeTool.getDateByOffset(1);
            }
        }

        log.info("🚄 车票查询解析完成：{} → {}，查询日期：{}", fromStation, toStation, finalDateStr);
        return trainTicketService.queryTickets(fromStation, toStation, finalDateStr, "");
    }
}