package com.demo.controller;

import com.alibaba.fastjson2.JSONObject;
import com.demo.service.travel.RestaurantService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 旅行·周边餐馆查询（美食推荐）。
 * 高德 key 在后端，前端只走后端代理。接口前缀匹配项目旅行模块 /api/desktop。
 */
@Slf4j
@RestController
@RequestMapping("/api/desktop/restaurant")
public class TravelRestaurantController {

    @Resource
    private RestaurantService restaurantService;

    /**
     * 周边餐馆搜索。
     * body: {locationName, lon, lat, radius, preference:{cuisineList,minPrice,maxPrice,minScore,tagList}, sortType}
     */
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (body == null) {
            out.put("code", 400);
            out.put("msg", "参数为空");
            return out;
        }
        String locationName = str(body.get("locationName"));
        Double lon = dbl(body.get("lon"));
        Double lat = dbl(body.get("lat"));
        String city = str(body.get("city"));
        int radius = body.get("radius") instanceof Number n ? n.intValue() : 2000;
        JSONObject preference = null;
        if (body.get("preference") instanceof Map<?, ?> pm) {
            preference = new JSONObject();
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                preference.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        String sortType = str(body.get("sortType"));
        out.putAll(restaurantService.search(locationName, lon, lat, city, radius, preference, sortType));
        return out;
    }

    /** 收藏餐馆。body: {travelPlanId, poiId, name, lon, lat, avgCost, rating} */
    @PostMapping("/favorite")
    public Map<String, Object> favorite(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (body == null) {
            out.put("code", 400);
            out.put("msg", "参数为空");
            return out;
        }
        out.putAll(restaurantService.favorite(
                str(body.get("travelPlanId")), str(body.get("poiId")), str(body.get("name")),
                str(body.get("lon")), str(body.get("lat")),
                body.get("avgCost") instanceof Number n ? n.intValue() : null,
                body.get("rating") instanceof Number n ? n.doubleValue() : null));
        return out;
    }

    /** 列出某行程的收藏。body: {travelPlanId} */
    @PostMapping("/favorites")
    public Map<String, Object> favorites(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(restaurantService.listFavorites(body == null ? null : str(body.get("travelPlanId"))));
        return out;
    }

    /** 添加餐馆到每日行程。body: {scheduleId, poiId, name, timePeriod(lunch/dinner), remark} */
    @PostMapping("/schedule/addRestaurant")
    public Map<String, Object> addRestaurant(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (body == null) {
            out.put("code", 400);
            out.put("msg", "参数为空");
            return out;
        }
        out.putAll(restaurantService.addRestaurantToSchedule(
                str(body.get("scheduleId")), str(body.get("poiId")), str(body.get("name")),
                str(body.get("timePeriod")), str(body.get("remark"))));
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static Double dbl(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
