package com.demo.agent;

import com.demo.service.tools.MapService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LangChain4j Agent 工具 —— 地图导航与定位
 * Agent 会根据用户问题自动决定是否调用此工具
 * 对接高德地图 API，支持地址解析、路线规划、周边搜索
 * 注意：所有参数统一为 String 类型，与 AgentExecutor 反射机制对齐
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MapTool {

    private final MapService mapService;

    @Tool("将地址转换为经纬度坐标。用户询问地址在哪、坐标是什么时调用")
    public String geocode(
            @P("详细地址，如：北京市朝阳区望京SOHO T1、杭州西湖区文三路、广州市天河区天河路385号") String address,
            @P("所在城市（可选），如：北京、上海、广州") String city) {
        log.info("🧰 Agent 调用地理编码，地址：{}，城市：{}", address, city);
        return mapService.geocode(address, city);
    }

    @Tool("将经纬度坐标转换为详细地址。用户问某个坐标在哪、这是哪里时调用")
    public String reverseGeocode(
            @P("经纬度坐标，格式：经度,纬度，如：116.397428,39.90923") String location) {
        log.info("🧰 Agent 调用逆地理编码，坐标：{}", location);
        return mapService.reverseGeocode(location);
    }

    @Tool("查询驾车路线规划。用户问开车怎么走、驾车路线、自驾导航时调用。"
            + "支持输入地址或坐标，如：从天安门到故宫、从北京到上海")
    public String drivingRoute(
            @P("起点，可以是地址或坐标，如：天安门、北京西站、116.397428,39.90923") String origin,
            @P("终点，可以是地址或坐标，如：故宫、上海虹桥站") String destination) {
        log.info("🧰 Agent 调用驾车路线，起点：{}，终点：{}", origin, destination);
        return mapService.drivingRoute(origin, destination);
    }

    @Tool("查询步行路线规划。用户问步行怎么走、走路路线时调用")
    public String walkingRoute(
            @P("起点，可以是地址或坐标") String origin,
            @P("终点，可以是地址或坐标") String destination) {
        log.info("🧰 Agent 调用步行路线，起点：{}，终点：{}", origin, destination);
        return mapService.walkingRoute(origin, destination);
    }

    @Tool("查询公交地铁路线规划。用户问坐公交怎么去、地铁换乘、公共交通路线时调用")
    public String transitRoute(
            @P("起点，可以是地址或坐标") String origin,
            @P("终点，可以是地址或坐标") String destination,
            @P("所在城市，如：北京、上海、广州") String city) {
        log.info("🧰 Agent 调用公交路线，起点：{}，终点：{}，城市：{}", origin, destination, city);
        return mapService.transitRoute(origin, destination, city);
    }

    @Tool("搜索周边兴趣点/地点，如餐厅、医院、银行、加油站等。"
            + "用户问附近有什么、帮我找、搜索某类地点时调用")
    public String searchPoi(
            @P("搜索关键词，如：火锅、医院、银行、加油站、星巴克") String keywords,
            @P("所在城市（可选），如：北京、上海") String city,
            @P("返回结果数量（可选），默认5，最多20，传数字如5") String pageSize) {
        log.info("🧰 Agent 调用POI搜索，关键词：{}，城市：{}，数量：{}", keywords, city, pageSize);
        return mapService.searchPoi(keywords, city, pageSize);
    }

    @Tool("测量两个或多个地点之间的距离。支持直线距离、驾车距离等。用户问多远、距离多少时调用")
    public String measureDistance(
            @P("起点坐标（支持多个用|分隔），如：116.397428,39.90923|116.327428,39.90923") String origins,
            @P("终点坐标，如：116.467428,39.90923") String destination,
            @P("测距类型，0=直线距离（默认），1=驾车距离，3=步行距离，传数字如0") String type) {
        log.info("🧰 Agent 调用距离测量，起点：{}，终点：{}，类型：{}", origins, destination, type);
        return mapService.measureDistance(origins, destination, type);
    }
}
