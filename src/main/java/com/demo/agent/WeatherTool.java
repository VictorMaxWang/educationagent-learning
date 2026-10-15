package com.demo.agent;

import com.demo.service.tools.WeatherService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LangChain4j Agent 工具 —— 天气查询
 * Agent 会根据用户问题自动决定是否调用此工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherTool {

    private final WeatherService weatherService;

    @Tool("查询指定城市的实时天气数据，包含温度、体感温度、天气状况。"
            + "当用户询问任何城市的冷热、温度、体感、天气情况时都必须调用此工具，"
            + "禁止凭常识回答温度体感类问题")
    public String getWeather(
            @P("城市名称，例如：北京、上海、广州、深圳、杭州等") String city) {
        log.info("🧰 Agent 调用天气工具，城市：{}", city);
        String result = weatherService.getWeatherInfo(city);
        log.info("🧰 天气工具返回：{}", result);
        return result;
    }
}
