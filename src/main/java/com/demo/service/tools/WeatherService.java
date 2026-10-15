package com.demo.service.tools;

import com.alibaba.fastjson2.JSONObject;
import com.demo.util.CityIdAiUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import jakarta.annotation.Resource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 天气查询业务服务
 * 通过和风天气API获取实时天气
 * 借助AI工具自动将城市名转为接口所需城市ID
 */
@Slf4j
@Service
public class WeatherService {

    @Value("${weather.key}")
    private String weatherKey;

    @Value("${weather.url}")
    private String weatherUrl;

    // HTTP请求工具
    @Resource
    private RestTemplate restTemplate;

    // 城市名称转和风ID工具类
    @Resource
    private CityIdAiUtil cityIdAiUtil;

    /**
     * 根据城市名称查询实时天气
     * @param city 用户输入城市名称
     * @return 格式化天气信息文本
     */
    public String getWeatherInfo(String city){
        // 校验城市名称是否为空
        if(city == null || city.isBlank()){
            log.warn("天气查询未传入城市名");
            return "请输入具体城市名称再查询！";
        }
        String displayCity = city.trim();

        // 调用AI工具把城市名转换成和风天气专用locationId
        String locationId = cityIdAiUtil.getQWeatherCityId(displayCity);
        // 转换失败直接返回提示
        if(locationId == null){
            return "无法识别【"+displayCity+"】，暂未匹配到对应城市ID，请更换城市重试";
        }
        log.info("AI自动转换：{} → 和风城市ID：{}",displayCity,locationId);

        try {
            // 拼接完整天气请求链接
            String fullUrl = weatherUrl + "?location=" + locationId + "&key=" + weatherKey;
            log.info("调用天气接口，城市：{}（ID：{}）", displayCity, locationId);
            String resJson = restTemplate.getForObject(fullUrl, String.class);

            // 解析接口返回JSON数据
            JSONObject json = JSONObject.parseObject(resJson);
            // 判断接口响应状态码
            if(!"200".equals(json.getString("code"))){
                return "暂无【" + displayCity + "】天气数据";
            }
            // 提取实时天气数据
            JSONObject now = json.getJSONObject("now");
            return displayCity + "天气：\n"
                    + "天气情况：" + now.getString("text") + "\n"
                    + "实时温度：" + now.getString("temp") + "℃\n"
                    + "体感温度：" + now.getString("feelsLike") + "℃";
        }catch (Exception e){
            log.error("天气接口调用失败",e);
            return "天气查询服务暂时不可用，请稍后再试。";
        }
    }

    /**
     * 结构化天气查询（出行模块用）：返回 {temp, condition}，供前端行程表单自动填充。
     * 城市无法识别或接口异常时返回空 Map。
     */
    public Map<String, Object> getWeatherStruct(String city) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (city == null || city.isBlank()) return out;
        String displayCity = city.trim();
        String locationId = cityIdAiUtil.getQWeatherCityId(displayCity);
        if (locationId == null) {
            log.warn("天气结构化：无法识别城市 {}", displayCity);
            return out;
        }
        try {
            String fullUrl = weatherUrl + "?location=" + locationId + "&key=" + weatherKey;
            String resJson = restTemplate.getForObject(fullUrl, String.class);
            JSONObject json = JSONObject.parseObject(resJson);
            if (!"200".equals(json.getString("code"))) return out;
            JSONObject now = json.getJSONObject("now");
            out.put("temp", now.getString("temp"));
            out.put("condition", now.getString("text"));
        } catch (Exception e) {
            log.error("天气结构化接口调用失败", e);
        }
        return out;
    }
}